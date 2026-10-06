package dev.zfold.assistant;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public final class PiRuntime {
  interface Listener { void event(JSONObject event); }
  private static volatile PiRuntime instance;
  static boolean testMode;
  static boolean instanceStopped(){return instance!=null&&instance.stopped;}
  static boolean phoneBusy(){return instance!=null&&instance.phoneActive;}
  static void takeoverPhone(Context context){
    PiRuntime runtime=instance;
    if(runtime==null||!runtime.phoneActive)return;
    runtime.phoneActive=false;runtime.stopped=true;
    android.widget.Toast.makeText(context,"检测到用户接管手机，正在停止后续操作",android.widget.Toast.LENGTH_LONG).show();
    runtime.send(json("type","stop"));
    PhoneHud.takeover(context,"已由你接管 · 任务正在停止");
  }
  public final Context context;
  private final boolean testing;
  private volatile File selectedDataRoot;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService writer = Executors.newSingleThreadExecutor();
  private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
  public volatile boolean ready, configured, busy, stopped;
  public volatile boolean imageInput=true;
  public volatile boolean stopUnconfirmed;
  private volatile boolean phoneActive;
  private volatile String activeTurnId="";
  public boolean jevConfigured,jevEnabled;public String jevModel="jev-1.13.0";
  public volatile String agentName="Zfold",agentAvatar,agentPrompt="";
  public volatile String agentDefaultPrompt="";
  public volatile JSONObject defaultConfig;
  public volatile org.json.JSONArray defaultModels;
  public volatile org.json.JSONArray modelProviders;
  public volatile org.json.JSONObject selectedModel;
  public volatile String currentProvider="";public volatile String currentModel="";public volatile JSONObject activeConfig;
  private boolean restoring;
  public String lastResponse="";
  public String lastPromptText="",lastPromptImage;
  public boolean lastPromptHasImage;
  public String sessionId="";
  final java.util.LinkedHashMap<String,JSONObject> lastTools=new java.util.LinkedHashMap<>();
  public volatile String status = "正在启动本地 Pi…";
  private static native boolean prepare(String cache);
  private static native int startNode(String entry, String data, String credentials, String nativeDir);
  private static native void writeCommand(byte[] command);
  private static native byte[] readEvents();
  static native int ptyOpen(String[] argv, String cwd, String[] env, int rows, int cols);
  static native void ptyWrite(int id, byte[] data);
  static native String ptyRead(int id, int timeoutMs);
  static native void ptyResize(int id, int rows, int cols);
  static native int ptyStatus(int id);
  static native void ptyClose(int id);

  public static synchronized PiRuntime get(Context context) {
    if(instance == null) instance = new PiRuntime(context.getApplicationContext());
    return instance;
  }
  static File agentRoot(Context context) {
    PiRuntime running=instance;
    if(running!=null&&running.selectedDataRoot!=null)return running.selectedDataRoot;
    return chooseDataRoot(context,testMode);
  }
  boolean isTestRuntime(){return testing;}
  String activeTurnId(){return activeTurnId;}
  private static File chooseDataRoot(Context context,boolean testing) {
    if(testing)return new File(context.getNoBackupFilesDir(),"agent-smoke");
    if(Environment.isExternalStorageManager()) {
      File storage=new File(Environment.getExternalStorageDirectory(),"pi");
      if(storage.isDirectory()||storage.mkdirs())return storage;
    }
    return new File(context.getNoBackupFilesDir(),"agent");
  }
  private static void migrateLegacyData(File legacy,File storage) {
    if(storage.isDirectory()&&new File(storage,".migrated").isFile())return;
    try{copyTree(new File(legacy,"workspace"),new File(storage,"workspace"));copyTree(new File(legacy,"sessions"),new File(storage,"sessions"));copyTree(new File(new File(legacy,"pi"),"skills"),new File(new File(storage,".pi"),"skills"));
      File active=new File(legacy,"active-session.json"),target=new File(storage,"active-session.json");
      if(active.isFile()&&!target.exists())java.nio.file.Files.copy(active.toPath(),target.toPath());
    }catch(Exception e){android.util.Log.w("ZfoldPi","Pi storage migration",e);}
    try{new File(storage,".migrated").createNewFile();}catch(Exception ignored){}
  }
  private static void copyTree(File source,File target) throws IOException {
    if(!source.exists())return;
    if(source.isDirectory()){target.mkdirs();String[] names=source.list();if(names==null)return;for(String name:names)copyTree(new File(source,name),new File(target,name));return;}
    File parent=target.getParentFile();if(parent!=null)parent.mkdirs();
    if(!target.exists())java.nio.file.Files.copy(source.toPath(),target.toPath());
  }
  private PiRuntime(Context context) {
    this.context=context;this.testing=testMode;
    new Thread(() -> {
      try {
        File runtime=new File(context.getFilesDir(),"runtime"); runtime.mkdirs();
        for(String name:context.getAssets().list("runtime"))copyAssetTree(context,"runtime/"+name,new File(runtime,name));
        deleteTree(new File(runtime,"lark-api.SKILL.md"));deleteTree(new File(runtime,"node-worker-entry.cjs"));deleteTree(new File(runtime,"npm"));deleteTree(new File(runtime,"npm-cache"));
        System.loadLibrary("node"); System.loadLibrary("zfold");
        if(!prepare(context.getCacheDir().getAbsolutePath())) throw new IOException("无法建立本机通信");
        new Thread(this::readLoop,"pi-events").start();
        File data=chooseDataRoot(context,testing);data.mkdirs();selectedDataRoot=data;
        File credentials=CredentialStore.prepare(context,data,testing);
        PiPackageManager.prepareBash(context);
        java.nio.file.Files.write(new File(runtime,"environment.json").toPath(),RuntimeEnvironment.base(context).toString().getBytes(StandardCharsets.UTF_8));
        if(!testMode)migrateLegacyData(new File(context.getNoBackupFilesDir(),"agent"),data);
        int exit=startNode(new File(runtime,"agent.cjs").getAbsolutePath(),data.getAbsolutePath(),credentials.getAbsolutePath(),context.getApplicationInfo().nativeLibraryDir);
        dispatch(json("type","error","message","Pi 运行时已退出（"+exit+"），请重新打开应用"));
      } catch(Throwable e) {
        Log.e("ZfoldPi","Runtime startup",e);
        dispatch(json("type","error","message","本地运行时启动失败："+e.getMessage()));
      }
    },"pi-node").start();
  }
  private static void copyAssetTree(Context context,String asset,File target) throws IOException {
    String[] children=context.getAssets().list(asset);
    if(children==null||children.length==0) {
      target.getParentFile().mkdirs();
      try(InputStream in=context.getAssets().open(asset); OutputStream out=new FileOutputStream(target)) { byte[] buffer=new byte[32768];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n); }
      return;
    }
    target.mkdirs();
    for(String child:children)copyAssetTree(context,asset+"/"+child,new File(target,child));
  }
  private void readLoop() {
    ByteArrayOutputStream line=new ByteArrayOutputStream();
    try {
      byte[] bytes;
      while((bytes=readEvents()) != null) for(byte b:bytes) {
        if(b=='\n') {
          String text=line.toString(StandardCharsets.UTF_8.name()); line.reset();
          try { dispatch(new JSONObject(text)); } catch(Exception e) {Log.w("ZfoldPi","Invalid runtime event");}
        } else {line.write(b);if(line.size()>4*1024*1024)throw new IOException("运行时消息过大");}
      }
    } catch(Exception e){dispatch(json("type","error","message",e.getMessage()));}
  }
  private void dispatch(JSONObject event) {
    if(event.optString("type").equals("tool_cancel")){NativeToolExecution.cancel(event.optString("id"));return;}
    main.post(() -> {
      String type=event.optString("type");
      if(type.equals("auth_state"))AuthSessionService.update(context,event);
      boolean callVisible=VoiceCallActivity.isForeground()||VoiceMiniOverlay.showing();
      if(type.equals("phone_halt"))stopped=true;
      if(type.equals("jev_status")){jevConfigured=event.optBoolean("configured");jevEnabled=event.optBoolean("enabled");jevModel=event.optString("model","jev-1.13.0");}
      if(type.equals("default_config")){defaultConfig=event.optJSONObject("config");defaultModels=event.optJSONArray("models");}
      if(type.equals("agent_identity")){agentName=event.optString("name","Zfold");agentAvatar=event.isNull("avatar")?null:event.optString("avatar",null);agentPrompt=event.optString("prompt");agentDefaultPrompt=event.optString("defaultPrompt",agentDefaultPrompt);}
      if(type.equals("ready")) {ready=true;status="Pi "+event.optString("pi")+" · Node "+event.optString("node");Log.i("ZfoldPi",event.toString());restoreConfiguration();}
      if(type.equals("configured")) {configured=true;imageInput=event.optBoolean("imageInput",true);restoring=false;currentModel=event.optString("model");activeConfig=event.optJSONObject("config");status="模型 · "+currentModel;sessionId=event.optString("sessionId");}
      if(type.equals("configured")){currentProvider=event.optString("provider","custom");activeConfig=event.optJSONObject("config");}
      if(type.equals("models_catalog")){modelProviders=event.optJSONArray("providers");selectedModel=event.optJSONObject("selected");}
      if(type.equals("configured")) send(json("type","history"));
      if(type.equals("error")) restoring=false;
      if(type.equals("session_changed")){UserQuestions.endTurn(context,null);sessionId=event.optString("sessionId");lastPromptText="";lastPromptImage=null;lastPromptHasImage=false;lastResponse="";lastTools.clear();}
      if(type.equals("history")){
        String previousSession=sessionId,previousText=lastPromptText,previousImage=lastPromptImage;
        sessionId=event.optString("sessionId",sessionId);lastPromptText="";lastPromptImage=null;lastPromptHasImage=false;lastResponse="";
        org.json.JSONArray items=event.optJSONArray("items");JSONObject latestUser=null;String latestAnswer="";
        lastTools.clear();
        if(items!=null)for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i);if(item==null)continue;if("user".equals(item.optString("role"))){latestUser=item;latestAnswer="";lastTools.clear();}else if("assistant".equals(item.optString("role")))latestAnswer+=item.optString("text");else if("tool".equals(item.optString("role")))lastTools.put(item.optString("id"),item);}
        if(latestUser!=null){lastPromptText=latestUser.optString("text");lastPromptHasImage=latestUser.optBoolean("hasImage");String picture=latestUser.optString("image");if(!picture.isEmpty())lastPromptImage=picture;else if(sessionId.equals(previousSession)&&lastPromptText.equals(previousText))lastPromptImage=previousImage;}
        lastResponse=latestAnswer;
      }
      if(type.equals("turn_start")) {busy=true;stopped=false;stopUnconfirmed=false;activeTurnId=event.optString("turnId");lastResponse="";lastTools.clear();}
      if(type.equals("tool_uncertain")&&activeTurnId.equals(event.optString("turnId"))){stopUnconfirmed=true;status=event.optString("message");}
      if(type.equals("tool_execution")) {lastTools.put(event.optString("id"),event);String toolName=event.optString("name");
        if(!callVisible&&(toolName.equals("phone_task")||toolName.equals("screen_read")||toolName.equals("screen_capture")||toolName.equals("ui_act")||toolName.equals("ui_tap")||toolName.equals("ui_input")||toolName.equals("ui_swipe")||toolName.equals("ui_back")||toolName.equals("app_open")||toolName.equals("split_screen")))PhoneHud.show(context,PhoneHud.action(event));}
      if(type.equals("delta")) lastResponse+=event.optString("text");
      if(type.equals("turn_end")||type.equals("error")) {busy=false;UserQuestions.endTurn(context,event.optString("turnId"));PhoneHud.hide();phoneActive=false;}
      if(type.equals("turn_start")||type.equals("turn_end")||type.equals("error"))CircleOverlay.setBusy(busy);
      if(type.equals("phone_halt")){PhoneHud.hide();phoneActive=false;}
      if(type.equals("probe")) Log.i("ZfoldPi",event.toString());
      if(type.equals("tool_request")) {
        if(stopped && !event.isNull("turnId")) {toolResult(event.optString("id"),null,"操作已停止");return;}
        String name=event.optString("name");
        if(name.equals("ask_user_question")){phoneActive=false;PhoneHud.hide();}
        if(name.equals("phone_call")||name.equals("phone_sms")||name.equals("camera_capture")||name.equals("contact_search"))phoneActive=false;
        boolean phoneAction=name.equals("screen_read")||name.equals("screen_capture")||name.equals("ui_act")||name.equals("ui_tap")||name.equals("ui_input")||name.equals("ui_input_focused")||name.equals("ui_swipe")||name.equals("ui_back")||name.equals("app_open")||name.equals("split_screen")||name.equals("open");
        boolean phoneTask=name.equals("phone_task");
        if((phoneAction||phoneTask)&&!callVisible)PhoneHud.show(context,PhoneHud.action(event));
        if(phoneAction||phoneTask)phoneActive=true;
        boolean handoff=false;
        if(phoneAction){
          handoff=AssistantSessionService.handoffToBubble(context);
          if(!handoff)handoff=AssistantOverlay.minimizeForPhoneControl(context);
          if(name.equals("app_open")&&!callVisible&&!SystemWindowNavigation.active())CircleOverlay.ensureBubble(context);
        }
        Runnable execute=()->{
          if(stopped&&!event.isNull("turnId")){toolResult(event.optString("id"),null,"操作已停止");return;}
          AndroidTools.execute(context,event,(result,error)->toolResult(event.optString("id"),result,error),
            ()->send(json("type","tool_heartbeat","id",event.optString("id"))));
        };
        if(handoff)main.postDelayed(execute,260);else execute.run();
      }
      for(Listener listener:listeners) listener.event(event);
    });
  }
  public void listen(Listener listener){listeners.add(listener);}
  public void unlisten(Listener listener){listeners.remove(listener);}
  public void restoreConfiguration(){
    if(testMode||!ready||configured||restoring)return;
    File file=new File(context.getNoBackupFilesDir(),"model.json");if(!file.isFile()){send(json("type","configure_default"));return;}
    try{JSONObject config=new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));restoring=true;send(json("type","configure","config",config));}
    catch(Exception e){restoring=false;dispatch(json("type","error","message","模型配置无法读取，请重新设置"));}
  }
  public void send(JSONObject command) {
    if(!ready){dispatch(json("type","error","message","本地 Pi 尚未就绪"));return;}
    if(command.optString("type").equals("prompt")){stopped=false;busy=true;lastPromptText=command.optString("text");lastPromptImage=command.optString("image");org.json.JSONArray images=command.optJSONArray("images");if(lastPromptImage.isEmpty()&&images!=null&&images.length()>0)lastPromptImage=images.optJSONObject(0).optString("data");lastPromptHasImage=!lastPromptImage.isEmpty();}
    if(command.optString("type").equals("stop")){PromptDispatcher.cancelPending();ScreenContext.cancelCapture();stopped=true;NativeToolExecution.cancelTurn(activeTurnId);PhoneHud.hide();}
    writer.execute(()->writeCommand(command.toString().getBytes(StandardCharsets.UTF_8)));
  }
  private void toolResult(String id,JSONObject result,String error){send(error==null?json("type","tool_result","id",id,"result",result):json("type","tool_result","id",id,"error",error));}
  public static JSONObject json(Object... values) {
    JSONObject o=new JSONObject();try{for(int i=0;i<values.length;i+=2)o.put(String.valueOf(values[i]),values[i+1]==null?JSONObject.NULL:values[i+1]);}catch(Exception ignored){}return o;
  }
  private static void deleteTree(File target) {
    if(target.isDirectory()){File[] children=target.listFiles();if(children!=null)for(File child:children)deleteTree(child);}
    target.delete();
  }
}
