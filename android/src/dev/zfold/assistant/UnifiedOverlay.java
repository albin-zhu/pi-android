package dev.zfold.assistant;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.LinearLayout;

/** One compact overlay slot shared by background calls and phone tasks. */
final class UnifiedOverlay {
  static View captureView(){return card;}
  enum Mode {TASK, CALL}

  private static Mode mode;
  private static boolean takeover;
  private static View card;
  private static WindowManager manager;
  private static android.widget.TextView statusView;
  private static final Handler main = new Handler(Looper.getMainLooper());

  static synchronized boolean showing(Mode value) {return card != null && mode == value;}

  static synchronized boolean task(Context context, String action) {
    return show(context, Mode.TASK, action == null || action.isBlank() ? "正在操作手机" : action, takeover);
  }

  static synchronized boolean takeover(Context context, String action) {
    return show(context, Mode.TASK, action == null || action.isBlank() ? "已由你接管 · 任务正在停止" : action, true);
  }

  static synchronized boolean call(Context context, String action) {
    return show(context, Mode.CALL, action == null || action.isBlank() ? "实时通话进行中" : action, false);
  }

  static synchronized void status(String action) {
    if (statusView == null) return;
    main.post(() -> {
      synchronized (UnifiedOverlay.class) {
        if (statusView != null) statusView.setText(action == null || action.isBlank() ? defaultStatus() : action);
      }
    });
  }

  static synchronized void hide(Mode value) {
    if (mode == value) hideLocked();
  }

  static synchronized void forceHide() {hideLocked();}

  static synchronized boolean hasAction(String description) {
    return card instanceof ViewGroup && hasAction((ViewGroup) card, description);
  }

  private static String defaultStatus() {
    return mode == Mode.CALL ? "实时通话进行中" : takeover ? "任务已由你接管" : "正在操作手机";
  }

