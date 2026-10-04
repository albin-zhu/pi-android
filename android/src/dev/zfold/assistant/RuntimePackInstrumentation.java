package dev.zfold.assistant;

import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONObject;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/** End-to-end check: approved tar.gz install, env injection, and private exec. */
public final class RuntimePackInstrumentation extends Instrumentation {
  private Bundle options;
  @Override public void onCreate(Bundle arguments){options=arguments;start();}

  @Override public void onStart(){
    Bundle outcome=new Bundle();String error="";int exitCode=-1;
    try {
      String name=options.getString("pack","git-base.tar.gz"),sha=options.getString("sha256",""),skipInstall=options.getString("skipInstall","");
      JSONObject installed=new JSONObject();
      if(!"1".equals(skipInstall)) {
        installed=PiRuntimePacks.install(getTargetContext(),name,sha);
      }
      outcome.putBoolean("installed",installed.optJSONObject("installed")!=null);
      File runtime=PiRuntimePacks.cache(getTargetContext());
      String copyFrom=options.getString("copyFrom","");
      if(!copyFrom.isEmpty()) {
        File source=new File(copyFrom).getCanonicalFile();
        File storage=new File(android.os.Environment.getExternalStorageDirectory(),"pi").getCanonicalFile();
        if(!source.getPath().startsWith(storage.getPath()+File.separator))throw new Exception("diagnostic copy source must be under storage/pi");
        java.nio.file.Files.copy(source.toPath(),new File(runtime,"usr/bin/diagnostic").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        new File(runtime,"usr/bin/diagnostic").setExecutable(true,false);
      }
      String copyBinary=options.getString("copyBinary","");
      if(!copyBinary.isEmpty()) {
        File source=new File(copyBinary).getCanonicalFile();
        File storage=new File(android.os.Environment.getExternalStorageDirectory(),"pi").getCanonicalFile();
        if(!source.getPath().startsWith(storage.getPath()+File.separator))throw new Exception("diagnostic binary source must be under storage/pi");
        java.nio.file.Files.copy(source.toPath(),new File(runtime,"usr/bin/diagnostic-bin").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        new File(runtime,"usr/bin/diagnostic-bin").setExecutable(true,false);
      }
      String binary=options.getString("binary","git"),arguments=options.getString("arguments","--version").replace("__EMPTY__",""),expected=options.getString("expect","git version").replace("__EMPTY__","");
      File executable=new File(runtime,"usr/bin").toPath().resolve(binary).normalize().toFile();
      if(!executable.getCanonicalPath().startsWith(runtime.getCanonicalPath()+File.separator))throw new Exception("binary path escapes runtime cache");
if("1".equals(options.getString("printAccess",""))) {System.out.println("ACCESS_FILE="+executable.getAbsolutePath());System.out.println("ACCESS_CAN_READ="+executable.canRead()+" EXEC="+executable.canExecute());}
      List<String> command=new ArrayList<>();command.add(executable.getAbsolutePath());
      for(String argument:arguments.split("\\s+"))if(!argument.isEmpty())command.add(argument.replace("__DASHDASH__","--").replace("__DASH__","-"));
      ProcessBuilder builder=new ProcessBuilder(command);builder.directory(runtime);
      JSONObject env="1".equals(options.getString("scopedEnv",""))
        ?PiRuntimePacks.environmentForExecutable(getTargetContext(),executable)
        :PiRuntimePacks.environment(getTargetContext());
      for(java.util.Iterator<String> iterator=env.keys();iterator.hasNext();){String key=iterator.next();builder.environment().put(key,String.valueOf(env.opt(key)));}
      String ldLibraryPath=options.getString("ldLibraryPath","");
      if(ldLibraryPath.equals("unset"))builder.environment().remove("LD_LIBRARY_PATH");
      else if(!ldLibraryPath.isEmpty())builder.environment().put("LD_LIBRARY_PATH",ldLibraryPath);
      if("1".equals(options.getString("removeLd","")))builder.environment().remove("LD_LIBRARY_PATH");
      java.lang.Process process=builder.start();
      String stdout=read(process.getInputStream()),stderr=read(process.getErrorStream());exitCode=process.waitFor();
      outcome.putInt("exitCode",exitCode);outcome.putString("stdout",stdout);outcome.putString("stderr",stderr);
      outcome.putString("binaryPath",command.get(0));outcome.putString("environment",env.toString());
      if(exitCode!=0||!stdout.startsWith(expected))error="installed runtime tool failed";
    } catch(Throwable failure) {
      error=String.valueOf(failure);outcome.putString("error",error);
    }
    finish(error.isEmpty()&&exitCode==0?-1:0,outcome);
  }

  private static String read(java.io.InputStream stream)throws Exception {
    StringBuilder value=new StringBuilder();
    try(BufferedReader reader=new BufferedReader(new InputStreamReader(stream))) {for(String line;(line=reader.readLine())!=null;)value.append(line).append('\n');}
    return value.toString().trim();
  }
}
