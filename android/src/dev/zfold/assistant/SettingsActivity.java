package dev.zfold.assistant;

import android.Manifest;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Standalone configuration surface, independent of the terminal and voice lifetimes. */
public final class SettingsActivity extends NavigableActivity implements PiRuntime.Listener {
  private PiRuntime runtime;
  private Ui.Theme theme;
  private LinearLayout content;
  private TextView status;
  private VoiceTurnController voice;
  private Runnable pendingAction;
  private String workbenchPage="";

  @Override protected void onCreate(Bundle state){
    super.onCreate(state);theme=new Ui.Theme(this);runtime=PiRuntime.running();
    build();if(runtime!=null)runtime.listen(this);route(getIntent());
  }
  private void build(){
    getWindow().setDecorFitsSystemWindows(false);getWindow().setStatusBarColor(theme.page);getWindow().setNavigationBarColor(theme.page);
    getWindow().getDecorView().setSystemUiVisibility(theme.dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(theme.page);
    root.setOnApplyWindowInsetsListener((view,insets)->{Insets bars=insets.getInsets(WindowInsets.Type.systemBars());root.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
    LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
    header.addView(new Ui.Action(this,R.drawable.ic_caret_down,"","返回",theme.ink,Color.TRANSPARENT,this::onBackPressed),new LinearLayout.LayoutParams(dp(48),dp(48)));
    header.addView(text("设置",23,theme.ink));root.addView(header);
    ScrollView scroll=new ScrollView(this);content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(18),0,dp(18),dp(24));scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    status=text(idleStatus(),13,theme.muted);content.addView(status);
    entry("模型与供应商",this::settings);
    entry("选择模型",()->whenReady(()->ModelPicker.show(content,runtime)));
    entry("模型思考设置",()->whenReady(()->ThinkingPicker.show(content,runtime)));
    entry("供应商账号登录",()->whenReady(()->ProviderAuthDialog.show(content,runtime)));
    entry("Agent 设置",()->whenReady(()->AgentSettings.show(content,runtime)));
    entry("工具目录",()->whenReady(()->showToolCenter(content)));
    entry("Skills 管理",()->whenReady(()->SkillManager.show(content,runtime)));
    entry("目标设置",()->whenReady(()->GoalPanel.show(this,runtime)));
    entry("连接 · MCP",()->ConnectorActivity.open(this));
    entry("手机能力与权限",this::permissions);
    entry("语音与唤醒",this::speechCapabilities);
    entry("实时语音与相机",()->VoiceCallActivity.show(this));
    entry("Runtime Packs",this::runtimePacksSettings);
    entry("软件包管理",()->startActivity(new Intent(this,RuntimePackageManagerActivity.class)));
    entry("启动设置",()->StartupPreferences.show(this));
    setContentView(root);
  }
  private void entry(String label,Runnable action){content.addView(new Ui.Action(this,R.drawable.ic_gear_six,label,label,theme.ink,theme.surface,action),new LinearLayout.LayoutParams(-1,dp(56)));}
  private int dp(float value){return Ui.dp(this,value);}
  private TextView text(String value,int size,int color){return Ui.text(this,value,size,color);}
  private String idleStatus(){return runtime==null?"终端与手机设置 · 模型功能按需启动":runtime.ready?runtime.configured?"本机运行时已就绪 · "+runtime.currentModel:"连接模型以配置 Agent":"正在准备本机运行时…";}
  private void showStatus(String value){status.setText(value);}
  private void notice(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
  private void requireRuntime(){if(runtime==null){runtime=PiRuntime.get(this);runtime.listen(this);showStatus(idleStatus());}}
  private void whenReady(Runnable action){requireRuntime();if(runtime.ready)action.run();else{pendingAction=action;showStatus("正在准备本机运行时，准备好后打开…");}}
  private void route(Intent intent){
    workbenchPage="";
    for(String page:new String[]{"open_models","open_provider_auth","open_capabilities","open_speech","open_tools","open_runtime_packs","open_agent","open_skills","open_goal"}){
      if(!intent.getBooleanExtra(page,false))continue;
      intent.removeExtra(page);if(intent.getBooleanExtra("return_workbench",false))workbenchPage=page;
      content.post(()->{
        if(isFinishing()||isDestroyed())return;
        switch(page){
          case "open_models":settings();break;
          case "open_provider_auth":whenReady(()->ProviderAuthDialog.show(content,runtime));break;
          case "open_capabilities":permissions();break;
          case "open_speech":speechCapabilities();break;
          case "open_tools":whenReady(()->showToolCenter(content));break;
          case "open_runtime_packs":runtimePacksSettings();break;
          case "open_agent":whenReady(()->AgentSettings.show(content,runtime));break;
          case "open_skills":whenReady(()->SkillManager.show(content,runtime));break;
          case "open_goal":whenReady(()->GoalPanel.show(this,runtime));break;
        }
      });
    }
  }
  private void returnToWorkbench(String page){
    if(!page.equals(workbenchPage)||!getIntent().getBooleanExtra("return_workbench",false)||isFinishing()||isChangingConfigurations())return;
    getIntent().removeExtra("return_workbench");WorkbenchActivity.returnFromSettings(this);finish();
  }
  private void showToolCenter(View anchor){
    ToolCenter.show(anchor,runtime,new ToolCenter.Host(){
      public void openModelSettings(){settings();}
      public void openAgentSettings(){AgentSettings.show(anchor,runtime);}
      public void openCapabilities(){permissions();}
      public void openSpeech(){speechCapabilities();}
      public void openPackages(){startActivity(new Intent(SettingsActivity.this,RuntimePackageManagerActivity.class));}
      public void openSkills(){SkillManager.show(anchor,runtime);}
      public void openProviderAuth(){ProviderAuthDialog.show(anchor,runtime);}
      public void openRuntimePacks(){runtimePacksSettings();}
      public void openTerminal(){TerminalActivity.open(SettingsActivity.this);}
    },0,()->returnToWorkbench("open_tools"));
  }
  @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);pendingAction=null;route(intent);}
  @Override public void event(JSONObject event){
    String type=event.optString("type");
    if(type.equals("ready")||type.equals("configured"))showStatus(idleStatus());
    if(type.equals("ready")&&pendingAction!=null){Runnable action=pendingAction;pendingAction=null;if(!isFinishing()&&!isDestroyed())action.run();}
    if(type.equals("error")){showStatus(Ui.errorMessage(event.optString("message")));if(!runtime.ready)pendingAction=null;}
    if(type.equals("probe"))new AlertDialog.Builder(this).setTitle("运行状态").setMessage(event.toString()).setPositiveButton("关闭",null).show();
  }
  @Override public void onBackPressed(){
    if(getIntent().getBooleanExtra("return_workbench",false)){WorkbenchActivity.returnFromSettings(this);finish();return;}
    if(getIntent().getBooleanExtra("return_terminal",false)){TerminalActivity.open(this);finish();return;}
    navigateUp();
  }
  @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
    super.onRequestPermissionsResult(request,permissions,results);
    if(request==22){for(int result:results)if(result!=PackageManager.PERMISSION_GRANTED){notice("开启唤醒需要麦克风与通知权限");return;}if(results.length>0)toggleWake();}
  }
  @Override protected void onPause(){SystemWindowNavigation.remember(this);super.onPause();}
  @Override protected void onDestroy(){pendingAction=null;if(runtime!=null)runtime.unlisten(this);super.onDestroy();}
  File configFile(){return new File(getNoBackupFilesDir(),"model.json");}
  JSONObject config(){try{
    JSONObject value=new JSONObject(new String(java.nio.file.Files.readAllBytes(configFile().toPath()),StandardCharsets.UTF_8));
    String provider=value.optString("provider","custom");
    JSONObject defaults=runtime.defaultConfig;
    if(defaults!=null&&(provider.equals("custom")||!value.has("provider")))for(String key:new String[]{"baseUrl","model","apiKey","api"})if(!value.has(key))value.put(key,defaults.optString(key));
    return value;
  }catch(Exception e){return runtime.defaultConfig==null?new JSONObject():runtime.defaultConfig;}}
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
    requireRuntime();
    JSONObject c=config();LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);fields.setPadding(dp(24),0,dp(24),dp(10));
    TextView intro=text("填写服务提供的模型与认证信息，也可以登录供应商账号。配置保存在手机私有目录，支持多个供应商。",13,theme.muted);fields.addView(intro);
    fields.addView(new Ui.Action(this,R.drawable.ic_arrow_square_out,"供应商账号登录","登录供应商账号",theme.accent,theme.field,()->ProviderAuthDialog.show(fields,runtime)));
    AutoCompleteTextView provider=providerField(fields,c.optString("provider","custom"));
    EditText endpoint=field(fields,"接口地址（custom 必填，包含 /v1；内置供应商留空）",c.optString("baseUrl"),false),key=field(fields,"API Key（custom 或 BYOK）",c.optString("apiKey"),true);
    AutoCompleteTextView model=modelField(fields,"模型名称（输入过滤或选择）",c.optString("model"));
    EditText api=field(fields,"消息格式（custom 必填：openai-responses / openai-completions / anthropic-messages / google-generative-ai）",c.optString("api","openai-responses"),false);
    ScrollView formScroll=new ScrollView(this);formScroll.addView(fields);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("连接你的模型").setView(formScroll).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
    dialog.setOnDismissListener(d->returnToWorkbench("open_models"));
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
  void circleAskSettings(){
    boolean assist=CircleAskPrefs.assistInvokesCircle(this);
    String[] items={
      (assist?"已开":"已关")+" · 默认助手唤起时圈选到终端",
      "设置无障碍快捷键（音量键）",
      "设置默认数字助手"
    };
    new AlertDialog.Builder(this).setTitle("圈选到终端").setItems(items,(d,i)->{
      if(i==0){CircleAskPrefs.setAssistInvokesCircle(this,!assist);notice(assist?"助手唤起改回打开终端":"助手唤起将圈选到终端，不自动发送");return;}
      if(i==1)try{startActivity(new Intent("android.settings.ACCESSIBILITY_SHORTCUT_SETTINGS"));}catch(Exception e){startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}
      if(i==2)startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS));
    }).setNegativeButton("关闭",null).show();
  }
  void permissions(){
    boolean defaultAssistant=android.service.voice.VoiceInteractionService.isActiveService(this,new ComponentName(this,AssistantVoiceService.class));
    String[] items={"默认助手 · "+(defaultAssistant?"已设置":"未设置"),"读屏与操作 · "+(PhoneAccessibility.instance!=null?"已连接":"未连接"),"兼容浮层权限 · "+(Settings.canDrawOverlays(this)?"已允许":"未允许"),"系统小窗与分屏","查看运行状态","唤起手势 · 从底角向内划","圈选到终端 · "+(CircleAskPrefs.assistInvokesCircle(this)?"助手唤起即圈选":"打开终端"),"本机语音 · 检测与下载","Jev 手机操作 · "+(runtime==null?"点击配置":runtime.jevEnabled&&runtime.jevConfigured?"已启用":"未配置"),"storage/pi 文件 · "+(Environment.isExternalStorageManager()?"已授权":"未授权"),"Runtime Packs","软件包管理","独立客户端手机工具 · "+(PhoneToolPolicy.alwaysAllow(this)?"始终允许":"每次确认")};
    new AlertDialog.Builder(this).setTitle("手机能力").setItems(items,(d,i)->{
      if(i==4){whenReady(()->runtime.send(PiRuntime.json("type","probe")));return;}
      if(i==5){new AlertDialog.Builder(this).setTitle("从当前应用唤起").setMessage("将橙橙设为默认助手后，侧键/关机键若绑定「数字助理」，以及底角向内划，会按「圈选到终端」设置唤起。圈选后保存图片并切换终端，由你选择会话并明确插入，不自动回车或启动 Agent。\n\n三星导航条正中长按的「圈选即搜」是系统功能，普通应用不能改绑。可用无障碍快捷键（音量键）作为可设置的圈选开关。").setPositiveButton("知道了",null).show();return;}
      if(i==6){circleAskSettings();return;}
      if(i==7){speechCapabilities();return;}
      if(i==8){jevSettings();return;}
      if(i==9){requestStorageAccess();return;}
      if(i==10){runtimePacksSettings();return;}
      if(i==11){startActivity(new Intent(this,RuntimePackageManagerActivity.class));return;}
      if(i==12){PhoneToolPolicy.show(this);return;}
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
      try{scan=PiRuntimePacks.available(this);}catch(Exception e){runOnUiThread(()->{notice("扫描失败："+e.getMessage());returnToWorkbench("open_runtime_packs");});return;}
      runOnUiThread(()->{
        if(isFinishing())return;showStatus(idleStatus());
        JSONArray archives=scan.optJSONArray("archives");
        if(archives==null||archives.length()==0){new AlertDialog.Builder(this).setTitle("Runtime Packs").setMessage("没有找到 .tar.gz。\n\n请把 `scripts/runtime-pack.py` 生成的包放到：\n/storage/pi/runtime-packs/").setPositiveButton("完成",null).setOnDismissListener(d->returnToWorkbench("open_runtime_packs")).show();return;}
        ArrayList<String> labels=new ArrayList<>();ArrayList<JSONObject> files=new ArrayList<>();
        for(int i=0;i<archives.length();i++){JSONObject item=archives.optJSONObject(i);if(item==null)continue;files.add(item);labels.add(item.optString("name")+"\n"+(item.optBoolean("installed")?"已安装 · ":"未安装 · ")+item.optString("sha256").substring(0,12)+" · "+(item.optLong("size")/1024/1024)+" MB");}
        AlertDialog packs=new AlertDialog.Builder(this).setTitle("Runtime Packs").setItems(labels.toArray(new String[0]),null).setNegativeButton("关闭",null).setOnDismissListener(d->returnToWorkbench("open_runtime_packs")).create();
        packs.show();packs.getListView().setOnItemClickListener((parent,view,i,id)->{
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
        });
      });
    },"pi-pack-scan").start();
  }
  void requestStorageAccess(){
    if(Environment.isExternalStorageManager()){notice("storage/pi 已授权；完全退出并重新打开 Zfold 后生效");return;}
    startActivityForResult(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:"+getPackageName())),23);
  }
  @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
    if(request==23){notice(Environment.isExternalStorageManager()?"已授权 storage/pi，请完全退出并重新打开 Zfold":"未授予所有文件访问，Pi 继续使用应用私有目录");returnToWorkbench("open_runtime_packs");}
  }
  void jevSettings(){
    requireRuntime();
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
    voice=VoiceCoordinator.get(this).controller;
    SpeechSettings.show(this,voice,this::localSpeechCapabilities,()->returnToWorkbench("open_speech"));
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
  void toggleWake(){
    ArrayList<String> missing=new ArrayList<>();
    if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.RECORD_AUDIO);
    if(Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=PackageManager.PERMISSION_GRANTED)missing.add("android.permission.POST_NOTIFICATIONS");
    if(!missing.isEmpty()){requestPermissions(missing.toArray(new String[0]),22);return;}
    boolean next=!VoiceWakeService.enabled(this);VoiceWakeService.setEnabled(this,next);String word=WakeConfig.get(this).word;notice(next?"已开启“"+word+"”唤醒":"已关闭“"+word+"”唤醒");
  }
}
