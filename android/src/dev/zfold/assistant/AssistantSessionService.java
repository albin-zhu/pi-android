package dev.zfold.assistant;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.service.voice.*;
import android.view.*;
import android.widget.*;
public final class AssistantSessionService extends VoiceInteractionSessionService {
  @Override public VoiceInteractionSession onNewSession(Bundle args){return new Session(this);}
  private static Session active;
  static boolean isShowing(){return active!=null;}
  static AssistantPanel currentPanel(){return active==null?null:active.panel;}
  static void yieldToCall(){Session session=active;if(session==null||session.panel==null)return;AssistantOverlay.preserveDraft(session.panel.draft());session.hideKeyboard();session.hide();}
  static void yieldToOverlay(){
    Session session=active;if(session==null||session.panel==null)return;
    if(!AssistantOverlay.hasSavedDraft())AssistantOverlay.preserveDraft(session.panel.draft());
    session.panel.voice.stopSession(false);session.hideKeyboard();session.hide();
  }
  static void startVoice(){
    Session session=active;
    if(session==null||session.panel==null||session.panel.disposed)return;
    if(session.touchSurface!=session.panel)session.restorePanel();
    session.panel.wakeVoice();
  }
  static boolean handoffToBubble(Context context){
    Session session=active;
    if(session==null||session.panel==null||session.panel.disposed)return false;
    CircleOverlay.ensureBubble(context);
    if(!CircleOverlay.hasBubble())return false;
    AssistantOverlay.preserveDraft(session.panel.draft());
    session.panel.voice.stopSession(false);session.hideKeyboard();session.hide();return true;
  }
  static final class Session extends VoiceInteractionSession {
    final Context context;AssistantPanel panel;FrameLayout container;Bitmap screen;View touchSurface;boolean selecting,expanded;
    Session(Context context){super(context);this.context=new ContextThemeWrapper(context,R.style.AppTheme);}
    int dp(int value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
    @Override public View onCreateContentView(){
      container=new FrameLayout(context);container.setBackgroundColor(Color.TRANSPARENT);createPanel();
      container.setOnApplyWindowInsetsListener((v,insets)->{if(selecting){container.setPadding(0,0,0,0);return insets;}android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()),ime=insets.getInsets(WindowInsets.Type.ime());int statusId=context.getResources().getIdentifier("status_bar_height","dimen","android");int top=Math.max(bars.top,statusId>0?context.getResources().getDimensionPixelSize(statusId):dp(24));if(touchSurface==panel){container.setPadding(bars.left,top,bars.right,ime.bottom);panel.setBottomInset(ime.bottom>bars.bottom?0:bars.bottom);}else container.setPadding(dp(14)+bars.left,top,dp(14)+bars.right,dp(16)+Math.max(bars.bottom,ime.bottom));return insets;});
      return container;
    }
    void createPanel(){
      if(panel!=null)panel.dispose();
      panel=new AssistantPanel(context,new AssistantPanel.Host(){
        public void close(){ScreenContext.setEnabled(false);AssistantOverlay.preserveDraft(panel.draft());panel.voice.stopSession(false);hideKeyboard();CircleOverlay.hideBubble();hide();}
        public void minimize(){minimizePanel();}
        public void toggleExpansion(){if(expanded)restorePanel();else expandPanel();}
        public void select(){selectRegion();}
      });
      restorePanel();
    }
    void restorePanel(){selecting=false;expanded=false;container.removeAllViews();panel.setTranslationY(0);
      android.graphics.Rect bounds=((WindowManager)context.getSystemService(Context.WINDOW_SERVICE)).getMaximumWindowMetrics().getBounds();
      FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(Math.min(dp(420),bounds.width()),Math.round(bounds.height()*.58f),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
      container.addView(panel,lp);panel.setExpanded(false);touchSurface=panel;container.requestApplyInsets();Ui.arrive(panel);
    }
    void expandPanel(){
      selecting=false;expanded=true;FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)panel.getLayoutParams();
      lp.width=FrameLayout.LayoutParams.MATCH_PARENT;lp.height=FrameLayout.LayoutParams.MATCH_PARENT;lp.gravity=Gravity.CENTER;
      panel.setLayoutParams(lp);panel.setExpanded(true);container.requestApplyInsets();
    }
    void minimizePanel(){
      AssistantOverlay.preserveDraft(panel.draft());panel.voice.stopSession(false);hideKeyboard();expanded=false;
      CircleOverlay.ensureBubble(context);
      if(CircleOverlay.hasBubble()||UnifiedOverlay.captureView()!=null)hide();
      else{container.removeAllViews();Ui.Bubble mini=new Ui.Bubble(context);mini.setOnClickListener(v->restorePanel());FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(dp(52),dp(52),Gravity.BOTTOM|Gravity.END);container.addView(mini,lp);touchSurface=mini;container.requestApplyInsets();}
    }
    void hideKeyboard(){panel.input.clearFocus();((android.view.inputmethod.InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(panel.getWindowToken(),0);}
    void selectRegion(){
      hideKeyboard();PhoneAccessibility service=PhoneAccessibility.instance;
      if(service!=null){
        panel.setVisibility(View.INVISIBLE);
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(()->{
          if(active!=this||panel.disposed)return;
          String app=service.screenApplicationPackage();
          if(app.isEmpty()||app.equals(context.getPackageName())){panel.setVisibility(View.VISIBLE);Toast.makeText(context,"请先切换到要圈选的其他应用",Toast.LENGTH_LONG).show();return;}
          try{service.captureApplication(app,(bitmap,error)->{if(active!=this||panel.disposed){if(bitmap!=null)bitmap.recycle();return;}panel.setVisibility(View.VISIBLE);if(error!=null){Toast.makeText(context,error,Toast.LENGTH_LONG).show();return;}showSelection(bitmap);});}
          catch(RuntimeException error){panel.setVisibility(View.VISIBLE);Toast.makeText(context,"读屏服务已断开，请重试",Toast.LENGTH_LONG).show();}
        },300);return;
      }
      if(screen==null){Toast.makeText(context,"重新唤起系统助手以取得画面，或开启读屏服务",Toast.LENGTH_LONG).show();return;}
      showSelection(screen.copy(Bitmap.Config.ARGB_8888,false));
    }
    void showSelection(Bitmap captured){
      selecting=true;CircleOverlay.Selection selection=new CircleOverlay.Selection(context,captured,null,data->panel.attachImage(data,"已附上圈选内容"));selection.dismissed=()->restorePanel();
      container.removeAllViews();container.setPadding(0,0,0,0);container.addView(selection,new FrameLayout.LayoutParams(-1,-1));touchSurface=selection;container.requestApplyInsets();
    }
    @Override public void onShow(Bundle args,int flags){super.onShow(args,flags);active=this;if(AssistantOverlay.currentPanel()!=null)AssistantOverlay.preserveDraft(AssistantOverlay.currentPanel().draft());AssistantOverlay.hide();CircleOverlay.hideBubble();UnifiedOverlay.forceHide();if(container!=null&&(panel==null||panel.disposed))createPanel();if(panel!=null)panel.restore(AssistantOverlay.takeDraft());Window window=getWindow().getWindow();window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND|WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);window.setNavigationBarColor(Color.TRANSPARENT);window.setNavigationBarContrastEnforced(false);window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);}
    @Override public void onComputeInsets(Insets out){super.onComputeInsets(out);out.touchableInsets=Insets.TOUCHABLE_INSETS_REGION;out.touchableRegion.setEmpty();if(touchSurface!=null&&touchSurface.getVisibility()==View.VISIBLE){int[] location=new int[2];touchSurface.getLocationInWindow(location);out.touchableRegion.set(location[0],location[1],location[0]+touchSurface.getWidth(),location[1]+touchSurface.getHeight());}}
    @Override public void onHandleScreenshot(Bitmap screenshot){if(screen!=null)screen.recycle();screen=screenshot==null?null:screenshot.copy(Bitmap.Config.ARGB_8888,false);if(screen!=null&&panel!=null&&!panel.disposed&&panel.image==null)panel.attachImage(PhoneAccessibility.encode(screen.copy(Bitmap.Config.ARGB_8888,false)),"已附上当前屏幕");}
    @Override public void onBackPressed(){
      if(selecting){if(touchSurface instanceof CircleOverlay.Selection){Bitmap bitmap=((CircleOverlay.Selection)touchSurface).screenshot;if(!bitmap.isRecycled())bitmap.recycle();}restorePanel();}
      else if(touchSurface!=panel)restorePanel();
      else if(expanded)restorePanel();
      else{minimizePanel();}
    }
    @Override public void onHide(){if(active==this)active=null;if(panel!=null)panel.dispose();if(screen!=null){screen.recycle();screen=null;}super.onHide();}
    @Override public void onDestroy(){if(active==this)active=null;if(panel!=null)panel.dispose();if(screen!=null)screen.recycle();super.onDestroy();}
  }
}
