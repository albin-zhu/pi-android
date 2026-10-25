package dev.zfold.assistant;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Rational;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Default entry for native read-only :9, with an explicit Xvfb/JPEG compatibility viewer.
 *
 * The Xvfb server keeps running when this activity closes; only this viewer's
 * capture relay and socket are detached. No input is forwarded
 * to the virtual display. Layout is deliberately canvas-first: the picture is
 * letterboxed, chrome floats over it and hides itself. A bottom sheet is used
 * for display selection so the same layout works on the cover screen, the
 * unfolded screen, split screen and half-fold postures.
 */
public final class VirtualScreenActivity extends NavigableActivity {
  private static final String EXTRA_DISPLAY = "display";
  private static final String ACTION_XVFB = "dev.zfold.assistant.OPEN_XVFB_PREVIEW";
  private static final String MAGIC = "XMF1";
  private static final int MAX_FRAME = 8 * 1024 * 1024;
  private static final int AUTO_HIDE_MS = 4200;
  private static final Pattern SOCKET = Pattern.compile("X(\\d+)");

  private Ui.Theme theme;
  private FrameLayout root;
  private FrameLayout stage;
  private ImageView image;
  private View tapLayer;
  private LinearLayout topBar;
  private TextView title;
  private TextView subtitle;
  private TextView chip;
  private LinearLayout empty;
  private TextView emptyTitle;
  private TextView emptyBody;
  private LinearLayout emptyActions;
  private ProgressBar emptyProgress;
  private FrameLayout pickerLayer;
  private LinearLayout pickerCard;
  private LinearLayout pickerList;
  private Insets bars = Insets.NONE;

