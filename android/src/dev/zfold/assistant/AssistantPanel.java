package dev.zfold.assistant;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.os.*;
import android.speech.*;
import android.speech.tts.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.*;

/** One native interaction surface, shared by the assistant role and floating entry. */
public final class AssistantPanel extends LinearLayout implements PiRuntime.Listener {
  interface Host { void close(); void minimize(); void select(); void toggleExpansion(); }
  static final class Draft {String text,image,label;boolean sharedScreen;ArrayList<LocalFileAttachment> files;Draft(String t,String i,String l,boolean s,ArrayList<LocalFileAttachment> f){text=t;image=i;label=l;sharedScreen=s;files=f;}}
  final Host host;final PiRuntime runtime;final Ui.Theme theme;final Handler handler=new Handler(Looper.getMainLooper());
  TextView state,attachmentTitle,attachmentHint,identityTitle,answerName;Ui.RichBlock replyBlock;
  Ui.Action mic,modelChip,select,fileButton,screenShare,sidebar,expandAction,primary,copy,retry,configure,overflow;EditText input;
  LinearLayout attachment,fileList,answerBlock,errorActions,answerMeta,sentCard,composerSurface,actionToolbar;android.view.View slashSuggest;ImageView thumbnail,preview,sentThumbnail,sentExpanded;TextView sentText;ScrollView resultArea;
  Ui.AgentAvatar identityBadge,answerBadge;
  ProgressBar progress;String image,imageLabel,lastSentText="",lastSentImage,lastSentImageLabel,rawReply="";
  LinearLayout toolList;final Map<String,ToolCallView> toolViews=new LinkedHashMap<>();
  LinearLayout interruptionList;final Map<String,InterruptionView> interruptionViews=new LinkedHashMap<>();
  TextView retryLabel;ScrollView contentArea;
  VoiceTurnController voice;Bitmap imagePreview,sentBitmap;
  boolean speakingMode,disposed,expandedImage,renderPending,compact,screenSharing,capturingScreen;
  boolean expanded;private int bottomInset;
  final Runnable screenListener=()->{screenSharing=ScreenContext.enabled();updateScreenShareControl();if(!screenSharing&&imageLabel!=null&&imageLabel.startsWith("屏幕 ·"))removeImage();};
  int voiceConfigWaits;
  String pendingTaskPrompt;
  String lastSentFiles="";
  final ArrayList<LocalFileAttachment> localFiles=new ArrayList<>();

