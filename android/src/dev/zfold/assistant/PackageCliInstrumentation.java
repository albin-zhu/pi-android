package dev.zfold.assistant;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Emulator-only end-to-end CLI checks in a separate package root, without model calls. */
public final class PackageCliInstrumentation extends Instrumentation {
  @Override public void onCreate(Bundle arguments){start();}
  @Override public void onStart(){
    Bundle outcome=new Bundle();File fixture=null;String error="";
    try{
      if(!Build.FINGERPRINT.contains("generic")&&!Build.MODEL.contains("Emulator")&&!Build.MODEL.contains("sdk_gphone"))throw new IllegalStateException("Package CLI fixtures require an emulator");
      fixture=Files.createTempDirectory(getTargetContext().getCacheDir().toPath(),"pkg-cli-").toFile();
      File root=fixture;
      Context context=new ContextWrapper(getTargetContext()){
        public File getFilesDir(){return directory(root,"files");}
        public File getCacheDir(){return directory(root,"cache");}
        public File getNoBackupFilesDir(){return directory(root,"no_backup");}
        public Context getApplicationContext(){return this;}
      };
      JSONObject environment=RuntimeEnvironment.base(context);
      check(environment.getString("PATH").startsWith(new File(context.getFilesDir(),"runtime/bin").getPath()+":"),"Built-in CLI must precede Termux pkg");
      JSONObject help=command(context,"pkg --help");check(help.getInt("exitCode")==0&&help.getString("stdout").contains("工作台"),"CLI help");
      JSONObject list=command(context,"pkg list");check(list.getInt("exitCode")==0&&list.getString("stdout").contains("尚未安装"),"Offline installed list");
      check(!new File(PiPackageManager.root(context),"Packages").exists(),"Listing installed packages fetched the index");
      check(command(context,"pkg install ../invalid").getInt("exitCode")==2,"Invalid package name accepted");
      check(command(context,"pkg upgrade").getInt("exitCode")==2,"Unsupported command accepted");
      outcome.putBoolean("cliParsingAndOfflineList",true);

      JSONObject search=command(context,"pkg search '^zfold-no-such-package$'");check(search.getInt("exitCode")==0,"Repository search: "+search);
      JSONObject installed=ptyCommand(context,"pkg install -y tree");check(installed.getInt("exitCode")==0,"Real package installation: "+installed);
      check(PiPackageManager.installed(context).has("tree"),"CLI did not update shared state");
      JSONObject tree=command(context,"tree --version");check(tree.getInt("exitCode")==0&&tree.getString("stdout").contains("tree"),"Installed binary execution: "+tree);
      JSONObject repeated=command(context,"pkg install tree");check(repeated.getInt("exitCode")==0&&repeated.getString("stdout").contains("已安装"),"Idempotent install");
      check(command(context,"pkg install zfold-no-such-package-123").getInt("exitCode")!=0,"Missing package reported success");
      check(command(context,"pkg list-installed").getString("stdout").contains("tree "),"Installed-list mismatch");
      outcome.putBoolean("repositoryInstallAndExecution",true);outcome.putBoolean("samePackageDatabase",true);outcome.putBoolean("realPtyInstall",true);

      File executable=new File(context.getFilesDir(),"runtime/bin/pkg");
      CountDownLatch completed=new CountDownLatch(1);JSONObject[] result={null};String[] nativeError={null};
      AndroidTools.execute(context,PiRuntime.json("id","pkg-cli-native-test","name","runtime_exec","args",PiRuntime.json("argv",new JSONArray(Arrays.asList(executable.getPath(),"list")))),(value,failure)->{result[0]=value;nativeError[0]=failure;completed.countDown();});
      check(completed.await(30,TimeUnit.SECONDS)&&nativeError[0]==null&&result[0]!=null&&result[0].optInt("exitCode",-1)==0,"Native runtime_exec route: "+nativeError[0]);
      outcome.putBoolean("agentExecutionRoute",true);

      check(command(context,"pkg remove tree").getInt("exitCode")==0&&!PiPackageManager.installed(context).has("tree"),"Remove did not share state");
      synchronized(PiPackageManager.class){
        ProcessBuilder builder=builder(context,"pkg install tree");
        builder.command(executable.getPath(),"install","tree");
        java.lang.Process waiting=builder.start();long deadline=SystemClock.uptimeMillis()+5000;
        while(waiting.getErrorStream().available()==0&&waiting.isAlive()&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(20);
        check(waiting.getErrorStream().available()>0&&waiting.isAlive(),"Queued install did not enter shared lock");
        waiting.destroy();check(waiting.waitFor(5,TimeUnit.SECONDS),"CLI failed to exit on cancellation");
        SystemClock.sleep(250);
      }
      command(context,"pkg list");check(!PiPackageManager.installed(context).has("tree"),"Disconnected queued install still mutated packages");
      outcome.putBoolean("disconnectCancelsQueuedInstall",true);
      outcome.putString("treeVersion",tree.getString("stdout").trim());
    }catch(Throwable failure){error=failure.toString();outcome.putString("error",error);}
    finally{
      if(fixture!=null)try(var paths=Files.walk(fixture.toPath())){paths.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(IOException ignored){}});}catch(IOException ignored){}
    }
    finish(error.isEmpty()?-1:0,outcome);
  }
  private static File directory(File root,String name){File file=new File(root,name);file.mkdirs();return file;}
  private static ProcessBuilder builder(Context context,String command)throws Exception {
    ProcessBuilder builder=new ProcessBuilder("/system/bin/sh","-c",command);
    builder.directory(context.getFilesDir());RuntimeEnvironment.apply(builder.environment(),RuntimeEnvironment.base(context),null);return builder;
  }
  private static JSONObject command(Context context,String command)throws Exception {return RuntimeProcess.execute(builder(context,command),300000);}
  private static JSONObject ptyCommand(Context context,String command)throws Exception {
    ProcessBuilder builder=builder(context,command);
    int id=PiRuntime.ptyOpen(builder.command().toArray(new String[0]),builder.directory().getPath(),
      builder.environment().entrySet().stream().map(entry->entry.getKey()+"="+entry.getValue()).toArray(String[]::new),24,100);
    ByteArrayOutputStream output=new ByteArrayOutputStream();long deadline=SystemClock.uptimeMillis()+300000;
    try{
      for(;;){
        String encoded=PiRuntime.ptyRead(id,150);if(encoded!=null&&!encoded.isEmpty())output.write(Base64.getDecoder().decode(encoded));
        int status=PiRuntime.ptyStatus(id);
        if(status>=0){for(int i=0;i<3;i++){String tail=PiRuntime.ptyRead(id,30);if(tail!=null&&!tail.isEmpty())output.write(Base64.getDecoder().decode(tail));}return PiRuntime.json("exitCode",status,"stdout",output.toString(StandardCharsets.UTF_8.name()));}
        if(status==-2||SystemClock.uptimeMillis()>deadline)throw new IOException("PTY package command did not complete");
      }
    }finally{PiRuntime.ptyClose(id);}
  }
  private static void check(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
