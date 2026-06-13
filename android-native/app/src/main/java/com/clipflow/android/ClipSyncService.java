package com.clipflow.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONObject;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ClipSyncService extends Service {
    static final String ACTION_START = "com.clipflow.android.START_SYNC";
    static final String ACTION_STOP = "com.clipflow.android.STOP_SYNC";
    static final String ACTION_STATUS = "com.clipflow.android.STATUS";
    static final String EXTRA_STATE = "state";
    static final String EXTRA_MESSAGE = "message";

    private static final String CHANNEL_ID = "clip_flow_sync";
    private static final int NOTIFICATION_ID = 42;
    private static final int CLIPBOARD_POLL_MS = 1000;
    private static final int RELAY_POLL_TIMEOUT_MS = 25000;

    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ClipboardManager clipboard;
    private ClipRelayClient client;
    private boolean autoAcceptOffers;
    private String lastLocalClipboard = "";
    private String lastAppliedRemote = "";

    @Override
    public void onCreate() {
        super.onCreate();
        clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSync();
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildNotification("Starting sync"));
        startSync();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopSync();
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startSync() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        reloadClientFromPrefs();
        publishStatus("Starting", "Sync service started");
        updateNotification("Syncing clipboard");
        io.execute(this::localClipboardLoop);
        io.execute(this::remoteRelayLoop);
    }

    private void reloadClientFromPrefs() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        client = new ClipRelayClient(
                prefs.getString(MainActivity.KEY_RELAY, MainActivity.DEFAULT_RELAY),
                getOrCreateDeviceId(prefs),
                prefs.getString(MainActivity.KEY_NAME, MainActivity.DEFAULT_NAME),
                prefs.getString(MainActivity.KEY_SECRET, MainActivity.DEFAULT_SECRET)
        );
        autoAcceptOffers = prefs.getBoolean(MainActivity.KEY_AUTO_ACCEPT, true);
    }

    private void stopSync() {
        if (running.compareAndSet(true, false)) {
            publishStatus("Stopped", "Sync service stopped");
            updateNotification("Stopped");
        }
    }

    private void localClipboardLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                if (client == null) {
                    reloadClientFromPrefs();
                }
                String value = readClipboardText();
                if (!value.isEmpty() && !value.equals(lastLocalClipboard) && !value.equals(lastAppliedRemote)) {
                    lastLocalClipboard = value;
                    client.register();
                    JSONObject result = client.sendText(value);
                    publishStatus("Running", "Sent clipboard, queuedFor=" + result.optInt("queuedFor"));
                    updateNotification("Sent clipboard");
                }
            } catch (Exception error) {
                publishStatus("Warning", "Clipboard sync failed: " + shortError(error));
            }

            sleep(CLIPBOARD_POLL_MS);
        }
    }

    private void remoteRelayLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                if (client == null) {
                    reloadClientFromPrefs();
                }
                client.register();
                client.pollOnce(RELAY_POLL_TIMEOUT_MS, autoAcceptOffers, handler());
            } catch (Exception error) {
                publishStatus("Warning", "Receive failed: " + shortError(error));
                sleep(1200);
            }
        }
    }

    private ClipRelayClient.MessageHandler handler() {
        return new ClipRelayClient.MessageHandler() {
            @Override
            public void onPlainText(String sourceDeviceId, String text) {
                lastAppliedRemote = text;
                lastLocalClipboard = text;
                writeClipboardText(text);
                publishStatus("Running", "Received from " + sourceDeviceId + ": " + shortText(text));
                updateNotification("Received clipboard");
            }

            @Override
            public void onOffer(String sourceDeviceId, JSONObject message) {
                publishStatus("Offer", "Sensitive offer from " + sourceDeviceId);
                updateNotification("Sensitive offer received");
            }

            @Override
            public void onLog(String text) {
                publishStatus("Running", text);
            }
        };
    }

    private String readClipboardText() {
        if (clipboard == null || !clipboard.hasPrimaryClip() || clipboard.getPrimaryClip() == null || clipboard.getPrimaryClip().getItemCount() == 0) {
            return "";
        }

        CharSequence text = clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);
        return text == null ? "" : text.toString();
    }

    private void writeClipboardText(String text) {
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Clip Flow", text));
        }
    }

    private String getOrCreateDeviceId(SharedPreferences prefs) {
        String existing = prefs.getString(MainActivity.KEY_DEVICE_ID, null);
        if (existing != null) {
            return existing;
        }

        String created = UUID.randomUUID().toString();
        prefs.edit().putString(MainActivity.KEY_DEVICE_ID, created).apply();
        return created;
    }

    private void publishStatus(String state, String message) {
        Intent intent = new Intent(ACTION_STATUS);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_STATE, state);
        intent.putExtra(EXTRA_MESSAGE, message);
        sendBroadcast(intent);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Clip Flow Sync",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Keeps Clip Flow clipboard sync running.");
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                1,
                openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Intent stopIntent = new Intent(this, ClipSyncService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this,
                2,
                stopIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentTitle("Clip Flow")
                .setContentText(text)
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private String shortText(String text) {
        return text.length() > 80 ? text.substring(0, 80) + "..." : text;
    }

    private String shortError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return error.getClass().getSimpleName();
        }

        return message.length() > 80 ? message.substring(0, 80) + "..." : message;
    }
}