  AssistantPanel(Context context,Host host){
    super(context);this.host=host;screenSharing=ScreenContext.enabled();runtime=PiRuntime.get(context);theme=new Ui.Theme(context);
    voice=VoiceCoordinator.get(context).observe(this,new VoiceTurnController.Callback(){
      public boolean active(){return speakingMode&&!disposed;}
      public void status(String text){state.setText(text);}
      public void voiceState(boolean session,boolean speaking){speakingMode=session;mic.setText(session?(speaking?"打断":"结束对话"):"说话");mic.setIcon(session&&speaking?R.drawable.ic_stop:R.drawable.ic_microphone);updateControls();}
      public void prompt(String text){send(text);}
      public void sessionEnded(String reason){speakingMode=false;mic.setText("说话");mic.setIcon(R.drawable.ic_microphone);state.setText(reason);updateControls();}
    });
    setOrientation(VERTICAL);setPadding(dp(14),dp(14),dp(14),dp(10));setBackground(Ui.sheet(context,theme.surface,24,theme.line));setElevation(dp(10));setFocusableInTouchMode(true);
    buildHeader();buildAttachment();buildAnswer();buildComposer();buildActions();
    removeView(attachment);LinearLayout.LayoutParams attachmentParams=new LinearLayout.LayoutParams(-1,-2);attachmentParams.bottomMargin=dp(7);composerSurface.addView(attachment,0,attachmentParams);
    LinearLayout content=new LinearLayout(context);content.setOrientation(VERTICAL);
    for(int i=0;i<2;i++){View child=getChildAt(0);removeViewAt(0);content.addView(child);}
    ScrollView contentScroll=new ScrollView(context);contentArea=contentScroll;contentScroll.setFillViewport(false);contentScroll.setVerticalScrollBarEnabled(false);contentScroll.addView(content);
    addView(contentScroll,0,new LayoutParams(-1,-2,1));
    if(!runtime.lastPromptText.isEmpty()||runtime.lastPromptHasImage){lastSentText=runtime.lastPromptText;lastSentImage=runtime.lastPromptImage;showSent(lastSentText,lastSentImage,runtime.lastPromptHasImage);}
    if(!runtime.lastResponse.isEmpty())showReply(runtime.lastResponse);
    restoreTools();restoreInterruptions();renderRetry();
    runtime.listen(this);ScreenContext.listen(screenListener);screenListener.run();runtime.restoreConfiguration();if(runtime.configured)runtime.send(PiRuntime.json("type","history"));updateControls();requestFocus();
  }
  int dp(float n){return Ui.dp(getContext(),n);}
  void setBottomInset(int inset){bottomInset=Math.max(0,inset);int bottom=dp(compact?6:10)+bottomInset,top=dp(compact?6:14);if(getPaddingBottom()!=bottom||getPaddingTop()!=top)setPadding(dp(14),top,dp(14),bottom);}
  @Override protected void onMeasure(int widthSpec,int heightSpec){
    boolean next=MeasureSpec.getMode(heightSpec)!=MeasureSpec.UNSPECIFIED&&MeasureSpec.getSize(heightSpec)<dp(340);
    if(state!=null&&attachment!=null&&next!=compact){compact=next;setBottomInset(bottomInset);state.setVisibility(VISIBLE);attachment.setVisibility(image!=null&&!compact?VISIBLE:GONE);input.setHint(compact&&image!=null?"想对这张图做什么？":"想问什么，或让我做什么？");}
    super.onMeasure(widthSpec,heightSpec);
  }
  TextView text(String s,int size,int color){return Ui.text(getContext(),s,size,color);}
  Ui.Action action(int icon,String label,String description,int tint,int fill,Runnable task){return new Ui.Action(getContext(),icon,label,description,tint,fill,task);}
  LinearLayout row(){LinearLayout row=new LinearLayout(getContext());row.setGravity(Gravity.CENTER_VERTICAL);return row;}
  void buildHeader(){
    LinearLayout head=row();identityBadge=new Ui.AgentAvatar(getContext(),runtime,32);identityBadge.setPadding(dp(8),dp(8),dp(8),dp(8));identityBadge.setBackground(Ui.ripple(getContext(),Color.TRANSPARENT,24,theme.accent));identityBadge.setFocusable(true);identityBadge.setContentDescription("打开工作台");identityBadge.setOnClickListener(v->{Ui.click(v);if(voice.sessionActive()||voice.speaking())voice.forceInterrupt();else{hideKeyboard();WorkbenchActivity.open(getContext());}});head.addView(identityBadge,new LayoutParams(dp(48),dp(48)));
    LinearLayout identity=new LinearLayout(getContext());identity.setOrientation(VERTICAL);identity.setPadding(dp(10),0,0,0);
    TextView title=text(runtime.agentName,16,theme.ink);identityTitle=title;title.setTypeface(Typeface.create("sans-serif-medium",0));identity.addView(title);
    LinearLayout statusRow=row();statusRow.setPadding(0,dp(5),0,0);
    progress=new ProgressBar(getContext(),null,android.R.attr.progressBarStyleSmall);progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(theme.accent));statusRow.addView(progress,new LayoutParams(dp(12),dp(12)));
    state=text(runtime.busy?"正在处理":runtime.configured?"随时待命":"连接模型后开始",12,theme.muted);state.setPadding(dp(4),0,0,0);statusRow.addView(state,new LayoutParams(0,-2,1));identity.addView(statusRow);head.addView(identity,new LayoutParams(0,-2,1));
    expandAction=action(R.drawable.ic_arrows_out_simple,"",expanded?"收缩为半屏":"展开全屏",theme.muted,Color.TRANSPARENT,host::toggleExpansion);
    head.addView(expandAction,new LayoutParams(dp(48),dp(48)));
    head.addView(action(R.drawable.ic_minus,"","最小化助手",theme.muted,Color.TRANSPARENT,()->{hideKeyboard();host.minimize();}),new LayoutParams(dp(48),dp(48)));
    head.addView(action(R.drawable.ic_x,"","关闭助手",theme.muted,Color.TRANSPARENT,()->{hideKeyboard();voice.stopSession(false);host.close();}),new LayoutParams(dp(48),dp(48)));addView(head);
    identity.setOnTouchListener(new OnTouchListener(){float origin;boolean moving;
      public boolean onTouch(View v,MotionEvent e){switch(e.getActionMasked()){
        case MotionEvent.ACTION_DOWN:animate().cancel();origin=e.getRawY()-getTranslationY();moving=false;return true;
        case MotionEvent.ACTION_MOVE:float dy=Math.max(0,e.getRawY()-origin);moving|=dy>dp(8);if(moving)setTranslationY(dy);return true;
        case MotionEvent.ACTION_UP:if(getTranslationY()>dp(56)){setTranslationY(0);hideKeyboard();host.minimize();return true;}
        case MotionEvent.ACTION_CANCEL:if(Ui.motion())animate().translationY(0).setDuration(170).start();else setTranslationY(0);return true;
      }return true;}});
  }
  void buildAttachment(){
    attachment=new LinearLayout(getContext());attachment.setOrientation(VERTICAL);attachment.setVisibility(GONE);attachment.setBackground(Ui.shape(getContext(),theme.field,16,0));
    LinearLayout top=row(),open=row();open.setPadding(dp(8),dp(8),0,dp(8));open.setMinimumHeight(dp(56));open.setContentDescription("预览附带的屏幕图片");open.setBackground(Ui.ripple(getContext(),Color.TRANSPARENT,16,theme.ink));open.setOnClickListener(v->{expandedImage=!expandedImage;preview.setVisibility(expandedImage?VISIBLE:GONE);attachmentHint.setText(expandedImage?"轻点收起预览":"随本次提问发送 · 轻点预览");});
    thumbnail=new ImageView(getContext());thumbnail.setScaleType(ImageView.ScaleType.FIT_CENTER);thumbnail.setBackground(Ui.shape(getContext(),theme.surface,9,0));thumbnail.setClipToOutline(true);open.addView(thumbnail,new LayoutParams(dp(38),dp(38)));
    LinearLayout labels=new LinearLayout(getContext());labels.setOrientation(VERTICAL);labels.setPadding(dp(10),0,0,0);attachmentTitle=text("当前屏幕",12,theme.ink);attachmentTitle.setTypeface(Typeface.create("sans-serif-medium",0));attachmentTitle.setSingleLine(true);attachmentTitle.setEllipsize(TextUtils.TruncateAt.END);labels.addView(attachmentTitle);attachmentHint=text("随本次提问发送 · 轻点预览",11,theme.muted);attachmentHint.setPadding(0,dp(4),0,0);if(getResources().getConfiguration().fontScale>1.3f)attachmentHint.setVisibility(GONE);labels.addView(attachmentHint);open.addView(labels,new LayoutParams(0,-2,1));top.addView(open,new LayoutParams(0,-2,1));
    top.addView(action(R.drawable.ic_x,"","移除屏幕图片",theme.muted,Color.TRANSPARENT,this::removeImage),new LayoutParams(dp(48),dp(48)));attachment.addView(top);
    preview=new ImageView(getContext());preview.setScaleType(ImageView.ScaleType.FIT_CENTER);preview.setVisibility(GONE);preview.setPadding(dp(8),0,dp(8),dp(8));attachment.addView(preview,new LayoutParams(-1,dp(120)));
    LayoutParams lp=new LayoutParams(-1,-2);lp.topMargin=dp(12);addView(attachment,lp);
  }
  void buildAnswer(){
    answerBlock=new LinearLayout(getContext());answerBlock.setOrientation(VERTICAL);answerBlock.setVisibility(GONE);
    sentCard=new LinearLayout(getContext());sentCard.setOrientation(VERTICAL);sentCard.setBackground(Ui.shape(getContext(),theme.field,16,0));sentCard.setVisibility(GONE);
    LinearLayout sentRow=row();sentRow.setPadding(dp(12),dp(10),dp(12),dp(10));sentText=text("",14,theme.ink);sentText.setTextIsSelectable(true);sentRow.addView(sentText,new LayoutParams(0,-2,1));
    sentThumbnail=new ImageView(getContext());sentThumbnail.setScaleType(ImageView.ScaleType.FIT_CENTER);sentThumbnail.setBackground(Ui.shape(getContext(),theme.surface,9,0));sentThumbnail.setClipToOutline(true);sentThumbnail.setVisibility(GONE);
    LayoutParams thumbnailParams=new LayoutParams(dp(66),dp(66));thumbnailParams.leftMargin=dp(10);sentRow.addView(sentThumbnail,thumbnailParams);sentCard.addView(sentRow);
    sentExpanded=new ImageView(getContext());sentExpanded.setScaleType(ImageView.ScaleType.FIT_CENTER);sentExpanded.setVisibility(GONE);sentExpanded.setPadding(dp(8),0,dp(8),dp(8));sentCard.addView(sentExpanded,new LayoutParams(-1,dp(160)));
    sentCard.setOnClickListener(v->{if(sentBitmap!=null)sentExpanded.setVisibility(sentExpanded.getVisibility()==VISIBLE?GONE:VISIBLE);});
    LayoutParams sentParams=new LayoutParams(Math.min(dp(340),getResources().getDisplayMetrics().widthPixels-dp(42)),-2);sentParams.gravity=Gravity.RIGHT;sentParams.topMargin=dp(10);answerBlock.addView(sentCard,sentParams);
    LinearLayout meta=row();answerMeta=meta;answerBadge=new Ui.AgentAvatar(getContext(),runtime,23);meta.addView(answerBadge,new LinearLayout.LayoutParams(dp(23),dp(23)));TextView label=text(runtime.agentName,12,theme.ink);answerName=label;label.setTypeface(Typeface.create("sans-serif-medium",0));LinearLayout.LayoutParams labelParams=new LinearLayout.LayoutParams(0,-2,1);labelParams.leftMargin=dp(8);meta.addView(label,labelParams);copy=action(R.drawable.ic_copy,"","复制回答",theme.muted,Color.TRANSPARENT,()->{
      ((android.content.ClipboardManager)getContext().getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(runtime.agentName,rawReply));copy.setIcon(R.drawable.ic_check);copy.setContentDescription("已复制回答");handler.postDelayed(()->{if(!disposed){copy.setIcon(R.drawable.ic_copy);copy.setContentDescription("复制回答");}},1600);
    });meta.addView(copy,new LayoutParams(dp(48),dp(48)));answerBlock.addView(meta);
    resultArea=new ScrollView(getContext()){@Override protected void onMeasure(int ws,int hs){int available=MeasureSpec.getSize(hs);int cap=dp(getResources().getConfiguration().screenHeightDp<440?96:200);super.onMeasure(ws,MeasureSpec.makeMeasureSpec(Math.min(cap,available>0?available:cap),MeasureSpec.AT_MOST));}};
    LinearLayout resultContent=new LinearLayout(getContext());resultContent.setOrientation(VERTICAL);
    toolList=new LinearLayout(getContext());toolList.setOrientation(VERTICAL);resultContent.addView(toolList);
    replyBlock=Ui.rich(getContext(),"",15,theme.ink);replyBlock.setPadding(dp(2),dp(8),dp(2),dp(8));resultContent.addView(replyBlock);resultArea.addView(resultContent);answerBlock.addView(resultArea);
    interruptionList=new LinearLayout(getContext());interruptionList.setOrientation(VERTICAL);answerBlock.addView(interruptionList,new LayoutParams(-1,-2));
    errorActions=row();retry=action(R.drawable.ic_arrow_clockwise,"重试","重试本次请求",theme.accent,theme.accentSoft,()->send(lastSentText));configure=action(R.drawable.ic_gear_six,"模型设置","打开模型设置",theme.muted,theme.field,this::setup);errorActions.addView(retry);LayoutParams cp=new LayoutParams(-2,-2);cp.leftMargin=dp(8);errorActions.addView(configure,cp);errorActions.setVisibility(GONE);answerBlock.addView(errorActions);
    LayoutParams lp=new LayoutParams(-1,-2);lp.topMargin=dp(4);addView(answerBlock,lp);
  }
  void buildComposer(){
    composerSurface=new LinearLayout(getContext());composerSurface.setOrientation(VERTICAL);composerSurface.setPadding(dp(7),dp(5),dp(7),dp(5));composerSurface.setBackground(Ui.shape(getContext(),theme.surface,24,0));
    retryLabel=text("",12,theme.error);retryLabel.setPadding(dp(9),dp(5),dp(9),dp(5));retryLabel.setVisibility(GONE);composerSurface.addView(retryLabel,new LayoutParams(-1,-2));
    input=new EditText(getContext());input.setBackgroundColor(Color.TRANSPARENT);input.setTextSize(16);input.setTextColor(theme.ink);input.setHintTextColor(theme.muted);input.setHint("想问什么，或让我做什么？");input.setMinHeight(dp(46));input.setMinLines(1);input.setMaxLines(3);input.setPadding(dp(9),dp(9),dp(9),dp(3));input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);input.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI|android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN);composerSurface.addView(input,new LayoutParams(-1,-2));
    input.setOnFocusChangeListener((v,focus)->composerSurface.setBackground(Ui.shape(getContext(),theme.surface,20,focus?theme.accent:theme.line)));
    input.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){updateControls();}public void afterTextChanged(Editable s){}});
    slashSuggest=SlashSuggest.attach(input,theme,this::runSlashCommand);composerSurface.addView(slashSuggest,new LayoutParams(-1,-2));
    fileList=new LinearLayout(getContext());fileList.setOrientation(VERTICAL);LayoutParams filesParams=new LayoutParams(-1,-2);filesParams.topMargin=dp(6);composerSurface.addView(fileList,filesParams);
    LayoutParams lp=new LayoutParams(-1,-2);lp.topMargin=dp(9);addView(composerSurface,lp);
  }
  void buildActions(){
    LinearLayout actions=row();actions.setPadding(dp(1),0,dp(1),0);actionToolbar=actions;mic=action(R.drawable.ic_microphone,"说话","开始语音对话",theme.muted,Color.TRANSPARENT,this::voice);actions.addView(mic);
    modelChip=action(R.drawable.ic_chat_circle_text,runtime.currentModel.isEmpty()?"模型":runtime.currentModel,"快速切换模型",theme.muted,Color.TRANSPARENT,()->ModelPicker.show(modelChip,runtime));modelChip.label.setMaxWidth(dp(78));modelChip.label.setTextSize(12);actions.addView(modelChip);
    sidebar=action(R.drawable.ic_sidebar,"","会话与设置",theme.muted,Color.TRANSPARENT,this::openSidebar);LayoutParams sidebarParams=new LayoutParams(-2,-2);sidebarParams.leftMargin=dp(2);actions.addView(sidebar,sidebarParams);
    select=action(R.drawable.ic_corners_out,"圈选","圈选屏幕内容",theme.muted,Color.TRANSPARENT,()->{voice.stopSession(false);hideKeyboard();host.select();});LayoutParams selectParams=new LayoutParams(-2,-2);selectParams.leftMargin=dp(2);actions.addView(select,selectParams);
    screenShare=action(R.drawable.ic_screen_share,"",screenSharing?"关闭屏幕共享":"共享屏幕聊天",theme.muted,Color.TRANSPARENT,this::toggleScreenShare);LayoutParams shareParams=new LayoutParams(-2,-2);shareParams.leftMargin=dp(2);actions.addView(screenShare,shareParams);
    fileButton=action(R.drawable.ic_file,"","选择本地文件",theme.muted,Color.TRANSPARENT,this::pickFiles);LayoutParams fileParams=new LayoutParams(-2,-2);fileParams.leftMargin=dp(2);actions.addView(fileButton,fileParams);
    overflow=action(R.drawable.ic_dots_three,"","更多工具",theme.muted,Color.TRANSPARENT,this::showToolbarOverflow);LayoutParams overflowParams=new LayoutParams(-2,-2);overflowParams.leftMargin=dp(2);actions.addView(overflow,overflowParams);
    actions.addView(new View(getContext()),new LayoutParams(0,1,1));
    primary=action(R.drawable.ic_arrow_up,"","发送",theme.onAccent,theme.accent,()->{if(runtime.busy)stop();else send(input.getText().toString());});primary.setBackground(Ui.ripple(getContext(),theme.accent,24,theme.onAccent));actions.addView(primary,new LayoutParams(dp(48),dp(48)));composerSurface.addView(actions);
  }
  void updateControls(){
    if(primary==null||mic==null)return;
    if(identityBadge!=null)identityBadge.setContentDescription(voice.sessionActive()||voice.speaking()?"点击头像打断语音":"打开工作台");
    primary.setIcon(runtime.busy?R.drawable.ic_stop:R.drawable.ic_arrow_up);primary.setContentDescription(runtime.busy?"停止当前任务":"发送");primary.setEnabled(runtime.busy||!localFiles.isEmpty()||input.getText().toString().trim().length()>0);
    updateScreenShareControl();
    updateComposerToolbar();
    if(modelChip!=null){String model=runtime.currentModel;String label=model.isEmpty()?"模型":model;if(!runtime.currentProvider.isEmpty()&&!runtime.currentProvider.equals("custom")&&!model.isEmpty())label=runtime.currentProvider+"/"+model;modelChip.setText(label);modelChip.setContentDescription(runtime.busy?"当前模型 "+label+"，执行中不能切换":"快速切换模型，当前 "+label);modelChip.setEnabled(!runtime.busy);}
    mic.setEnabled(true);select.setEnabled(!runtime.busy);copy.setEnabled(!rawReply.isEmpty());progress.setVisibility(runtime.busy&&Ui.motion()?VISIBLE:GONE);
  }
  void attachImage(String data,String label){
    image=data;imageLabel=label;attachmentTitle.setText(label.contains("圈选")?"圈选区域":"当前屏幕");attachmentHint.setText("随本次提问发送 · 轻点预览");expandedImage=false;preview.setVisibility(GONE);attachment.setVisibility(compact?GONE:VISIBLE);
    Bitmap next=Ui.thumbnail(data,600);if(next!=null){thumbnail.setImageBitmap(next);preview.setImageBitmap(next);if(imagePreview!=null)imagePreview.recycle();imagePreview=next;}else thumbnail.setImageDrawable(Ui.icon(getContext(),R.drawable.ic_image,theme.muted));
  }
  void showSent(String text,String picture,boolean hasImage){
    sentText.setText(text);sentCard.setVisibility(VISIBLE);answerBlock.setVisibility(VISIBLE);sentExpanded.setVisibility(GONE);sentThumbnail.setVisibility(hasImage?VISIBLE:GONE);
    sentCard.setClickable(hasImage);
    if(sentBitmap!=null){sentThumbnail.setImageDrawable(null);sentExpanded.setImageDrawable(null);sentBitmap.recycle();sentBitmap=null;}
    if(hasImage){sentBitmap=Ui.thumbnail(picture,480);if(sentBitmap!=null){sentThumbnail.setImageBitmap(sentBitmap);sentExpanded.setImageBitmap(sentBitmap);sentCard.setContentDescription("预览本条消息的圈选图片");}else{sentThumbnail.setImageDrawable(Ui.icon(getContext(),R.drawable.ic_image,theme.muted));sentCard.setContentDescription("这条消息包含图片");}}
    else sentCard.setContentDescription(null);
  }
  void removeImage(){image=null;imageLabel=null;attachment.setVisibility(GONE);thumbnail.setImageDrawable(null);preview.setImageDrawable(null);if(imagePreview!=null){imagePreview.recycle();imagePreview=null;}}
  Draft draft(){return new Draft(input.getText().toString(),image,imageLabel,screenSharing,new ArrayList<>(localFiles));}
  void restore(Draft d){if(d==null)return;screenSharing=ScreenContext.enabled();updateScreenShareControl();localFiles.clear();if(d.files!=null)localFiles.addAll(d.files);renderFiles();input.setText(d.text);if(d.image!=null)attachImage(d.image,d.label==null?"当前屏幕":d.label);}
  void setExpanded(boolean value){
    expanded=value;
    if(expandAction!=null){
      expandAction.setIcon(value?R.drawable.ic_arrows_in_simple:R.drawable.ic_arrows_out_simple);
      expandAction.setContentDescription(value?"收缩为半屏":"展开全屏");
    }
    if(fileList!=null)renderFiles();
  }
  void showReply(String value){
    boolean follow=resultArea.getChildAt(0)==null||resultArea.getChildAt(0).getHeight()-resultArea.getHeight()-resultArea.getScrollY()<dp(28);
    rawReply=value;replyBlock.setText(value);replyBlock.setVisibility(value.isEmpty()?GONE:VISIBLE);answerBlock.setVisibility(VISIBLE);answerMeta.setVisibility(value.isEmpty()&&toolViews.isEmpty()?GONE:VISIBLE);resultArea.setVisibility(value.isEmpty()&&toolViews.isEmpty()?GONE:VISIBLE);copy.setEnabled(!value.isEmpty());if(follow)resultArea.post(()->resultArea.fullScroll(FOCUS_DOWN));
  }
  void showTool(JSONObject item){
    String id=item.optString("id");ToolCallView card=toolViews.get(id);
    if(card==null){card=new ToolCallView(getContext(),item);toolViews.put(id,card);LayoutParams lp=new LayoutParams(-1,-2);lp.bottomMargin=dp(6);toolList.addView(card,lp);}else card.update(item);
    answerBlock.setVisibility(VISIBLE);answerMeta.setVisibility(VISIBLE);resultArea.setVisibility(VISIBLE);
  }
  void restoreTools(){toolList.removeAllViews();toolViews.clear();for(JSONObject item:runtime.lastTools.values())showTool(item);}
  void showInterruption(JSONObject item){
    String id=item.optString("id");if(interruptionViews.containsKey(id))return;
    InterruptionView card=new InterruptionView(getContext(),item,null);interruptionViews.put(id,card);
    LayoutParams lp=new LayoutParams(-1,-2);lp.topMargin=dp(8);interruptionList.addView(card,lp);answerBlock.setVisibility(VISIBLE);
  }
  void restoreInterruptions(){
    Set<String> open=new HashSet<>();for(Map.Entry<String,InterruptionView> card:interruptionViews.entrySet())if(card.getValue().expanded)open.add(card.getKey());
    interruptionViews.clear();interruptionList.removeAllViews();for(JSONObject item:runtime.lastInterruptions.values()){showInterruption(item);if(open.contains(item.optString("id")))interruptionViews.get(item.optString("id")).setExpanded(true);}
  }
  void renderRetry(){if(retryLabel==null)return;String value=InterruptionView.retryText(runtime.retryStatus);retryLabel.setText(value);retryLabel.setVisibility(value.isEmpty()?GONE:VISIBLE);}
  void hideKeyboard(){input.clearFocus();requestFocus();((InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(getWindowToken(),0);}
  void setup(){voice.stopSession(false);host.close();getContext().startActivity(new Intent(getContext(),MainActivity.class).putExtra("open_models",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));}
  void send(String raw){
    String text=raw.trim();
    if(text.equalsIgnoreCase("/goal")){runSlashCommand("goal","");input.setText("");return;}
    if(text.isEmpty()&&localFiles.isEmpty())return;
    if(!runtime.ready){state.setText("正在准备助手…");return;}
    if(!runtime.configured){state.setText("需要连接模型");showReply("连接你自己的模型后，就能在这里开始对话。");errorActions.setVisibility(VISIBLE);retry.setVisibility(GONE);return;}
    if(runtime.busy){
      boolean stopOnly=isStopCommand(text);pendingTaskPrompt=stopOnly?null:text;
      state.setText(stopOnly?"收到停止指令 · 正在停止任务":"收到新指令 · 停止旧任务后执行");
      runtime.send(PiRuntime.json("type","stop"));updateControls();return;
    }
    if(screenSharing){captureScreen("正在读取共享屏幕…",()->beginSend(text));return;}
    beginSend(text);
  }
  private void beginSend(String text){
    if(text.isBlank())text="请分析这些本地文件。";
    voice.beginPrompt();lastSentText=text;lastSentImage=image;lastSentImageLabel=imageLabel;lastSentFiles=fileSummary(localFiles);
    JSONObject command=PiRuntime.json("type","prompt","id",UUID.randomUUID().toString(),"text",text);
    try{
      if(image!=null)command.put("image",image);
      JSONArray images=new JSONArray(),files=new JSONArray();
      for(LocalFileAttachment file:localFiles){
        files.put(PiRuntime.json("name",file.name,"mime",file.mime,"kind",file.kind,"size",file.size,"detail",file.detail,"excerpt",file.excerpt));
        if(file.image!=null)images.put(PiRuntime.json("data",file.image,"mime",file.mime));
        for(String picture:file.images)images.put(PiRuntime.json("data",picture,"mime","image/jpeg"));
      }
      if(images.length()>0)command.put("images",images);if(files.length()>0)command.put("files",files);
    }catch(Exception ignored){}
    showSent(text,image,image!=null);input.setText("");removeImage();localFiles.clear();renderFiles();hideKeyboard();errorActions.setVisibility(GONE);answerMeta.setVisibility(GONE);resultArea.setVisibility(GONE);rawReply="";state.setText("正在思考…");runtime.send(command);updateControls();
  }
  void toggleScreenShare(){
    if(ScreenContext.enabled()){ScreenContext.setEnabled(false);if(imageLabel!=null&&imageLabel.startsWith("屏幕 ·"))removeImage();state.setText("看屏幕已关闭");return;}
    if(PhoneAccessibility.instance==null){state.setText("需要先开启读屏与操作服务");getContext().startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return;}
    ScreenContext.setEnabled(true);captureScreen("正在查看其他应用…",null);
  }
  private void captureScreen(String waiting,Runnable ready){
    if(capturingScreen)return;capturingScreen=true;state.setText(waiting);hideKeyboard();
    ScreenContext.capture(getContext(),(encoded,label,error)->{
      capturingScreen=false;if(disposed)return;
      if(error!=null){removeImage();state.setText(error);updateControls();return;}
      attachImage(encoded,"屏幕 · "+label);updateScreenShareControl();
      if(ready!=null)ready.run();else state.setText("正在看 "+label+" · 每次发送刷新");
    });
  }
  private void updateScreenShareControl(){
    if(screenShare==null)return;screenSharing=ScreenContext.enabled();
    int tint=screenSharing?theme.accent:theme.muted;screenShare.tint=tint;
    screenShare.setText("看屏幕");screenShare.image.setImageDrawable(Ui.icon(getContext(),R.drawable.ic_screen_share,tint));
    screenShare.setContentDescription(screenSharing?"关闭看屏幕 · "+ScreenContext.label():"开启看屏幕，每次发送读取其他应用");
  }
  private void updateAgentIdentity(){
    if(identityTitle!=null)identityTitle.setText(runtime.agentName);
    if(identityBadge!=null)identityBadge.update();
    if(answerBadge!=null)answerBadge.update();
    if(answerName!=null)answerName.setText(runtime.agentName);
  }
  void updateComposerToolbar(){
    if(actionToolbar==null)return;
    int width=actionToolbar.getWidth()>0?actionToolbar.getWidth():getResources().getDisplayMetrics().widthPixels;
    int widthDp=Math.round(width/getResources().getDisplayMetrics().density);
    int mode=widthDp>=680?0:widthDp>=570?1:widthDp>=420?2:3;
    modelChip.label.setMaxWidth(mode==3?dp(52):mode==2?dp(66):dp(78));
    modelChip.setCompact(mode>=3);modelChip.setVisibility(widthDp<350&&getResources().getConfiguration().fontScale>1.15f?View.GONE:View.VISIBLE);screenShare.label.setMaxWidth(dp((mode>=2?42:100)*getResources().getConfiguration().fontScale));
    mic.setCompact(mode>=1);sidebar.setCompact(true);select.setCompact(mode>=1);screenShare.setCompact(false);fileButton.setCompact(mode>=1);overflow.setCompact(true);primary.setCompact(false);

    sidebar.setVisibility(mode==0?VISIBLE:GONE);
    select.setVisibility(mode<=1?VISIBLE:GONE);
    screenShare.setVisibility(VISIBLE);
    fileButton.setVisibility(mode<=1?VISIBLE:GONE);
    overflow.setVisibility(mode==0?GONE:VISIBLE);
    overflow.setContentDescription("更多工具"+(mode>=3?"：会话、圈选、文件、共享":"：会话、共享"));
  }
  void showToolbarOverflow(){
    ArrayList<Ui.Action> items=new ArrayList<>();
    if(modelChip.getVisibility()==GONE)items.add(modelChip);
    if(sidebar.getVisibility()==GONE)items.add(sidebar);
    if(select.getVisibility()==GONE)items.add(select);
    if(fileButton.getVisibility()==GONE)items.add(fileButton);
    if(screenShare.getVisibility()==GONE)items.add(screenShare);
    if(!items.isEmpty())ToolbarOverflow.show(overflow,items);
  }
  @Override public void event(JSONObject e){
    if(disposed)return;
    switch(e.optString("type")){
      case "configured":voiceConfigWaits=0;state.setText(voice.sessionActive()?"语音连续对话中":"随时待命");break;
      case "agent_identity":updateAgentIdentity();break;
      case "session_changed":restoreTools();interruptionViews.clear();interruptionList.removeAllViews();renderRetry();lastSentText="";lastSentImage=null;lastSentImageLabel=null;rawReply="";sentCard.setVisibility(GONE);answerBlock.setVisibility(GONE);pendingTaskPrompt=null;localFiles.clear();renderFiles();state.setText("随时待命");break;
      case "history":{int position=contentArea.getScrollY();restoreTools();if(!runtime.lastPromptText.isEmpty()||runtime.lastPromptHasImage){lastSentText=runtime.lastPromptText;lastSentImage=runtime.lastPromptImage;showSent(lastSentText,lastSentImage,runtime.lastPromptHasImage);showReply(runtime.lastResponse);}else{sentCard.setVisibility(GONE);showReply(runtime.lastResponse);answerBlock.setVisibility(runtime.lastResponse.isEmpty()?GONE:VISIBLE);}restoreInterruptions();renderRetry();contentArea.post(()->contentArea.scrollTo(0,position));break;}
      case "turn_start":renderRetry();restoreTools();lastSentText=runtime.lastPromptText;lastSentImage=runtime.lastPromptImage;showSent(lastSentText,lastSentImage,runtime.lastPromptHasImage);if(!lastSentFiles.isEmpty())sentText.append(lastSentFiles);showReply("");break;
      case "assistant_start":break;
      case "assistant_interruption":showInterruption(e);break;
      case "retry_status":renderRetry();break;
      case "delta":if(!renderPending){renderPending=true;handler.postDelayed(()->{renderPending=false;if(!disposed)showReply(runtime.lastResponse);},32);}break;
      case "tool_execution":showTool(e);state.setText("running".equals(e.optString("status"))?PhoneHud.action(e):"正在处理…");
        break;
      case "turn_end":{
        renderRetry();
        showReply(runtime.lastResponse);if(e.optBoolean("aborted"))state.setText(runtime.stopUnconfirmed?"停止尚未确认，请检查工具记录":"已停止");if(!e.optBoolean("aborted")&&!voice.sessionActive())state.setText(ScreenContext.enabled()?ScreenContext.label():"随时待命");
        String queued=pendingTaskPrompt;pendingTaskPrompt=null;
        if(queued!=null&&!disposed)handler.postDelayed(()->{if(!disposed){voice();send(queued);}},380);
        break;
      }
      case "error":pendingTaskPrompt=null;state.setText("这次没有完成，请查看中断详情");showReply(runtime.lastResponse);JSONObject interruption=e.optJSONObject("interruption");showInterruption(interruption==null?runtime.errorInterruption(e):interruption);renderRetry();errorActions.setVisibility(VISIBLE);retry.setVisibility(lastSentText.isEmpty()?GONE:VISIBLE);if(input.getText().length()==0&&!lastSentText.isEmpty()){input.setText(lastSentText);if(lastSentImage!=null)attachImage(lastSentImage,lastSentImageLabel==null?"圈选区域":lastSentImageLabel);}break;
    }updateControls();
  }
  void voice(){
    if(!runtime.configured){
      if(voiceConfigWaits<40){voiceConfigWaits++;state.setText("正在恢复模型…");handler.postDelayed(this::voice,250);return;}
      setup();return;
    }
    if(getContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){state.setText("需要麦克风权限");getContext().startActivity(new Intent(getContext(),MicrophonePermissionActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return;}
    VoiceCallActivity.show(getContext());
    if(runtime.busy){voice.beginTaskVoice();updateControls();return;}
    if(voice.sessionActive()){voice.forceInterrupt();return;}
    voice.beginSession();
    updateControls();
  }
  void wakeVoice(){
    voice();
  }
  void openSidebar(){
    hideKeyboard();
    SessionPicker.show(sidebar,runtime,new SessionPicker.Host(){
      public void openModelSettings(){setup();}
      public void openProviderAuth(){getContext().startActivity(new Intent(getContext(),MainActivity.class).putExtra("open_provider_auth",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));}
      public void openToolCenter(){showToolCenter();}
      public void openCapabilities(){
        getContext().startActivity(new Intent(getContext(),MainActivity.class).putExtra("open_capabilities",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));
      }
      public void openSpeech(){
        getContext().startActivity(new Intent(getContext(),MainActivity.class).putExtra("open_speech",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));
      }
    });
  }
  boolean runSlashCommand(String name,String argument){
    switch(name){
      case "goal":getContext().startActivity(new Intent(getContext(),MainActivity.class).putExtra("open_goal",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));return true;
      case "tools":showToolCenter();return true;
      case "skills":showToolCenter(1);return true;
      case "mcp":ConnectorActivity.open(getContext());return true;
      case "packages":getContext().startActivity(new Intent(getContext(),RuntimePackageManagerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));return true;
      case "model":setup();return true;
      case "agent":AgentSettings.show(sidebar,runtime);return true;
      case "phone":getContext().startActivity(new Intent(getContext(),MainActivity.class).putExtra("open_capabilities",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));return true;
      case "voice":voice();return true;
      case "share":toggleScreenShare();return true;
      case "new":
        if(runtime.busy){state.setText("请先停止当前任务");return false;}
        runtime.send(PiRuntime.json("type","new_session"));return true;
      case "stop":stop();return true;
      case "skill":send(SlashCommands.skillPrompt(argument));return true;
      case "pkg":send(SlashCommands.packagePrompt(argument));return true;
      case "runtime":send(SlashCommands.runtimePrompt());return true;
    }
    return false;
  }
  void showToolCenter(){showToolCenter(0);}
  void showToolCenter(int section){
    ToolCenter.show(sidebar,runtime,new ToolCenter.Host(){
      public void openModelSettings(){setup();}
      public void openAgentSettings(){AgentSettings.show(sidebar,runtime);}
      public void openCapabilities(){getContext().startActivity(new Intent(getContext(),MainActivity.class).putExtra("open_capabilities",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));}
      public void openSpeech(){getContext().startActivity(new Intent(getContext(),MainActivity.class).putExtra("open_speech",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));}
      public void openPackages(){getContext().startActivity(new Intent(getContext(),RuntimePackageManagerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));}
      public void openSkills(){SkillManager.show(sidebar,runtime);}
      public void openProviderAuth(){getContext().startActivity(new Intent(getContext(),MainActivity.class).putExtra("open_provider_auth",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));}
      public void openRuntimePacks(){getContext().startActivity(new Intent(getContext(),MainActivity.class).putExtra("open_runtime_packs",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));}
      public void openTerminal(){TerminalActivity.open(getContext());}
      public void newSession(){if(!runtime.busy)runtime.send(PiRuntime.json("type","new_session"));}
      public void stopTask(){stop();}
      public void toggleVoice(){voice();}
      public void toggleScreenShare(){toggleScreenShare();}
      public void prompt(String text){send(text);}
    },section);
  }
  void stop(){pendingTaskPrompt=null;localFiles.clear();renderFiles();voice.stopSession(true);VoiceWakeService.resume(getContext());if(runtime.busy){runtime.send(PiRuntime.json("type","stop"));state.setText("正在停止…");primary.setEnabled(false);}else state.setText("随时待命");}
  void dispose(){if(disposed)return;disposed=true;VoiceCoordinator.get(getContext()).forget(this);runtime.unlisten(this);ScreenContext.unlisten(screenListener);handler.removeCallbacksAndMessages(null);if(imagePreview!=null){thumbnail.setImageDrawable(null);preview.setImageDrawable(null);imagePreview.recycle();imagePreview=null;}if(sentBitmap!=null){sentThumbnail.setImageDrawable(null);sentExpanded.setImageDrawable(null);sentBitmap.recycle();sentBitmap=null;}pendingTaskPrompt=null;}
  void pickFiles(){
    FilePickActivity.request(getContext(),uris->{
      if(uris.isEmpty())return;
      new Thread(()->{
        try{
          List<LocalFileAttachment> files=LocalFiles.prepare(getContext(),uris,message->handler.post(()->state.setText(message)));
          handler.post(()->{
            for(LocalFileAttachment file:files)if(localFiles.size()<LocalFiles.MAX_FILES)localFiles.add(file);
            renderFiles();state.setText(localFiles.isEmpty()?"没有选择文件":"已添加 "+localFiles.size()+" 个本地文件");
          });
        }catch(Exception error){handler.post(()->state.setText(Ui.errorMessage(error.getMessage())));}
      },"zfold-overlay-files").start();
    });
  }
  private void renderFiles(){
    if(fileList==null)return;fileList.removeAllViews();
    for(LocalFileAttachment file:localFiles){
      LinearLayout row=new LinearLayout(getContext());row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(6),dp(6),dp(2),dp(6));
      row.setBackground(Ui.card(getContext(),theme.field,14,theme.line));
      ImageView icon=new ImageView(getContext());int resource=file.isImage()?R.drawable.ic_image:file.kind.equals("audio")||file.kind.equals("video")?R.drawable.ic_microphone:R.drawable.ic_file;
      icon.setImageDrawable(Ui.icon(getContext(),resource,theme.accent));row.addView(icon,new LayoutParams(dp(20),dp(20)));
      LinearLayout labels=new LinearLayout(getContext());labels.setOrientation(VERTICAL);LayoutParams labelParams=new LayoutParams(0,-2,1);labelParams.leftMargin=dp(9);row.addView(labels,labelParams);
      TextView title=text(file.name,13,theme.ink);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);title.setTypeface(Typeface.create("sans-serif-medium",0));labels.addView(title);
      TextView detail=text(file.detail.replace("\n"," · "),11,theme.muted);detail.setSingleLine(true);detail.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);labels.addView(detail);
      row.addView(new Ui.Action(getContext(),R.drawable.ic_x,"","移除 "+file.name,theme.muted,Color.TRANSPARENT,()->{localFiles.remove(file);renderFiles();updateControls();}),new LayoutParams(dp(48),dp(48)));
      fileList.addView(row,new LayoutParams(-1,-2));
    }
  }
  private static String fileSummary(List<LocalFileAttachment> files){
    if(files==null||files.isEmpty())return "";
    StringBuilder out=new StringBuilder("[本地文件：");for(int i=0;i<files.size();i++){if(i>0)out.append("，");out.append(files.get(i).name);}return out.append("]").toString();
  }
  private static boolean isStopCommand(String value){
    String text=value.toLowerCase(Locale.ROOT).replaceAll("[\\s，,。：:！!？?]+","");
    return text.equals("停")||text.equals("停止")||text.equals("停下")||text.equals("取消")||text.equals("别做了")||text.equals("请停止")||text.equals("停止任务")||text.equals("停止当前任务");
  }
}
