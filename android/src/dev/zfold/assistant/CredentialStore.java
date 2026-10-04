package dev.zfold.assistant;

import android.content.Context;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/** Credentials keep one private home regardless of shared-storage permission. */
final class CredentialStore {
  private static final String[] FILES={"auth.json","models.json","jev.json","agent.json","system.md","avatar.png"};
  private static final long MAX_BYTES=8*1024*1024;

  static File directory(Context context){return directory(context,PiRuntime.testMode);}
  private static File directory(Context context,boolean testing){return new File(context.getNoBackupFilesDir(),testing?"agent-smoke-credentials":"credentials");}
  static File prepare(Context context,File data,boolean testing)throws Exception{
    File destination=directory(context,testing);secureDirectory(destination);
    migrate(new File(data,".pi/agent"),destination);
    if(!testing){
      File previous=new File(context.getNoBackupFilesDir(),"agent/.pi/agent");
      if(!previous.getCanonicalFile().equals(new File(data,".pi/agent").getCanonicalFile()))migrate(previous,destination);
    }
    return destination;
  }

  /** Existing private files win; conflicting legacy values remain recoverable. */
  static synchronized JSONObject migrate(File legacy,File destination)throws Exception{
    secureDirectory(destination);
    JSONArray copied=new JSONArray(),conflicts=new JSONArray(),retained=new JSONArray();
    if(!legacy.isDirectory()||legacy.getCanonicalFile().equals(destination.getCanonicalFile()))return report(copied,conflicts,retained);
    File backups=new File(destination,"legacy-backup");
    for(String name:FILES){
      File source=new File(legacy,name),target=new File(destination,name);
      if(!Files.isRegularFile(source.toPath(),LinkOption.NOFOLLOW_LINKS))continue;
      if(source.length()>MAX_BYTES){retained.put(name);continue;}
      byte[] value=Files.readAllBytes(source.toPath());
      // Keep a private recovery copy before any cleanup of the legacy location.
      secureDirectory(backups);
      File backup=new File(backups,name+"."+digest(value));
      if(Files.isSymbolicLink(backup.toPath())||backup.length()>MAX_BYTES)throw new IOException("凭据恢复副本无效："+name);
      if(!backup.exists())atomicWrite(backup,value);
      if(!Arrays.equals(value,Files.readAllBytes(backup.toPath())))throw new IOException("凭据恢复副本校验失败："+name);
      if(Files.isSymbolicLink(target.toPath()))throw new IOException("私有凭据文件不能是符号链接："+name);
      if(!target.exists()){
        atomicWrite(target,value);
        if(!Arrays.equals(value,Files.readAllBytes(target.toPath())))throw new IOException("私有凭据校验失败："+name);
        copied.put(name);
      }else if(target.length()>MAX_BYTES||!Arrays.equals(value,Files.readAllBytes(target.toPath())))conflicts.put(name);
      // A concurrent writer keeps its new file. No values are logged or returned.
      if(Files.isRegularFile(source.toPath(),LinkOption.NOFOLLOW_LINKS)&&Arrays.equals(value,Files.readAllBytes(source.toPath()))){
        if(!source.delete())retained.put(name);
      }else retained.put(name);
    }
    JSONObject report=report(copied,conflicts,retained);
    if(copied.length()+conflicts.length()+retained.length()>0){
      atomicWrite(new File(destination,"migration-status.json"),report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      android.util.Log.i("ZfoldPi","Private credential migration: copied="+copied.length()+", conflicts="+conflicts.length()+", retained="+retained.length());
    }
    return report;
  }
  private static JSONObject report(JSONArray copied,JSONArray conflicts,JSONArray retained){return PiRuntime.json("copied",copied,"conflicts",conflicts,"retainedLegacyFiles",retained,"recoveryDirectory","legacy-backup");}
  private static void secureDirectory(File directory)throws IOException{
    if(Files.isSymbolicLink(directory.toPath()))throw new IOException("私有凭据目录不能是符号链接");
    if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("无法创建私有凭据目录");
    if(!directory.setReadable(false,false)||!directory.setWritable(false,false)||!directory.setExecutable(false,false)
      ||!directory.setReadable(true,true)||!directory.setWritable(true,true)||!directory.setExecutable(true,true))throw new IOException("无法设置私有凭据目录权限");
  }
  private static void atomicWrite(File target,byte[] value)throws IOException{
    File temporary=File.createTempFile("credential-",".tmp",target.getParentFile());
    try{
      if(!temporary.setReadable(false,false)||!temporary.setWritable(false,false)||!temporary.setReadable(true,true)||!temporary.setWritable(true,true))throw new IOException("无法设置凭据文件权限");
      try(FileOutputStream output=new FileOutputStream(temporary)){output.write(value);output.getFD().sync();}
      Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }finally{temporary.delete();}
  }
  private static String digest(byte[] value)throws Exception{
    byte[] hash=MessageDigest.getInstance("SHA-256").digest(value);StringBuilder hex=new StringBuilder();for(byte b:hash)hex.append(String.format("%02x",b&255));return hex.toString();
  }
}