  private static boolean show(Context base, Mode value, String action, boolean takeoverMode) {
    Context app = base.getApplicationContext();
    PhoneAccessibility service = PhoneAccessibility.instance;
    if (service == null && !Settings.canDrawOverlays(app)) return false;
    if (card != null && mode == value) {
      takeover = value == Mode.TASK && takeoverMode;
      status(action);
      return true;
    }
    removeLocked();
    CircleOverlay.suspendForCompact();

    Context host = service != null ? service : app;
    WindowManager wm = (WindowManager) host.getSystemService(Context.WINDOW_SERVICE);
    LinearLayout body = new LinearLayout(new android.view.ContextThemeWrapper(host, R.style.AppTheme));
    body.setOrientation(LinearLayout.HORIZONTAL);
    body.setGravity(Gravity.CENTER_VERTICAL);
    body.setBackground(Ui.shape(app, 0xf00f1722, 24, Ui.dp(app, 1), 0xff253244));
    body.setElevation(Ui.dp(app, 10));
    body.setPadding(Ui.dp(app, 10), Ui.dp(app, 9), Ui.dp(app, 5), Ui.dp(app, 9));
    body.setMinimumHeight(Ui.dp(app, 48));
    PiRuntime runtime=PiRuntime.running();

    View mark = Ui.agentMark(app, runtime==null?null:runtime.agentAvatar, 30);
    if(value==Mode.CALL){
      android.widget.FrameLayout avatar=new android.widget.FrameLayout(app);avatar.addView(mark,new android.widget.FrameLayout.LayoutParams(Ui.dp(app,30),Ui.dp(app,30),Gravity.CENTER));
      avatar.setBackground(Ui.ripple(app,Color.TRANSPARENT,24,0xffe8eff8));avatar.setContentDescription("点击头像打断语音");avatar.setFocusable(true);avatar.setOnClickListener(v->{Ui.click(v);VoiceTurnController controller=MainActivity.activeVoice();if(controller!=null)controller.forceInterrupt();});body.addView(avatar,new LinearLayout.LayoutParams(Ui.dp(app,48),Ui.dp(app,48)));
    }else body.addView(mark, new LinearLayout.LayoutParams(Ui.dp(app, 30), Ui.dp(app, 30)));

    LinearLayout labels = new LinearLayout(app);
    labels.setOrientation(LinearLayout.VERTICAL);
    labels.setPadding(Ui.dp(app, 10), 0, 0, 0);
    android.widget.TextView title = Ui.text(app, runtime==null?"独立手机工具":runtime.agentName, 13, 0xffedf4ff);
    title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
    title.setSingleLine(true);
    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    statusView = Ui.text(app, action, 11, 0xffa8bed4);
    statusView.setSingleLine(true);
    statusView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
    labels.addView(title);
    labels.addView(statusView);
    body.addView(labels, new LinearLayout.LayoutParams(0, -2, 1f));

    if (value == Mode.TASK && !takeoverMode) {
      body.addView(new Ui.Action(app, R.drawable.ic_microphone, "", "语音接管当前任务", 0xffe8eff8, Color.TRANSPARENT,
          () -> {PhoneToolDispatcher.stopExternal();AssistantOverlay.showForVoice(app);}), new LinearLayout.LayoutParams(Ui.dp(app, 48), Ui.dp(app, 48)));
      body.addView(new Ui.Action(app, R.drawable.ic_x, "", "停止当前手机操作", 0xffe8eff8, Color.TRANSPARENT,
          () -> PhoneToolDispatcher.stopPhone(app)), new LinearLayout.LayoutParams(Ui.dp(app, 48), Ui.dp(app, 48)));
      body.setOnClickListener(view -> {PhoneToolDispatcher.stopExternal();hideLocked(); AssistantOverlay.show(app);});
      body.setContentDescription("Zfold 正在操作手机。点主体展开助手；右侧可用语音接管或停止。");
    } else if (value == Mode.CALL) {
      if (runtime!=null&&runtime.busy) body.addView(new Ui.Action(app, R.drawable.ic_x, "", "停止当前手机操作", 0xffe8eff8, Color.TRANSPARENT,
          () -> PhoneToolDispatcher.stopPhone(app)), new LinearLayout.LayoutParams(Ui.dp(app, 48), Ui.dp(app, 48)));
      body.addView(new Ui.Action(app, R.drawable.ic_x, "", "结束实时通话", 0xffff4553, Color.TRANSPARENT,
          () -> {
            VoiceTurnController controller = MainActivity.activeVoice();
            if (controller != null) controller.stopSession(false);
            hideLocked();
          }), new LinearLayout.LayoutParams(Ui.dp(app, 48), Ui.dp(app, 48)));
      body.setOnClickListener(view -> {hideLocked(); VoiceCallActivity.show(app);});
      body.setContentDescription("实时通话已转到后台。点按返回通话，右侧按钮结束。");
    } else {
      body.setOnClickListener(view -> {takeover = false; hideLocked(); AssistantOverlay.show(app);});
      body.setContentDescription("Zfold 任务已由用户接管。点这里回到助手继续。");
    }

    int type = service != null ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    WindowManager.LayoutParams params = new WindowManager.LayoutParams(-2, -2, type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT);
    boolean callSurface = value == Mode.CALL;
    params.gravity = callSurface ? Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL : Gravity.TOP | Gravity.CENTER_HORIZONTAL;
    params.y = Ui.dp(app, callSurface ? 82 : 54);
    params.width = Math.min(wm.getMaximumWindowMetrics().getBounds().width() - Ui.dp(app, 32), Ui.dp(app, 430));
    params.setFitInsetsTypes(WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout()
        | (callSurface ? WindowInsets.Type.navigationBars() : WindowInsets.Type.statusBars()));
    try {
      wm.addView(body, params);
      manager = wm;
      card = body;
      mode = value;
      takeover = value == Mode.TASK && takeoverMode;
      Ui.arrive(body);
      return true;
    } catch (RuntimeException ignored) {
      removeLocked();
      return false;
    }
  }

  private static void hideLocked() {
    if (card == null) return;
    View old = card;
    WindowManager active = manager;
    card = null;
    manager = null;
    statusView = null;
    mode = null;
    takeover = false;
    try {active.removeView(old);} catch (Exception ignored) {}
    CircleOverlay.resumeAfterCompact(old.getContext());
  }

  private static void removeLocked() {hideLocked();}

  private static boolean hasAction(ViewGroup group, String description) {
    for (int i = 0; i < group.getChildCount(); i++) {
      View child = group.getChildAt(i);
      if (description.equals(child.getContentDescription() == null ? "" : child.getContentDescription().toString())) return true;
      if (child instanceof ViewGroup && hasAction((ViewGroup) child, description)) return true;
    }
    return false;
  }
}
