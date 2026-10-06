package dev.zfold.assistant;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import android.provider.Settings;
import android.provider.DocumentsContract;
import android.net.Uri;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.*;
import java.io.*;
import java.util.*;

public final class AndroidTools {
  interface Result {void done(JSONObject value,String error);}
  static void execute(Context context,JSONObject request,Result result) {
    execute(context,request,result,null);
  }
  static void execute(Context context,JSONObject request,Result result,Runnable heartbeat) {
    String name=request.optString("name");
    NativeToolExecution call=NativeToolExecution.register(request,result,heartbeat);
    if(call==null)return;
    Context app=context.getApplicationContext();
    Runnable operation=()->call.run(()->executeBody(app,request,call::complete));
    // PiRuntime dispatches on the UI thread. Process waits, PTY I/O and file
    // staging must leave that thread before doing any potentially blocking work.
    if(Looper.myLooper()==Looper.getMainLooper()&&
        (name.equals("runtime_exec")||name.equals("runtime_stage")||name.equals("pty")||name.equals("pkg")||name.equals("runtime_environment"))){
      new Thread(operation,"pi-tool-"+name).start();
      return;
    }
    operation.run();
  }
  private static void executeBody(Context context,JSONObject request,Result result){
    String name=request.optString("name");
    JSONObject args=request.optJSONObject("args");if(args==null)args=new JSONObject();
    try {
      if(name.equals("ask_user_question")){UserQuestions.ask(context,request,args,result);return;}
      if(name.equals("phone_capabilities")||name.equals("phone_call")||name.equals("phone_sms")||name.equals("camera_capture")||name.equals("contact_search")){
        PhoneActions.execute(context,name,args,request.optString("id"),result);return;
      }
      if(name.equals("runtime_environment")){result.done(RuntimeEnvironment.base(context),null);return;}
      if(name.equals("device_info")) {
        Intent battery=context.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level=battery==null?-1:battery.getIntExtra(BatteryManager.EXTRA_LEVEL,-1),scale=battery==null?100:battery.getIntExtra(BatteryManager.EXTRA_SCALE,100);
        result.done(PiRuntime.json("manufacturer",Build.MANUFACTURER,"model",Build.MODEL,"android",Build.VERSION.RELEASE,"batteryPercent",scale>0?level*100/scale:-1,
          "accessibility",PhoneAccessibility.instance!=null,"overlay",Settings.canDrawOverlays(context),"runtimeLocation","inside-apk","packageName",context.getPackageName(),"phoneCapabilities",PhoneActions.capabilities(context)),null);return;
      }
      if(name.equals("app_list")) {
        Intent intent=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        JSONArray apps=new JSONArray();
        for(ResolveInfo info:context.getPackageManager().queryIntentActivities(intent,0))apps.put(PiRuntime.json("name",info.loadLabel(context.getPackageManager()).toString(),"packageName",info.activityInfo.packageName));
        result.done(PiRuntime.json("apps",apps),null);return;
      }
      if(name.equals("app_open")) {
        String packageName=args.getString("packageName");
        Intent intent=context.getPackageManager().getLaunchIntentForPackage(packageName);
        if(intent==null)throw new Exception("找不到这个应用的启动入口");
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        awaitLaunchedWindow(packageName,result);return;
      }
      if(name.equals("split_screen")) {
        PhoneAccessibility service=PhoneAccessibility.instance;
        String action=args.optString("action","state");
        if(action.equals("state")) {
          if(service==null)throw new Exception("请在手机能力中开启 Zfold 无障碍服务");
          service.splitState(result);return;
        }
        if(action.equals("toggle")) {
          if(service==null)throw new Exception("请在手机能力中开启 Zfold 无障碍服务");
          service.toggleSplitScreen(result);return;
        }
        if(action.equals("launch")) {
          String packageName=args.getString("packageName");
          Intent intent=context.getPackageManager().getLaunchIntentForPackage(packageName);
          if(intent==null)throw new Exception("找不到这个应用的启动入口");
          intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
          Object wmService=context.getSystemService(Context.WINDOW_SERVICE);
          android.graphics.Rect display=(wmService instanceof WindowManager)?((WindowManager)wmService).getMaximumWindowMetrics().getBounds():new android.graphics.Rect(0,0,1080,2400);
          String position=args.optString("position","secondary");
          android.graphics.Rect bounds=new android.graphics.Rect();
          boolean landscape=display.width()>=display.height();
          if(position.equals("left")||position.equals("primary")||(landscape?position.equals("right"):position.equals("bottom")))bounds.set(display.left,display.top,display.centerX(),display.bottom);
          else bounds.set(display.centerX(),display.top,display.right,display.bottom);
          android.app.ActivityOptions options=android.app.ActivityOptions.makeBasic();
          try{android.app.ActivityOptions.class.getMethod("setLaunchWindowingMode",int.class).invoke(options,6);}catch(Exception ignored){}
          options.setLaunchBounds(bounds);
          context.startActivity(intent,options.toBundle());
          if(service==null){result.done(PiRuntime.json("launched",true,"ready",false,"mode","multi_window","position",position,"bounds",bounds.left+"/"+bounds.top+"/"+bounds.right+"/"+bounds.bottom),null);return;}
          service.awaitSplitLaunch(packageName,result);return;
        }
        throw new Exception("分屏动作只能是 state、toggle 或 launch");
      }
      if(name.equals("runtime_exec")) {
        if(context.getApplicationInfo().targetSdkVersion!=28)throw new Exception("当前 targetSdk 不允许执行写入的本地二进制");
        File cache=new File(context.getFilesDir(),"runtime/runtime-exec-test").getCanonicalFile();
        File pkgRoot=PiPackageManager.root(context).getCanonicalFile();
        File toolsRoot=new File(context.getFilesDir(),"runtime/tools").getCanonicalFile();
        JSONArray argv=args.optJSONArray("argv");
        if(argv==null||argv.length()<1||argv.length()>64)throw new Exception("runtime_exec 需要 1 到 64 个 argv");
        List<String> command=new ArrayList<>();
        for(int i=0;i<argv.length();i++) {
          String value=argv.optString(i);
          if(value==null||value.isEmpty()||value.length()>4096||value.indexOf('\0')>=0)throw new Exception("argv 非法");
          command.add(value);
        }
        File executable=new File(command.get(0));
        String canonical=executable.getCanonicalPath();
        boolean trustedCache=canonical.startsWith(cache.getPath()+File.separator),trustedPackage=canonical.startsWith(pkgRoot.getPath()+File.separator);
        boolean trustedTools=canonical.startsWith(toolsRoot.getPath()+File.separator);
        if(!trustedCache&&!trustedPackage&&!trustedTools&&!PackageCliServer.isClient(context,executable))throw new Exception("只能执行内置 pkg、Runtime Pack、用户软件包或 tool_install 安装的私有工具");
        ProcessBuilder builder=new ProcessBuilder(command).redirectErrorStream(false);
        File cwd=RuntimeEnvironment.workingDirectory(context,args.optString("cwd",null));
        builder.directory(cwd);
        RuntimeEnvironment.apply(builder.environment(),RuntimeEnvironment.forExecutable(context,executable),args.optJSONObject("env"));
        long timeout=Math.min(Math.max(args.optLong("timeoutMs",60000),1000),600000);
        result.done(RuntimeProcess.execute(builder,timeout),null);return;
      }
      if(name.equals("pty")) {
        if(context.getApplicationInfo().targetSdkVersion!=28)throw new Exception("PTY runtime 只在 compat28 通道可用");
        String action=args.optString("action","open");
        File cache=new File(context.getFilesDir(),"runtime/runtime-exec-test").getCanonicalFile();
        File pkgRoot=PiPackageManager.root(context).getCanonicalFile();
        File toolsRoot=new File(context.getFilesDir(),"runtime/tools").getCanonicalFile();
        if(action.equals("open")) {
          JSONArray input=args.optJSONArray("argv");
          if(input==null||input.length()<1||input.length()>64)throw new Exception("PTY 需要 1 到 64 个 argv");
          List<String> command=new ArrayList<>();
          for(int i=0;i<input.length();i++) {
            String value=input.optString(i);
            if(value==null||value.isEmpty()||value.length()>4096||value.indexOf('\0')>=0)throw new Exception("PTY argv 非法");
            command.add(value);
          }
          File executable=new File(command.get(0));
          String canonical=executable.getCanonicalPath();
          boolean trusted=canonical.startsWith(cache.getPath()+File.separator)||canonical.startsWith(pkgRoot.getPath()+File.separator)||canonical.startsWith(toolsRoot.getPath()+File.separator);
          if(!trusted&&!PackageCliServer.isClient(context,executable))throw new Exception("PTY 只能执行内置 pkg、Runtime Pack、用户软件包或 tool_install 安装的私有工具");
          File cwd=RuntimeEnvironment.workingDirectory(context,args.optString("cwd",null));
          String cwdPath=cwd.getCanonicalPath();
          ProcessBuilder environmentSource=new ProcessBuilder("true");
          RuntimeEnvironment.apply(environmentSource.environment(),RuntimeEnvironment.forExecutable(context,executable),args.optJSONObject("env"));
          List<String> env=new ArrayList<>();
          for(java.util.Map.Entry<String,String> entry:environmentSource.environment().entrySet())env.add(entry.getKey()+"="+entry.getValue());
          int rows=Math.min(Math.max(args.optInt("rows",40),2),200),cols=Math.min(Math.max(args.optInt("cols",120),20),500);
          NativeToolExecution.checkpoint();
          int opened=PiRuntime.ptyOpen(command.toArray(new String[0]),cwdPath,env.toArray(new String[0]),rows,cols);
          try{NativeToolExecution.checkpoint();}catch(java.util.concurrent.CancellationException cancelled){PiRuntime.ptyClose(opened);throw cancelled;}
          result.done(PiRuntime.json("id",opened,"argv",command,"cwd",cwdPath,"rows",rows,"cols",cols),null);
          return;
        }
        int id=args.optInt("id");
        if(id<=0)throw new Exception("PTY id 非法");
        if(action.equals("write")) {
          String data=args.optString("data");
          if(data.length()>65536)throw new Exception("PTY 单次输入最多 64KB");
          byte[] bytes=data.getBytes(java.nio.charset.StandardCharsets.UTF_8);
          PiRuntime.ptyWrite(id,bytes);
          result.done(PiRuntime.json("written",bytes.length),null);return;
        }
        if(action.equals("read")) {
          int timeout=Math.min(Math.max(args.optInt("timeoutMs",1000),100),10000);
          try {
              String encoded=PiRuntime.ptyRead(id,timeout);
              byte[] bytes=encoded==null||encoded.isEmpty()?new byte[0]:java.util.Base64.getDecoder().decode(encoded);
              String output=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
              int status=PiRuntime.ptyStatus(id);
              result.done(PiRuntime.json("output",output,"bytes",bytes.length,"alive",status<0,"exitCode",status>=0?Integer.valueOf(status):JSONObject.NULL),null);
          }catch(Throwable e){result.done(null,e.getMessage());}
          return;
        }
        if(action.equals("status")) {
          int status=PiRuntime.ptyStatus(id);
          if(status==-2)throw new Exception("PTY 会话不存在");
          result.done(PiRuntime.json("alive",status<0,"exitCode",status>=0?status:null),null);return;
        }
        if(action.equals("resize")) {
          PiRuntime.ptyResize(id,Math.min(Math.max(args.optInt("rows",40),2),200),Math.min(Math.max(args.optInt("cols",120),20),500));
          result.done(PiRuntime.json("resized",true,"rows",args.optInt("rows",40),"cols",args.optInt("cols",120)),null);return;
        }
        if(action.equals("close")) {
          PiRuntime.ptyClose(id);
          result.done(PiRuntime.json("closed",true),null);return;
        }
        throw new Exception("PTY action 只能是 open/write/read/status/resize/close");
      }
      if(name.equals("runtime_stage")) {
        if(context.getApplicationInfo().targetSdkVersion!=28)throw new Exception("当前 targetSdk 不允许建立可执行 runtime cache");
        String sourcePath=args.getString("source"),name2=args.getString("name");
        if(!name2.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))throw new Exception("工具名非法");
        File source=new File(sourcePath).getCanonicalFile(),tools=new File(context.getFilesDir(),"runtime/tools").getCanonicalFile(),target=new File(tools,name2);
        String sourcePathCanonical=source.getPath();
        boolean trustedDiagnostic=sourcePathCanonical.startsWith("/system/bin/")||sourcePathCanonical.startsWith("/system/xbin/");
        File storageRoot=PiRuntime.agentRoot(context).getCanonicalFile();
        boolean workspaceSource=sourcePathCanonical.startsWith(storageRoot.getPath()+File.separator);
        if(!trustedDiagnostic&&!workspaceSource)throw new Exception("runtime_stage 来源必须经过 Runtime Pack 安装器或位于 /storage/pi");
        tools.mkdirs();
        java.nio.file.Files.copy(source.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        target.setExecutable(true,false);target.setReadable(true,false);target.setWritable(true,false);
        rewriteToolShebang(context,target);
        result.done(PiRuntime.json("path",target.getCanonicalPath(),"size",target.length()),null);return;
      }
      if(name.equals("open")) {
        String target=args.getString("target"),mime=args.optString("mime");
        if(target==null||target.trim().isEmpty()||target.length()>4096)throw new Exception("open 需要 1 到 4096 个字符的 target");
        target=target.trim();Uri uri;String kind;
        Intent intent;
        if(target.matches("(?i)^(https?|mailto|tel|sms|geo|market)://.+")) {
          uri=Uri.parse(target);kind="uri";intent=new Intent(Intent.ACTION_VIEW,uri);
        }else if(target.startsWith("content://")) {
          uri=Uri.parse(target);kind="content";intent=new Intent(Intent.ACTION_VIEW,uri);
          if(!mime.isEmpty())intent.setType(mime);
        }else {
          File storageRoot=new File(Environment.getExternalStorageDirectory(),"pi").getCanonicalFile();
          File file=new File(target).getCanonicalFile();
          if(!file.getPath().equals(storageRoot.getPath())&&!file.getPath().startsWith(storageRoot.getPath()+File.separator))
            throw new Exception("open 只能打开 /storage/pi 内的文件或目录；URL 可直接打开");
          String relative=storageRoot.toPath().relativize(file.toPath()).toString().replace(File.separatorChar,'/');
          String documentId="primary"+(relative.isEmpty()?"":":"+relative);
          uri=DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",documentId);
          boolean directory=file.isDirectory();
          if(mime.isEmpty()) {
            if(directory)mime="vnd.android.document/directory";
            else {
              int dot=file.getName().lastIndexOf('.');
              String extension=dot>=0?file.getName().substring(dot+1).toLowerCase(Locale.ROOT):"";
              mime=android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
              if(mime==null||mime.isEmpty())mime="application/octet-stream";
            }
          }
          kind=directory?"directory":"file";
          intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
          if(intent.resolveActivity(context.getPackageManager())==null&&!directory) {
            File parent=file.getParentFile();
            if(parent!=null&&parent.getPath().startsWith(storageRoot.getPath())) {
              String parentRelative=storageRoot.toPath().relativize(parent.toPath()).toString().replace(File.separatorChar,'/');
              Uri parentUri=DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:"+parentRelative);
              Intent fallback=new Intent(Intent.ACTION_VIEW).setDataAndType(parentUri,"vnd.android.document/directory").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
              if(fallback.resolveActivity(context.getPackageManager())!=null) {
                context.startActivity(fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                result.done(PiRuntime.json("opened",false,"fallbackOpened",true,"kind",kind,"target",target,"mime",mime,
                  "reason","no file handler","fallback",parent.getPath()),null);return;
              }
            }
            throw new Exception("系统没有找到能打开 "+mime+" 的应用");
          }
        }
        if(args.optBoolean("chooser",false))intent=Intent.createChooser(intent,"使用以下方式打开");
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ResolveInfo handler=context.getPackageManager().resolveActivity(intent,PackageManager.MATCH_DEFAULT_ONLY);
        result.done(PiRuntime.json("opened",true,"kind",kind,"target",target,"mime",mime,
          "handler",handler==null?"":handler.loadLabel(context.getPackageManager()).toString(),
          "handlerPackage",handler==null?"":handler.activityInfo.packageName),null);return;
      }
      if(name.equals("pkg")) {
        final String action=args.optString("action","list"),packageName=args.optString("name"),query=args.optString("query");
        final boolean refresh=args.optBoolean("refresh",false);
        try {
            JSONObject value;
            if(action.equals("search"))value=PiPackageManager.search(context,query,refresh);
            else if(action.equals("list"))value=PiPackageManager.status(context,false);
            else if(action.equals("update"))value=PiPackageManager.status(context,true);
            else if(action.equals("install"))value=PiPackageManager.install(context,packageName);
            else if(action.equals("remove"))value=PiPackageManager.remove(context,packageName);
            else throw new Exception("pkg action 只能是 list/search/update/install/remove");
            result.done(value,null);
        }catch(Exception e){result.done(null,e.getMessage());}
        return;
      }
      PhoneAccessibility service=PhoneAccessibility.instance;
      if(service==null)throw new Exception("请在手机能力中开启 Zfold 无障碍服务");
      service.execute(name,args,result);
    }catch(Exception e){result.done(null,e.getMessage());}
  }
  private static void awaitLaunchedWindow(String packageName,Result result){
    PhoneAccessibility service=PhoneAccessibility.instance;
    if(service==null){result.done(PiRuntime.json("launched",true,"ready",false),null);return;}
    Handler handler=new Handler(Looper.getMainLooper());long deadline=SystemClock.uptimeMillis()+3500;
    Runnable check=new Runnable(){
      public void run(){
        if(PhoneAccessibility.instance!=service){result.done(PiRuntime.json("launched",true,"ready",false),null);return;}
        String foreground="";
        try{
          AccessibilityNodeInfo root=service.getRootInActiveWindow();
          if(root!=null){CharSequence value=root.getPackageName();if(value!=null)foreground=value.toString();root.recycle();}
        }catch(RuntimeException ignored){}
        if(packageName.equals(foreground)){result.done(PiRuntime.json("launched",true,"ready",true),null);return;}
        if(SystemClock.uptimeMillis()>=deadline){result.done(PiRuntime.json("launched",true,"ready",false),null);return;}
        handler.postDelayed(this,120);
      }
    };handler.postDelayed(check,120);
  }
  private static void rewriteToolShebang(Context context,File target)throws IOException {
    if(target.length()>8_000_000)return;
    byte[] value=java.nio.file.Files.readAllBytes(target.toPath());
    if(value.length<2||value[0]!='#'||value[1]!='!')return;
    String text=new String(value,java.nio.charset.StandardCharsets.UTF_8);
    String packageBin=PiPackageManager.root(context).getCanonicalPath()+"/usr/bin";
    String updated=text
      .replaceFirst("^#!\\s*/usr/bin/env\\b","#!"+packageBin+"/env")
      .replaceFirst("^#!\\s*/bin/env\\b","#!"+packageBin+"/env")
      .replaceFirst("^#!\\s*/usr/bin/(node|python3?|bash|sh)\\b","#!"+packageBin+"/$1");
    if(!updated.equals(text))java.nio.file.Files.write(target.toPath(),updated.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}
