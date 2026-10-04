package dev.zfold.assistant;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.graphics.RectF;
import org.json.JSONObject;

/** Immersive, real-time voice conversation surface. */
public final class VoiceCallActivity extends Activity implements PiRuntime.Listener {
  private static volatile VoiceCallActivity active;
  private static volatile boolean foreground;
  private final Handler main=new Handler(Looper.getMainLooper());
  private PiRuntime runtime;private Ui.Theme theme;
  private CallOrb orb;private TextView stateText;private TextView liveText;
  private String assistantText="";private boolean session,speaking;
  private long startedAt;
  private static volatile boolean controllerSession,controllerSpeaking;

  static void show(Context context){context.startActivity(new Intent(context,VoiceCallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));}
  static void onControllerStatus(String text){VoiceMiniOverlay.status(text);VoiceCallActivity target=active;if(target!=null)target.main.post(()->target.setStateText(text));}
  static void onControllerState(boolean session,boolean speaking){controllerSession=session;controllerSpeaking=speaking;VoiceMiniOverlay.state(session,speaking);VoiceCallActivity target=active;if(target!=null)target.main.post(()->{target.session=session;target.speaking=speaking;target.orb.setState(!session?"ended":speaking?"speaking":"listening");});}
  static void onPrompt(String text){VoiceMiniOverlay.status("正在处理你的话");VoiceCallActivity target=active;if(target!=null)target.main.post(()->target.setStateText("正在处理你的话"));}
  static void onSessionEnded(String reason){VoiceMiniOverlay.hide();VoiceCallActivity target=active;if(target!=null)target.main.post(()->{target.orb.setState("ended");target.setStateText(Ui.errorMessage(reason));});MainActivity.clearForegroundVoice();}
  static boolean isActive(){return active!=null;}
  static boolean isForeground(){return foreground&&active!=null;}
  static boolean hasLiveController(){return controllerSession;}

  @Override protected void onCreate(Bundle state){
    super.onCreate(state);runtime=PiRuntime.get(getApplicationContext());theme=new Ui.Theme(this);active=this;
    getWindow().setStatusBarColor(0xff070b12);getWindow().setNavigationBarColor(0xff070b12);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    CircleOverlay.hideBubble();
    LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(0xff070b12);root.setKeepScreenOn(true);
    root.setOnApplyWindowInsetsListener((view,insets)->{android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars());root.setPadding(bars.left,bars.top+Ui.dp(this,18),bars.right,bars.bottom+Ui.dp(this,16));return insets;});

