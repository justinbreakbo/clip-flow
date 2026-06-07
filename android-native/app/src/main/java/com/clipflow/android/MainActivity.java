package com.clipflow.android;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
    private Button watchButton;

    private ClipRelayClient client;
    private boolean watching = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLayout());
        restoreDefaults();
        handleShareIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShareIntent(intent);
    }

    @Override
    protected void onDestroy() {
        watching = false;
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

        statusText = label("未连接");
        root.addView(statusText);

        relayInput = input("Relay URL", "http://127.0.0.1:42821", false);
        secretInput = input("共享 Secret", "change-this-local-secret", false);
        deviceNameInput = input("设备名", "Android", false);
        sendInput = input("发送内容", "hello from native Android", true);

        root.addView(relayInput);
        root.addView(secretInput);
        root.addView(deviceNameInput);
        root.addView(sendInput);

        autoAcceptInput = new CheckBox(this);
        autoAcceptInput.setText("自动接收 offer（测试用）");
        autoAcceptInput.setChecked(true);
        root.addView(autoAcceptInput);

        LinearLayout row1 = row();
        row1.addView(button("连接 Relay", view -> connect()));
        row1.addView(button("发送文本", view -> sendText()));
        root.addView(row1);

        LinearLayout row2 = row();
        row2.addView(button("发送剪贴板", view -> sendClipboard()));
        row2.addView(button("拉取一次", view -> pollOnce()));
        root.addView(row2);

        LinearLayout row3 = row();
        row3.addView(button("发送测试文本", view -> sendPresetText()));
        row3.addView(button("发送敏感测试", view -> sendSensitivePreset()));
        root.addView(row3);

        watchButton = button("开始接收", view -> toggleWatch());
        root.addView(watchButton);

        TextView receivedLabel = label("最近接收");
        receivedLabel.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(receivedLabel);

        receivedText = box("等待内容");
        root.addView(receivedText);

        TextView logLabel = label("日志");
        logLabel.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(logLabel);

        logText = box("");
        root.addView(logText);

        return scroll;
    }

    private void restoreDefaults() {
        SharedPreferences prefs = getSharedPreferences("clip-flow", MODE_PRIVATE);
        relayInput.setText(prefs.getString("relay", relayInput.getText().toString()));
        secretInput.setText(prefs.getString("secret", secretInput.getText().toString()));
        deviceNameInput.setText(prefs.getString("name", deviceNameInput.getText().toString()));
        log("原生 Android 工程已启动");
    }

    private void saveDefaults() {
        getSharedPreferences("clip-flow", MODE_PRIVATE)
                .edit()
                .putString("relay", relayInput.getText().toString())
                .putString("secret", secretInput.getText().toString())
                .putString("name", deviceNameInput.getText().toString())
                .apply();
    }

    private void handleShareIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) {
            return;
        }

        CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (text != null) {
            sendInput.setText(text.toString());
            log("已从分享菜单接收文本");
        }
    }

    private void connect() {
        saveDefaults();
        String deviceId = getOrCreateDeviceId();
        client = new ClipRelayClient(
                relayInput.getText().toString().trim(),
                deviceId,
                deviceNameInput.getText().toString().trim(),
                secretInput.getText().toString()
        );

        runIo(() -> {
            client.register();
            ui(() -> {
                statusText.setText("已连接 " + deviceId.substring(0, 8));
                log("已连接 relay");
            });
        });
    }

    private void sendText() {
        ensureClient();
        String value = sendInput.getText().toString();
        runIo(() -> {
            JSONObject result = client.sendText(value);
            ui(() -> log("已发送，queuedFor=" + result.optInt("queuedFor")));
        });
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
        sendText();
    }

    private void sendPresetText() {
        sendInput.setText("android native test " + System.currentTimeMillis());
        sendText();
    }

    private void sendSensitivePreset() {
        sendInput.setText("api_key=androidnative" + System.currentTimeMillis());
        sendText();
    }

    private void pollOnce() {
        ensureClient();
        runIo(() -> client.pollOnce(0, autoAcceptInput.isChecked(), handler()));
    }

    private void toggleWatch() {
        ensureClient();
        watching = !watching;
        watchButton.setText(watching ? "停止接收" : "开始接收");

        if (watching) {
            runIo(this::watchLoop);
        }
    }

    private void watchLoop() throws Exception {
        while (watching && !Thread.currentThread().isInterrupted()) {
            try {
                client.pollOnce(25000, autoAcceptInput.isChecked(), handler());
            } catch (Exception error) {
                ui(() -> log("接收连接中断，正在重试：" + shortError(error)));
                Thread.sleep(1200);
            }
        }
    }

    private ClipRelayClient.MessageHandler handler() {
        return new ClipRelayClient.MessageHandler() {
            @Override
            public void onPlainText(String sourceDeviceId, String text) {
                ui(() -> {
                    receivedText.setText(text);
                    writeClipboard(text);
                    log("收到并写入剪贴板：" + shortText(text));
                });
            }

            @Override
            public void onOffer(String sourceDeviceId, JSONObject message) {
                ui(() -> log("收到待确认 offer：" + message.optJSONObject("offer").optString("contentType")));
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

    private void ensureClient() {
        if (client == null) {
            connect();
        }
    }

    private String getOrCreateDeviceId() {
        SharedPreferences prefs = getSharedPreferences("clip-flow", MODE_PRIVATE);
        String existing = prefs.getString("deviceId", null);
        if (existing != null) {
            return existing;
        }

        String created = UUID.randomUUID().toString();
        prefs.edit().putString("deviceId", created).apply();
        return created;
    }

    private void runIo(ThrowingRunnable runnable) {
        io.execute(() -> {
            try {
                runnable.run();
            } catch (Exception error) {
                ui(() -> log("错误：" + error.getMessage()));
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
