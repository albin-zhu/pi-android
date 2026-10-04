package dev.zfold.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** User-facing terminal screen backed by the app-private Termux PTY broker. */
public final class TerminalActivity extends Activity {
  private LinearLayout outputContainer;
  private ScrollView scroll;
  private EditText input;
  private TextView status;
  private Handler main;
  private int ptyId;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private Thread reader;
  private MinimapView minimap;
  private final java.util.ArrayList<String> minimapLines = new java.util.ArrayList<>();
  private final Runnable hideMinimap = this::hideMinimap;

  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    main = new Handler(Looper.getMainLooper());
    try { System.loadLibrary("zfold"); } catch (UnsatisfiedLinkError ignored) {}
    build();
  }

  @Override protected void onDestroy() {
    running.set(false);
    if (reader != null) reader.interrupt();
    if (ptyId > 0) { try { PiRuntime.ptyClose(ptyId); } catch (Throwable ignored) {} ptyId = 0; }
    super.onDestroy();
  }

  @Override public void onBackPressed() {
    if (ptyId > 0 && PiRuntime.ptyStatus(ptyId) < 0) {
      new AlertDialog.Builder(this).setTitle("关闭终端")
        .setMessage("PTY 会话中运行的进程也会被终止。确定关闭？")
        .setPositiveButton("关闭", (d, w) -> finish()).setNegativeButton("取消", null).show();
      return;
    }
    super.onBackPressed();
  }

  private void build() {
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(0xFF0D0D0F);
    root.setClickable(true);
    setContentView(root);

    LinearLayout head = new LinearLayout(this);
    head.setGravity(Gravity.CENTER_VERTICAL);
    head.setPadding(dp(14), statusBarHeight() + dp(10), dp(8), dp(8));
    TextView title = text("命令终端", 15, 0xFFF5F5F5, Typeface.create("sans-serif-medium", 0));
    head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    head.addView(action(R.drawable.ic_arrow_clockwise, "新建 PTY 会话", this::openPty), btn(42));
    head.addView(action(R.drawable.ic_x, "关闭终端", this::finish), btn(42));
    root.addView(head);

    status = text("未连接", 11, 0xFF8E8E93, Typeface.MONOSPACE);
    status.setPadding(dp(14), 0, dp(14), dp(6));
    root.addView(status);

    scroll = new ScrollView(this);
    scroll.setVerticalScrollBarEnabled(false);
    scroll.setFillViewport(true);
    outputContainer = new LinearLayout(this);
    outputContainer.setOrientation(LinearLayout.VERTICAL);
    outputContainer.setPadding(dp(12), dp(8), dp(12), dp(16));
    scroll.addView(outputContainer, new ViewGroup.LayoutParams(-1, -2));
    // Wrap scroll in a FrameLayout so minimap can overlay on top.
    FrameLayout scrollHost = new FrameLayout(this);
    scrollHost.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    minimap = new MinimapView(this);
    minimap.setVisibility(View.GONE);
    minimap.setAlpha(0f);
    scrollHost.addView(minimap, new FrameLayout.LayoutParams(dp(44), -1, Gravity.END | Gravity.CENTER_VERTICAL));
    scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> showMinimap());
    root.addView(scrollHost, new LinearLayout.LayoutParams(-1, 0, 1));

    LinearLayout composer = new LinearLayout(this);
    composer.setOrientation(LinearLayout.HORIZONTAL);
    composer.setGravity(Gravity.CENTER_VERTICAL);
    composer.setPadding(dp(10), dp(6), dp(10), dp(4));
    composer.setBackgroundColor(0xFF1A1A1E);
    input = new EditText(this);
    input.setHint("输入命令…");
    input.setHintTextColor(0xFF5A5A5E);
    input.setTextColor(0xFFE5E5E5);
    input.setTextSize(13);
    input.setTypeface(Typeface.MONOSPACE);
    input.setBackground(null);
    input.setImeOptions(EditorInfo.IME_ACTION_SEND);
    input.setRawInputType(InputType.TYPE_CLASS_TEXT);
    input.setOnEditorActionListener((v, actionId, event) -> {
      if (actionId == EditorInfo.IME_ACTION_SEND) { sendEnter(); return true; }
      return false;
    });
    composer.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
    composer.addView(actionText("↵", "发送回车", this::sendEnter));
    composer.addView(actionText("⌃C", "发送 Ctrl+C", () -> writeRaw(new byte[]{3})));
    root.addView(composer, new LinearLayout.LayoutParams(-1, -2));

    LinearLayout keys = new LinearLayout(this);
    keys.setOrientation(LinearLayout.HORIZONTAL);
    keys.setGravity(Gravity.CENTER);
    keys.setPadding(dp(8), dp(3), dp(8), dp(3) + navigationBarHeight());
    keys.setBackgroundColor(0xFF141418);
    for (String label : new String[]{"Tab", "↑", "↓", "ls", "pwd", "clear"}) {
      TextView key = text(label, 11, 0xFFB0B0B5, Typeface.MONOSPACE);
      key.setGravity(Gravity.CENTER);key.setPadding(dp(10), dp(5), dp(10), dp(5));
      key.setClickable(true);key.setFocusable(true);
      key.setOnClickListener(v -> {
        if (ptyId <= 0) { toast("请先新建会话"); return; }
        switch (label) {
          case "Tab": writeRaw(new byte[]{'\t'}); break;
          case "↑": writeRaw(new byte[]{27, '[', 'A'}); break;
          case "↓": writeRaw(new byte[]{27, '[', 'B'}); break;
          case "ls": writeText("ls\n"); break;
          case "pwd": writeText("pwd\n"); break;
          case "clear": writeText("clear\n"); break;
        }
      });
      keys.addView(key, new LinearLayout.LayoutParams(-2, -2));
    }
    root.addView(keys, new LinearLayout.LayoutParams(-1, -2));

    appendLine("点击右上角 ↻ 打开 PTY 会话。", 0xFF6D6D72);
  }

  private void openPty() {
    File bash;
    try { bash = PiPackageManager.executable(this, "bash"); } catch (Exception e) { setStatus("bash 查找失败：" + e.getMessage()); return; }
    if (bash == null) { setStatus("bash 未安装。请在软件包管理中安装 bash。"); return; }
    if (ptyId > 0) { try { PiRuntime.ptyClose(ptyId); } catch (Throwable ignored) {} ptyId = 0; }
    outputContainer.removeAllViews();
    String executable;
    try { executable = bash.getCanonicalPath(); } catch (Exception e) { setStatus("bash 路径读取失败"); return; }
    File storage = new File(Environment.getExternalStorageDirectory(), "pi");
    if (!storage.isDirectory()) storage = getFilesDir();
    File pkgRoot = PiPackageManager.root(this);
    File usr = new File(pkgRoot, "usr");
    new File(pkgRoot, "home").mkdirs();
    new File(pkgRoot, "tmp").mkdirs();
    String[] argv = {executable, "--noprofile", "--norc"};
    String[] env = {
      "PATH=" + usr + "/bin:/system/bin:/system/xbin:/vendor/bin",
      "HOME=" + new File(pkgRoot, "home"),
      "TMPDIR=" + new File(pkgRoot, "tmp"),
      "PREFIX=" + usr,
      "TERM=xterm-256color",
      "TERMINFO=" + usr + "/share/terminfo",
      "LD_LIBRARY_PATH=" + usr + "/lib",
    };
    try {
      ptyId = PiRuntime.ptyOpen(argv, storage.getCanonicalPath(), env, 40, 120);
    } catch (Throwable e) { setStatus("PTY 打开失败：" + e.getMessage()); return; }
    if (ptyId <= 0) { setStatus("PTY 打开失败"); return; }
    setStatus("会话 #" + ptyId + " · bash 已启动");
    appendLine("$ bash (PTY #" + ptyId + ")\n", 0xFF34C759);
    startReader();
    showKeyboard();
  }

  private void startReader() {
    running.set(true);
    reader = new Thread(() -> {
      while (running.get() && ptyId > 0) {
        try {
          String encoded = PiRuntime.ptyRead(ptyId, 300);
          if (encoded == null || encoded.isEmpty()) {
            int st = PiRuntime.ptyStatus(ptyId);
            if (st >= 0) {
              main.post(() -> { setStatus("进程已退出 (" + st + ")"); appendLine("\n[进程已退出 " + st + "]", 0xFFFF9500); running.set(false); });
              break;
            }
            continue;
          }
          byte[] bytes = java.util.Base64.getDecoder().decode(encoded);
          String output = new String(bytes, StandardCharsets.UTF_8);
          main.post(() -> { appendLine(stripAnsi(output), 0xFFE5E5E5); scrollBottom(); });
        } catch (Throwable e) {
          if (!running.get()) break;
          main.post(() -> setStatus("读取错误：" + e.getMessage()));
          break;
        }
      }
    }, "zfold-terminal-read");
    reader.start();
  }

  private void sendEnter() {
    String command = input.getText().toString();
    if (ptyId <= 0) { toast("请先新建会话"); return; }
    writeText(command.isEmpty() ? "\n" : command + "\n");
    input.setText("");
  }

  private void writeText(String value) { writeRaw(value.getBytes(StandardCharsets.UTF_8)); }
  private void writeRaw(byte[] data) {
    if (ptyId <= 0) { toast("会话未打开"); return; }
    try { PiRuntime.ptyWrite(ptyId, data); } catch (Throwable e) { setStatus("写入失败：" + e.getMessage()); }
  }

  private void showKeyboard() {
    input.requestFocus();
    InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
    if (im != null) im.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
  }

  private void appendLine(String value, int color) {
    for (String line : value.split("\n", -1)) minimapLines.add(line);
    if (minimapLines.size() > 2000) minimapLines.subList(0, minimapLines.size() - 2000).clear();
    if (minimap != null) minimap.invalidate();
    TextView line = text(value, 12, color, Typeface.MONOSPACE);
    line.setTextIsSelectable(true);
    outputContainer.addView(line, new LinearLayout.LayoutParams(-1, -2));
  }

  private void scrollBottom() { scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN)); }

  private void showMinimap() {
    if (minimap == null || minimapLines.size() < 5) return;
    minimap.setVisibility(View.VISIBLE);
    minimap.animate().alpha(0.85f).setDuration(120).start();
    minimap.updateViewport(scroll.getScrollY(), scroll.getHeight(), outputContainer.getHeight());
    main.removeCallbacks(hideMinimap);
    main.postDelayed(hideMinimap, 1800);
  }

  private void hideMinimap() {
    if (minimap == null) return;
    minimap.animate().alpha(0f).setDuration(200).withEndAction(() -> minimap.setVisibility(View.GONE)).start();
  }

  /** Scaled-down terminal content preview with viewport indicator and touch-to-jump. */
  private final class MinimapView extends View {
    private final android.graphics.Paint textPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Paint viewportPaint = new android.graphics.Paint();
    private final android.graphics.Paint bgPaint = new android.graphics.Paint();
    private float viewportTop, viewportHeight;
    private int totalContentHeight, visibleHeight;

    MinimapView(Context context) {
      super(context);
      textPaint.setTextSize(dp(3));
      textPaint.setColor(0xFF8E8E93);
      textPaint.setTypeface(Typeface.MONOSPACE);
      viewportPaint.setColor(0x3034C759);
      bgPaint.setColor(0xD0141418);
      setOnTouchListener((v, event) -> {
        if (totalContentHeight <= 0 || visibleHeight <= 0 || visibleHeight >= totalContentHeight) return false;
        float ratio = event.getY() / getHeight();
        int target = (int) (ratio * (totalContentHeight - visibleHeight));
        scroll.smoothScrollTo(0, Math.max(0, Math.min(target, totalContentHeight - visibleHeight)));
        showMinimap();
        return true;
      });
    }

    void updateViewport(int scrollY, int visible, int contentHeight) {
      visibleHeight = visible; totalContentHeight = contentHeight;
      if (contentHeight <= 0 || visible <= 0) { viewportTop = 0; viewportHeight = 1; invalidate(); return; }
      float contentRatio = Math.min(1f, (float) visible / contentHeight);
      float scrollRatio = (float) scrollY / Math.max(1, contentHeight - visible);
      viewportHeight = contentRatio * getHeight();
      viewportTop = scrollRatio * (getHeight() - viewportHeight);
      invalidate();
    }

    @Override protected void onDraw(android.graphics.Canvas canvas) {
      canvas.drawRoundRect(0, 0, getWidth(), getHeight(), dp(6), dp(6), bgPaint);
      int lineHeight = dp(4);
      int maxVisible = (int) ((getHeight() - dp(8)) / lineHeight);
      int total = minimapLines.size();
      int start = Math.max(0, total - maxVisible);
      float x = dp(4);
      float y = dp(4) + textPaint.getTextSize();
      for (int i = start; i < total && y < getHeight() - dp(4); i++) {
        String line = minimapLines.get(i);
        if (!line.trim().isEmpty()) canvas.drawText(line, 0, Math.min(line.length(), 28), x, y, textPaint);
        y += lineHeight;
      }
      if (viewportHeight > 0 && viewportHeight < getHeight()) {
        canvas.drawRoundRect(0, viewportTop, getWidth(), viewportTop + viewportHeight, dp(3), dp(3), viewportPaint);
      }
    }
  }
  private void setStatus(String value) { main.post(() -> { if (status != null && !isFinishing()) status.setText(value); }); }
  private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_SHORT).show(); }

  private static String stripAnsi(String value) {
    if (value == null) return "";
    return value.replaceAll("\u001B\\[[0-9;]*[A-Za-z]", "").replaceAll("\u001B\\][^\u0007]*\u0007", "").replaceAll("\r", "");
  }

  private int statusBarHeight() {
    int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
    return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
  }
  private int navigationBarHeight() {
    int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
    return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
  }
  private TextView text(String value, int size, int color, Typeface face) {
    TextView view = new TextView(this);
    view.setText(value);view.setTextSize(size);view.setTextColor(color);view.setTypeface(face);
    return view;
  }
  private ImageView action(int icon, String desc, Runnable tap) {
    ImageView view = new ImageView(this);
    view.setImageDrawable(Ui.icon(this, icon, 0xFFB0B0B5));
    view.setContentDescription(desc);view.setClickable(true);view.setFocusable(true);
    view.setBackground(Ui.ripple(this, Color.TRANSPARENT, 0, 0xFFE5E5E5));
    view.setOnClickListener(v -> { Ui.click(view); tap.run(); });
    return view;
  }
  private TextView actionText(String label, String desc, Runnable tap) {
    TextView view = text(label, 12, 0xFF34C759, Typeface.MONOSPACE);
    view.setGravity(Gravity.CENTER);view.setPadding(dp(8), dp(6), dp(8), dp(6));
    view.setContentDescription(desc);view.setClickable(true);view.setFocusable(true);
    view.setOnClickListener(v -> { Ui.click(view); tap.run(); });
    return view;
  }
  private LinearLayout.LayoutParams btn(int size) {
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(size), dp(size));
    params.leftMargin = dp(4);
    return params;
  }
  private int dp(int value) { return Ui.dp(this, value); }
}
