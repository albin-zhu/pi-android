package dev.zfold.assistant;

import android.animation.ValueAnimator;
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
public final class VoiceCallActivity extends NavigableActivity implements PiRuntime.Listener {
  private static volatile VoiceCallActivity active;
  private static volatile boolean foreground;
  private final Handler main=new Handler(Looper.getMainLooper());
  private PiRuntime runtime;private Ui.Theme theme;
  private CallOrb orb;private TextView stateText;private TextView liveText;
  private String assistantText="";private boolean session,speaking;
  private long startedAt;
  private static volatile boolean controllerSession,controllerSpeaking;
  private CameraCoordinator camera;private android.view.TextureView cameraView;private FrameLayout videoStage;
  private TextView cameraStatus,videoStatus,videoText,lastFrameLabel;private CallOrb videoOrb;private View cameraAction;
  private boolean video;private Runnable adaptLayout;private String lastFrameImage;private boolean frameDialogOpen;
  private TextView audioOutput;private android.app.AlertDialog audioDialog;private final Runnable audioChanged=this::updateAudioOutput;
  private TextView questionAction;private final Runnable questionChanged=this::updateQuestionAction;
  static VoiceCallActivity foregroundActivity(){return foreground?active:null;}

  static void onFrameSubmitted(JSONObject metadata,org.json.JSONArray images){VoiceCallActivity target=active;if(target==null||metadata==null)return;target.main.post(()->{
    if(images!=null)for(int i=0;i<images.length();i++){JSONObject item=images.optJSONObject(i);if(item!=null&&metadata.optString("frameId").equals(item.optString("frameId")))target.lastFrameImage=item.optString("data");}
    target.lastFrameLabel.setText("已附图 · "+new java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.getDefault()).format(new java.util.Date(metadata.optLong("capturedAt")))+" · "+("front".equals(metadata.optString("facing"))?"前置":"后置"));
    target.lastFrameLabel.setVisibility(View.VISIBLE);
  });}
  static void onSubmissionFailed(String text,String error){VoiceCallActivity target=active;if(target==null)return;target.main.post(()->{
    target.setStateText(error);if(!foreground||target.isFinishing()||target.frameDialogOpen)return;target.frameDialogOpen=true;
    new android.app.AlertDialog.Builder(target).setTitle("这条话还未发送").setMessage(error+"\n\n"+text)
      .setPositiveButton("重试附图",(d,w)->VoiceCoordinator.get(target).retry(true))
      .setNeutralButton("仅发文字",(d,w)->VoiceCoordinator.get(target).retry(false)).setNegativeButton("保留",null)
      .setOnDismissListener(d->target.frameDialogOpen=false).show();
  });}

  static void show(Context context){SystemWindowNavigation.open(context,VoiceCallActivity.class,false);}
  static void onControllerStatus(String text){VoiceMiniOverlay.status(text);VoiceCallActivity target=active;if(target!=null)target.main.post(()->target.setStateText(text));}
  static void onControllerState(boolean session,boolean speaking){controllerSession=session;controllerSpeaking=speaking;VoiceMiniOverlay.state(session,speaking);VoiceCallActivity target=active;if(target!=null)target.main.post(()->{target.session=session;target.speaking=speaking;target.setOrbState(!session?"ended":speaking?"speaking":"listening");});}
  static void onPrompt(String text){VoiceMiniOverlay.status("正在处理你的话");VoiceCallActivity target=active;if(target!=null)target.main.post(()->target.setStateText("正在处理你的话"));}
  static void onSessionEnded(String reason){VoiceMiniOverlay.hide();VoiceCallActivity target=active;if(target!=null)target.main.post(()->{target.setOrbState("ended");target.setStateText(Ui.errorMessage(reason));});MainActivity.clearForegroundVoice();}
  static boolean isActive(){return active!=null;}
  static boolean isForeground(){return foreground&&active!=null;}
  static boolean hasLiveController(){return controllerSession;}

  @Override protected void onCreate(Bundle state){
    super.onCreate(state);runtime=PiRuntime.get(getApplicationContext());theme=new Ui.Theme(this);active=this;camera=CameraCoordinator.get(this);VoiceCoordinator.get(this);
    setVolumeControlStream(android.media.AudioManager.STREAM_VOICE_CALL);
    getWindow().setStatusBarColor(0xff070b12);getWindow().setNavigationBarColor(0xff070b12);getWindow().getDecorView().setSystemUiVisibility(0);
    AssistantSessionService.yieldToCall();if(AssistantOverlay.currentPanel()!=null)AssistantOverlay.preserveDraft(AssistantOverlay.currentPanel().draft());AssistantOverlay.hide();CircleOverlay.hideBubble();
    LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(0xff070b12);root.setKeepScreenOn(true);
    getWindow().setDecorFitsSystemWindows(false);
    root.setOnApplyWindowInsetsListener((view,insets)->{android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars());root.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});

    videoStage=new FrameLayout(this);videoStage.setBackgroundColor(0xff070b12);videoStage.setVisibility(View.GONE);
    cameraView=new android.view.TextureView(this);videoStage.addView(cameraView,new FrameLayout.LayoutParams(-1,-1));
    cameraStatus=Ui.text(this,"本地预览",12,0xffffe4cc);cameraStatus.setPadding(Ui.dp(this,12),Ui.dp(this,12),Ui.dp(this,8),Ui.dp(this,8));cameraStatus.setMaxLines(2);
    cameraStatus.setBackgroundColor(0xc0070b12);
    FrameLayout.LayoutParams cs=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP|Gravity.LEFT);cs.rightMargin=Ui.dp(this,64);videoStage.addView(cameraStatus,cs);
    cameraStatus.setOnClickListener(v->cameraMenu());cameraStatus.setContentDescription("相机状态与画面操作");
    View flip=callAction(R.drawable.ic_arrow_clockwise,"","切换前后摄像头",0xcc223047,()->camera.switchLens());
    FrameLayout.LayoutParams fp=new FrameLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48),Gravity.TOP|Gravity.RIGHT);fp.setMargins(0,Ui.dp(this,6),Ui.dp(this,8),0);videoStage.addView(flip,fp);
    LinearLayout videoCaption=new LinearLayout(this);videoCaption.setGravity(Gravity.CENTER_VERTICAL);videoCaption.setPadding(Ui.dp(this,6),Ui.dp(this,4),Ui.dp(this,6),Ui.dp(this,4));videoCaption.setBackgroundColor(0xb3070b12);
    videoOrb=new CallOrb(this);videoOrb.setClickable(true);videoOrb.setFocusable(true);videoOrb.setContentDescription("点击头像，打断播报并说话");videoOrb.setOnClickListener(v->tapMicrophone());videoCaption.addView(videoOrb,new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)));
    LinearLayout captionText=new LinearLayout(this);captionText.setOrientation(LinearLayout.VERTICAL);videoStatus=Ui.text(this,"正在听…",12,0xffeef4ff);videoStatus.setMaxLines(2);captionText.addView(videoStatus);
    videoText=Ui.text(this,"每次提问附一张画面",11,0xffaab5c3);videoText.setMaxLines(2);videoText.setEllipsize(android.text.TextUtils.TruncateAt.END);captionText.addView(videoText);
    lastFrameLabel=Ui.text(this,"",11,0xffffb27a);lastFrameLabel.setVisibility(View.GONE);lastFrameLabel.setOnClickListener(v->showLastFrame());captionText.addView(lastFrameLabel);
    videoCaption.addView(captionText,new LinearLayout.LayoutParams(0,-2,1));videoStage.addView(videoCaption,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));root.addView(videoStage,new LinearLayout.LayoutParams(-1,0,1));

    LinearLayout top=new LinearLayout(this);top.setOrientation(LinearLayout.VERTICAL);top.setGravity(Gravity.CENTER_HORIZONTAL);top.setPadding(Ui.dp(this,24),Ui.dp(this,16),Ui.dp(this,24),0);
    orb=new CallOrb(this);
    orb.setClickable(true);orb.setFocusable(true);orb.setContentDescription("点击头像，打断播报并说话");
    orb.setForeground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x288fb8ff),null,Ui.circle(this,Color.WHITE,0)));
    orb.setOnClickListener(view->{view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);tapMicrophone();});
    top.addView(orb,new LinearLayout.LayoutParams(Ui.dp(this,228),Ui.dp(this,228)));
    TextView interruptHint=Ui.text(this,"点头像打断",12,0xff9db0c7);interruptHint.setGravity(Gravity.CENTER);top.addView(interruptHint);
    liveText=Ui.text(this,"",14,0xff9db0c7);liveText.setLineSpacing(Ui.dp(this,3),1f);liveText.setGravity(Gravity.CENTER);liveText.setMaxLines(3);liveText.setEllipsize(android.text.TextUtils.TruncateAt.END);LinearLayout.LayoutParams liveParams=new LinearLayout.LayoutParams(-1,-2);liveParams.topMargin=Ui.dp(this,10);top.addView(liveText,liveParams);
    TextView name=Ui.text(this,runtime.agentName,22,0xffeef4ff);name.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));name.setGravity(Gravity.CENTER);LinearLayout.LayoutParams nameParams=new LinearLayout.LayoutParams(-1,-2);nameParams.topMargin=Ui.dp(this,20);top.addView(name,nameParams);
    stateText=Ui.text(this,"正在准备实时通话…",14,0xff9db0c7);stateText.setGravity(Gravity.CENTER);LinearLayout.LayoutParams stateParams=new LinearLayout.LayoutParams(-1,-2);stateParams.topMargin=Ui.dp(this,7);top.addView(stateText,stateParams);
    ScrollView callScroll=new ScrollView(this);callScroll.setFillViewport(true);callScroll.addView(top);root.addView(callScroll,new LinearLayout.LayoutParams(-1,0,1));

    questionAction=Ui.text(this,"有问题需要回答",13,0xffffc98d);questionAction.setGravity(Gravity.CENTER);questionAction.setBackground(Ui.ripple(this,0xff2c231b,14,0xffffc98d));questionAction.setOnClickListener(v->UserQuestions.show(this));questionAction.setContentDescription("回答助手的问题");questionAction.setVisibility(View.GONE);root.addView(questionAction,new LinearLayout.LayoutParams(-1,Ui.dp(this,48)));
    audioOutput=Ui.text(this,"声音 · 自动",12,0xffc5d3e5);audioOutput.setGravity(Gravity.CENTER);audioOutput.setSingleLine(true);audioOutput.setEllipsize(android.text.TextUtils.TruncateAt.END);
    audioOutput.setPadding(Ui.dp(this,12),0,Ui.dp(this,12),0);audioOutput.setBackground(Ui.ripple(this,0xff172235,22,0xffc5d3e5));audioOutput.setOnClickListener(v->showAudioOutputs());
    LinearLayout.LayoutParams audioParams=new LinearLayout.LayoutParams(-2,Ui.dp(this,44));audioParams.gravity=Gravity.CENTER_HORIZONTAL;root.addView(audioOutput,audioParams);
    LinearLayout controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER);controls.setPadding(0,Ui.dp(this,8),0,0);
    controls.addView(callAction(R.drawable.ic_chat_circle_text,"转文字","切换到文字对话",0xff223047,()->{startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP));finish();}),new LinearLayout.LayoutParams(Ui.dp(this,72),Ui.dp(this,72)));
    cameraAction=callAction(R.drawable.ic_camera,"","开启摄像头",0xff223047,this::toggleCamera);cameraAction.setOnLongClickListener(v->{cameraMenu();return true;});controls.addView(cameraAction,new LinearLayout.LayoutParams(Ui.dp(this,72),Ui.dp(this,72)));
    FrameLayout.LayoutParams endParams=new FrameLayout.LayoutParams(Ui.dp(this,84),Ui.dp(this,84));endParams.setMargins(Ui.dp(this,24),0,Ui.dp(this,24),0);
    controls.addView(callAction(R.drawable.ic_x,"","结束实时通话",0xffff4553,this::endCall),endParams);
    controls.addView(callAction(R.drawable.ic_microphone,"","打断播报/重新收听",0xff223047,this::tapMicrophone),new LinearLayout.LayoutParams(Ui.dp(this,72),Ui.dp(this,72)));
    root.addView(controls,new LinearLayout.LayoutParams(-1,-2));
    final boolean[] applied={false},compact={false};
    Runnable adapt=()->{
      if(root.getWidth()==0)return;boolean small=root.getWidth()<Ui.dp(this,360)||root.getHeight()-root.getPaddingTop()-root.getPaddingBottom()<Ui.dp(this,440);
      applied[0]=true;compact[0]=small;videoStage.setVisibility(video?View.VISIBLE:View.GONE);callScroll.setVisibility(video?View.GONE:View.VISIBLE);camera.transform();
      top.setPadding(Ui.dp(this,small?8:24),Ui.dp(this,small?6:24),Ui.dp(this,small?8:24),Ui.dp(this,8));top.setGravity(Gravity.CENTER);
      int diameter=Ui.dp(this,small?80:228);orb.setLayoutParams(new LinearLayout.LayoutParams(diameter,diameter));liveText.setVisibility(small?View.GONE:View.VISIBLE);
      name.setTextSize(small?16:22);LinearLayout.LayoutParams np=(LinearLayout.LayoutParams)name.getLayoutParams();np.topMargin=Ui.dp(this,small?4:20);name.setLayoutParams(np);
      stateText.setTextSize(small?12:14);stateText.setMaxLines(2);stateText.setEllipsize(android.text.TextUtils.TruncateAt.END);
      controls.setPadding(0,Ui.dp(this,4),0,Ui.dp(this,small?6:16));
      videoText.setVisibility(small?View.GONE:View.VISIBLE);lastFrameLabel.setMaxLines(1);lastFrameLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
      for(int i=0;i<controls.getChildCount();i++){int size=Ui.dp(this,small?48:64);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(size,size);lp.leftMargin=Ui.dp(this,small?3:8);lp.rightMargin=lp.leftMargin;controls.getChildAt(i).setLayoutParams(lp);}
    };
    adaptLayout=adapt;
    root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->adapt.run());root.post(adapt);
    setContentView(root);
    runtime.listen(this);
    VoiceMiniOverlay.hide();
    setLiveText("打开摄像头，每次提问附一张画面");
    VoiceTurnController controller=MainActivity.activeVoice();
    if(controller!=null&&controller.sessionActive()){
      session=true;speaking=controller.speaking();setOrbState(speaking?"speaking":controller.listeningForInput()?"listening":"thinking");
      setStateText(speaking?"正在播报 · 点头像可打断":controller.listeningForInput()?"正在听…":"正在思考…");
    }else{setOrbState("preparing");setStateText("语音未开启 · 点麦克风开始");}
    if(runtime.agentAvatar!=null&&!runtime.agentAvatar.isEmpty()){orb.setAvatar(runtime.agentAvatar);videoOrb.setAvatar(runtime.agentAvatar);}
    startedAt=System.currentTimeMillis();
  }

  private View callAction(int icon,String label,String description,int fill,Runnable action){
    LinearLayout button=new LinearLayout(this);button.setOrientation(LinearLayout.VERTICAL);button.setGravity(Gravity.CENTER);button.setBackground(Ui.circle(this,fill,0));button.setClickable(true);button.setFocusable(true);
    button.setOnClickListener(v->{button.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);action.run();});
    ImageView image=new ImageView(this);image.setImageDrawable(Ui.icon(this,icon,fill==0xffff4553?0xffffffff:0xffdce8f8));image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);button.addView(image,new LinearLayout.LayoutParams(Ui.dp(this,24),Ui.dp(this,24)));
    if(!label.isEmpty()){TextView text=Ui.text(this,label,10,0xffaebdce);text.setGravity(Gravity.CENTER);text.setPadding(0,Ui.dp(this,4),0,0);button.addView(text);}
    button.setContentDescription(description);return button;
  }

  private void setStateText(String text){if(stateText!=null)stateText.setText(text==null?"":text);if(videoStatus!=null)videoStatus.setText(text==null?"":text);}
  private void setOrbState(String state){if(orb!=null)orb.setState(state);if(videoOrb!=null)videoOrb.setState(state);}
  private void setLiveText(String text){if(liveText!=null)liveText.setText(text);if(videoText!=null)videoText.setText(text);}
  private void updateAudioOutput(){if(audioOutput==null)return;String label=AudioModeOwner.outputLabel();audioOutput.setText("声音 · "+label+" ▾");audioOutput.setContentDescription("切换声音输出，当前"+label);}
  private void updateQuestionAction(){if(questionAction!=null)questionAction.setVisibility(UserQuestions.current()==null?View.GONE:View.VISIBLE);}
  private void showAudioOutputs(){
    if(!AudioModeOwner.active()){android.widget.Toast.makeText(this,"开始语音后可切换扬声器、听筒或耳机",android.widget.Toast.LENGTH_SHORT).show();return;}
    if(audioDialog!=null&&audioDialog.isShowing())return;
    java.util.List<android.media.AudioDeviceInfo> devices=AudioModeOwner.choices();java.util.ArrayList<String> labels=new java.util.ArrayList<>();java.util.ArrayList<Integer> ids=new java.util.ArrayList<>();
    labels.add("自动 · 耳机优先，否则扬声器");ids.add(0);
    if(android.os.Build.VERSION.SDK_INT>=31){for(android.media.AudioDeviceInfo device:devices){labels.add(AudioModeOwner.label(device));ids.add(device.getId());}}
    else{labels.add("扬声器");ids.add(-1);labels.add("听筒 / 系统耳机");ids.add(-2);}
    audioDialog=new android.app.AlertDialog.Builder(this).setTitle("声音输出 · "+AudioModeOwner.outputLabel())
      .setSingleChoiceItems(labels.toArray(new String[0]),ids.indexOf(AudioModeOwner.selected()),(dialog,which)->{
        if(!AudioModeOwner.select(ids.get(which)))android.widget.Toast.makeText(this,"暂时无法切换，请检查耳机连接",android.widget.Toast.LENGTH_SHORT).show();dialog.dismiss();
      }).setNegativeButton("关闭",null).create();audioDialog.setOnDismissListener(d->audioDialog=null);audioDialog.show();
  }
  private void toggleCamera(){
    if(video&&camera.state().equals("preview")){camera.disable();video=false;cameraAction.setContentDescription("开启摄像头");adaptLayout.run();return;}
    if(checkSelfPermission(android.Manifest.permission.CAMERA)!=android.content.pm.PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{android.Manifest.permission.CAMERA},31);return;}
    video=true;adaptLayout.run();camera.bind(this,cameraView,(state,message)->{
      cameraStatus.setText(message);cameraAction.setContentDescription(state.equals("off")?"开启摄像头":state.equals("preview")?"关闭摄像头":"恢复摄像头");
      if(state.equals("off")){video=false;adaptLayout.run();}
    },runtime.sessionId,true);
  }
  private void cameraMenu(){
    String[] choices={"发送当前画面","切换前后摄像头","查看上次附图","重试未发送的话","仅发送上次的话"};
    new android.app.AlertDialog.Builder(this).setTitle("摄像头画面").setItems(choices,(d,which)->{
      if(which==0){if(!video||!camera.ready()){setStateText("先开启摄像头并等待画面就绪");return;}VoiceCoordinator.get(this).submit("请看看当前摄像头画面。",true);}
      else if(which==1)camera.switchLens();else if(which==2)showLastFrame();else VoiceCoordinator.get(this).retry(which==3);
    }).show();
  }
  private void showLastFrame(){if(lastFrameImage==null){setStateText("还没有发送过摄像头画面");return;}ImageView image=new ImageView(this);image.setAdjustViewBounds(true);image.setImageBitmap(Ui.thumbnail(lastFrameImage,1280));new android.app.AlertDialog.Builder(this).setTitle(lastFrameLabel.getText()).setView(image).setPositiveButton("关闭",null).show();}
  @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);boolean granted=results.length>0&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED;if(request==31){if(granted)toggleCamera();else setStateText("未允许相机，语音仍可继续");}else if(request==32){if(granted)tapMicrophone();else setStateText("未允许麦克风，可继续查看画面");}}
  private void tapMicrophone(){
    if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},32);return;}
    VoiceTurnController controller=MainActivity.activeVoice();
    if(controller==null){setStateText("语音控制器已释放，请从聊天重新开始");setOrbState("error");return;}
    if(controller.sessionActive()){controller.forceInterrupt();}else controller.beginSession();
  }
  private void endCall(){
    camera.endVideo(this);
    VoiceTurnController controller=MainActivity.activeVoice();
    if(controller!=null)controller.stopSession(false);
    finish();
  }

  @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);}
  @Override protected void onResume(){super.onResume();active=this;foreground=true;UserQuestions.listen(questionChanged);AudioModeOwner.listen(audioChanged);VoiceMiniOverlay.hide();getWindow().getDecorView().post(()->{if(active==this&&foreground)VoiceCoordinator.get(this).voiceViewReady(true);});if(video&&(!camera.owns(this)||!camera.ready())){cameraStatus.setText("摄像头已暂停，点击相机恢复");cameraAction.setContentDescription("恢复摄像头");}}
  @Override protected void onPause(){UserQuestions.unlisten(questionChanged);AudioModeOwner.unlisten(audioChanged);SystemWindowNavigation.remember(this);super.onPause();if(active==this){foreground=false;VoiceCoordinator.get(this).voiceViewReady(false);}if(!isFinishing())VoiceMiniOverlay.showIfCallActive(this);}
  @Override protected void onStop(){camera.pause(this);super.onStop();}
  @Override protected void onDestroy(){if(audioDialog!=null)audioDialog.dismiss();if(isFinishing())camera.endVideo(this);camera.detach(this);if(active==this)active=null;runtime.unlisten(this);super.onDestroy();}
  @Override public void onBackPressed(){navigateUp();}

  @Override public void event(JSONObject event){
    String type=event.optString("type");
    if(type.equals("agent_identity")){main.post(()->{if(orb!=null)orb.setAvatar(runtime.agentAvatar);if(videoOrb!=null)videoOrb.setAvatar(runtime.agentAvatar);});return;}
    if(type.equals("turn_start")){main.post(()->{session=true;speaking=false;assistantText="";setLiveText(runtime.lastPromptText);setStateText("正在思考…");setOrbState("thinking");});return;}
    if(type.equals("assistant_start"))return;
    if(type.equals("delta")){main.post(()->{VoiceTurnController vc=MainActivity.activeVoice();
      if(vc==null||!vc.listeningForInput()){session=true;speaking=vc!=null&&vc.speaking();setOrbState(speaking?"speaking":"thinking");if(speaking)setStateText(runtime.agentName+" 正在说话");}
      String latest=runtime.lastResponse;int cut=Math.max(0,latest.length()-200);
      setLiveText(cut>0?"…"+latest.substring(cut):latest);});return;}
    if(type.equals("tool_execution")){main.post(()->{
      VoiceTurnController vc=MainActivity.activeVoice();
      if(vc==null||!vc.listeningForInput()){setStateText(PhoneHud.action(event));setOrbState("thinking");}
      });return;}
    if(type.equals("turn_end")){main.post(()->{VoiceTurnController vc=MainActivity.activeVoice();session=vc!=null&&vc.sessionActive();speaking=vc!=null&&vc.speaking();assistantText=runtime.lastResponse;setOrbState(!session?"ended":speaking?"speaking":"listening");if(speaking)setStateText("播报完成后自动听你说话");});return;}
    if(type.equals("error")){main.post(()->{setStateText("通话出现错误，请看状态");setOrbState("error");});return;}
  }

  static final class CallOrb extends View {
    private final Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG),ring=new Paint(Paint.ANTI_ALIAS_FLAG),avatarPaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG),letter=new Paint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap avatar;private String mode="preparing";private long frame;
    CallOrb(Context context){super(context);ring.setStyle(Paint.Style.STROKE);ring.setStrokeCap(Paint.Cap.ROUND);letter.setColor(0xffdce8f8);letter.setTextAlign(Paint.Align.CENTER);letter.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));}
    @Override public CharSequence getAccessibilityClassName(){return android.widget.Button.class.getName();}
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