  private boolean chromeVisible = true;
  private boolean streaming;
  private boolean screenOn;
  private boolean stoppedByLifecycle;
  private volatile boolean wanted;
  private volatile long generation;
  private String display;
  private Bitmap current;
  private volatile LocalSocket socket;
  private Process relay;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final StringBuilder relayLog = new StringBuilder();
  private final Runnable hideChrome = () -> setChrome(false);

  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    if (routeNative(getIntent())) return;
    theme = new Ui.Theme(this);
    getWindow().setStatusBarColor(Color.BLACK);
    getWindow().setNavigationBarColor(Color.BLACK);
    getWindow().setDecorFitsSystemWindows(false);
    getWindow().getDecorView().setSystemUiVisibility(0);
    build();
    int requested = requestedDisplay(getIntent());
    if (requested >= 0) {
      scan(false);
      startStream(requested);
    } else {
      scan(true);
      showPicker();
    }
  }

  static void open(Context context, String display) {
    int requested = number(display);
    if (requested < 0 || requested == 9) {
      com.termux.x11.MainActivity.openReadOnly(
          context instanceof LauncherActivity ? context.getApplicationContext() : context);
      return;
    }
    openXvfb(context, ":" + requested);
  }

  public static void openXvfb(Context context, String display) {
    if (number(display) == 9) {
      open(context, display);
      return;
    }
    Intent intent = new Intent(context, VirtualScreenActivity.class)
        .setAction(ACTION_XVFB).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
    if (!(context instanceof Activity) || context instanceof LauncherActivity)
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    if (display != null && !display.trim().isEmpty())
      intent.putExtra(EXTRA_DISPLAY, display.trim());
    context.startActivity(intent);
  }

  @Override protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    if (routeNative(intent)) return;
    int requested = requestedDisplay(intent);
    if (requested >= 0) startStream(requested);
    else {
      wanted = false;
      stopStream(true);
      scan(false);
      showPicker();
    }
  }

  private boolean routeNative(Intent intent) {
    int requested = requestedDisplay(intent);
    if (requested != 9 && (requested >= 0
        || (intent != null && ACTION_XVFB.equals(intent.getAction())))) return false;
    wanted = false;
    // Root shortcuts keep an independent viewer task; in-app entries keep their parent.
    // Build a fresh native intent through the API, never forwarding external extras.
    com.termux.x11.MainActivity.openReadOnly(isTaskRoot() ? getApplicationContext() : this);
    finish();
    return true;
  }

  private static int requestedDisplay(Intent intent) {
    return intent == null ? -1 : number(intent.getStringExtra(EXTRA_DISPLAY));
  }

  @Override public void onConfigurationChanged(Configuration configuration) {
    super.onConfigurationChanged(configuration);
    if (root != null) {
      root.requestApplyInsets();
      applySizes();
    }
  }

  private void build() {
    root = new FrameLayout(this);
    root.setBackgroundColor(Color.BLACK);
    stage = new FrameLayout(this);
    stage.setBackgroundColor(Color.BLACK);

    image = new ImageView(this);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    image.setBackgroundColor(Color.BLACK);
    image.setContentDescription("Xvfb/JPEG 兼容预览画面，只读");
    stage.addView(image, match());

    tapLayer = new View(this);
    tapLayer.setBackgroundColor(Color.TRANSPARENT);
    tapLayer.setContentDescription("显示或隐藏控制栏");
    tapLayer.setFocusable(true);
    tapLayer.setOnClickListener(v -> {
      Ui.click(v);
      setChrome(!chromeVisible);
    });
    stage.addView(tapLayer, match());

    topBar = buildTopBar();
    stage.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

    empty = buildEmpty();
    stage.addView(empty, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
    empty.setVisibility(View.GONE);

    chip = Ui.text(this, "只读 · 不会向虚拟屏发送操作", 12, Color.WHITE);
    chip.setPadding(dp(12), dp(7), dp(12), dp(7));
    chip.setBackground(Ui.shape(this, 0x99000000, 20, 0));
    chip.setSingleLine(true);
    chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
    chip.setMaxWidth(dp(340));
    chip.setVisibility(View.INVISIBLE);
    stage.addView(chip, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

    root.addView(stage, match());
    pickerLayer = buildPicker();
    root.addView(pickerLayer, match());
    root.setOnApplyWindowInsetsListener((view, insets) -> {
      bars = insets.getInsets(WindowInsets.Type.systemBars()
          | WindowInsets.Type.displayCutout());
      applySizes();
      return insets;
    });
    setContentView(root);
    applySizes();
  }

  private LinearLayout buildTopBar() {
    LinearLayout bar = new LinearLayout(this);
    bar.setOrientation(LinearLayout.HORIZONTAL);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    GradientDrawable scrim = new GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xE6000000, 0x00000000});
    bar.setBackground(scrim);
    bar.addView(actionButton("关闭", "关闭虚拟屏",
        Color.WHITE, 0x33000000, this::finish), new LinearLayout.LayoutParams(-2, dp(48)));

    LinearLayout labels = new LinearLayout(this);
    labels.setOrientation(LinearLayout.VERTICAL);
    title = Ui.text(this, "Xvfb/JPEG 兼容预览", 17, Color.WHITE);
    title.setTypeface(Typeface.create("sans-serif-medium", 0));
    title.setSingleLine(true);
    labels.addView(title);
    subtitle = Ui.text(this, "仅兼容旧任务 · JPEG 只读画面", 12, 0xCCFFFFFF);
    subtitle.setSingleLine(true);
    subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
    LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-2, -2);
    subtitleParams.topMargin = dp(2);
    labels.addView(subtitle, subtitleParams);
    bar.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

    bar.addView(actionButton("切换", "选择虚拟屏",
        Color.WHITE, 0x33000000, this::showPicker), new LinearLayout.LayoutParams(-2, dp(48)));
    if (supportsPip()) {
      bar.addView(actionButton("画中画", "画中画",
          Color.WHITE, 0x33000000, this::enterPip), new LinearLayout.LayoutParams(-2, dp(48)));
    }
    return bar;
  }

  private LinearLayout buildEmpty() {
    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setGravity(Gravity.CENTER_HORIZONTAL);
    card.setBackground(Ui.card(this, theme.surface, 22, theme.line));
    card.setPadding(dp(22), dp(20), dp(22), dp(20));

    emptyProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
    if (emptyProgress.getIndeterminateDrawable() != null)
      emptyProgress.getIndeterminateDrawable().setTint(theme.accent);
    emptyProgress.setVisibility(View.GONE);
    card.addView(emptyProgress, new LinearLayout.LayoutParams(dp(26), dp(26)));

    emptyTitle = Ui.text(this, "没有发现虚拟屏", 18, theme.ink);
    emptyTitle.setTypeface(Typeface.create("sans-serif-medium", 0));
    emptyTitle.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-2, -2);
    titleParams.topMargin = dp(10);
    card.addView(emptyTitle, titleParams);

    emptyBody = Ui.text(this, "", 13, theme.muted);
    emptyBody.setGravity(Gravity.CENTER);
    emptyBody.setLineSpacing(dp(3), 1f);
    emptyBody.setMaxWidth(dp(340));
    LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-2, -2);
    bodyParams.topMargin = dp(6);
    card.addView(emptyBody, bodyParams);

    emptyActions = new LinearLayout(this);
    emptyActions.setGravity(Gravity.CENTER_HORIZONTAL);
    emptyActions.setPadding(0, dp(14), 0, 0);
    card.addView(emptyActions, new LinearLayout.LayoutParams(-2, -2));
    return card;
  }

  private FrameLayout buildPicker() {
    FrameLayout layer = new FrameLayout(this);
    layer.setVisibility(View.GONE);
    layer.setClickable(true);
    View scrim = new View(this);
    scrim.setBackgroundColor(0xB3000000);
    scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    scrim.setOnClickListener(v -> hidePicker());
    layer.addView(scrim, match());

    pickerCard = new LinearLayout(this);
    pickerCard.setOrientation(LinearLayout.VERTICAL);
    pickerCard.setBackground(Ui.card(this, theme.surface, 22, theme.line));
    pickerCard.setPadding(dp(18), dp(14), dp(18), dp(10));
    pickerCard.setClickable(true);
    layer.addView(pickerCard, new FrameLayout.LayoutParams(-2, -2,
        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

    View handle = new View(this);
    handle.setBackground(Ui.shape(this, theme.line, 3, 0));
    LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(dp(36), dp(4));
    handleParams.gravity = Gravity.CENTER_HORIZONTAL;
    handleParams.bottomMargin = dp(12);
    pickerCard.addView(handle, handleParams);

    LinearLayout header = new LinearLayout(this);
    header.setGravity(Gravity.CENTER_VERTICAL);
    TextView heading = Ui.text(this, "选择 Xvfb 兼容显示", 17, theme.ink);
    heading.setTypeface(Typeface.create("sans-serif-medium", 0));
    header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
    header.addView(actionButton("刷新", "刷新虚拟屏列表",
        theme.ink, Color.TRANSPARENT, () -> scan(false)),
        new LinearLayout.LayoutParams(-2, dp(48)));
    header.addView(actionButton("关闭", "关闭选择",
        theme.ink, Color.TRANSPARENT, this::hidePicker),
        new LinearLayout.LayoutParams(-2, dp(48)));
    pickerCard.addView(header, new LinearLayout.LayoutParams(-1, -2));

    TextView hint = Ui.text(this, "JPEG 只读预览 · 非原生显示加速 · 不发送操作", 12, theme.muted);
    hint.setPadding(0, dp(2), 0, dp(10));
    pickerCard.addView(hint);

    ScrollView scroll = new ScrollView(this);
    scroll.setVerticalScrollBarEnabled(true);
    pickerList = new LinearLayout(this);
    pickerList.setOrientation(LinearLayout.VERTICAL);
    scroll.addView(pickerList);
    pickerCard.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    return layer;
  }

  private void applySizes() {
    if (topBar == null) return;
    topBar.setPadding(dp(8) + bars.left, dp(6) + bars.top, dp(8) + bars.right, dp(18));

    Rect bounds = getWindowManager().getCurrentWindowMetrics().getBounds();
    FrameLayout.LayoutParams emptyParams = (FrameLayout.LayoutParams) empty.getLayoutParams();
    emptyParams.width = Math.min(dp(420),
        Math.max(dp(200), bounds.width() - bars.left - bars.right - dp(48)));
    emptyParams.leftMargin = bars.left;
    emptyParams.rightMargin = bars.right;
    empty.setLayoutParams(emptyParams);

    FrameLayout.LayoutParams chipParams = (FrameLayout.LayoutParams) chip.getLayoutParams();
    chipParams.bottomMargin = bars.bottom + dp(14);
    chipParams.leftMargin = bars.left + dp(12);
    chipParams.rightMargin = bars.right + dp(12);
    chip.setLayoutParams(chipParams);

    FrameLayout.LayoutParams cardParams = (FrameLayout.LayoutParams) pickerCard.getLayoutParams();
    int availableWidth = bounds.width() - bars.left - bars.right - dp(32);
    cardParams.width = Math.min(dp(520), Math.max(dp(180), availableWidth));
    cardParams.height = Math.max(dp(160),
        Math.min(dp(440), (int) (bounds.height() * 0.62f)));
    cardParams.bottomMargin = bars.bottom + dp(12);
    cardParams.leftMargin = bars.left + dp(16);
    cardParams.rightMargin = bars.right + dp(16);
    cardParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
    pickerCard.setLayoutParams(cardParams);
  }

  private void scan(boolean initial) {
    List<Integer> found = displays();
    renderDisplays(found);
    if (found.isEmpty()) {
      if (streaming) stopStream(false);
      showEmpty("没有发现 Xvfb 兼容显示",
          "旧任务可在 Ubuntu 中启动 Xvfb 后点刷新。新图形任务请使用原生 :9 虚拟屏。",
          "刷新", () -> scan(false), false);
      subtitle.setText("Xvfb/JPEG 兼容预览 · 只读");
      return;
    }
    if (display != null && found.contains(number(display))) return;
    if (streaming) stopStream(false);
    // Never auto-connect an unverified socket: stale Xvfb sockets survive a
    // process/app restart and would otherwise open on a dead display.
    showEmpty("选择一个 Xvfb 兼容显示",
        found.size() == 1 ? "发现 1 个显示，点按选择；不会自动连接。" :
            "发现 " + found.size() + " 个显示。选择一个观看，画面只读。",
        "选择", this::showPicker, false);
  }

  private List<Integer> displays() {
    TreeSet<Integer> numbers = new TreeSet<>();
    File directory = new File(getFilesDir(), "runtime/x11/tmp/.X11-unix");
    String[] names = directory.list();
    if (names != null) for (String name : names) {
      Matcher matcher = SOCKET.matcher(name);
      if (!matcher.matches()) continue;
      try {
        int number = Integer.parseInt(matcher.group(1));
        if (number != 9) numbers.add(number);
      } catch (NumberFormatException ignored) {
      }
    }
    return new ArrayList<>(numbers);
  }

  private void renderDisplays(List<Integer> found) {
    if (pickerList == null) return;
    pickerList.removeAllViews();
    if (found.isEmpty()) {
      TextView none = Ui.text(this, "没有发现 Xvfb 兼容显示。启动 Xvfb 后点刷新。", 13, theme.muted);
      none.setPadding(0, dp(12), 0, dp(12));
      pickerList.addView(none, new LinearLayout.LayoutParams(-1, -2));
      return;
    }
    for (int number : found) pickerList.addView(displayRow(number),
        new LinearLayout.LayoutParams(-1, -2));
  }

  private View displayRow(int number) {
    String value = ":" + number;
    boolean active = value.equals(display);
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(dp(60));
    row.setPadding(dp(12), dp(10), dp(12), dp(10));
    row.setBackground(Ui.ripple(this, active ? theme.accentSoft : Color.TRANSPARENT, 14, theme.accent));

    LinearLayout labels = new LinearLayout(this);
    labels.setOrientation(LinearLayout.VERTICAL);
    TextView name = Ui.text(this, "Xvfb " + value, 15, active ? theme.accent : theme.ink);
    name.setTypeface(Typeface.create("sans-serif-medium", 0));
    labels.addView(name);
    TextView detail = Ui.text(this, active ? "正在观看 · 只读" : "点按观看",
        12, active ? theme.accent : theme.muted);
    detail.setPadding(0, dp(3), 0, 0);
    labels.addView(detail);
    row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));

    TextView arrow = Ui.text(this, "›", 20, active ? theme.accent : theme.muted);
    arrow.setGravity(Gravity.CENTER);
    row.addView(arrow, new LinearLayout.LayoutParams(dp(18), -2));
    row.setContentDescription("虚拟屏 " + value + "，" + (active ? "正在观看" : "点按观看"));
    row.setFocusable(true);
    row.setOnClickListener(v -> {
      Ui.click(v);
      if (active && streaming) {
        hidePicker();
        return;
      }
      startStream(number);
      hidePicker();
    });
    return row;
  }

  private void startStream(int number) {
    if (number < 0 || number == 9) return;
    stopStream(false);
    final long token = ++generation;
    display = ":" + number;
    wanted = true;
    streaming = true;
    if (current != null) {
      image.setImageDrawable(null);
      current.recycle();
      current = null;
    }
    showEmpty("正在连接 " + display, "只读画面即将出现…", null, null, true);
    subtitle.setText(display + " · 正在连接");
    setChrome(true);

    if (getApplicationInfo().targetSdkVersion != 28) {
      streaming = false;
      showError("需要 compat28 安装包才能启动 Ubuntu 中继。");
      return;
    }
    File runtime = new File(getFilesDir(), "runtime");
    File ubuntu = new File(runtime, "bin/ubuntu");
    if (!new File(runtime, "ubuntu/usr/bin/env").isFile()) {
      streaming = false;
      showError("Ubuntu 运行环境尚未安装。请先在终端安装 Ubuntu。");
      return;
    }
    try {
      ensureRelayScript();
    } catch (IOException error) {
      streaming = false;
      showError("无法准备虚拟屏组件：" + message(error));
      return;
    }
    synchronized (relayLog) { relayLog.setLength(0); }
    try {
      ProcessBuilder builder = new ProcessBuilder(ubuntu.getPath(), "/usr/bin/python3",
          "/opt/pi-phone/x11-mirror.py", display,
          "/run/zfold-x11/mirror-" + number + ".sock");
      builder.directory(runtime);
      builder.redirectErrorStream(true);
      RuntimeEnvironment.apply(builder.environment(), RuntimeEnvironment.base(this), null);
      relay = builder.start();
    } catch (Exception error) {
      streaming = false;
      showError("无法启动虚拟屏中继：" + message(error));
      return;
    }
    drainRelay(relay);
    Thread reader = new Thread(() -> readFrames(number, token), "virtual-screen-" + number);
    reader.setDaemon(true);
    reader.start();
  }

  private void readFrames(int number, long token) {
    File path = new File(getFilesDir(), "runtime/x11/mirror-" + number + ".sock");
    LocalSocket local = null;
    try {
      long deadline = SystemClock.elapsedRealtime() + 9000;
      while (token == generation && streaming && SystemClock.elapsedRealtime() < deadline) {
        try {
          LocalSocket candidate = new LocalSocket();
          candidate.connect(new LocalSocketAddress(path.getPath(),
              LocalSocketAddress.Namespace.FILESYSTEM));
          candidate.setSoTimeout(20000);
          local = candidate;
          break;
        } catch (IOException error) {
          if (relay != null && !relay.isAlive()) break;
          SystemClock.sleep(120);
        }
      }
      if (local == null) throw new IOException("虚拟屏中继没有响应" + relayHint());
      socket = local;
      DataInputStream input = new DataInputStream(new BufferedInputStream(local.getInputStream()));
      byte[] magic = new byte[4];
      input.readFully(magic);
      if (!MAGIC.equals(new String(magic, StandardCharsets.US_ASCII)))
        throw new IOException("虚拟屏中继协议不匹配");
      int size = input.readInt();
      if (size < 0 || size > 65536) throw new IOException("虚拟屏中继握手无效");
      byte[] payload = new byte[size];
      input.readFully(payload);
      JSONObject handshake = new JSONObject(new String(payload, StandardCharsets.UTF_8));
      if (handshake.has("error")) throw new IOException(handshake.optString("error"));
      final int width = handshake.optInt("width");
      final int height = handshake.optInt("height");
      local.setSoTimeout(15000);
      main.post(() -> {
        if (token != generation) return;
        subtitle.setText(display + " · " + width + "×" + height + " · 只读");
        title.setText("虚拟屏");
      });
      while (token == generation && streaming) {
        int length = input.readInt();
        if (length <= 0 || length > MAX_FRAME) throw new IOException("虚拟屏画面帧无效");
        byte[] jpeg = new byte[length];
        input.readFully(jpeg);
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, length);
        if (bitmap == null) continue;
        main.post(() -> showFrame(token, bitmap));
      }
    } catch (IOException | JSONException error) {
      if (token == generation && streaming) {
        String value = message(error);
        fail(token, value.isEmpty() ? "画面连接已断开" : value);
      }
    } finally {
      try {
        if (local != null) local.close();
      } catch (IOException ignored) {
      }
      if (socket == local) socket = null;
    }
  }

  private void showFrame(long token, Bitmap bitmap) {
    if (token != generation || !streaming) {
      bitmap.recycle();
      return;
    }
    boolean first = current == null;
    Bitmap previous = current;
    current = bitmap;
    image.setImageBitmap(bitmap);
    if (previous != null && previous != bitmap) previous.recycle();
    empty.setVisibility(View.GONE);
    if (!screenOn) {
      screenOn = true;
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    if (first) setChrome(true);
  }

  private void fail(long token, String reason) {
    main.post(() -> {
      if (token != generation) return;
      stopStream(false);
      showError(reason);
    });
  }

  private void showError(String reason) {
    String target = display == null ? "虚拟屏" : display;
    showEmpty("无法观看 " + target, clean(reason), "选择其他显示",
        this::showPicker, false);
    subtitle.setText("连接失败");
    chip.setVisibility(View.INVISIBLE);
  }

  private void showEmpty(String heading, String body, String label,
                         Runnable run, boolean progress) {
    emptyTitle.setText(heading);
    emptyBody.setText(body);
    emptyActions.removeAllViews();
    if (label != null && run != null) {
      emptyActions.addView(actionButton(label, label,
          theme.onAccent, theme.accent, run), new LinearLayout.LayoutParams(-2, -2));
    }
    emptyProgress.setVisibility(progress ? View.VISIBLE : View.GONE);
    empty.setVisibility(View.VISIBLE);
    emptyTitle.announceForAccessibility(heading);
  }

  private void stopStream(boolean clear) {
    generation++;
    streaming = false;
    main.removeCallbacks(hideChrome);
    LocalSocket local = socket;
    socket = null;
    if (local != null) {
      try {
        local.close();
      } catch (IOException ignored) {
      }
    }
    Process process = relay;
    relay = null;
    if (process != null) {
      try {
        process.destroy();
      } catch (Exception ignored) {
      }
    }
    if (screenOn) {
      screenOn = false;
      getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    chip.setVisibility(View.INVISIBLE);
    if (clear) {
      display = null;
      wanted = false;
      Bitmap previous = current;
      current = null;
      image.setImageDrawable(null);
      if (previous != null) previous.recycle();
    }
  }

  private void setChrome(boolean show) {
    if (topBar == null) return;
    chromeVisible = show;
    main.removeCallbacks(hideChrome);
    topBar.animate().cancel();
    if (show) {
      topBar.setVisibility(View.VISIBLE);
      topBar.setAlpha(0f);
      topBar.animate().alpha(1f).setDuration(160).start();
      chip.setVisibility(View.VISIBLE);
      chip.setAlpha(0f);
      chip.animate().alpha(1f).setDuration(160).start();
      if (streaming) main.postDelayed(hideChrome, AUTO_HIDE_MS);
    } else {
      topBar.animate().alpha(0f).setDuration(160).withEndAction(() -> {
        if (!chromeVisible) topBar.setVisibility(View.INVISIBLE);
      }).start();
      chip.animate().alpha(0f).setDuration(160).withEndAction(() -> {
        if (!chromeVisible) chip.setVisibility(View.INVISIBLE);
      }).start();
    }
  }

  private void showPicker() {
    if (pickerLayer == null) return;
    renderDisplays(displays());
    pickerLayer.setVisibility(View.VISIBLE);
    pickerLayer.setAlpha(0f);
    pickerLayer.animate().alpha(1f).setDuration(150).start();
    setChrome(true);
  }

  private void hidePicker() {
    if (pickerLayer == null) return;
    pickerLayer.setVisibility(View.GONE);
  }

  private boolean supportsPip() {
    return getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
  }

  private void enterPip() {
    if (!supportsPip() || current == null) return;
    int width = current.getWidth();
    int height = current.getHeight();
    Rational ratio;
    float value = width / (float) height;
    if (value > 2.39f) ratio = new Rational(239, 100);
    else if (value < 1f / 2.39f) ratio = new Rational(100, 239);
    else ratio = new Rational(width, height);
    try {
      enterPictureInPictureMode(new PictureInPictureParams.Builder()
          .setAspectRatio(ratio).build());
    } catch (IllegalStateException ignored) {
    }
  }

  @Override public void onUserLeaveHint() {
    super.onUserLeaveHint();
    if (streaming && current != null && supportsPip()) enterPip();
  }

  @Override public void onPictureInPictureModeChanged(boolean inPip, Configuration configuration) {
    super.onPictureInPictureModeChanged(inPip, configuration);
    if (inPip) {
      hidePicker();
      setChrome(false);
    } else {
      setChrome(true);
    }
  }

  @Override protected void onStart() {
    super.onStart();
    if (stoppedByLifecycle) {
      stoppedByLifecycle = false;
      int number = number(display);
      if (wanted && number >= 0 && !streaming && !isFinishing()) startStream(number);
    }
  }

  @Override protected void onStop() {
    if (!isInPictureInPictureMode() && streaming) {
      stopStream(false);
      stoppedByLifecycle = true;
    }
    super.onStop();
  }

  @Override protected void onDestroy() {
    wanted = false;
    if (root != null) stopStream(true);
    super.onDestroy();
  }

  @Override public void onBackPressed() {
    if (pickerLayer != null && pickerLayer.getVisibility() == View.VISIBLE) {
      hidePicker();
      return;
    }
    navigateUp();
  }

  private void drainRelay(Process process) {
    Thread thread = new Thread(() -> {
      byte[] buffer = new byte[4096];
      try (InputStream input = process.getInputStream()) {
        int count;
        while ((count = input.read(buffer)) >= 0) {
          if (count == 0) continue;
          synchronized (relayLog) {
            relayLog.append(new String(buffer, 0, count, StandardCharsets.UTF_8));
            if (relayLog.length() > 6000) relayLog.delete(0, relayLog.length() - 3000);
          }
        }
      } catch (IOException ignored) {
      }
    }, "virtual-screen-relay-log");
    thread.setDaemon(true);
    thread.start();
  }

  private String relayHint() {
    synchronized (relayLog) {
      String text = relayLog.toString().trim();
      if (text.isEmpty()) return "";
      String[] lines = text.split("\n");
      for (int i = lines.length - 1; i >= 0; i--) {
        String line = lines[i].trim();
        if (!line.isEmpty()) return "：" + line;
      }
    }
    return "";
  }

  private static String message(Throwable error) {
    String value = error.getMessage();
    return value == null || value.isEmpty() ? error.getClass().getSimpleName() : value;
  }

  private static String clean(String value) {
    if (value == null) return "未知错误";
    String text = value.trim();
    if (text.length() > 300) text = text.substring(0, 300) + "…";
    return text.isEmpty() ? "未知错误" : text;
  }

  private static int number(String value) {
    if (value == null) return -1;
    value = value.trim();
    if (value.startsWith(":")) value = value.substring(1);
    if (value.isEmpty()) return -1;
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException error) {
      return -1;
    }
  }

  /** The viewer can be launched before PiRuntime copies runtime assets; copy the
   *  relay itself so a cold start still works. */
  private void ensureRelayScript() throws IOException {
    File directory = new File(getFilesDir(), "runtime/phone-tools");
    if (!directory.isDirectory() && !directory.mkdirs())
      throw new IOException("无法创建手机工具目录");
    File script = new File(directory, "x11-mirror.py");
    try (InputStream input = getAssets().open("runtime/phone-tools/x11-mirror.py");
         java.io.FileOutputStream output = new java.io.FileOutputStream(script)) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
    }
  }

  private TextView actionButton(String label, String description, int foreground,
                                int fill, Runnable run) {
    TextView view = Ui.text(this, label, 13, foreground);
    view.setTypeface(Typeface.create("sans-serif-medium", 0));
    view.setGravity(Gravity.CENTER);
    view.setMinHeight(dp(48));
    view.setMinWidth(dp(48));
    view.setPadding(dp(14), dp(10), dp(14), dp(10));
    view.setBackground(Ui.ripple(this, fill, 14, foreground));
    view.setClickable(true);
    view.setFocusable(true);
    view.setContentDescription(description);
    view.setOnClickListener(v -> {
      if (!v.isEnabled()) return;
      Ui.click(v);
      run.run();
    });
    return view;
  }

  private FrameLayout.LayoutParams match() {
    return new FrameLayout.LayoutParams(-1, -1);
  }

  private int dp(float value) {
    return Ui.dp(this, value);
  }
}
