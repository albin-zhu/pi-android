package dev.zfold.assistant;

import android.app.Instrumentation;
import android.os.Bundle;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Proves whether the real target process may exec a file written at runtime. */
public final class RuntimeExecInstrumentation extends Instrumentation {
  @Override public void onCreate(Bundle arguments){start();}

  @Override public void onStart(){
    Bundle outcome=new Bundle();
    String error="";
    int exitCode=-1;
    try {
      File executable=new File(getTargetContext().getFilesDir(),"runtime-exec-test/cat");
      executable.getParentFile().mkdirs();
      Files.copy(new File("/system/bin/cat").toPath(),executable.toPath(),StandardCopyOption.REPLACE_EXISTING);
      executable.setReadable(true,false);executable.setExecutable(true,false);
      Process process=Runtime.getRuntime().exec(new String[]{executable.getAbsolutePath(),"/proc/self/attr/current"});
      String stdout=read(process.getInputStream()),stderr=read(process.getErrorStream());
      exitCode=process.waitFor();
      outcome.putInt("exitCode",exitCode);
      outcome.putString("stdout",stdout);
      outcome.putString("stderr",stderr);
      outcome.putString("targetSdk",String.valueOf(getTargetContext().getApplicationInfo().targetSdkVersion));
      outcome.putString("executable",executable.getAbsolutePath());
      File runtime=new File(getTargetContext().getFilesDir(),"runtime/runtime-exec-test");
      File git=new File(runtime,"usr/bin/git");
      if(git.canExecute()) {
        ProcessBuilder builder=new ProcessBuilder(git.getAbsolutePath(),"--version");
        builder.directory(runtime);
        builder.environment().put("LD_LIBRARY_PATH",new File(runtime,"usr/lib").getAbsolutePath());
        builder.environment().put("GIT_EXEC_PATH",new File(runtime,"usr/libexec/git-core").getAbsolutePath());
        builder.environment().put("GIT_CONFIG_NOSYSTEM","1");
        builder.environment().put("HOME",runtime.getAbsolutePath());
        builder.environment().put("TMPDIR",runtime.getAbsolutePath());
        Process gitProcess=builder.start();
        String gitOutput=read(gitProcess.getInputStream()),gitError=read(gitProcess.getErrorStream());
        outcome.putInt("gitExitCode",gitProcess.waitFor());
        outcome.putString("gitVersion",gitOutput);
        outcome.putString("gitError",gitError);
        if(gitProcess.exitValue()!=0||!gitOutput.startsWith("git version "))error="git runtime pack failed";
      }
    } catch(Throwable failure) {
      error=String.valueOf(failure);outcome.putString("error",error);
    }
    finish(error.isEmpty()&&exitCode==0?-1:0,outcome);
  }

  private static String read(java.io.InputStream stream)throws Exception {
    StringBuilder value=new StringBuilder();
    try(BufferedReader reader=new BufferedReader(new InputStreamReader(stream))) {
      for(String line;(line=reader.readLine())!=null;)value.append(line).append('\n');
    }
    return value.toString().trim();
  }
}
