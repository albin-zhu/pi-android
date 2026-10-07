package dev.zfold.assistant;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.speech.*;
import android.speech.tts.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class MainActivity extends Activity implements PiRuntime.Listener {
  static final int BG=Color.rgb(245,246,247), INK=Color.rgb(16,20,24), MUTED=Color.rgb(95,107,120), ACCENT=Color.rgb(47,111,237), LINE=Color.rgb(226,230,234);
  static volatile MainActivity current;
  PiRuntime runtime; LinearLayout conversation,root,intro,draftAttachment,fileList,composerSurface,statusRow,actionToolbar; android.view.View slashSuggest; FrameLayout composerFrame;ConversationMinimap.Scroll scroll;ConversationMinimap minimap; TextView status,draftLabel; Ui.RichBlock answer; Ui.Action voiceButton,modelButton,circleButton,attachButton,screenShareButton,sendButton,sessionButton,overflowButton; EditText input; ImageView draftThumbnail,draftExpanded; Bitmap draftBitmap; Ui.Theme theme;
  StringBuilder response=new StringBuilder(); boolean continuous,screenSharing,capturingScreen,composerCompact,screenWindowPending;
  final Runnable screenListener=()->{screenSharing=ScreenContext.enabled();updateScreenShareControl();};
  private boolean returnWorkbench,temporarySettingsHost,smallWindow,windowLayoutApplied;
  LinearLayout inputRow; Ui.Action workbenchButton; View welcomeMark,suggestions; TextView welcomeTitle,welcomeDescription; ScrollView attachmentScroll;
  private android.graphics.Insets windowBars=android.graphics.Insets.NONE,windowIme=android.graphics.Insets.NONE;
  private int toolbarWidth=-1;private float toolbarFontScale=-1;private boolean toolbarSmall;
  SwipeNavigationLayout swipeNavigation;private Object backCallback;private ActionMode textSelection;
  String lastSentFiles="";
  int statusToken;
  VoiceTurnController voice;
  private static VoiceTurnController foregroundVoice;
  String pendingImage,pendingImageLabel; final Handler handler=new Handler(Looper.getMainLooper());
  final List<LocalFileAttachment> localFiles=new ArrayList<>();
  String pendingTaskPrompt;
  private Ui.Action questionAction;private final Runnable questionChanged=this::updateQuestionAction;
  final Map<String,ToolCallView> toolViews=new LinkedHashMap<>();
  final Map<String,ToolGroupView> toolGroups=new LinkedHashMap<>();
  private String displayedSessionId="";private int followVersion;
  @Override public void onCreate(Bundle state){
    super.onCreate(state);returnWorkbench=getIntent().getBooleanExtra("return_workbench",false);temporarySettingsHost=returnWorkbench;theme=new Ui.Theme(this);getWindow().setStatusBarColor(theme.page);getWindow().setNavigationBarColor(theme.page);
    getWindow().getDecorView().setSystemUiVisibility(theme.dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    current=this;
    runtime=PiRuntime.get(this);runtime.listen(this);
    if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)VoiceWakeService.resume(this);
    voice=VoiceCoordinator.get(this).observe(this,new VoiceTurnController.Callback(){
      public boolean active(){return continuous&&!isFinishing();}
      public void status(String text){MainActivity.this.showStatus(text);}
      public void voiceState(boolean session,boolean speaking){continuous=session;voiceButton.setText(session?(speaking?"打断":"结束对话"):"说话");}
      public void prompt(String text){VoiceCallActivity.onPrompt(text);sendText(text);}
      public void sessionEnded(String reason){continuous=false;voiceButton.setText("说话");showStatus(reason);}
    });
    foregroundVoice=voice;
    getWindow().setDecorFitsSystemWindows(false);
    buildUI();UserQuestions.listen(questionChanged);backCallback=ChatBackNavigation.register(this);ScreenContext.listen(screenListener);screenListener.run();
    if(runtime.ready&&!runtime.configured)runtime.restoreConfiguration();
    else if(runtime.configured)runtime.send(PiRuntime.json("type","history"));
    if(getIntent().getBooleanExtra("open_capabilities",false)){getIntent().removeExtra("open_capabilities");permissions();}
    if(getIntent().getBooleanExtra("open_speech",false)){getIntent().removeExtra("open_speech");speechCapabilities();}
    if(getIntent().getBooleanExtra("open_tools",false)){getIntent().removeExtra("open_tools");sessionButton.post(()->showToolCenter(sessionButton));}
    if(getIntent().getBooleanExtra("open_models",false)){getIntent().removeExtra("open_models");settings();}
    if(getIntent().getBooleanExtra("open_sessions",false)){getIntent().removeExtra("open_sessions");sessionButton.post(this::openSidebar);}
    if(getIntent().getBooleanExtra("open_provider_auth",false)){getIntent().removeExtra("open_provider_auth");sessionButton.post(()->ProviderAuthDialog.show(sessionButton,runtime));}
    if(getIntent().getBooleanExtra("open_provider_auth",false)){getIntent().removeExtra("open_provider_auth");sessionButton.post(()->ProviderAuthDialog.show(sessionButton,runtime));}
    if(getIntent().getBooleanExtra("open_runtime_packs",false)){getIntent().removeExtra("open_runtime_packs");runtimePacksSettings();}
  }
  int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
  static MainActivity current(){return current;}
  static VoiceTurnController activeVoice(){return VoiceCoordinator.current();}
  static void clearForegroundVoice(){foregroundVoice=null;}
  String idleStatus(){return runtime.configured?"本机 Pi · 随时待命":runtime.ready?"连接模型后开始":"正在准备助手…";}
  TextView text(String value,int size,int color){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setFontFeatureSettings("kern");return v;}
  GradientDrawable surface(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
  Ui.Action button(String label,boolean filled,Runnable action){
    int icon=R.drawable.ic_chat_circle_text;String visible=label;
    if(label.equals("↑")){icon=R.drawable.ic_arrow_up;visible="";}
    else if(label.contains("语音")){icon=R.drawable.ic_microphone;visible="说话";}
    else if(label.contains("圈选")){icon=R.drawable.ic_corners_out;visible="圈选";}
    else if(label.contains("停止")){icon=R.drawable.ic_stop;visible="停止";}
    else if(label.equals("会话")){icon=R.drawable.ic_sidebar;visible="会话";}
    else if(label.equals("模型")||label.equals("手机能力")){icon=label.equals("模型")?R.drawable.ic_gear_six:R.drawable.ic_arrows_out_simple;visible="";}
    else visible=label.replace("↗  ","");
    boolean chrome=label.equals("会话")||label.equals("模型")||label.equals("手机能力");
    return new Ui.Action(this,icon,visible,label.equals("↑")?"发送":label,filled?theme.onAccent:chrome?theme.muted:theme.ink,filled?theme.accent:chrome?Color.TRANSPARENT:theme.surface,action);
  }
  private LinearLayout pendingRows;
  private Ui.Action steerAction,stopAction;
  private String pendingSignature="";
  private final Runnable queueChanged=()->renderPendingMessages();
  boolean hasDraft(){return input!=null&&(!input.getText().toString().trim().isEmpty()||pendingImage!=null||!localFiles.isEmpty());}
  void insertGuidance(){
    if(!runtime.busy){notice("任务已结束，请直接发送");return;}
    String text=input.getText().toString().trim();
    if(text.isEmpty()||pendingImage!=null||!localFiles.isEmpty()){notice("引导仅支持非空文字；附件请排队发送");return;}
    new AlertDialog.Builder(this).setTitle("引导当前任务")
      .setMessage("将在工具调用结束后的安全边界交给模型。提交后暂不能单独撤回。")
      .setNegativeButton("取消",null).setPositiveButton("插入",(d,w)->runtime.send(PiRuntime.json("type","steer","id",UUID.randomUUID().toString(),"sessionId",runtime.sessionId,"text",text))).show();
  }
  void renderPendingMessages(){
    if(pendingRows==null)return;
    PromptDispatcher dispatcher=PromptDispatcher.get(this);java.util.List<JSONObject> items=dispatcher.pending();
    StringBuilder signature=new StringBuilder();for(JSONObject item:items)signature.append(item.optString("id")).append(item.optString("text"));
    if(signature.toString().equals(pendingSignature))return;pendingSignature=signature.toString();
    pendingRows.removeAllViews();pendingRows.setVisibility(items.isEmpty()?View.GONE:View.VISIBLE);
    if(items.isEmpty())return;
    TextView heading=text("待发 "+items.size()+" 条 · 依次执行",12,theme.accent);heading.setPadding(dp(8),dp(5),dp(8),dp(4));pendingRows.addView(heading);
    for(JSONObject item:items){
      String id=item.optString("id"),value=item.optString("text");
      LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setBackground(Ui.shape(this,theme.field,12,0));
      TextView preview=text(value,13,theme.ink);preview.setMaxLines(2);preview.setEllipsize(android.text.TextUtils.TruncateAt.END);preview.setPadding(dp(10),dp(6),dp(6),dp(6));
      if(item.has("image")||item.has("images")||item.has("files"))preview.setText(value+"  [附件]");
      row.addView(preview,new LinearLayout.LayoutParams(0,-2,1));
      Ui.Action edit=new Ui.Action(this,R.drawable.ic_file,"编辑","编辑待发消息",theme.accent,Color.TRANSPARENT,()->{
        EditText editor=new EditText(this);editor.setText(value);editor.setMinLines(2);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("编辑消息 · 保留附件").setView(editor).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{if(dispatcher.edit(id,editor.getText().toString()))dialog.dismiss();else notice("未保存：消息已开始或文字无效");}));dialog.show();
      });row.addView(edit);
      row.addView(new Ui.Action(this,R.drawable.ic_x,"","取消待发消息",theme.muted,Color.TRANSPARENT,()->{if(!dispatcher.cancel(id))notice("消息已开始，无法取消");}));
      LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(3);pendingRows.addView(row,params);
    }
    MicroMotion.reveal(pendingRows);
  }
  void buildUI(){
    root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(theme.page);root.setPadding(dp(12),0,dp(12),dp(10));
    root.setOnApplyWindowInsetsListener((view,insets)->{windowBars=insets.getInsets(WindowInsets.Type.systemBars());windowIme=insets.getInsets(WindowInsets.Type.ime());applyWindowPadding();return insets;});
    scroll=new ConversationMinimap.Scroll(this);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);scroll.setClipToPadding(false);
    conversation=new LinearLayout(this);conversation.setOrientation(LinearLayout.VERTICAL);conversation.setGravity(Gravity.CENTER_VERTICAL);scroll.addView(conversation);FrameLayout viewport=new FrameLayout(this);viewport.setMotionEventSplittingEnabled(false);viewport.addView(scroll,new FrameLayout.LayoutParams(-1,-1));minimap=new ConversationMinimap(this,scroll,conversation);viewport.addView(minimap,new FrameLayout.LayoutParams(dp(48),-1,Gravity.RIGHT));root.addView(viewport,new LinearLayout.LayoutParams(-1,0,1));
    intro=new LinearLayout(this);intro.setOrientation(LinearLayout.VERTICAL);intro.setGravity(Gravity.CENTER);intro.setPadding(dp(12),dp(34),dp(12),dp(30));
    welcomeMark=new Ui.Mark(this);welcomeMark.setElevation(dp(6));intro.addView(welcomeMark,new LinearLayout.LayoutParams(dp(54),dp(54)));
    TextView title=text("今天想做什么？",27,theme.ink);welcomeTitle=title;title.setGravity(Gravity.CENTER);title.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));title.setLineSpacing(dp(3),1f);LinearLayout.LayoutParams titleParams=new LinearLayout.LayoutParams(-1,-2);titleParams.topMargin=dp(22);intro.addView(title,titleParams);
    TextView desc=text("说一句话，或圈住屏幕里的内容。",14,theme.muted);welcomeDescription=desc;desc.setGravity(Gravity.CENTER);desc.setLineSpacing(dp(4),1f);LinearLayout.LayoutParams descParams=new LinearLayout.LayoutParams(-1,-2);descParams.topMargin=dp(10);intro.addView(desc,descParams);
    LinearLayout suggestions=new LinearLayout(this);this.suggestions=suggestions;suggestions.setGravity(Gravity.CENTER);suggestions.setOrientation(LinearLayout.HORIZONTAL);
    Ui.Action sample=new Ui.Action(this,R.drawable.ic_arrows_out_simple,"看看手机状态","查看手机状态",theme.ink,theme.surface,()->sendText("请使用工具查看这台手机的型号和电量。"));
    suggestions.addView(sample,new LinearLayout.LayoutParams(-2,-2));
    LinearLayout.LayoutParams suggestionParams=new LinearLayout.LayoutParams(-2,-2);suggestionParams.topMargin=dp(24);intro.addView(suggestions,suggestionParams);
    conversation.addView(intro);
    draftAttachment=new LinearLayout(this);draftAttachment.setOrientation(LinearLayout.VERTICAL);draftAttachment.setBackground(Ui.shape(this,theme.field,16,0));draftAttachment.setVisibility(View.GONE);
    LinearLayout draftRow=new LinearLayout(this);draftRow.setGravity(Gravity.CENTER_VERTICAL);draftRow.setPadding(dp(8),dp(6),dp(4),dp(6));
    draftThumbnail=new ImageView(this);draftThumbnail.setScaleType(ImageView.ScaleType.FIT_CENTER);draftRow.addView(draftThumbnail,new LinearLayout.LayoutParams(dp(52),dp(52)));
    draftLabel=text(draftLabelText()+" · 随下一条消息发送",13,theme.ink);draftLabel.setPadding(dp(12),0,0,0);draftRow.addView(draftLabel,new LinearLayout.LayoutParams(0,-2,1));
    draftRow.addView(new Ui.Action(this,R.drawable.ic_x,"","移除屏幕图片",theme.muted,Color.TRANSPARENT,()->{pendingImage=null;pendingImageLabel=null;updateDraftAttachment();}),new LinearLayout.LayoutParams(dp(48),dp(48)));
    draftRow.setOnClickListener(v->draftExpanded.setVisibility(draftExpanded.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));draftRow.setContentDescription("预览随消息发送的屏幕图片");draftAttachment.addView(draftRow);
    draftExpanded=new ImageView(this);draftExpanded.setScaleType(ImageView.ScaleType.FIT_CENTER);draftExpanded.setVisibility(View.GONE);draftAttachment.addView(draftExpanded,new LinearLayout.LayoutParams(-1,dp(160)));
    fileList=new LinearLayout(this);fileList.setOrientation(LinearLayout.VERTICAL);draftAttachment.addView(fileList,new LinearLayout.LayoutParams(-1,-2));
    composerSurface=new LinearLayout(this);composerSurface.setOrientation(LinearLayout.VERTICAL);composerSurface.setBackground(Ui.shape(this,theme.surface,28,0));composerSurface.setElevation(0);composerSurface.setPadding(dp(10),dp(8),dp(10),dp(8));
    attachmentScroll=new ScrollView(this){@Override protected void onMeasure(int width,int height){super.onMeasure(width,View.MeasureSpec.makeMeasureSpec(dp(smallWindow?64:200),View.MeasureSpec.AT_MOST));}};
    attachmentScroll.setVisibility(View.GONE);attachmentScroll.addView(draftAttachment);composerSurface.addView(attachmentScroll,new LinearLayout.LayoutParams(-1,-2));
    pendingRows=new LinearLayout(this);pendingRows.setOrientation(LinearLayout.VERTICAL);pendingRows.setVisibility(View.GONE);
    ScrollView pendingScroll=new ScrollView(this){@Override protected void onMeasure(int w,int h){super.onMeasure(w,View.MeasureSpec.makeMeasureSpec(dp(160),View.MeasureSpec.AT_MOST));}};
    pendingScroll.addView(pendingRows);composerSurface.addView(pendingScroll,new LinearLayout.LayoutParams(-1,-2));
    LinearLayout guidance=new LinearLayout(this);guidance.setGravity(Gravity.RIGHT);
    steerAction=new Ui.Action(this,R.drawable.ic_arrow_up,"插入引导","引导当前任务",theme.accent,Color.TRANSPARENT,this::insertGuidance);guidance.addView(steerAction);
    stopAction=new Ui.Action(this,R.drawable.ic_stop,"停止","停止当前任务并清空队列",theme.muted,Color.TRANSPARENT,this::stop);guidance.addView(stopAction);
    composerSurface.addView(guidance);PromptDispatcher.get(this).listenQueue(queueChanged);renderPendingMessages();
    questionAction=new Ui.Action(this,R.drawable.ic_chat_circle_text,"需要回答","回答助手的问题",theme.accent,theme.accentSoft,()->UserQuestions.show(this));questionAction.setVisibility(View.GONE);composerSurface.addView(questionAction,new LinearLayout.LayoutParams(-1,-2));
    statusRow=new LinearLayout(this);statusRow.setGravity(Gravity.CENTER_VERTICAL);statusRow.setPadding(dp(8),0,dp(8),dp(4));
    status=text(idleStatus(),11,theme.muted);status.setSingleLine(true);status.setEllipsize(android.text.TextUtils.TruncateAt.END);statusRow.addView(status,new LinearLayout.LayoutParams(0,-2,1));
    statusRow.setVisibility(View.GONE);
    composerSurface.addView(statusRow,new LinearLayout.LayoutParams(-1,-2));
    inputRow=new LinearLayout(this);inputRow.setGravity(Gravity.CENTER_VERTICAL);composerSurface.addView(inputRow,new LinearLayout.LayoutParams(-1,-2));
    input=new EditText(this);input.setTextSize(16);input.setTextColor(theme.ink);input.setHintTextColor(theme.muted);input.setHint("说说你的想法…");input.setBackgroundColor(Color.TRANSPARENT);input.setMinLines(1);input.setMaxLines(5);input.setMinimumHeight(dp(48));input.setPadding(dp(11),dp(4),dp(11),dp(6));input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI|android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN);inputRow.addView(input,new LinearLayout.LayoutParams(0,-2,1));
    slashSuggest=SlashSuggest.attach(input,theme,this::runSlashCommand);composerSurface.addView(slashSuggest,new LinearLayout.LayoutParams(-1,-2));
    LinearLayout toolbar=new LinearLayout(this);toolbar.setGravity(Gravity.CENTER_VERTICAL);toolbar.setPadding(dp(2),dp(4),dp(2),0);actionToolbar=toolbar;
    modelButton=new Ui.Action(this,R.drawable.ic_chat_circle_text,"模型","快速切换模型",theme.muted,Color.TRANSPARENT,()->ModelPicker.show(modelButton,runtime));modelButton.label.setMaxWidth(dp(84));modelButton.label.setTextSize(12);toolbar.addView(modelButton);
    voiceButton=new Ui.Action(this,R.drawable.ic_microphone,"说话","开始语音对话",theme.muted,Color.TRANSPARENT,this::toggleVoice);toolbar.addView(voiceButton);
    sessionButton=new Ui.Action(this,R.drawable.ic_sidebar,"","会话侧边栏",theme.muted,Color.TRANSPARENT,this::openSidebar);LinearLayout.LayoutParams sessionParams=new LinearLayout.LayoutParams(-2,-2);sessionParams.leftMargin=dp(2);toolbar.addView(sessionButton,sessionParams);
    circleButton=new Ui.Action(this,R.drawable.ic_corners_out,"圈选","圈选屏幕内容",theme.muted,Color.TRANSPARENT,()->CircleOverlay.begin(this));LinearLayout.LayoutParams circleParams=new LinearLayout.LayoutParams(-2,-2);circleParams.leftMargin=dp(2);toolbar.addView(circleButton,circleParams);
    attachButton=new Ui.Action(this,R.drawable.ic_file,"文件","选择本地文件、图片、表格或媒体",theme.muted,Color.TRANSPARENT,this::pickFiles);LinearLayout.LayoutParams fileParams=new LinearLayout.LayoutParams(-2,-2);fileParams.leftMargin=dp(2);toolbar.addView(attachButton,fileParams);
    screenShareButton=new Ui.Action(this,R.drawable.ic_screen_share,"",screenSharing?"关闭共享屏幕":"开启共享屏幕聊天",theme.muted,Color.TRANSPARENT,this::toggleScreenShare);LinearLayout.LayoutParams shareParams=new LinearLayout.LayoutParams(-2,-2);shareParams.leftMargin=dp(2);toolbar.addView(screenShareButton,shareParams);
    overflowButton=new Ui.Action(this,R.drawable.ic_dots_three,"","更多工具",theme.muted,Color.TRANSPARENT,()->showToolbarOverflow());LinearLayout.LayoutParams overflowParams=new LinearLayout.LayoutParams(-2,-2);overflowParams.leftMargin=dp(2);toolbar.addView(overflowButton,overflowParams);
    workbenchButton=new Ui.Action(this,R.drawable.ic_workbench,"工作台","打开工作台",theme.ink,Color.TRANSPARENT,()->WorkbenchActivity.open(this));
    View spacer=new View(this);toolbar.addView(spacer,new LinearLayout.LayoutParams(0,1,1));
    sendButton=button("↑",true,()->{if(runtime.busy&&!hasDraft())stop();else sendText(input.getText().toString());});sendButton.setBackground(Ui.ripple(this,theme.accent,26,theme.onAccent));sendButton.setElevation(0);sendButton.setOnLongClickListener(v->{showPendingMessages();return true;});toolbar.addView(sendButton,new LinearLayout.LayoutParams(dp(80),dp(48)));composerSurface.addView(toolbar);
    input.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence t,int a,int b,int c){}public void afterTextChanged(android.text.Editable e){}public void onTextChanged(CharSequence t,int a,int b,int c){updateSend();}});updateSend();
    composerFrame=new FrameLayout(this);composerFrame.addView(composerSurface,new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER_HORIZONTAL));LinearLayout.LayoutParams footerParams=new LinearLayout.LayoutParams(-1,-2);footerParams.topMargin=dp(8);root.addView(composerFrame,footerParams);
    root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,oright,ob)->adjustColumn());swipeNavigation=new SwipeNavigationLayout(this);swipeNavigation.addView(root,new FrameLayout.LayoutParams(-1,-1));swipeNavigation.conversationRegion(scroll);setContentView(swipeNavigation);root.post(this::adjustColumn);
  }
  void adjustColumn(){
    if(scroll==null||composerFrame==null)return;int viewport=scroll.getWidth();if(viewport<=0)return;
    updateWindowLayout();
    int width=Math.min(viewport,dp(720)),side=Math.max(0,(viewport-width)/2);
    if(conversation.getPaddingLeft()!=side||conversation.getPaddingTop()!=dp(smallWindow?4:14))conversation.setPadding(side,dp(smallWindow?4:14),side,dp(smallWindow?8:22));
    FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)composerSurface.getLayoutParams();
    if(params.width!=width){params.width=width;composerSurface.setLayoutParams(params);}
    updateComposerToolbar(params.width);
  }
  private void applyWindowPadding(){int side=dp(smallWindow?8:12),bottom=dp(smallWindow?6:10);root.setPadding(side+windowBars.left,windowBars.top,side+windowBars.right,bottom+Math.max(windowBars.bottom,windowIme.bottom));}
  private void updateWindowLayout(){
    int usableHeight=root.getHeight()-windowBars.top-Math.max(windowBars.bottom,windowIme.bottom);
    boolean small=root.getWidth()<dp(360)||usableHeight<dp(400);
    if(windowLayoutApplied&&smallWindow==small)return;windowLayoutApplied=true;smallWindow=small;
    applyWindowPadding();
    composerSurface.setPadding(dp(small?4:10),dp(small?4:8),dp(small?4:10),dp(small?4:8));
    input.setMaxLines(small?2:5);input.setTextSize(small?15:16);input.setPadding(dp(small?4:11),dp(4),dp(small?4:11),dp(6));
    ((ViewGroup)sendButton.getParent()).removeView(sendButton);((ViewGroup)overflowButton.getParent()).removeView(overflowButton);
    if(small){inputRow.addView(overflowButton,0,new LinearLayout.LayoutParams(dp(48),dp(48)));inputRow.addView(sendButton,new LinearLayout.LayoutParams(dp(48),dp(48)));}
    else{actionToolbar.addView(overflowButton,actionToolbar.getChildCount()-1,new LinearLayout.LayoutParams(dp(48),dp(48)));actionToolbar.addView(sendButton,new LinearLayout.LayoutParams(dp(48),dp(48)));}
    actionToolbar.setVisibility(small?View.GONE:View.VISIBLE);
    welcomeMark.setVisibility(small?View.GONE:View.VISIBLE);suggestions.setVisibility(small?View.GONE:View.VISIBLE);welcomeTitle.setTextSize(small?19:27);
    welcomeDescription.setText(small?"随时说说你的想法":"说一句话，或圈住屏幕里的内容。");
    intro.setPadding(dp(8),dp(small?8:34),dp(8),dp(small?8:30));
    LinearLayout.LayoutParams titleParams=(LinearLayout.LayoutParams)welcomeTitle.getLayoutParams();titleParams.topMargin=dp(small?0:22);welcomeTitle.setLayoutParams(titleParams);
  }
  void openSidebar(){
    if(minimap!=null)minimap.hideNow();
    if(textSelection!=null)textSelection.finish();
    if(swipeNavigation.hasDrawer()){swipeNavigation.open();return;}
    if(input!=null){android.view.inputmethod.InputMethodManager manager=(android.view.inputmethod.InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);if(manager!=null)manager.hideSoftInputFromWindow(input.getWindowToken(),0);}
    SessionPicker.show(sessionButton,runtime,new SessionPicker.Host(){
      public void openModelSettings(){settings();}
      public void openProviderAuth(){ProviderAuthDialog.show(sessionButton,runtime);}
      public void openCapabilities(){permissions();}
      public void openSpeech(){speechCapabilities();}
      public void openToolCenter(){showToolCenter(sessionButton);}
    });
  }
  void syncMinimapSuppression(){if(minimap!=null)minimap.suppress(textSelection!=null||swipeNavigation!=null&&swipeNavigation.hasDrawer());}
  boolean runSlashCommand(String name,String argument){
    switch(name){
      case "tools":showToolCenter(sessionButton);return true;
      case "skills":showToolCenter(sessionButton,1);return true;
      case "mcp":ConnectorActivity.open(this);return true;
      case "packages":startActivity(new Intent(this,RuntimePackageManagerActivity.class));return true;
      case "model":settings();return true;
      case "agent":AgentSettings.show(sessionButton,runtime);return true;
      case "phone":permissions();return true;
      case "voice":toggleVoice();return true;
      case "share":toggleScreenShare();return true;
      case "new":
        if(runtime.busy){showStatus("请先停止当前任务");return false;}
        runtime.send(PiRuntime.json("type","new_session"));return true;
      case "stop":stop();return true;
      case "skill":sendText(SlashCommands.skillPrompt(argument));return true;
      case "pkg":sendText(SlashCommands.packagePrompt(argument));return true;
      case "runtime":sendText(SlashCommands.runtimePrompt());return true;
    }
    return false;
  }
  void showToolCenter(View anchor){showToolCenter(anchor,0);}
  void showToolCenter(View anchor,int section){
    ToolCenter.show(anchor,runtime,new ToolCenter.Host(){
      public void openModelSettings(){settings();}
      public void openAgentSettings(){AgentSettings.show(anchor,runtime);}
      public void openCapabilities(){permissions();}
      public void openSpeech(){speechCapabilities();}
      public void openPackages(){startActivity(new Intent(MainActivity.this,RuntimePackageManagerActivity.class));}
      public void openSkills(){SkillManager.show(anchor,runtime);}
      public void openProviderAuth(){ProviderAuthDialog.show(anchor,runtime);}
      public void openRuntimePacks(){runtimePacksSettings();}
      public void openTerminal(){TerminalActivity.open(MainActivity.this);}
      public void newSession(){if(!runtime.busy)runtime.send(PiRuntime.json("type","new_session"));}
      public void stopTask(){stop();}
      public void toggleVoice(){toggleVoice();}
      public void toggleScreenShare(){toggleScreenShare();}
      public void prompt(String text){sendText(text);}
    },section);
  }
  void bubble(String value,boolean user){
    LinearLayout line=new LinearLayout(this);ConversationMinimap.mark(line,user?ConversationMinimap.USER:ConversationMinimap.ASSISTANT);line.setGravity(user?Gravity.END:Gravity.START);line.setOrientation(LinearLayout.HORIZONTAL);
    LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setBackground(null);
    if(user){
      TextView view=text(value,16,theme.ink);
      card.setBackground(Ui.card(this,theme.field,22,0));card.setPadding(dp(16),dp(11),dp(14),dp(12));
      view.setMaxWidth(dp(560));card.addView(view,new LinearLayout.LayoutParams(-2,-2));line.addView(card,new LinearLayout.LayoutParams(-2,-2));
    }else{
      Ui.RichBlock view=Ui.rich(this,value,16,theme.ink);
      card.setPadding(dp(2),0,dp(4),0);
      LinearLayout meta=new LinearLayout(this);meta.setGravity(Gravity.CENTER_VERTICAL);
      Ui.AgentAvatar mark=new Ui.AgentAvatar(this,runtime,23);meta.addView(mark,new LinearLayout.LayoutParams(dp(23),dp(23)));
      TextView name=text(runtime.agentName,12,theme.muted);name.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));LinearLayout.LayoutParams nameParams=new LinearLayout.LayoutParams(-2,-2);nameParams.leftMargin=dp(8);meta.addView(name,nameParams);
      View space=new View(this);meta.addView(space,new LinearLayout.LayoutParams(0,1,1));
      Ui.Action copy=new Ui.Action(this,R.drawable.ic_copy,"","复制回答",theme.muted,Color.TRANSPARENT,()->{
        ((android.content.ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Zfold",value));
        Toast.makeText(this,"回答已复制",Toast.LENGTH_SHORT).show();
      });meta.addView(copy,new LinearLayout.LayoutParams(dp(38),dp(38)));
      card.addView(meta);LinearLayout.LayoutParams replyParams=new LinearLayout.LayoutParams(-1,-2);replyParams.topMargin=dp(9);card.addView(view,replyParams);
      line.addView(card,new LinearLayout.LayoutParams(0,-2,1));
      answer=view;
    }
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(user?14:18);lp.bottomMargin=dp(user?4:6);conversation.addView(line,lp);
    if(Ui.motion()){line.setAlpha(0);line.setTranslationY(dp(9));line.animate().alpha(1).translationY(0).setDuration(150).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();}
    minimap.changed();
  }
  void bubble(String value,boolean user,String picture,boolean hasPicture){
    bubble(value,user);
    if(!user||!hasPicture)return;
    Bitmap bitmap=Ui.thumbnail(picture,480);
    LinearLayout imageRow=new LinearLayout(this);ConversationMinimap.mark(imageRow,ConversationMinimap.IMAGE);imageRow.setGravity(Gravity.END);
    if(bitmap!=null){ImageView image=new ImageView(this);image.setImageBitmap(bitmap);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setBackground(Ui.card(this,theme.surface,18,theme.line));image.setElevation(dp(3));image.setPadding(dp(4),dp(4),dp(4),dp(4));image.setClipToOutline(true);image.setContentDescription("本条消息附带的屏幕图片");imageRow.addView(image,new LinearLayout.LayoutParams(dp(180),dp(140)));}
    else{TextView label=text("已附图片",13,theme.muted);label.setPadding(dp(12),dp(8),dp(12),dp(8));label.setBackground(Ui.shape(this,theme.field,12,0));imageRow.addView(label);}
    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(5);conversation.addView(imageRow,lp);minimap.changed();
  }
  void updateDraftAttachment(){
    if(draftAttachment==null)return;
    if(input!=null)input.setHint(pendingImage==null?"说说你的想法…":"已附上屏幕内容，想怎么处理？");
    if(draftLabel!=null)draftLabel.setText(draftLabelText()+" · 随下一条消息发送");
    draftAttachment.setVisibility(pendingImage==null&&localFiles.isEmpty()?View.GONE:View.VISIBLE);draftExpanded.setVisibility(View.GONE);
    attachmentScroll.setVisibility(draftAttachment.getVisibility());
    renderFiles();
    draftThumbnail.setImageDrawable(null);draftExpanded.setImageDrawable(null);if(draftBitmap!=null){draftBitmap.recycle();draftBitmap=null;}
    if(pendingImage!=null){draftBitmap=Ui.thumbnail(pendingImage,480);if(draftBitmap!=null){draftThumbnail.setImageBitmap(draftBitmap);draftExpanded.setImageBitmap(draftBitmap);}else draftThumbnail.setImageDrawable(Ui.icon(this,R.drawable.ic_image,theme.muted));}
  }
  void busyMessageMenu(){
    new AlertDialog.Builder(this).setTitle("任务执行中")
      .setItems(new String[]{"排队：当前任务完成后发送","引导当前任务（仅文字，提交后不可撤回）","查看 / 编辑 / 取消待发队列","停止任务并清空队列"},(d,which)->{
        if(which==0)sendText(input.getText().toString());
        else if(which==1){
          String text=input.getText().toString().trim();
          if(text.isEmpty()){notice("请先输入引导文字");return;}
          if(pendingImage!=null||!localFiles.isEmpty()){notice("引导暂不支持附件，请选择排队发送");return;}
          runtime.send(PiRuntime.json("type","steer","id",UUID.randomUUID().toString(),"sessionId",runtime.sessionId,"text",text));
        }else if(which==2)showPendingMessages();else stop();
      }).show();
  }
  void showPendingMessages(){
    PromptDispatcher dispatcher=PromptDispatcher.get(this);
    java.util.List<JSONObject> items=dispatcher.pending();
    if(items.isEmpty()){notice("没有待发消息；长按发送按钮可查看队列");return;}
    String[] labels=new String[items.size()];
    for(int i=0;i<items.size();i++)labels[i]=(i+1)+". "+items.get(i).optString("text");
    new AlertDialog.Builder(this).setTitle("待发队列（仅本次运行保留）").setItems(labels,(d,index)->{
      JSONObject item=items.get(index);String id=item.optString("id");
      EditText editor=new EditText(this);editor.setText(item.optString("text"));editor.setMinLines(3);
      new AlertDialog.Builder(this).setTitle("编辑待发消息（保留原附件）").setView(editor)
        .setNegativeButton("返回",null).setNeutralButton("取消这条",(a,b)->{notice(dispatcher.cancel(id)?"已取消":"消息已开始，无法取消");})
        .setPositiveButton("保存",(a,b)->{notice(dispatcher.edit(id,editor.getText().toString())?"已更新":"未保存：消息已开始或文字无效");}).show();
    }).setNegativeButton("关闭",null).show();
  }
  void sendText(String value){
    String text=value.trim();if(text.isEmpty()&&pendingImage==null&&localFiles.isEmpty())return;
    SystemAssistEntry.cancel();
    if(!runtime.configured){settings();return;}
    if(PromptDispatcher.isStop(text)){stop();return;}
    if(ScreenContext.enabled()||screenWindowPending){openScreenWorkspace(text,true);return;}
    dispatchText(text);
  }
  private void dispatchText(String text){
    if(text.isBlank())text="请分析这些本地文件。";
    String draftText=input.getText().toString(),draftImage=pendingImage;List<LocalFileAttachment> submittedFiles=new ArrayList<>(localFiles);
    JSONObject command=PiRuntime.json("type","prompt","id",UUID.randomUUID().toString(),"text",text);
    try{
      if(pendingImage!=null)command.put("image",pendingImage);
      JSONArray images=new JSONArray(),files=new JSONArray();
      for(LocalFileAttachment file:localFiles){
        files.put(PiRuntime.json("name",file.name,"mime",file.mime,"kind",file.kind,"size",file.size,"detail",file.detail,"excerpt",file.excerpt));
        if(file.image!=null)images.put(PiRuntime.json("data",file.image,"mime",file.mime));
        for(String image:file.images)images.put(PiRuntime.json("data",image,"mime","image/jpeg"));
      }
      if(images.length()>0)command.put("images",images);if(files.length()>0)command.put("files",files);
      lastSentFiles=fileSummary(submittedFiles);
    }catch(Exception ignored){}
    PromptDispatcher.get(this).submit(command,true,(accepted,queued,error)->{
      if(isFinishing()||isDestroyed())return;
      if(!accepted){showStatus(error);notice(error);return;}
      if(input.getText().toString().equals(draftText))input.setText("");
      if(Objects.equals(pendingImage,draftImage)){pendingImage=null;pendingImageLabel=null;}
      localFiles.removeAll(submittedFiles);updateDraftAttachment();showStatus(queued?"已排队，完成当前任务后继续":"正在思考…");updateSend();
    });
  }
  boolean followsBottom(){return !scroll.touching()&&!minimap.interacting()&&conversation.getHeight()-scroll.getHeight()-scroll.getScrollY()<dp(48);}
  void followBottom(boolean follow){int version=followVersion;if(follow)scroll.post(()->{if(version==followVersion)scroll.followLatest();});}
  void toolDisclosureChanged(){
    int position=scroll.getScrollY(),version=++followVersion;scroll.haltFling();minimap.changed();
    scroll.post(()->{if(version==followVersion)scroll.scrollTo(0,position);});
  }
  void showTool(JSONObject item){
    String id=item.optString("id");ToolCallView card=toolViews.get(id);
    if(card==null){
      View last=conversation.getChildCount()==0?null:conversation.getChildAt(conversation.getChildCount()-1);
      ToolGroupView group=last instanceof ToolGroupView?(ToolGroupView)last:new ToolGroupView(this,id,this::toolDisclosureChanged);
      if(group.getParent()==null){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(8);conversation.addView(group,lp);}
      card=new ToolCallView(this,item);toolViews.put(id,card);toolGroups.put(id,group);group.add(card);
    }else{card.update(item);ToolGroupView group=toolGroups.get(id);if(group!=null)group.refresh();}minimap.changed();
  }
  void finishToolGroups(boolean cancelled){for(ToolGroupView group:new LinkedHashSet<>(toolGroups.values()))group.finish(cancelled);minimap.changed();}
  void showHistory(JSONObject event){
    String session=event.optString("sessionId",runtime.sessionId);boolean same=displayedSessionId.equals(session);
    boolean follow=!same||conversation.getChildCount()==0||conversation.getChildAt(0)==intro||followsBottom();int position=scroll.getScrollY(),version=++followVersion;
    Set<String> openGroups=new HashSet<>(),openCalls=new HashSet<>();
    if(same){for(ToolGroupView group:toolGroups.values())if(group.expanded)openGroups.add(group.key);for(Map.Entry<String,ToolCallView> call:toolViews.entrySet())if(call.getValue().expanded)openCalls.add(call.getKey());}
    displayedSessionId=session;
    minimap.reset();
    JSONArray items=event.optJSONArray("items");conversation.removeAllViews();toolViews.clear();toolGroups.clear();answer=null;response.setLength(0);
    if(items==null||items.length()==0){conversation.setGravity(Gravity.CENTER_VERTICAL);conversation.addView(intro);return;}
    conversation.setGravity(Gravity.TOP);
    for(int i=0;i<items.length();i++){
      JSONObject item=items.optJSONObject(i);if(item==null)continue;
      if("tool".equals(item.optString("role"))){showTool(item);answer=null;response.setLength(0);continue;}
      boolean user="user".equals(item.optString("role"));String picture=item.optString("image");
      if(!user&&item.optString("text").trim().isEmpty())continue;
      if(picture.isEmpty()&&user&&item.optString("text").equals(runtime.lastPromptText)&&runtime.lastPromptImage!=null)picture=runtime.lastPromptImage;
      bubble(item.optString("text"),user,picture,item.optBoolean("hasImage")||!picture.isEmpty());response.setLength(0);if(user)answer=null;else response.append(item.optString("text"));
    }
    for(ToolGroupView group:new LinkedHashSet<>(toolGroups.values()))group.setExpanded(openGroups.contains(group.key));
    for(String id:openCalls){ToolCallView call=toolViews.get(id);if(call!=null)call.setExpanded(true);}
    if(!event.optBoolean("busy",runtime.busy))finishToolGroups(false);
    if(follow)followBottom(true);else scroll.post(()->{if(version==followVersion)scroll.scrollTo(0,position);});
  }
  @Override public void event(JSONObject event){
    switch(event.optString("type")){
      case "ready":showStatus(idleStatus());updateModelChip();break;
      case "configured":showStatus(idleStatus());updateModelChip();break;
      case "agent_identity":runtime.send(PiRuntime.json("type","history"));break;
      case "steer_result":
        if(event.optBoolean("accepted")){
          if(input.getText().toString().trim().equals(event.optString("text")))input.setText("");
          bubble("[引导已提交] "+event.optString("text"),true,null,false);
          showStatus("引导已提交，将在安全边界处理");
        }else notice(event.optString("message"));
        break;
      case "jev_status":break;
      case "jev_error":notice(event.optString("message"));break;
      case "session_changed":followVersion++;displayedSessionId=event.optString("sessionId",runtime.sessionId);minimap.reset();conversation.removeAllViews();toolViews.clear();toolGroups.clear();conversation.setGravity(Gravity.CENTER_VERTICAL);conversation.addView(intro);answer=null;response.setLength(0);pendingTaskPrompt=null;showStatus("已切换会话");break;
      case "history":showHistory(event);showStatus(runtime.busy?"正在处理…":idleStatus());break;
      case "turn_start":{
        displayedSessionId=runtime.sessionId;
        if(intro.getParent()==conversation)conversation.removeView(intro);conversation.setGravity(Gravity.TOP);
        JSONArray files=event.optJSONArray("files");StringBuilder summary=new StringBuilder(runtime.lastPromptText);
        if(files!=null&&files.length()>0){if(summary.length()>0)summary.append('\n');summary.append("[本地文件：");for(int i=0;i<files.length();i++){if(i>0)summary.append('，');summary.append(files.optJSONObject(i)==null?"文件":files.optJSONObject(i).optString("name","文件"));}summary.append(']');}
        bubble(summary.toString(),true,runtime.lastPromptImage,runtime.lastPromptHasImage);answer=null;response.setLength(0);followBottom(true);break;
      }
      case "assistant_start":answer=null;response.setLength(0);break;
      case "delta":boolean follow=followsBottom();String delta=event.optString("text");response.append(delta);if(answer==null){if(response.toString().trim().isEmpty())break;bubble("",false);}answer.setText(response.toString());followBottom(follow);break;
      case "tool_execution":boolean followTool=followsBottom(),newTool=!toolViews.containsKey(event.optString("id"));showTool(event);if(newTool)answer=null;showStatus("running".equals(event.optString("status"))?PhoneHud.action(event):"正在处理…");if("running".equals(event.optString("status"))&&!voice.listeningForInput())VoiceCallActivity.onControllerStatus(PhoneHud.action(event));followBottom(followTool);break;
      case "turn_end":{
        showStatus(idleStatus());
        finishToolGroups(event.optBoolean("aborted"));
        if(event.optBoolean("aborted"))showStatus(runtime.stopUnconfirmed?"停止尚未确认，请检查工具记录":"已停止");
        String queued=pendingTaskPrompt;pendingTaskPrompt=null;
        if(queued!=null&&!isFinishing())handler.postDelayed(()->{if(!isFinishing()){toggleVoice();sendText(queued);}},380);
        break;
      }
      case "error":finishToolGroups(false);showStatus("当前任务出错，请检查待发队列");pendingTaskPrompt=null;notice(Ui.errorMessage(event.optString("message")));break;
      case "probe":JSONObject d=event.optJSONObject("device");new AlertDialog.Builder(this).setTitle("本地运行正常").setMessage("Pi "+event.optString("pi")+"\nNode "+event.optString("node")+"\n架构 "+event.optString("arch")+"\n\n原生工具返回：\n"+(d==null?"":d.toString())).setPositiveButton("完成",null).show();break;
    }
    updateSend();updateModelChip();
  }
  void updateSend(){
    if(sendButton==null)return;boolean draft=hasDraft(),busy=runtime.busy;
    sendButton.setIcon(busy&&!draft?R.drawable.ic_stop:R.drawable.ic_arrow_up);
    sendButton.setText(busy&&draft?"排队":"");
    sendButton.setContentDescription(busy?(draft?"加入待发队列":"停止当前任务并清空队列"):"发送");sendButton.setEnabled(busy||draft);
    if(steerAction!=null){steerAction.setVisibility(busy&&draft?View.VISIBLE:View.GONE);stopAction.setVisibility(busy&&draft?View.VISIBLE:View.GONE);}
  }

  private void updateQuestionAction(){if(questionAction==null)return;UserQuestions.Request question=UserQuestions.current();questionAction.setVisibility(question==null?View.GONE:View.VISIBLE);if(question!=null){questionAction.setText("有 "+question.questions.length()+" 个问题需要回答");questionAction.setContentDescription("回答助手的问题");}}
  void updateComposerToolbar(float width){
    if(actionToolbar==null)return;
    int pixels=Math.round(width);float scale=getResources().getConfiguration().fontScale;
    if(toolbarWidth==pixels&&toolbarFontScale==scale&&toolbarSmall==smallWindow)return;
    toolbarWidth=pixels;toolbarFontScale=scale;toolbarSmall=smallWindow;
    if(smallWindow){
      for(Ui.Action action:new Ui.Action[]{modelButton,voiceButton,sessionButton,circleButton,attachButton,screenShareButton})action.setVisibility(View.GONE);
      overflowButton.setVisibility(View.VISIBLE);overflowButton.setCompact(true);overflowButton.setContentDescription("更多工具");sendButton.setCompact(true);updateModelChip();updateScreenShareControl();return;
    }
    int widthDp=width>0?Math.round(width/getResources().getDisplayMetrics().density):composerCompact?360:520;
    int mode=widthDp>=680?0:widthDp>=570?1:widthDp>=420?2:3;
    composerCompact=mode>=1;
    modelButton.label.setMaxWidth(mode==3?dp(56):mode==2?dp(70):dp(84));
    modelButton.setCompact(mode>=3);modelButton.setVisibility(widthDp<350&&getResources().getConfiguration().fontScale>1.15f?View.GONE:View.VISIBLE);screenShareButton.label.setMaxWidth(dp((mode>=2?42:100)*getResources().getConfiguration().fontScale));
    voiceButton.setCompact(mode>=1);
    voiceButton.setVisibility(View.VISIBLE);
    sessionButton.setCompact(true);
    circleButton.setCompact(mode>=1);
    attachButton.setCompact(mode>=1);
    screenShareButton.setCompact(false);
    sendButton.setCompact(false);overflowButton.setCompact(true);

    sessionButton.setVisibility(View.GONE);
    circleButton.setVisibility(mode<=1?View.VISIBLE:View.GONE);
    attachButton.setVisibility(mode<=1?View.VISIBLE:View.GONE);
    screenShareButton.setVisibility(View.VISIBLE);
    overflowButton.setVisibility(View.VISIBLE);
    overflowButton.setContentDescription("更多工具：会话、工作台"+(mode>=2?"、圈选、文件":""));
    updateModelChip();updateScreenShareControl();
  }
  void showToolbarOverflow(){
    ArrayList<Ui.Action> items=new ArrayList<>();
    if(modelButton.getVisibility()==View.GONE)items.add(modelButton);
    if(voiceButton.getVisibility()==View.GONE)items.add(voiceButton);
    if(sessionButton.getVisibility()==View.GONE)items.add(sessionButton);
    if(circleButton.getVisibility()==View.GONE)items.add(circleButton);
    if(attachButton.getVisibility()==View.GONE)items.add(attachButton);
    if(screenShareButton.getVisibility()==View.GONE)items.add(screenShareButton);
    items.add(workbenchButton);
    ToolbarOverflow.show(overflowButton,items);
  }
  void updateModelChip(){
    if(modelButton==null)return;String model=runtime.currentModel;String label=model.isEmpty()?"模型":model;
    if(!runtime.currentProvider.isEmpty()&&!runtime.currentProvider.equals("custom")&&!model.isEmpty())label=runtime.currentProvider+"/"+model;
    modelButton.setText(label);modelButton.setContentDescription((runtime.busy?"当前模型 "+label+"，执行中不能切换":"快速切换模型，当前 "+label));modelButton.setEnabled(!runtime.busy);
  }
  void showStatus(String value){
    if(status==null||statusRow==null)return;
    status.setText(value==null?"":value);
    String text=value==null?"":value.trim();
    boolean idle=text.equals(idleStatus())||text.equals("已切换会话")||text.equals("已停止")&&runtime.busy;
    if(text.isEmpty()||idle){statusRow.setVisibility(View.GONE);return;}
    statusRow.setVisibility(View.VISIBLE);
    int token=++statusToken;
    handler.postDelayed(()->{
      if(statusToken==token&&!runtime.busy&&!voice.sessionActive())statusRow.setVisibility(View.GONE);
    },4000);
  }
  File configFile(){return new File(getNoBackupFilesDir(),"model.json");}
  JSONObject config(){try{
    JSONObject value=new JSONObject(new String(java.nio.file.Files.readAllBytes(configFile().toPath()),StandardCharsets.UTF_8));
    String provider=value.optString("provider","custom");
    JSONObject defaults=runtime.defaultConfig;
    if(defaults!=null&&(provider.equals("custom")||!value.has("provider")))for(String key:new String[]{"baseUrl","model","apiKey","api"})if(!value.has(key))value.put(key,defaults.optString(key));
    return value;
  }catch(Exception e){return runtime.defaultConfig==null?new JSONObject():runtime.defaultConfig;}}
  void loadConfig(){JSONObject c=config();if(c.has("model"))runtime.send(PiRuntime.json("type","configure","config",c));}
  EditText field(LinearLayout body,String label,String value,boolean secret){TextView l=text(label,12,theme.muted);l.setPadding(0,dp(14),0,dp(3));body.addView(l);EditText e=new EditText(this);e.setSingleLine(true);e.setTextSize(14);e.setTextColor(theme.ink);e.setPadding(dp(12),dp(14),dp(12),dp(14));e.setBackground(Ui.shape(this,theme.field,12,theme.line));e.setText(value);e.setInputType(secret?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);body.addView(e,new LinearLayout.LayoutParams(-1,-2));return e;}
  AutoCompleteTextView modelField(LinearLayout body,String label,String value){
    TextView title=text(label,12,theme.muted);title.setPadding(0,dp(14),0,dp(3));body.addView(title);
    AutoCompleteTextView input=new AutoCompleteTextView(this);input.setSingleLine(true);input.setTextSize(14);input.setTextColor(theme.ink);input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);input.setThreshold(1);input.setPadding(dp(12),dp(14),dp(12),dp(14));input.setBackground(Ui.shape(this,theme.field,12,theme.line));input.setText(value);
    ArrayList<String> options=new ArrayList<>();org.json.JSONArray defaults=runtime.defaultModels;
    if(defaults!=null)for(int i=0;i<defaults.length();i++){String model=defaults.optString(i);if(!model.isEmpty()&&!options.contains(model))options.add(model);}
    if(!value.isEmpty()&&!options.contains(value))options.add(0,value);
    input.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_dropdown_item_1line,options));
    body.addView(input,new LinearLayout.LayoutParams(-1,-2));return input;
  }
  AutoCompleteTextView providerField(LinearLayout body,String value){
    TextView title=text("供应商 ID（custom 表示自建接口）",12,theme.muted);title.setPadding(0,dp(14),0,dp(3));body.addView(title);
    AutoCompleteTextView input=new AutoCompleteTextView(this);input.setSingleLine(true);input.setTextSize(14);input.setTextColor(theme.ink);input.setInputType(InputType.TYPE_CLASS_TEXT);input.setThreshold(1);input.setPadding(dp(12),dp(14),dp(12),dp(14));input.setBackground(Ui.shape(this,theme.field,12,theme.line));input.setText(value);
    ArrayList<String> options=new ArrayList<>();options.add("custom");
    if(runtime.modelProviders!=null)for(int i=0;i<runtime.modelProviders.length();i++){String provider=runtime.modelProviders.optJSONObject(i)==null?"":runtime.modelProviders.optJSONObject(i).optString("id");if(!provider.isEmpty()&&!options.contains(provider))options.add(provider);}
    input.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_dropdown_item_1line,options));
    body.addView(input,new LinearLayout.LayoutParams(-1,-2));return input;
  }
  void settings(){
    JSONObject c=config();LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);fields.setPadding(dp(24),0,dp(24),dp(10));
    TextView intro=text("填写服务提供的模型与认证信息，也可以登录供应商账号。配置保存在手机私有目录，支持多个供应商。",13,theme.muted);fields.addView(intro);
    fields.addView(new Ui.Action(this,R.drawable.ic_arrow_square_out,"供应商账号登录","登录供应商账号",theme.accent,theme.field,()->ProviderAuthDialog.show(fields,runtime)));
    AutoCompleteTextView provider=providerField(fields,c.optString("provider","custom"));
    EditText endpoint=field(fields,"接口地址（custom 必填，包含 /v1；内置供应商留空）",c.optString("baseUrl"),false),key=field(fields,"API Key（custom 或 BYOK）",c.optString("apiKey"),true);
    AutoCompleteTextView model=modelField(fields,"模型名称（输入过滤或选择）",c.optString("model"));
    EditText api=field(fields,"消息格式（custom 必填：openai-responses / openai-completions / anthropic-messages / google-generative-ai）",c.optString("api","openai-responses"),false);
    ScrollView formScroll=new ScrollView(this);formScroll.addView(fields);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("连接你的模型").setView(formScroll).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
    dialog.setOnDismissListener(d->{if(returnWorkbench){returnWorkbench=false;getIntent().removeExtra("return_workbench");WorkbenchActivity.returnFromSettings(this);if(temporarySettingsHost)finish();}});
    dialog.setOnShowListener(x->dialog.getButton(-1).setOnClickListener(v->{
      if(runtime.busy){notice("请先停止当前任务");return;}
      String providerId=provider.getText().toString().trim(),url=endpoint.getText().toString().trim(),apiValue=api.getText().toString().trim();
      if(!providerId.matches("[a-z0-9][a-z0-9_.-]{0,63}")){provider.setError("供应商 ID 非法");provider.requestFocus();return;}
      JSONObject value=PiRuntime.json("provider",providerId,"model",model.getText().toString().trim(),"apiKey",key.getText().toString().trim(),"api",apiValue);
      try{if(!url.isEmpty())value.put("baseUrl",url);}catch(Exception ignored){}
      try{if(!url.isEmpty()){java.net.URI uri=new java.net.URI(url);if(!("https".equals(uri.getScheme())||"http".equals(uri.getScheme()))||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)throw new Exception();}}catch(Exception invalid){endpoint.setError("填写完整 HTTP(S) 接口地址，不包含密码或查询参数");endpoint.requestFocus();return;}
      if(model.getText().toString().trim().isEmpty()){model.setError("填写服务提供的模型名称");model.requestFocus();return;}
      if(providerId.equals("custom")||!url.isEmpty()){
        if(url.isEmpty()){endpoint.setError("自定义供应商需要接口地址");endpoint.requestFocus();return;}
        if(!java.util.Arrays.asList("openai-responses","openai-completions","anthropic-messages","google-generative-ai").contains(apiValue)){api.setError("消息格式不合法");api.requestFocus();return;}
      }
      try(FileOutputStream out=new FileOutputStream(configFile())){out.write(value.toString().getBytes(StandardCharsets.UTF_8));}catch(Exception e){notice("保存失败");return;}
      runtime.send(PiRuntime.json("type","configure","config",value));dialog.dismiss();
    }));dialog.show();
  }
  void permissions(){
    boolean defaultAssistant=android.service.voice.VoiceInteractionService.isActiveService(this,new ComponentName(this,AssistantVoiceService.class));
    String[] items={"默认助手 · "+(defaultAssistant?"已设置":"未设置"),"读屏与操作 · "+(PhoneAccessibility.instance!=null?"已连接":"未连接"),"兼容浮层权限 · "+(Settings.canDrawOverlays(this)?"已允许":"未允许"),"系统小窗与分屏","查看运行状态","唤起手势 · 从底角向内划","本机语音 · 检测与下载","Jev 手机操作 · "+(runtime.jevEnabled&&runtime.jevConfigured?"已启用":"未配置"),"storage/pi 文件 · "+(Environment.isExternalStorageManager()?"已授权":"未授权"),"Runtime Packs","软件包管理"};
    new AlertDialog.Builder(this).setTitle("手机能力").setItems(items,(d,i)->{
      if(i==4){runtime.send(PiRuntime.json("type","probe"));return;}
      if(i==5){new AlertDialog.Builder(this).setTitle("从当前应用唤起").setMessage("将 Zfold 设为默认助手后，从屏幕左下角或右下角向内划，即可唤起助手。\n\n导航条正中长按在当前 Galaxy 系统中属于「圈选即搜」，不能由普通应用改绑。也可以使用 Zfold 悬浮入口。").setPositiveButton("知道了",null).show();return;}
      if(i==6){speechCapabilities();return;}
      if(i==7){jevSettings();return;}
      if(i==8){requestStorageAccess();return;}
      if(i==9){runtimePacksSettings();return;}
      if(i==10){startActivity(new Intent(this,RuntimePackageManagerActivity.class));return;}
      if(i==0)startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS));
      if(i==1)startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
      if(i==2)startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));
      if(i==3){SystemWindowNavigation.openSmallWindow(this);}
  }).setNegativeButton("关闭",null).show();
  }
  void runtimePacksSettings(){
    if(!Environment.isExternalStorageManager()){requestStorageAccess();return;}
    showStatus("扫描 Runtime Packs…");
    new Thread(()->{
      JSONObject scan;
      try{scan=PiRuntimePacks.available(this);}catch(Exception e){runOnUiThread(()->notice("扫描失败："+e.getMessage()));return;}
      runOnUiThread(()->{
        if(isFinishing())return;showStatus(idleStatus());
        JSONArray archives=scan.optJSONArray("archives");
        if(archives==null||archives.length()==0){new AlertDialog.Builder(this).setTitle("Runtime Packs").setMessage("没有找到 .tar.gz。\n\n请把 `scripts/runtime-pack.py` 生成的包放到：\n/storage/pi/runtime-packs/").setPositiveButton("完成",null).show();return;}
        ArrayList<String> labels=new ArrayList<>();ArrayList<JSONObject> files=new ArrayList<>();
        for(int i=0;i<archives.length();i++){JSONObject item=archives.optJSONObject(i);if(item==null)continue;files.add(item);labels.add(item.optString("name")+"\n"+(item.optBoolean("installed")?"已安装 · ":"未安装 · ")+item.optString("sha256").substring(0,12)+" · "+(item.optLong("size")/1024/1024)+" MB");}
        new AlertDialog.Builder(this).setTitle("Runtime Packs").setItems(labels.toArray(new String[0]),(d,i)->{
          JSONObject item=files.get(i);
          new AlertDialog.Builder(this).setTitle("安装 Runtime Pack").setMessage("文件："+item.optString("name")+"\n\nSHA-256："+item.optString("sha256")+"\n\n安装会解压 Android/bionic 二进制到应用私有目录。请确认来源和哈希。").setNegativeButton("取消",null).setPositiveButton("安装",(x,y)->{
            showStatus("安装 "+item.optString("name")+"…");
            new Thread(()->{
              try{JSONObject result=PiRuntimePacks.install(this,item.optString("name"),item.optString("sha256"));runOnUiThread(()->{if(isFinishing())return;showStatus(idleStatus());notice("Runtime Pack 已安装");
                JSONObject installed=result.optJSONObject("installed");int packageCount=installed==null?0:installed.optJSONArray("packages")==null?0:installed.optJSONArray("packages").length();
                String message=(installed==null||installed.optString("name").isEmpty()?"Runtime Pack":installed.optString("name"))+" 已安装"+(packageCount==0?"":" · "+packageCount+" 个包");
                new AlertDialog.Builder(this).setTitle("安装完成").setMessage(message).setPositiveButton("完成",null).show();});}
              catch(Exception error){runOnUiThread(()->{if(isFinishing())return;showStatus(idleStatus());notice("安装失败："+error.getMessage());});}
            },"pi-pack-install").start();
          }).show();
        }).setNegativeButton("关闭",null).show();
      });
    },"pi-pack-scan").start();
  }
  void requestStorageAccess(){
    if(Environment.isExternalStorageManager()){notice("storage/pi 已授权；完全退出并重新打开 Zfold 后生效");return;}
    startActivityForResult(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:"+getPackageName())),23);
  }
  @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
    if(request==23)notice(Environment.isExternalStorageManager()?"已授权 storage/pi，请完全退出并重新打开 Zfold":"未授予所有文件访问，Pi 继续使用应用私有目录");
  }
  void jevSettings(){
    LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);fields.setPadding(dp(24),0,dp(24),dp(12));
    fields.addView(text("Jev 根据当前界面选择手机动作。启用后，任务目标和必要的界面文字会发给 TypeSafe；实际操作在本机执行。",13,theme.muted));
    EditText model=field(fields,"Jev 模型",runtime.jevModel,false),key=field(fields,runtime.jevConfigured?"API Key（留空保留已保存的密钥）":"API Key","",true);
    fields.addView(text("保存配置后，在下一次手机任务中使用。密钥仅保存在应用私有目录。",12,theme.muted));
    ScrollView scroll=new ScrollView(this);scroll.addView(fields);
    AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Jev 手机操作").setView(scroll).setNegativeButton("取消",null).setNeutralButton("停用并清除密钥",(d,w)->{if(runtime.busy){notice("请先停止当前任务");return;}runtime.send(PiRuntime.json("type","configure_jev","config",PiRuntime.json("enabled",false,"apiKey","")));}).setPositiveButton("保存并启用",null).create();
    dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{
      if(runtime.busy){notice("请先停止当前任务");return;}
      String modelName=model.getText().toString().trim(),secret=key.getText().toString().trim();
      if(!modelName.matches("jev-[a-zA-Z0-9.-]{1,80}")){model.setError("填写有效的 Jev 模型名称");return;}
      if(secret.isEmpty()&&!runtime.jevConfigured){key.setError("填写 Jev API Key");return;}
      JSONObject next=PiRuntime.json("enabled",true,"model",modelName);try{if(!secret.isEmpty())next.put("apiKey",secret);}catch(Exception ignored){}
      runtime.send(PiRuntime.json("type","configure_jev","config",next));key.setText("");dialog.dismiss();
    }));dialog.show();
  }
  void speechCapabilities(){
    SpeechSettings.show(this,voice,this::localSpeechCapabilities);
  }
  private void localSpeechCapabilities(){
    showStatus("检查本机语音能力…");PhoneSpeech.inspectMandarin(this,support->{
      if(isFinishing())return;
      String recognition=support.installed?"简体中文识别包已安装，语音输入会优先在本机完成。":support.downloadable?"简体中文识别包可下载，但尚未安装；当前语音输入仍用系统默认识别，可能联网。":support.service?"本机识别服务存在，但暂未报告可用的简体中文模型。":"这台设备暂未提供本机识别服务。";
      voice.availableOfflineVoice(localVoice->runOnUiThread(()->{
        if(isFinishing())return;showStatus(idleStatus());
        String synthesis=localVoice==null?"尚未检测到已安装的离线中文声线。":"已安装离线中文声线："+localVoice;
        boolean auto=VoiceVadMonitor.enabled(this);String interruption="自动插话 · "+(auto?"已开启":"未开启")+"。本机 VAD 检测插话候选，再由所选语音识别服务确认；噪声、外放与蓝牙仍需实测。";
        String word=WakeConfig.get(this).word;boolean wake=VoiceWakeService.enabled(this);String wakeState=(wake?"已开启":"未开启")+"，当前唤醒词为“"+word+"”。接通并可收听时响一声短提示音。";
        if(wake&&VoiceWakeService.lastFailure!=null)wakeState+="\n当前状态："+VoiceWakeService.lastFailure;
        LinearLayout info=new LinearLayout(this);info.setOrientation(LinearLayout.VERTICAL);info.setPadding(dp(22),dp(8),dp(22),dp(10));
        info.addView(Ui.text(this,"识别 · "+recognition+"\n\n播报 · "+synthesis+"\n\n"+interruption+"\n\n唤醒 · "+wakeState,14,theme.ink));
        if(!support.installed&&support.downloadable){Button download=new Button(this);download.setText("下载本机中文识别包");download.setOnClickListener(v->PhoneSpeech.downloadMandarin(this,message->{if(!isFinishing())notice(message);}));info.addView(download);}
        ScrollView content=new ScrollView(this);content.addView(info);
        AlertDialog.Builder dialog=new AlertDialog.Builder(this).setTitle("本机语音与唤醒").setView(content).setNegativeButton("关闭",null).setNeutralButton(auto?"关闭自动插话":"开启自动插话",(d,w)->{VoiceVadMonitor.setEnabled(this,!auto);localSpeechCapabilities();})
          .setPositiveButton(wake?"关闭“"+word+"”唤醒":"开启“"+word+"”唤醒",(d,w)->toggleWake());
        dialog.show();
      }));
    });
  }
  void toggleVoice(){
    if(!runtime.configured){settings();return;}
    if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},21);return;}
    if(runtime.busy){voice.beginTaskVoice();VoiceCallActivity.show(this);updateSend();return;}
    if(voice.sessionActive()){VoiceCallActivity.show(this);return;}
    VoiceCallActivity.show(this);voice.beginSession();updateSend();
  }
  void notice(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
  private String draftLabelText(){return pendingImageLabel==null?"圈选区域":pendingImageLabel;}
  private void toggleScreenShare(){
    if(screenWindowPending)return;
    if(ScreenContext.enabled()){ScreenContext.setEnabled(false);showStatus("看屏幕已关闭");return;}
    if(PhoneAccessibility.instance==null){new AlertDialog.Builder(this).setTitle("开启读屏与操作").setMessage("从悬浮窗查看其他应用，需要先在系统设置开启 Zfold 的无障碍服务。").setNegativeButton("返回",null).setPositiveButton("前往设置",(d,w)->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))).show();return;}
    if(!isInMultiWindowMode()){
      screenWindowPending=true;
      showStatus("正在切换系统小窗…");
      SystemWindowNavigation.enterSmallWindow(this,success->{
        screenWindowPending=false;
        if(isFinishing()||isDestroyed())return;
        if(!success){showStatus("未能切换小窗，看屏幕尚未开启");SystemWindowNavigation.explain(this);return;}
        ScreenContext.setEnabled(true);showStatus("已开启看屏幕，每次发送读取旁边的应用");
      });return;
    }
    ScreenContext.setEnabled(true);showStatus("已开启看屏幕，每次发送读取旁边的应用");
  }
  private void openScreenWorkspace(String text,boolean send){
    if(capturingScreen)return;capturingScreen=true;
    if(screenWindowPending||!isInMultiWindowMode()){
      showStatus("正在切换系统小窗…");
      SystemWindowNavigation.enterSmallWindow(this,success->{
        capturingScreen=false;if(isFinishing()||isDestroyed())return;
        if(!success){showStatus("未能切换小窗，请求尚未发送");SystemWindowNavigation.explain(this);return;}
        if(ScreenContext.enabled())openScreenWorkspace(text,send);
      });return;
    }
    if(!send){capturingScreen=false;return;}
    pendingImage=null;pendingImageLabel=null;updateDraftAttachment();
    ((android.view.inputmethod.InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);
    ScreenContext.capture(this,(image,label,error)->{capturingScreen=false;if(isFinishing()||isDestroyed())return;if(error!=null){showStatus(error);notice(error);return;}pendingImage=image;pendingImageLabel=label;dispatchText(text);});
  }
  private void updateScreenShareControl(){
    if(screenShareButton==null)return;screenSharing=ScreenContext.enabled();int tint=screenSharing?theme.accent:theme.muted;
    screenShareButton.tint=tint;screenShareButton.setText("看屏幕");screenShareButton.image.setImageDrawable(Ui.icon(this,R.drawable.ic_screen_share,tint));
    screenShareButton.setContentDescription(screenSharing?"关闭看屏幕":"看屏幕 · 读取旁边的应用");
  }
  private static boolean isStopCommand(String value){
    String text=value.toLowerCase(Locale.ROOT).replaceAll("[\\s，,。：:！!？?]+","");
    return text.equals("停")||text.equals("停止")||text.equals("停下")||text.equals("取消")||text.equals("别做了")||text.equals("请停止")||text.equals("停止任务")||text.equals("停止当前任务");
  }
  void stop(){PromptDispatcher.cancelPending();pendingTaskPrompt=null;localFiles.clear();updateDraftAttachment();voice.stopSession(true);showStatus(runtime.busy?"正在停止…":"已停止");VoiceWakeService.resume(this);}
  void toggleWake(){
    ArrayList<String> missing=new ArrayList<>();
    if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.RECORD_AUDIO);
    if(Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=PackageManager.PERMISSION_GRANTED)missing.add("android.permission.POST_NOTIFICATIONS");
    if(!missing.isEmpty()){requestPermissions(missing.toArray(new String[0]),22);return;}
    boolean next=!VoiceWakeService.enabled(this);VoiceWakeService.setEnabled(this,next);String word=WakeConfig.get(this).word;notice(next?"已开启“"+word+"”唤醒":"已关闭“"+word+"”唤醒");
  }
  void acceptImage(){if(CircleOverlay.selectedImage!=null){pendingImage=CircleOverlay.selectedImage;pendingImageLabel="圈选区域";CircleOverlay.selectedImage=null;input.setHint("已附上屏幕内容，想怎么处理？");updateDraftAttachment();}}
  void acceptLegacyDraft(){
    if(!AssistantOverlay.hasSavedDraft()||input.getText().length()!=0||pendingImage!=null||!localFiles.isEmpty())return;
    AssistantPanel.Draft draft=AssistantOverlay.takeDraft();if(draft==null)return;input.setText(draft.text);pendingImage=draft.image;pendingImageLabel=draft.label;if(draft.files!=null)localFiles.addAll(draft.files);updateDraftAttachment();
  }
  void pickFiles(){
    FilePickActivity.request(this,uris->{
      if(uris.isEmpty())return;
      new Thread(()->{
        try{
          List<LocalFileAttachment> files=LocalFiles.prepare(this,uris,message->runOnUiThread(()->showStatus(message)));
          runOnUiThread(()->{
            for(LocalFileAttachment file:files)if(localFiles.size()<LocalFiles.MAX_FILES)localFiles.add(file);
            updateDraftAttachment();showStatus(localFiles.isEmpty()?"没有选择文件":"已添加 "+localFiles.size()+" 个本地文件");
          });
        }catch(Exception error){runOnUiThread(()->showStatus(Ui.errorMessage(error.getMessage())));}
      },"zfold-read-files").start();
    });
  }
  private void renderFiles(){
    if(fileList==null)return;fileList.removeAllViews();
    for(LocalFileAttachment file:localFiles){
      LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(4),dp(5),dp(2),dp(5));
      ImageView icon=new ImageView(this);int resource=file.isImage()?R.drawable.ic_image:file.kind.equals("audio")||file.kind.equals("video")?R.drawable.ic_microphone:R.drawable.ic_file;
      icon.setImageDrawable(Ui.icon(this,resource,theme.accent));row.addView(icon,new LinearLayout.LayoutParams(dp(20),dp(20)));
      LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);LinearLayout.LayoutParams labelParams=new LinearLayout.LayoutParams(0,-2,1);labelParams.leftMargin=dp(9);row.addView(labels,labelParams);
      TextView title=text(file.name,13,theme.ink);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);title.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0));labels.addView(title);
      String detail=file.detail.replace("\n"," · ");TextView detailView=text(detail,11,theme.muted);detailView.setSingleLine(true);detailView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);labels.addView(detailView);
      row.addView(new Ui.Action(this,R.drawable.ic_x,"","移除 "+file.name,theme.muted,Color.TRANSPARENT,()->{localFiles.remove(file);updateDraftAttachment();}),new LinearLayout.LayoutParams(dp(48),dp(48)));
      row.setBackground(Ui.ripple(this,theme.surface,12,theme.ink));row.setClickable(true);row.setFocusable(true);
      fileList.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }
  }
  private static String fileSummary(List<LocalFileAttachment> files){
    if(files==null||files.isEmpty())return "";
    StringBuilder out=new StringBuilder("[本地文件：");for(int i=0;i<files.size();i++){if(i>0)out.append("，");out.append(files.get(i).name);}return out.append("]").toString();
  }
  private void acceptWakeEntry(){
    long id=getIntent().getLongExtra("wake_event_id",0);if(id==0)return;getIntent().removeExtra("wake_event_id");
    if(!VoiceWakeService.pending(id))return;
    if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){VoiceWakeService.discard(id);showStatus("麦克风权限不可用，语音尚未接通");return;}
    VoiceCallActivity.show(this);VoiceCoordinator.get(this).requestWake(id);
  }
  @Override protected void onResume(){super.onResume();acceptLegacyDraft();acceptImage();acceptWakeEntry();if(getIntent().getBooleanExtra("open_voice",false)){getIntent().removeExtra("open_voice");root.post(this::toggleVoice);}}
  @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);root.post(this::acceptWakeEntry);returnWorkbench=intent.getBooleanExtra("return_workbench",false);acceptImage();if(intent.getBooleanExtra("open_tools",false)){intent.removeExtra("open_tools");sessionButton.post(()->showToolCenter(sessionButton));}if(intent.getBooleanExtra("open_models",false)){intent.removeExtra("open_models");settings();}if(intent.getBooleanExtra("open_capabilities",false)){intent.removeExtra("open_capabilities");permissions();}if(intent.getBooleanExtra("open_speech",false)){intent.removeExtra("open_speech");speechCapabilities();}if(intent.getBooleanExtra("open_sessions",false)){intent.removeExtra("open_sessions");sessionButton.post(this::openSidebar);}if(intent.getBooleanExtra("open_provider_auth",false)){intent.removeExtra("open_provider_auth");sessionButton.post(()->ProviderAuthDialog.show(sessionButton,runtime));}if(intent.getBooleanExtra("open_runtime_packs",false)){intent.removeExtra("open_runtime_packs");runtimePacksSettings();}}
  @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==21&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)toggleVoice();if(request==22)toggleWake();}
  @Override protected void onPause(){SystemWindowNavigation.remember(this);super.onPause();if(voice.sessionActive()&&!VoiceCallActivity.isActive()){VoiceWakeService.resume(this);}}
  @Override public void onConfigurationChanged(android.content.res.Configuration config){super.onConfigurationChanged(config);if(new Ui.Theme(this).dark!=theme.dark){recreate();return;}root.post(this::adjustColumn);SystemWindowNavigation.remember(this);}
  boolean canNavigateDrawerBack(){WindowInsets insets=getWindow().getDecorView().getRootWindowInsets();return textSelection==null&&(insets==null||!insets.isVisible(WindowInsets.Type.ime()));}
  void handleBack(){
    WindowInsets insets=getWindow().getDecorView().getRootWindowInsets();
    if(insets!=null&&insets.isVisible(WindowInsets.Type.ime())){((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);return;}
    if(textSelection!=null){textSelection.finish();return;}
    if(swipeNavigation.hasDrawer()){swipeNavigation.close();return;}
    if(draftExpanded.getVisibility()==View.VISIBLE){draftExpanded.setVisibility(View.GONE);return;}
    showStatus("已在当前对话");
  }
  @Override public void onBackPressed(){handleBack();}
  @Override public void onActionModeStarted(ActionMode mode){super.onActionModeStarted(mode);textSelection=mode;syncMinimapSuppression();if(swipeNavigation!=null)swipeNavigation.selecting(true);}
  @Override public void onActionModeFinished(ActionMode mode){super.onActionModeFinished(mode);if(textSelection==mode)textSelection=null;syncMinimapSuppression();if(swipeNavigation!=null)swipeNavigation.selecting(false);}
  @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);if(root!=null)SystemWindowNavigation.remember(this);if(focus)SystemAssistEntry.attach(this);}
  @Override protected void onDestroy(){PromptDispatcher.get(this).unlistenQueue(queueChanged);UserQuestions.unlisten(questionChanged);ChatBackNavigation.unregister(this,backCallback);if(minimap!=null)minimap.dispose();if(swipeNavigation!=null)swipeNavigation.dispose();if(current==this)current=null;runtime.unlisten(this);ScreenContext.unlisten(screenListener);pendingTaskPrompt=null;VoiceCoordinator.get(this).forget(this);if(isFinishing()&&!VoiceCallActivity.isActive())voice.stopSession(false);if(draftBitmap!=null){draftThumbnail.setImageDrawable(null);draftExpanded.setImageDrawable(null);draftBitmap.recycle();draftBitmap=null;}super.onDestroy();}
}
