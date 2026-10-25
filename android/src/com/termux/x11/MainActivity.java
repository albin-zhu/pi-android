package com.termux.x11;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.graphics.Typeface;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import dev.zfold.assistant.NavigableActivity;
import dev.zfold.assistant.R;
import dev.zfold.assistant.Ui;
import dev.zfold.assistant.VirtualScreenActivity;
import java.util.ArrayList;

/** Window manager for the single authenticated :9 server. Rendering lives in X11Service. */
public final class MainActivity extends NavigableActivity implements RootlessClient.Listener {
    private static final String EXTRA_READ_ONLY = "com.termux.x11.READ_ONLY";
    private RootlessClient client;
    private TextView status;
    private LinearLayout windows;
    private Ui.Theme theme;
    private String session;
    private boolean readOnly;
    private ArrayList<Bundle> snapshot = new ArrayList<>();

    public static void open(Context context) { open(context, false); }
    public static void openReadOnly(Context context) { open(context, true); }
    private static void open(Context context, boolean readOnly) {
        Intent intent = new Intent(context, MainActivity.class).putExtra(EXTRA_READ_ONLY, readOnly)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
    // Registered by the pinned engine's JNI_OnLoad, even though this UI uses document tasks.
    private static native void nativeSetLaunchWindowingModeFreeform(Object options);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        readOnly = getIntent().getBooleanExtra(EXTRA_READ_ONLY, false);
        buildContent(); client = new RootlessClient(this, this, readOnly);
        startDisplay();
    }
    private void buildContent() {
        CharSequence previousStatus = status == null ? "正在连接 X11 :9…" : status.getText();
        theme = new Ui.Theme(this);
        setTitle(readOnly ? "X11 :9 · 只读窗口" : "X11 :9 · 应用窗口");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(theme.page);
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(8), dp(4), dp(8), dp(4));
        toolbar.setBackgroundColor(theme.surface);
        Ui.Action back = action(R.drawable.ic_caret_down, "", "返回", this::onBackPressed);
        back.getChildAt(0).setRotation(90);
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = Ui.text(this, "X11 :9", 18, theme.ink);
        title.setTypeface(Typeface.create("sans-serif-medium", 0));
        title.setPadding(dp(8), 0, dp(4), 0);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        toolbar.addView(action(R.drawable.ic_arrow_clockwise, "", "启动或重新连接", this::startDisplay),
                new LinearLayout.LayoutParams(dp(48), dp(48)));
        if (!readOnly) {
            Ui.Action more = action(R.drawable.ic_dots_three, "", "更多操作", () -> {
                PopupMenu menu = new PopupMenu(this, toolbar, Gravity.END);
                menu.getMenu().add("停止 X11 服务").setOnMenuItemClickListener(item -> {
                    new android.app.AlertDialog.Builder(this)
                            .setTitle("停止 X11 服务？")
                            .setMessage("停止 :9 会断开所有图形应用。是否继续？")
                            .setPositiveButton("停止", (dialog, which) -> startService(
                                    new Intent(this, X11Service.class).setAction(X11Service.ACTION_STOP)))
                            .setNegativeButton("取消", null).show();
                    return true;
                });
                menu.show();
            });
            toolbar.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(20));
        TextView mode = Ui.text(this, readOnly ? "只读预览" : "交互模式", 14, theme.accent);
        mode.setTypeface(Typeface.create("sans-serif-medium", 0));
        content.addView(mode);
        TextView explanation = Ui.text(this, readOnly ? "仅查看画面，不发送键盘或触控输入" : "选择应用窗口，使用键盘和触控交互", 13, theme.muted);
        explanation.setPadding(0, dp(6), 0, dp(10));
        content.addView(explanation);
        status = Ui.text(this, "", 12, theme.muted);
        status.setText(previousStatus);
        status.setTextIsSelectable(true);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setPadding(dp(12), dp(10), dp(12), dp(10));
        status.setBackground(Ui.shape(this, theme.field, 12, 0));
        content.addView(status, new LinearLayout.LayoutParams(-1, -2));
        if (readOnly) {
            addEntry(content, action(R.drawable.ic_screen_share, "整个桌面 · 只读", "打开整个桌面，只读", () -> {
                if (session == null) return;
                Bundle desktop = new Bundle(); desktop.putLong("window", 0); desktop.putLong("generation", 0);
                WindowActivity.open(this, session, desktop, true);
            }));
            addEntry(content, action(R.drawable.ic_workbench, "Xvfb 兼容预览", "打开 Xvfb 兼容预览",
                    () -> VirtualScreenActivity.openXvfb(this, null)));
        }
        TextView heading = Ui.text(this, "应用窗口", 14, theme.ink);
        heading.setTypeface(Typeface.create("sans-serif-medium", 0));
        heading.setPadding(0, dp(22), 0, dp(4));
        content.addView(heading);
        windows = new LinearLayout(this);
        windows.setOrientation(LinearLayout.VERTICAL);
        content.addView(windows, new LinearLayout.LayoutParams(-1, -2));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        showWindows();
    }
    private int dp(int value) { return Ui.dp(this, value); }
    private Ui.Action action(int icon, String label, String description, Runnable callback) {
        Ui.Action action = new Ui.Action(this, icon, label, description, theme.ink, theme.surface, callback);
        if (Build.VERSION.SDK_INT >= 26) action.setTooltipText(description);
        return action;
    }
    private void addEntry(LinearLayout parent, View view) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(10);
        parent.addView(view, params);
    }
    private void startDisplay() {
        try { X11Service.start(this); }
        catch (RuntimeException error) { status.setText("无法启动 X11：" + error.getMessage()); }
    }
    private void showWindows() {
        windows.removeAllViews();
        if (snapshot.isEmpty()) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setPadding(dp(16), dp(20), dp(16), dp(20));
            empty.setBackground(Ui.shape(this, theme.card, 16, 1, theme.line));
            TextView title = Ui.text(this, "暂无应用窗口", 16, theme.ink);
            title.setTypeface(Typeface.create("sans-serif-medium", 0));
            empty.addView(title);
            TextView hint = Ui.text(this, "请在 Ubuntu 终端中使用 DISPLAY=:9 启动图形应用。\n离开此页不会停止 X11 服务。", 13, theme.muted);
            hint.setPadding(0, dp(8), 0, 0);
            hint.setLineSpacing(dp(3), 1f);
            empty.addView(hint);
            addEntry(windows, empty);
        }
        for (Bundle info : snapshot) {
            String title = info.getString("title", "X11 应用");
            if (title == null || title.trim().isEmpty()) title = "X11 应用";
            int width = info.getInt("width"), height = info.getInt("height");
            String dimensions = width > 0 && height > 0 ? width + " × " + height : "尺寸未知";
            String detail = dimensions + (readOnly ? " · 只读预览" : " · 点击打开，长按显示键盘");
            String popup = X11Service.popupDescription(info.getString("popupState"));
            if (!popup.isEmpty()) detail += "\n" + popup;
            LinearLayout card = new LinearLayout(this);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setMinimumHeight(dp(80));
            card.setPadding(dp(14), dp(14), dp(14), dp(14));
            card.setBackground(Ui.shape(this, theme.card, 16, 1, theme.line));
            card.setForeground(Ui.ripple(this, android.graphics.Color.TRANSPARENT, 16, theme.ink));
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(Ui.icon(this, R.drawable.ic_workbench, theme.accent));
            icon.setPadding(dp(9), dp(9), dp(9), dp(9));
            icon.setBackground(Ui.shape(this, theme.accentSoft, 12, 0));
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            card.addView(icon, new LinearLayout.LayoutParams(dp(39), dp(39)));
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            TextView name = Ui.text(this, title, 15, theme.ink);
            name.setTypeface(Typeface.create("sans-serif-medium", 0));
            labels.addView(name);
            TextView description = Ui.text(this, detail, 12, theme.muted);
            description.setPadding(0, dp(6), 0, 0);
            labels.addView(description);
            labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
            labelParams.leftMargin = dp(12);
            card.addView(labels, labelParams);
            card.setContentDescription(title + "，" + detail);
            if (Build.VERSION.SDK_INT >= 26) card.setTooltipText(title + "，" + detail);
            card.setFocusable(true);
            card.setOnClickListener(view -> {
                Ui.click(view);
                WindowActivity.open(this, session, info, readOnly);
            });
            if (!readOnly) card.setOnLongClickListener(view -> {
                Ui.click(view);
                WindowActivity.openKeyboard(this, session, info);
                return true;
            });
            addEntry(windows, card);
        }
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        readOnly = intent.getBooleanExtra(EXTRA_READ_ONLY, false); buildContent();
        client.setReadOnly(readOnly);
    }
    @Override protected void onStart() { super.onStart(); if (client != null) client.start(); }
    @Override protected void onStop() { if (client != null) client.stop(); super.onStop(); }
    @Override public void reply(int what, Bundle data) {
        session = data.getString("session");
        if (what == X11Service.STATUS) status.setText(data.getString("status"));
        else if (what == X11Service.WINDOWS) {
            ArrayList<Bundle> next = data.getParcelableArrayList("windows");
            snapshot = next == null ? new ArrayList<>() : next; showWindows();
        }
    }
    @Override public void disconnected() {
        session = null; snapshot.clear(); showWindows();
        status.setText("正在重新连接 X11 服务…");
    }
    // Compatibility callbacks for the pinned LorieView ABI; rootless UI never constructs it.
    void onRendererReady() { status.setText("X11 渲染器已连接"); }
    void onGeometryError(String message) { status.setText("X11 画面尺寸错误：" + message); }
}
