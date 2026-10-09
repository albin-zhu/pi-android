package dev.zfold.assistant;

import android.content.*;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.util.Locale;
import org.json.JSONObject;

/** One external-open path for message links and the agent's open tool. */
final class OpenTargets {
  static File local(Context c,String target)throws Exception {
    String value=target;
    if(value.startsWith("file:")){
      Uri input=Uri.parse(value);
      if(!"file".equals(input.getScheme())||(input.getAuthority()!=null&&!input.getAuthority().isEmpty())||input.getQuery()!=null||input.getFragment()!=null)throw new IOException("无效文件链接");
      value=input.getPath();
    }
    File root=ArtifactProvider.root(c);
    for(String alias:new String[]{"/storage/pi","/sdcard/pi","/storage/emulated/0/pi"})if(value.equals(alias)||value.startsWith(alias+"/")){value=root.getPath()+value.substring(alias.length());break;}
    File file=new File(value).getCanonicalFile();
    if(!file.equals(root)&&!file.getPath().startsWith(root.getPath()+File.separator))throw new IOException("只能打开用户文件目录内的文件");
    if(!file.exists())throw new FileNotFoundException("文件不存在或已移除");return file;
  }
  static Uri fileUri(Context c,File file)throws Exception {
    file=ArtifactProvider.checked(c,file.getPath());
    String relative=ArtifactProvider.root(c).toPath().relativize(file.toPath()).toString();
    return new Uri.Builder().scheme("content").authority(c.getPackageName()+".artifacts").appendPath(relative).build();
  }
  static Intent directoryIntent(Context c,File directory)throws Exception {
    String relative=ArtifactProvider.root(c).toPath().relativize(directory.toPath()).toString().replace(File.separatorChar,'/');
    String volumePath=ArtifactProvider.root(c).getName()+(relative.isEmpty()?"":"/"+relative);
    Uri uri=DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:"+volumePath);
    // We do not own the DocumentsProvider URI: do not claim permission to grant it.
    return new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"vnd.android.document/directory");
  }
  static JSONObject open(Context c,String target,String overrideMime,boolean chooser)throws Exception {
    if(target==null||target.trim().isEmpty()||target.length()>4096)throw new IOException("无效打开目标");
    target=target.trim();String mime=overrideMime==null?"":overrideMime,kind="uri";File file=null;Intent intent;
    Uri input=Uri.parse(target);String scheme=input.getScheme();
    if(scheme!=null&&java.util.Arrays.asList("https","http","mailto","tel","sms","geo","market").contains(scheme.toLowerCase(Locale.ROOT))){intent=new Intent(Intent.ACTION_VIEW,input);}
    else if("content".equals(scheme)){
      intent=new Intent(Intent.ACTION_VIEW);if(mime.isEmpty())intent.setData(input);else intent.setDataAndType(input,mime);
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.setClipData(ClipData.newRawUri("文件",input));kind="content";
    }else{
      file=local(c,target);kind=file.isDirectory()?"directory":"file";
      if(file.isDirectory()){mime="vnd.android.document/directory";intent=directoryIntent(c,file);}
      else{if(mime.isEmpty())mime=ArtifactProvider.mime(file);Uri uri=fileUri(c,file);intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.setClipData(ClipData.newRawUri(file.getName(),uri));}
    }
    final Intent launch=(chooser?Intent.createChooser(intent,"使用以下方式打开"):intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    if(file!=null&&file.getName().toLowerCase(Locale.ROOT).endsWith(".apk")){
      UpdateGate.confirm(c,file,()->c.startActivity(launch));return PiRuntime.json("opened",false,"confirmationRequested",true,"kind",kind,"target",target);
    }
    try{c.startActivity(launch);}catch(ActivityNotFoundException noHandler){
      if(file==null||file.isDirectory())throw noHandler;
      c.startActivity(directoryIntent(c,file.getParentFile()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      return PiRuntime.json("opened",false,"fallbackOpened",true,"kind",kind,"target",target,"mime",mime,"reason","no file handler","fallback",file.getParent());
    }
    ResolveInfo handler=c.getPackageManager().resolveActivity(intent,0);
    return PiRuntime.json("opened",true,"kind",kind,"target",target,"mime",mime,"handler",handler==null?"":handler.loadLabel(c.getPackageManager()).toString(),"handlerPackage",handler==null?"":handler.activityInfo.packageName);
  }
}
