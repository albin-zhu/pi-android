package dev.zfold.assistant;

import android.app.Instrumentation;
import android.os.Bundle;
import android.system.Os;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Synthetic credential migration and shared execution configuration checks. */
public final class RuntimeConfigurationInstrumentation extends Instrumentation {
  @Override public void onCreate(Bundle arguments){PiRuntime.testMode=true;start();}
  @Override public void onStart(){
    Bundle result=new Bundle();String error="";File fixture=null;
    try{
      PiRuntime.testMode=true;
      fixture=Files.createTempDirectory(getTargetContext().getCacheDir().toPath(),"configuration-").toFile();
      File legacy=new File(fixture,"legacy"),target=new File(fixture,"private");legacy.mkdirs();target.mkdirs();
      String value="{\"fixture\":\"synthetic-migration-value\"}";
      Files.writeString(new File(legacy,"auth.json").toPath(),value);
      Files.writeString(new File(legacy,"models.json").toPath(),"{}");
      JSONObject moved=CredentialStore.migrate(legacy,target);
      check(moved.getJSONArray("copied").length()==2,"Initial migration failed");
      check(value.equals(Files.readString(new File(target,"auth.json").toPath())),"Credential bytes changed");
      check(!new File(legacy,"auth.json").exists(),"Verified shared source remained");
      check((Os.stat(new File(target,"auth.json").getPath()).st_mode&0777)==0600,"Credential file is not owner-only");
      check((Os.stat(target.getPath()).st_mode&0777)==0700,"Credential directory is not owner-only");
      check(new File(target,"legacy-backup").list().length==2,"Recovery copy missing");
      check(!moved.toString().contains("synthetic-migration-value"),"Migration report exposed content");
      Files.writeString(new File(legacy,"auth.json").toPath(),"{\"fixture\":\"older-conflicting-value\"}");
      JSONObject conflict=CredentialStore.migrate(legacy,target);
      check(conflict.getJSONArray("conflicts").length()==1,"Conflicting legacy value not recorded");
      check(value.equals(Files.readString(new File(target,"auth.json").toPath())),"Private credential was overwritten");
      check(new File(target,"legacy-backup").list().length==3,"Conflict recovery copy missing");
      check(CredentialStore.migrate(legacy,target).getJSONArray("copied").length()==0,"Migration is not idempotent");
      CredentialStore.migrate(target,target);check(new File(target,"auth.json").exists(),"Self migration deleted credentials");
      Files.writeString(new File(legacy,"jev.json").toPath(),"{}");
      File invalid=new File(fixture,"invalid");Files.writeString(invalid.toPath(),"not a directory");
      try{CredentialStore.migrate(legacy,invalid);throw new IllegalStateException("Invalid destination accepted");}catch(java.io.IOException expected){}
      check(new File(legacy,"jev.json").exists(),"Failed migration removed source");
      result.putBoolean("credentialMigration",true);result.putBoolean("privatePermissions",true);result.putBoolean("recoveryAndConflicts",true);

      JSONObject base=RuntimeEnvironment.base(getTargetContext());
      check(base.getString("HOME").endsWith("/runtime/pkg/home"),"Unexpected shared HOME");
      check(base.getString("PATH").indexOf("/runtime/tools")<base.getString("PATH").indexOf("/runtime/pkg/usr/bin"),"PATH priority changed");
      Map<String,String> env=new HashMap<>();RuntimeEnvironment.apply(env,base,PiRuntime.json("LD_LIBRARY_PATH",null,"CUSTOM_VALUE","literal $()"));
      check(!env.containsKey("LD_LIBRARY_PATH")&&env.get("CUSTOM_VALUE").equals("literal $()"),"Environment override failed");
      check(RuntimeEnvironment.workingDirectory(getTargetContext(),"/storage/pi").equals(PiRuntime.agentRoot(getTargetContext()).getCanonicalFile()),"Data alias inconsistent");
      try{RuntimeEnvironment.workingDirectory(getTargetContext(),"/storage/pi/../outside");throw new IllegalStateException("Directory traversal accepted");}catch(java.io.IOException expected){}
      check(CredentialStore.directory(getTargetContext()).getPath().startsWith(getTargetContext().getNoBackupFilesDir().getPath()),"Credentials outside private directory");
      result.putBoolean("executionEnvironment",true);result.putBoolean("cwdBoundary",true);
    }catch(Throwable failure){error=failure.toString();result.putString("error",error);}
    finally{if(fixture!=null)try(var paths=Files.walk(fixture.toPath())){paths.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(Exception ignored){}});}catch(Exception ignored){}}
    finish(error.isEmpty()?-1:0,result);
  }
  private static void check(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
