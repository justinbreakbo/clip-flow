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
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
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
    static final String ACTION_APPLY_PENDING = "com.clipflow.android.APPLY_PENDING";
    static final String ACTION_STATUS = "com.clipflow.android.STATUS";
    static final String EXTRA_STATE = "state";
    static final String EXTRA_MESSAGE = "message";

    private static final String CHANNEL_ID = "clip_flow_sync";
    static final String PENDING_CHANNEL_ID = "clip_flow_pending_v2";
    private static final int NOTIFICATION_ID = 42;
    private static final int PENDING_CLIPBOARD_NOTIFICATION_ID = 43;
    private static final int CLIPBOARD_POLL_MS = 1000;
    private static final int RELAY_POLL_TIMEOUT_MS = 25000;

    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ClipboardManager clipboard;
    private ClipboardManager.OnPrimaryClipChangedListener clipboardListener;
    private ClipRelayClient client;
    private boolean autoAcceptOffers;
    private String lastLocalClipboard = "";
    private String lastAppliedRemote = "";

    @Override
    public void onCreate() {
        super.onCreate();
        clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        createNotificationChannel();
        clipboardListener = () -> io.execute(() -> sendCurrentClipboardIfChanged("Clipboard changed"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSync();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_APPLY_PENDING.equals(action)) {
            applyPendingRemoteClipboard();
            return running.get() ? START_STICKY : START_NOT_STICKY;
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
        attachClipboardListener();
        io.execute(() -> sendCurrentClipboardIfChanged("Initial clipboard check"));
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
            detachClipboardListener();
            publishStatus("Stopped", "Sync service stopped");
            updateNotification("Stopped");
        }
    }

    private void localClipboardLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                sendCurrentClipboardIfChanged("Clipboard poll");
            } catch (Exception error) {
                publishStatus("Warning", "Clipboard sync failed: " + shortError(error));
            }

            sleep(CLIPBOARD_POLL_MS);
        }
    }

    private void attachClipboardListener() {
        if (clipboard != null && clipboardListener != null) {
            clipboard.removePrimaryClipChangedListener(clipboardListener);
            clipboard.addPrimaryClipChangedListener(clipboardListener);
        }
    }

    private void detachClipboardListener() {
        if (clipboard != null && clipboardListener != null) {
            clipboard.removePrimaryClipChangedListener(clipboardListener);
        }
    }

    private void sendCurrentClipboardIfChanged(String reason) {
        if (!running.get()) {
            return;
        }

        try {
            if (client == null) {
                reloadClientFromPrefs();
            }

            sendTextIfChanged(readClipboardText(), reason);
        } catch (Exception error) {
            publishStatus("Warning", reason + " failed: " + shortError(error));
        }
    }

    private void sendTextIfChanged(String value, String reason) {
        try {
            if (client == null) {
                reloadClientFromPrefs();
            }

            if (value == null) {
                return;
            }

            String trimmed = value.trim();
            if (trimmed.isEmpty() || trimmed.equals(lastLocalClipboard) || trimmed.equals(lastAppliedRemote)) {
                return;
            }

            lastLocalClipboard = trimmed;
            client.register();
            JSONObject result = client.sendText(trimmed);
            publishStatus("Running", reason + ": sent text, queuedFor=" + result.optInt("queuedFor"));
            updateNotification("Sent clipboard");
        } catch (Exception error) {
            publishStatus("Warning", reason + " failed: " + shortError(error));
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
                if (isAppForeground()) {
                    writeClipboardText(text);
                    publishStatus("Running", "Received from " + sourceDeviceId + ": " + shortText(text));
                    updateNotification("Received clipboard");
                    return;
                }

                savePendingRemoteClipboard(sourceDeviceId, text);
                publishStatus("Pending", "Remote clipboard from " + sourceDeviceId + " needs confirmation");
                showPendingClipboardNotification(sourceDeviceId, text);
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

    private boolean isAppForeground() {
        return getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getBoolean(MainActivity.KEY_APP_FOREGROUND, false);
    }

    private void savePendingRemoteClipboard(String sourceDeviceId, String text) {
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .edit()
                .putString(MainActivity.KEY_PENDING_REMOTE_TEXT, text)
                .putString(MainActivity.KEY_PENDING_REMOTE_SOURCE, sourceDeviceId)
                .putLong(MainActivity.KEY_PENDING_REMOTE_CREATED_AT, System.currentTimeMillis())
                .apply();
    }

    private void applyPendingRemoteClipboard() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        String text = prefs.getString(MainActivity.KEY_PENDING_REMOTE_TEXT, "");
        String source = prefs.getString(MainActivity.KEY_PENDING_REMOTE_SOURCE, "remote device");

        if (text == null || text.isEmpty()) {
            publishStatus("Running", "No pending remote clipboard");
            return;
        }

        lastAppliedRemote = text;
        lastLocalClipboard = text;
        writeClipboardText(text);
        prefs.edit()
                .remove(MainActivity.KEY_PENDING_REMOTE_TEXT)
                .remove(MainActivity.KEY_PENDING_REMOTE_SOURCE)
                .remove(MainActivity.KEY_PENDING_REMOTE_CREATED_AT)
                .apply();

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(PENDING_CLIPBOARD_NOTIFICATION_ID);
        }

        publishStatus("Running", "Copied pending clipboard from " + source + ": " + shortText(text));
        updateNotification("Copied remote clipboard");
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
        NotificationChannel pendingChannel = new NotificationChannel(
                PENDING_CHANNEL_ID,
                "Clip Flow Clipboard Alerts",
                NotificationManager.IMPORTANCE_HIGH
        );
        Uri notificationSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        pendingChannel.setDescription("Shows incoming remote clipboards that are ready to copy.");
        pendingChannel.enableVibration(true);
        pendingChannel.setVibrationPattern(new long[] { 0, 160, 80, 160 });
        pendingChannel.setSound(notificationSound, audioAttributes);
        pendingChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(channel);
            manager.createNotificationChannel(pendingChannel);
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

    private void showPendingClipboardNotification(String sourceDeviceId, String text) {
        Intent applyIntent = new Intent(this, ClipSyncService.class);
        applyIntent.setAction(ACTION_APPLY_PENDING);
        PendingIntent applyPendingIntent = PendingIntent.getService(
                this,
                3,
                applyIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, PENDING_CHANNEL_ID)
                : new Notification.Builder(this);

        Notification notification = builder
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .setContentTitle("Clip Flow clipboard from " + sourceDeviceId)
                .setContentText("Tap to copy to this phone")
                .setSubText(shortText(text))
                .setContentIntent(applyPendingIntent)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setPriority(Notification.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_ALL)
                .setVibrate(new long[] { 0, 160, 80, 160 })
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setAutoCancel(true)
                .addAction(android.R.drawable.ic_menu_save, "Copy", applyPendingIntent)
                .build();

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(PENDING_CLIPBOARD_NOTIFICATION_ID, notification);
        }
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
