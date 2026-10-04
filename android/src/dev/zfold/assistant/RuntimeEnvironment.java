package dev.zfold.assistant;

import android.content.Context;
import android.os.Environment;
import java.io.*;
import java.util.*;
import org.json.*;

/** Shared command environment for the Agent, one-shot processes and PTYs. */
final class RuntimeEnvironment {
  private static final Set<String> COMMON=new HashSet<>(Arrays.asList("HOME","TMPDIR","PATH","PREFIX","TERMINFO","TERM","LD_LIBRARY_PATH"));
  static JSONObject base(Context context)throws IOException{
    File runtime=new File(context.getFilesDir(),"runtime"),pkg=PiPackageManager.root(context),pack=PiRuntimePacks.cache(context);
    File home=new File(pkg,"home"),tmp=new File(pkg,"tmp");
    if((!home.isDirectory()&&!home.mkdirs())||(!tmp.isDirectory()&&!tmp.mkdirs()))throw new IOException("无法创建运行环境目录");
    JSONObject value=PiRuntime.json("HOME",home.getCanonicalPath(),"TMPDIR",tmp.getCanonicalPath(),
      "PATH",new File(runtime,"tools").getPath()+":"+new File(pkg,"usr/bin").getPath()+":"+new File(pack,"usr/bin").getPath()+":/system/bin:/system/xbin:/vendor/bin",
      "PREFIX",new File(pkg,"usr").getPath(),"TERMINFO",new File(pkg,"usr/share/terminfo").getPath(),"TERM","xterm-256color",
      "LD_LIBRARY_PATH",new File(pkg,"usr/lib").getPath()+":"+new File(pack,"usr/lib").getPath());
    JSONObject additions=PiRuntimePacks.environment(context);
    for(Iterator<String> keys=additions.keys();keys.hasNext();){String key=keys.next();if(!COMMON.contains(key))put(value,key,additions.opt(key));}
    return value;
  }
  static JSONObject forExecutable(Context context,File executable)throws IOException{
    JSONObject value=base(context),scoped=PiRuntimePacks.scopedEnvironment(context,executable);
    if(scoped!=null)for(Iterator<String> keys=scoped.keys();keys.hasNext();){String key=keys.next();
      // Profile-specific loaders/tool searches can differ; user home and temp do not.
      if(!key.equals("HOME")&&!key.equals("TMPDIR"))put(value,key,scoped.opt(key));
    }
    return value;
  }
  static File workingDirectory(Context context,String requested)throws IOException{
    File root=PiRuntime.agentRoot(context).getCanonicalFile();if(!root.isDirectory())root.mkdirs();
    String path=requested;
    if(path==null||path.isEmpty()){
      File project=new File(root,"workspace");
      try{
        JSONObject saved=new JSONObject(java.nio.file.Files.readString(new File(root,"active-workspace.json").toPath()));
        String name=saved.optString("name");File projects=new File(root,"workspaces"),candidate=new File(root,"workspaces/"+name).toPath().normalize().toFile();
        if(!name.isEmpty()&&!candidate.equals(projects)&&within(candidate,projects)&&candidate.getPath().equals(saved.optString("path"))&&candidate.isDirectory())project=candidate;
      }catch(Exception ignored){}
      if(!project.isDirectory()&&!project.mkdirs())throw new IOException("无法创建工作目录");
      path=project.getPath();
    }
    String[] aliases={"/storage/pi","/storage/emulated/0/pi","/sdcard/pi"};
    for(String alias:aliases)if(path.equals(alias)||path.startsWith(alias+"/")){path=root.getPath()+path.substring(alias.length());break;}
    File directory=new File(path);if(!directory.isAbsolute())directory=new File(root,path);directory=directory.getCanonicalFile();
    if(!within(directory,root))throw new IOException("工作目录必须在当前工作区数据根内");
    if(!directory.isDirectory())throw new IOException("工作目录不存在");
    return directory;
  }
  static void apply(Map<String,String> environment,JSONObject defaults,JSONObject overrides)throws IOException{
    for(Iterator<String> keys=defaults.keys();keys.hasNext();){String key=keys.next();environment.put(key,String.valueOf(defaults.opt(key)));}
    if(overrides!=null)for(Iterator<String> keys=overrides.keys();keys.hasNext();){String key=keys.next();
      if(!key.matches("[A-Za-z_][A-Za-z0-9_]*"))throw new IOException("环境变量名称非法");
      if(overrides.isNull(key)){environment.remove(key);continue;}
      String value=String.valueOf(overrides.opt(key));if(value.indexOf('\0')>=0)throw new IOException("环境变量不能含 NUL");environment.put(key,value);
    }
  }
  private static boolean within(File file,File root){return file.equals(root)||file.getPath().startsWith(root.getPath()+File.separator);}
  private static void put(JSONObject value,String key,Object item){try{value.put(key,item);}catch(JSONException impossible){throw new IllegalArgumentException(impossible);}}
}
