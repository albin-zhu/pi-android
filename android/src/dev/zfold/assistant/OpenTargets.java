package dev.zfold.assistant;

import android.content.*;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.util.Locale;
import org.json.JSONObject;

/** Shared local-preview and external-open path for message links and the open tool. */
final class OpenTargets {
  static File local(Context c,String target)throws Exception {
    File file=TerminalLinkTargets.localFile(target,ArtifactProvider.root(c));
    if(!file.exists())throw new FileNotFoundException("文件不存在或已移除");return file;
  }
  static Uri fileUri(Context c,File file)throws Exception {
    file=ArtifactProvider.checked(c,file.getPath());
    String relative=ArtifactProvider.root(c).toPath().relativize(file.toPath()).toString();
    return new Uri.Builder().scheme("content").authority(c.getPackageName()+".artifacts").appendPath(relative).build();
  }
  private static File artifactFile(Context c,Uri uri)throws IOException {
    if(uri.getQuery()!=null||uri.getFragment()!=null||uri.getPathSegments().size()!=1)throw new IOException("无效文件链接");
    String relative=uri.getPathSegments().get(0);
    if(relative.isEmpty()||new File(relative).isAbsolute())throw new IOException("无效文件链接");
    return ArtifactProvider.checked(c,new File(ArtifactProvider.root(c),relative).getPath());
  }
  private static JSONObject preview(Context c,File file,String target,String kind,String mime)throws Exception {
    PreviewActivity.open(c,file);
    return PiRuntime.json("opened",true,"previewDispatched",true,"kind",kind,"target",target,"mime",mime,
      "handler","PreviewActivity","handlerPackage",c.getPackageName(),"handlerActivity",PreviewActivity.class.getName());
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
    else if("content".equals(scheme)&&!(c.getPackageName()+".artifacts").equals(input.getAuthority())){
      intent=new Intent(Intent.ACTION_VIEW);if(mime.isEmpty())intent.setData(input);else intent.setDataAndType(input,mime);
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.setClipData(ClipData.newRawUri("文件",input));kind="content";
    }else{
      boolean artifact="content".equals(scheme);
      file=artifact?artifactFile(c,input):local(c,target);kind=artifact?"content":file.isDirectory()?"directory":"file";
      if(file.isDirectory()){mime="vnd.android.document/directory";intent=directoryIntent(c,file);}
      else{if(mime.isEmpty())mime=ArtifactProvider.mime(file);Uri uri=fileUri(c,file);intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.setClipData(ClipData.newRawUri(file.getName(),uri));}
    }
    final Intent launch=(chooser?Intent.createChooser(intent,"使用以下方式打开"):intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    if(file!=null&&file.getName().toLowerCase(Locale.ROOT).endsWith(".apk")){
      UpdateGate.confirm(c,file,()->c.startActivity(launch));return PiRuntime.json("opened",false,"confirmationRequested",true,"kind",kind,"target",target);
    }
    if(!chooser&&file!=null&&!file.isDirectory()&&PreviewFiles.supports(file,mime))return preview(c,file,target,kind,mime);
    try{c.startActivity(launch);}catch(ActivityNotFoundException noHandler){
      if(chooser||file==null||file.isDirectory())throw noHandler;
      return preview(c,file,target,kind,mime);
    }
    ResolveInfo handler=c.getPackageManager().resolveActivity(intent,0);
    return PiRuntime.json("opened",true,"kind",kind,"target",target,"mime",mime,"handler",handler==null?"":handler.loadLabel(c.getPackageManager()).toString(),"handlerPackage",handler==null?"":handler.activityInfo.packageName);
  }
}
