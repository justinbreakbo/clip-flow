package com.clipflow.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    static final String PREFS = "clip-flow";
    static final String KEY_RELAY = "relay";
    static final String KEY_SECRET = "secret";
    static final String KEY_NAME = "name";
    static final String KEY_DEVICE_ID = "deviceId";
    static final String KEY_AUTO_ACCEPT = "autoAccept";
    static final String KEY_AUTO_START = "autoStart";
    static final String KEY_APP_FOREGROUND = "appForeground";
    static final String KEY_PENDING_REMOTE_TEXT = "pendingRemoteText";
    static final String KEY_PENDING_REMOTE_SOURCE = "pendingRemoteSource";
    static final String KEY_PENDING_REMOTE_CREATED_AT = "pendingRemoteCreatedAt";
    static final String DEFAULT_RELAY = "http://127.0.0.1:42821";
    static final String DEFAULT_SECRET = "change-this-local-secret";
    static final String DEFAULT_NAME = "Android";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private EditText relayInput;
    private EditText secretInput;
    private EditText deviceNameInput;
    private EditText sendInput;
    private TextView statusText;
    private TextView receivedText;
    private TextView logText;
    private CheckBox autoAcceptInput;
    private boolean notificationPromptShowing = false;

    private ClipRelayClient client;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ClipSyncService.ACTION_STATUS.equals(intent.getAction())) {
                return;
            }

            String state = intent.getStringExtra(ClipSyncService.EXTRA_STATE);
            String message = intent.getStringExtra(ClipSyncService.EXTRA_MESSAGE);
            statusText.setText(state == null ? "Unknown" : state);
            if (message != null && !message.trim().isEmpty()) {
                log(message);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLayout());
        restoreDefaults();
        handleShareIntent(getIntent());
        handleApplyPendingIntent(getIntent());
        startSyncService();
        ensureClipboardAlertNotifications();
    }

    @Override
    protected void onResume() {
        super.onResume();
        setAppForeground(true);
        IntentFilter filter = new IntentFilter(ClipSyncService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
    }

    @Override
    protected void onPause() {
        unregisterReceiver(statusReceiver);
        setAppForeground(false);
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShareIntent(intent);
        handleApplyPendingIntent(intent);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private View buildLayout() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 28, 32, 40);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Clip Flow Android");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        statusText = label("Stopped");
        statusText.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(statusText);

        relayInput = input("Relay URL", DEFAULT_RELAY, false);
        secretInput = input("Shared Secret", DEFAULT_SECRET, false);
        deviceNameInput = input("Device name", DEFAULT_NAME, false);
        sendInput = input("Text to send", "hello from Android", true);

        root.addView(relayInput);
        root.addView(secretInput);
        root.addView(deviceNameInput);
        root.addView(sendInput);

        autoAcceptInput = new CheckBox(this);
        autoAcceptInput.setText("Auto-accept sensitive offers for testing");
        autoAcceptInput.setChecked(true);
        root.addView(autoAcceptInput);

        LinearLayout row1 = row();
        row1.addView(button("Save", view -> saveDefaults()));
        row1.addView(button("Restart Sync", view -> startSyncService()));
        root.addView(row1);

        LinearLayout row2 = row();
        row2.addView(button("Stop Sync", view -> stopSyncService()));
        row2.addView(button("Copy Pending", view -> applyPendingRemoteClipboard()));
        root.addView(row2);

        LinearLayout row3 = row();
        row3.addView(button("Send Clipboard", view -> sendClipboard()));
        row3.addView(button("Send Test", view -> sendPresetText()));
        root.addView(row3);

        TextView receivedLabel = label("Latest received");
        receivedLabel.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(receivedLabel);

        receivedText = box("Waiting for content");
        root.addView(receivedText);

        TextView logLabel = label("Log");
        logLabel.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(logLabel);

        logText = box("");
        root.addView(logText);

        return scroll;
    }

    private void restoreDefaults() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        relayInput.setText(prefs.getString(KEY_RELAY, DEFAULT_RELAY));
        secretInput.setText(prefs.getString(KEY_SECRET, DEFAULT_SECRET));
        deviceNameInput.setText(prefs.getString(KEY_NAME, DEFAULT_NAME));
        autoAcceptInput.setChecked(prefs.getBoolean(KEY_AUTO_ACCEPT, true));
        log("Android app ready");
    }

    private void saveDefaults() {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putString(KEY_RELAY, relayInput.getText().toString().trim())
                .putString(KEY_SECRET, secretInput.getText().toString())
                .putString(KEY_NAME, deviceNameInput.getText().toString().trim())
                .putBoolean(KEY_AUTO_ACCEPT, autoAcceptInput.isChecked())
                .putBoolean(KEY_AUTO_START, true)
                .apply();
        log("Settings saved");
    }

    private void handleShareIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) {
            return;
        }

        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (text != null) {
            sendInput.setText(text.toString());
            log("Text received from Android share sheet");
        }
    }

    private void handleApplyPendingIntent(Intent intent) {
        if (intent == null || !ClipSyncService.ACTION_APPLY_PENDING.equals(intent.getAction())) {
            return;
        }

        applyPendingRemoteClipboard();
    }

    private void setAppForeground(boolean foreground) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_APP_FOREGROUND, foreground)
                .apply();
    }

    private void ensureClipboardAlertNotifications() {
        createClipboardAlertChannel();

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 1001);
            main.postDelayed(this::ensureClipboardAlertNotifications, 1200);
            return;
        }

        if (clipboardAlertsEnabled()) {
            return;
        }

        showNotificationSettingsPrompt();
    }

    private void createClipboardAlertChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || manager.getNotificationChannel(ClipSyncService.PENDING_CHANNEL_ID) != null) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                ClipSyncService.PENDING_CHANNEL_ID,
                "Clip Flow Clipboard Alerts",
                NotificationManager.IMPORTANCE_HIGH
        );
        Uri notificationSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        channel.setDescription("Shows incoming remote clipboards that are ready to copy.");
        channel.enableVibration(true);
        channel.setVibrationPattern(new long[] { 0, 160, 80, 160 });
        channel.setSound(notificationSound, audioAttributes);
        manager.createNotificationChannel(channel);
    }

    private boolean clipboardAlertsEnabled() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return true;
        }

        if (Build.VERSION.SDK_INT >= 24 && !manager.areNotificationsEnabled()) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = manager.getNotificationChannel(ClipSyncService.PENDING_CHANNEL_ID);
            return channel != null && channel.getImportance() >= NotificationManager.IMPORTANCE_HIGH;
        }

        return true;
    }

    private void showNotificationSettingsPrompt() {
        if (notificationPromptShowing || isFinishing()) {
            return;
        }

        notificationPromptShowing = true;
        new AlertDialog.Builder(this)
                .setTitle("Enable clipboard alerts")
                .setMessage("Clip Flow needs the Clipboard Alerts notification channel enabled as important so incoming clips can pop up at the top of the screen.")
                .setPositiveButton("Open Settings", (dialog, which) -> {
                    notificationPromptShowing = false;
                    openClipboardAlertSettings();
                })
                .setNegativeButton("Later", (dialog, which) -> {
                    notificationPromptShowing = false;
                    log("Clipboard alert notifications are not fully enabled");
                })
                .setOnCancelListener(dialog -> notificationPromptShowing = false)
                .show();
    }

    private void openClipboardAlertSettings() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= 26) {
            intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                    .putExtra(Settings.EXTRA_CHANNEL_ID, ClipSyncService.PENDING_CHANNEL_ID);
        } else {
            intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + getPackageName()));
        }

        startActivity(intent);
    }

    private ClipRelayClient ensureClient() {
        if (client == null) {
            saveDefaults();
            client = new ClipRelayClient(
                    relayInput.getText().toString().trim(),
                    getOrCreateDeviceId(),
                    deviceNameInput.getText().toString().trim(),
                    secretInput.getText().toString()
            );
        }

        return client;
    }

    private void startSyncService() {
        saveDefaults();
        Intent intent = new Intent(this, ClipSyncService.class);
        intent.setAction(ClipSyncService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        statusText.setText("Starting sync");
        log("Foreground sync service starting");
    }

    private void stopSyncService() {
        Intent intent = new Intent(this, ClipSyncService.class);
        intent.setAction(ClipSyncService.ACTION_STOP);
        startService(intent);
        statusText.setText("Stopped");
        log("Foreground sync service stopping");
    }

    private void sendClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        String value = "";

        if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip() != null) {
            ClipData.Item item = clipboard.getPrimaryClip().getItemAt(0);
            CharSequence text = item.coerceToText(this);
            if (text != null) {
                value = text.toString();
            }
        }

        sendInput.setText(value);
        sendText(value);
    }

    private void sendPresetText() {
        String value = "android native test " + System.currentTimeMillis();
        sendInput.setText(value);
        sendText(value);
    }

    private void sendText(String value) {
        ClipRelayClient relayClient = ensureClient();
        runIo(() -> {
            relayClient.register();
            JSONObject result = relayClient.sendText(value);
            ui(() -> log("Sent, queuedFor=" + result.optInt("queuedFor")));
        });
    }

    private void applyPendingRemoteClipboard() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String text = prefs.getString(KEY_PENDING_REMOTE_TEXT, "");
        String source = prefs.getString(KEY_PENDING_REMOTE_SOURCE, "remote device");

        if (text == null || text.isEmpty()) {
            log("No pending remote clipboard");
            return;
        }

        writeClipboard(text);
        receivedText.setText(text);
        prefs.edit()
                .remove(KEY_PENDING_REMOTE_TEXT)
                .remove(KEY_PENDING_REMOTE_SOURCE)
                .remove(KEY_PENDING_REMOTE_CREATED_AT)
                .apply();

        log("Copied pending clipboard from " + source + ": " + shortText(text));
    }

    private ClipRelayClient.MessageHandler handler() {
        return new ClipRelayClient.MessageHandler() {
            @Override
            public void onPlainText(String sourceDeviceId, String text) {
                ui(() -> {
                    receivedText.setText(text);
                    writeClipboard(text);
                    log("Received from " + sourceDeviceId + ": " + shortText(text));
                });
            }

            @Override
            public void onOffer(String sourceDeviceId, JSONObject message) {
                ui(() -> log("Offer from " + sourceDeviceId + ": " + message.optJSONObject("offer").optString("contentType")));
            }

            @Override
            public void onLog(String text) {
                ui(() -> log(text));
            }
        };
    }

    private void writeClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Clip Flow", text));
        }
    }

    private String getOrCreateDeviceId() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String existing = prefs.getString(KEY_DEVICE_ID, null);
        if (existing != null) {
            return existing;
        }

        String created = UUID.randomUUID().toString();
        prefs.edit().putString(KEY_DEVICE_ID, created).apply();
        return created;
    }

    private void runIo(ThrowingRunnable runnable) {
        io.execute(() -> {
            try {
                runnable.run();
            } catch (Exception error) {
                ui(() -> log("Error: " + shortError(error)));
            }
        });
    }

    private void ui(Runnable runnable) {
        main.post(runnable);
    }

    private void log(String text) {
        logText.setText(nowLabel() + "  " + text + "\n" + logText.getText());
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

    private String nowLabel() {
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US);
        return format.format(new java.util.Date());
    }

    private EditText input(String hint, String value, boolean multiline) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(value);
        input.setSingleLine(!multiline);
        input.setMinLines(multiline ? 4 : 1);
        input.setInputType(multiline ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE : InputType.TYPE_CLASS_TEXT);
        input.setPadding(0, 12, 0, 12);
        return input;
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14);
        view.setPadding(0, 12, 0, 8);
        return view;
    }

    private TextView box(String text) {
        TextView view = label(text);
        view.setMinLines(4);
        view.setGravity(Gravity.START);
        view.setBackgroundColor(0xFFF3F6F8);
        view.setPadding(18, 18, 18, 18);
        return view;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        return row;
    }

    private Button button(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(listener);
        button.setAllCaps(false);
        button.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return button;
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
