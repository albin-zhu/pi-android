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
        public void close(){minimizePanel();}
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
      panel.voice.stopSession(false);hideKeyboard();expanded=false;
      CircleOverlay.ensureBubble(context);
      if(CircleOverlay.hasBubble())hide();
      else{container.removeAllViews();Ui.Bubble mini=new Ui.Bubble(context);mini.setOnClickListener(v->restorePanel());FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(dp(52),dp(52),Gravity.BOTTOM|Gravity.END);container.addView(mini,lp);touchSurface=mini;container.requestApplyInsets();}
    }
    void hideKeyboard(){panel.input.clearFocus();((android.view.inputmethod.InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(panel.getWindowToken(),0);}
    void selectRegion(){
      if(screen==null){Toast.makeText(context,"当前屏幕暂不可用，请重新唤起后再试",Toast.LENGTH_LONG).show();return;}
      hideKeyboard();selecting=true;Bitmap captured=screen.copy(Bitmap.Config.ARGB_8888,false);
      CircleOverlay.Selection selection=new CircleOverlay.Selection(context,captured,null,data->panel.attachImage(data,"已附上圈选内容"));selection.dismissed=()->restorePanel();
      container.removeAllViews();container.setPadding(0,0,0,0);container.addView(selection,new FrameLayout.LayoutParams(-1,-1));touchSurface=selection;container.requestApplyInsets();
    }
    @Override public void onShow(Bundle args,int flags){super.onShow(args,flags);active=this;AssistantOverlay.hide();CircleOverlay.hideBubble();if(container!=null&&(panel==null||panel.disposed))createPanel();Window window=getWindow().getWindow();window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND|WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);window.setNavigationBarColor(Color.TRANSPARENT);window.setNavigationBarContrastEnforced(false);window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);}
    @Override public void onComputeInsets(Insets out){super.onComputeInsets(out);out.touchableInsets=Insets.TOUCHABLE_INSETS_REGION;out.touchableRegion.setEmpty();if(touchSurface!=null&&touchSurface.getVisibility()==View.VISIBLE){int[] location=new int[2];touchSurface.getLocationInWindow(location);out.touchableRegion.set(location[0],location[1],location[0]+touchSurface.getWidth(),location[1]+touchSurface.getHeight());}}
    @Override public void onHandleScreenshot(Bitmap screenshot){if(screen!=null)screen.recycle();screen=screenshot==null?null:screenshot.copy(Bitmap.Config.ARGB_8888,false);if(screen!=null&&panel!=null&&!panel.disposed)panel.attachImage(PhoneAccessibility.encode(screen.copy(Bitmap.Config.ARGB_8888,false)),"已附上当前屏幕");}
    @Override public void onBackPressed(){
      if(selecting){if(touchSurface instanceof CircleOverlay.Selection){Bitmap bitmap=((CircleOverlay.Selection)touchSurface).screenshot;if(!bitmap.isRecycled())bitmap.recycle();}restorePanel();}
      else if(touchSurface!=panel)restorePanel();
      else if(expanded)restorePanel();
      else{panel.stop();minimizePanel();}
    }
    @Override public void onHide(){if(active==this)active=null;if(panel!=null)panel.dispose();if(screen!=null){screen.recycle();screen=null;}super.onHide();}
    @Override public void onDestroy(){if(active==this)active=null;if(panel!=null)panel.dispose();if(screen!=null)screen.recycle();super.onDestroy();}
  }
}