    LinearLayout top=new LinearLayout(this);top.setOrientation(LinearLayout.VERTICAL);top.setGravity(Gravity.CENTER_HORIZONTAL);top.setPadding(Ui.dp(this,24),Ui.dp(this,16),Ui.dp(this,24),0);
    orb=new CallOrb(this);top.addView(orb,new LinearLayout.LayoutParams(Ui.dp(this,228),Ui.dp(this,228)));
    liveText=Ui.text(this,"",14,0xff9db0c7);liveText.setLineSpacing(Ui.dp(this,3),1f);liveText.setGravity(Gravity.CENTER);liveText.setMaxLines(3);liveText.setEllipsize(android.text.TextUtils.TruncateAt.END);LinearLayout.LayoutParams liveParams=new LinearLayout.LayoutParams(-1,-2);liveParams.topMargin=Ui.dp(this,10);top.addView(liveText,liveParams);
    TextView name=Ui.text(this,runtime.agentName,22,0xffeef4ff);name.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));name.setGravity(Gravity.CENTER);LinearLayout.LayoutParams nameParams=new LinearLayout.LayoutParams(-1,-2);nameParams.topMargin=Ui.dp(this,20);top.addView(name,nameParams);
    stateText=Ui.text(this,"正在准备实时通话…",14,0xff9db0c7);stateText.setGravity(Gravity.CENTER);LinearLayout.LayoutParams stateParams=new LinearLayout.LayoutParams(-1,-2);stateParams.topMargin=Ui.dp(this,7);top.addView(stateText,stateParams);
    root.addView(top,new LinearLayout.LayoutParams(-1,-2));

    LinearLayout controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER);controls.setPadding(0,Ui.dp(this,8),0,0);
    controls.addView(callAction(R.drawable.ic_chat_circle_text,"转文字","切换到文字对话",0xff223047,()->{startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP));finish();}),new LinearLayout.LayoutParams(Ui.dp(this,72),Ui.dp(this,72)));
    FrameLayout.LayoutParams endParams=new FrameLayout.LayoutParams(Ui.dp(this,84),Ui.dp(this,84));endParams.setMargins(Ui.dp(this,24),0,Ui.dp(this,24),0);
    controls.addView(callAction(R.drawable.ic_x,"","结束实时通话",0xffff4553,this::endCall),endParams);
    controls.addView(callAction(R.drawable.ic_microphone,"","打断播报/重新收听",0xff223047,this::tapMicrophone),new LinearLayout.LayoutParams(Ui.dp(this,72),Ui.dp(this,72)));
    root.addView(controls,new LinearLayout.LayoutParams(-1,-2));
    setContentView(root);
    runtime.listen(this);
    VoiceMiniOverlay.hide();
    liveText.setText("实时通话已就绪");
    orb.setState("preparing");
    if(runtime.agentAvatar!=null&&!runtime.agentAvatar.isEmpty())orb.setAvatar(runtime.agentAvatar);
    startedAt=System.currentTimeMillis();
  }

  private View callAction(int icon,String label,String description,int fill,Runnable action){
    LinearLayout button=new LinearLayout(this);button.setOrientation(LinearLayout.VERTICAL);button.setGravity(Gravity.CENTER);button.setBackground(Ui.circle(this,fill,0));button.setClickable(true);button.setFocusable(true);
    button.setOnClickListener(v->{button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);action.run();});
    ImageView image=new ImageView(this);image.setImageDrawable(Ui.icon(this,icon,fill==0xffff4553?0xffffffff:0xffdce8f8));image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);button.addView(image,new LinearLayout.LayoutParams(Ui.dp(this,24),Ui.dp(this,24)));
    if(!label.isEmpty()){TextView text=Ui.text(this,label,10,0xffaebdce);text.setPadding(0,Ui.dp(this,4),0,0);button.addView(text);}
    button.setContentDescription(description);return button;
  }

  private void setStateText(String text){if(stateText!=null)stateText.setText(text==null?"":text);}
  private void tapMicrophone(){
    VoiceTurnController controller=MainActivity.activeVoice();
    if(controller==null){setStateText("语音控制器已释放，请从聊天重新开始");orb.setState("error");return;}
    if(controller.sessionActive()){controller.forceInterrupt();}else controller.beginSession();
  }
  private void endCall(){
    VoiceTurnController controller=MainActivity.activeVoice();
    if(controller!=null)controller.stopSession(false);
    finish();
  }

  @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);}
  @Override protected void onResume(){super.onResume();active=this;foreground=true;VoiceMiniOverlay.hide();}
  @Override protected void onPause(){super.onPause();if(active==this)foreground=false;if(!isFinishing())VoiceMiniOverlay.showIfCallActive(this);}
  @Override protected void onDestroy(){if(active==this)active=null;runtime.unlisten(this);super.onDestroy();}
  @Override public void onBackPressed(){startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP));finish();}

  @Override public void event(JSONObject event){
    String type=event.optString("type");
    if(type.equals("agent_identity")){main.post(()->{if(orb!=null)orb.setAvatar(runtime.agentAvatar);});return;}
    if(type.equals("turn_start")){main.post(()->{session=true;speaking=false;assistantText="";liveText.setText(runtime.lastPromptText);setStateText("正在思考…");orb.setState("thinking");});return;}
    if(type.equals("assistant_start"))return;
    if(type.equals("delta")){main.post(()->{session=true;speaking=true;orb.setState("speaking");setStateText(runtime.agentName+" 正在说话");
      String latest=runtime.lastResponse;int cut=Math.max(0,latest.length()-200);
      liveText.setText(cut>0?"…"+latest.substring(cut):latest);});return;}
    if(type.equals("tool_execution")){main.post(()->{setStateText(PhoneHud.action(event));orb.setState("thinking");
      VoiceTurnController vc=MainActivity.activeVoice();
      if(vc!=null&&"running".equals(event.optString("status")))vc.toolAnnounce(PhoneHud.action(event));});return;}
    if(type.equals("turn_end")){main.post(()->{session=true;speaking=false;assistantText=runtime.lastResponse;setStateText("播报完成后自动听你说话");orb.setState("listening");});return;}
    if(type.equals("error")){main.post(()->{setStateText("通话出现错误，请看状态");orb.setState("error");});return;}
  }

  static final class CallOrb extends View {
    private final Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG),ring=new Paint(Paint.ANTI_ALIAS_FLAG),avatarPaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG),letter=new Paint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap avatar;private String mode="preparing";private long frame;
    CallOrb(Context context){super(context);ring.setStyle(Paint.Style.STROKE);ring.setStrokeCap(Paint.Cap.ROUND);letter.setColor(0xffdce8f8);letter.setTextAlign(Paint.Align.CENTER);letter.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));}
    void setAvatar(String encoded){Bitmap next=Ui.thumbnail(encoded,512);avatar=next==null?null:circle(next);invalidate();}
    void setState(String value){mode=value==null?"preparing":value;invalidate();}
    @Override protected void onDraw(Canvas canvas){
      frame++;long time=frame*16L;float w=getWidth(),h=getHeight(),cx=w/2f,cy=h/2f,radius=Math.min(w,h)*.27f;
      int accent=mode.equals("speaking")?0xff7ed3a2:mode.equals("thinking")?0xff8fb8ff:mode.equals("error")?0xffff6b78:mode.equals("listening")?0xff65a2ff:0xff8090a6;
      fill.setColor(0x14202c40);canvas.drawCircle(cx,cy,radius*1.28f,fill);
      int rings=mode.equals("thinking")?3:mode.equals("speaking")?2:1;
      for(int i=0;i<rings;i++){
        float phase=((time/1000f)+i*(1f/rings))%1f;float expand=mode.equals("speaking")?.18f+phase*.34f:.06f+phase*.16f;float alpha=(float)(1-phase)*.55f;
        ring.setColor(accent);ring.setAlpha((int)(alpha*255));ring.setStrokeWidth(Ui.dp(getContext(),mode.equals("speaking")?4:2));
        canvas.drawCircle(cx,cy,radius*(1+expand),ring);
      }
      ring.setColor(accent);ring.setAlpha(255);ring.setStrokeWidth(Ui.dp(getContext(),3));canvas.drawCircle(cx,cy,radius,ring);
      fill.setColor(0xff121a26);canvas.drawCircle(cx,cy,radius-Ui.dp(getContext(),5),fill);
      if(avatar!=null){float draw=(radius-Ui.dp(getContext(),5))*2f;canvas.drawBitmap(avatar,null,new RectF(cx-draw/2,cy-draw/2,cx+draw/2,cy+draw/2),avatarPaint);}
      else{String text=runtime()!=null&&!runtime().agentName.isEmpty()?runtime().agentName.substring(0,1):"Z";letter.setTextSize(radius*.75f);float textY=cy-(letter.descent()+letter.ascent())/2f;canvas.drawText(text,cx,textY,letter);}
      if(mode.equals("speaking"))drawWave(canvas,cx,cy+radius*1.45f,radius*.72f,accent);
      postInvalidateOnAnimation();
    }
    private void drawWave(Canvas canvas,float cx,float cy,float width,int color){
      Paint wave=new Paint(Paint.ANTI_ALIAS_FLAG);wave.setColor(color);wave.setStrokeWidth(Ui.dp(getContext(),2));wave.setStrokeCap(Paint.Cap.ROUND);
      Path path=new Path();float amplitude=mode.equals("speaking")?Ui.dp(getContext(),9):Ui.dp(getContext(),3);
      path.moveTo(cx-width,cy);
      for(int i=0;i<=48;i++){float x=cx-width+width*2*i/48f;float y=cy+(float)Math.sin(i*.42f+frame*.18f)*amplitude*(1f-Math.abs(i-24)/24f);path.lineTo(x,y);}
      canvas.drawPath(path,wave);
    }
    private Bitmap circle(Bitmap source){
      int side=Math.min(source.getWidth(),source.getHeight());Matrix matrix=new Matrix();matrix.setScale(1,1);
      Bitmap output=Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(output);
      float scale=side/(float)Math.min(source.getWidth(),source.getHeight());matrix.setScale(scale,scale,side/2f,side/2f);
      BitmapShader shader=new BitmapShader(source,BitmapShader.TileMode.CLAMP,BitmapShader.TileMode.CLAMP);shader.setLocalMatrix(matrix);
      Paint crop=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);crop.setShader(shader);
      canvas.drawCircle(side/2f,side/2f,side/2f,crop);return output;
    }
    private PiRuntime runtime(){return active==null?PiRuntime.get(getContext()):active.runtime;}
  }
}
