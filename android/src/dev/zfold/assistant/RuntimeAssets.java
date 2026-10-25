package dev.zfold.assistant;

import android.content.Context;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;

/** APK-owned files only: preparing a terminal must not start the embedded Pi runner. */
final class RuntimeAssets {
  private static final Set<File> prepared=new HashSet<>();
  private RuntimeAssets(){}

  /** GUI and terminal startup share one extraction, committed only after every asset succeeds. */
  static synchronized File prepare(Context context)throws IOException {
    File runtime=new File(context.getFilesDir(),"runtime").getCanonicalFile();
    try {
      if(prepared.contains(runtime))return runtime;
      Files.createDirectories(runtime.toPath());
      String[] names=context.getAssets().list("runtime");
      if(names==null||names.length==0)throw new IOException("APK 缺少内置运行时资源");
      byte[] buffer=new byte[32768];
      for(String name:names)copyAssetTree(context,"runtime/"+name,new File(runtime,name),buffer);
      // Refresh once per process, like the original GUI startup. A disk stamp would
      // incorrectly preserve missing or damaged bundled files across app restarts.
      // Installed packages, rootfs, HOME and other files absent from the APK remain untouched.
      prepared.add(runtime);
      return runtime;
    } catch(IOException|SecurityException error){
      throw new IOException("无法准备内置运行时资源："+error.getMessage(),error);
    }
  }


  private static void copyAssetTree(Context context,String asset,File target,byte[] buffer)throws IOException {
    String[] children=context.getAssets().list(asset);
    if(children==null||children.length==0){
      try(InputStream input=context.getAssets().open(asset)){
        replaceFile(input,target,buffer);
      } catch(IOException error){
        throw new IOException("无法提取 "+asset+"："+error.getMessage(),error);
      }
      return;
    }
    Files.createDirectories(target.toPath());
    for(String child:children)copyAssetTree(context,asset+"/"+child,new File(target,child),buffer);
  }

  private static void replaceFile(InputStream input,File target,byte[] buffer)throws IOException {
    Files.createDirectories(target.getParentFile().toPath());
    File temporary=File.createTempFile(".apk-asset-",".tmp",target.getParentFile());
    try {
      try(FileOutputStream output=new FileOutputStream(temporary)){
        int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);
        output.getFD().sync();
      }
      // Preserve executable launchers on updates before publishing the replacement inode.
      if(target.canExecute()&&!temporary.setExecutable(true,true))throw new IOException("无法保留执行权限："+target);
      Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    } finally {temporary.delete();}
  }
}
