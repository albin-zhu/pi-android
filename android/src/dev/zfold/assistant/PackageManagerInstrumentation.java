package dev.zfold.assistant;

import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/** Verifies repository-backed install, private execution, and package removal. */
public final class PackageManagerInstrumentation extends Instrumentation {
  private Bundle options;
  @Override public void onCreate(Bundle arguments){options=arguments;start();}

  @Override public void onStart(){
    Bundle outcome=new Bundle();
    String action=options.getString("action","search");
    String name=options.getString("name","");
    String query=options.getString("query",name);
    String binary=options.getString("binary","");
    String arguments=options.getString("arguments","--version");
    try {
      Object result;
      if(action.equals("install"))result=PiPackageManager.install(getTargetContext(),name);
      else if(action.equals("remove"))result=PiPackageManager.remove(getTargetContext(),name);
      else if(action.equals("status"))result=PiPackageManager.status(getTargetContext(),options.getBoolean("refresh",false));
      else if(action.equals("search"))result=PiPackageManager.search(getTargetContext(),query,options.getBoolean("refresh",false));
      else throw new IllegalArgumentException("Unsupported action: "+action);
      outcome.putString("result",bound(String.valueOf(result)));
      if(!binary.isEmpty()) {
        File usr=PiPackageManager.root(getTargetContext());
        File executable=PiPackageManager.executable(getTargetContext(),binary);
        if(executable==null)throw new IllegalStateException("installed package does not provide "+binary);
        List<String> command=new ArrayList<>();
        command.add(executable.getCanonicalPath());
        for(String argument:arguments.split("\\s+"))if(!argument.isEmpty())command.add(argument);
        ProcessBuilder builder=new ProcessBuilder(command);
        builder.directory(new File(usr,"home"));
        JSONObject env=PiPackageManager.environmentForExecutable(getTargetContext(),executable);
        for(java.util.Iterator<String> it=env.keys();it.hasNext();){String key=it.next();builder.environment().put(key,String.valueOf(env.opt(key)));}
        android.util.Log.i("PiPackageManager","executing "+command);
        Process process=builder.start();
        String stdout=read(process.getInputStream()),stderr=read(process.getErrorStream());
        int exitCode=process.waitFor();
        outcome.putInt("exitCode",exitCode);
        outcome.putString("stdout",bound(stdout));
        outcome.putString("stderr",bound(stderr));
        outcome.putString("binaryPath",executable.getAbsolutePath());
        if(exitCode!=0)throw new IllegalStateException(binary+" exited "+exitCode+": "+stderr);
      }
      finish(-1,outcome);
    } catch(Throwable failure) {
      outcome.putString("error",String.valueOf(failure));
      finish(0,outcome);
    }
  }

  private static String read(java.io.InputStream stream)throws Exception {
    StringBuilder value=new StringBuilder();
    try(BufferedReader reader=new BufferedReader(new InputStreamReader(stream))) {
      for(String line;(line=reader.readLine())!=null;)value.append(line).append('\n');
    }
    return value.toString().trim();
  }

  private static String bound(String value) {
    String text=value;
    return text.length()>262144?text.substring(0,262144)+"\n[output truncated]":text;
  }
}
