package dev.zfold.assistant;

import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.*;
import android.widget.Toast;
import java.io.File;
import java.util.*;

/** Preliminary identity check; Android's installer remains the signature authority. */
final class UpdateGate {
  static void confirm(Context context,File file,Runnable launch)throws Exception {
    PackageManager pm=context.getPackageManager();
    PackageInfo archive=pm.getPackageArchiveInfo(file.getPath(),PackageManager.GET_SIGNING_CERTIFICATES);
    if(archive==null)throw new IllegalArgumentException("无法解析 APK");
    if(!context.getPackageName().equals(archive.packageName)){
      new AlertDialog.Builder(context).setTitle("安装其他应用")
        .setMessage("包名："+archive.packageName+"\n版本："+archive.versionName+"\n这不是橙橙自身更新。继续后由系统确认安装。")
        .setNegativeButton("取消",null).setPositiveButton("继续",(d,w)->{
          try{launch.run();}catch(Exception error){Toast.makeText(context,"无法打开安装页："+error.getMessage(),Toast.LENGTH_LONG).show();}
        }).show();return;
    }
    PackageInfo installed=pm.getPackageInfo(context.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);
    if(!sameSigners(installed,archive))throw new IllegalArgumentException("签名与当前安装不一致，已拒绝更新；不要卸载旧版");
    if(archive.getLongVersionCode()<installed.getLongVersionCode())throw new IllegalArgumentException("版本号低于当前安装，已拒绝降级");
    new AlertDialog.Builder(context).setTitle("更新橙橙")
      .setMessage("当前："+installed.versionName+" ("+installed.getLongVersionCode()+")\n候选："+archive.versionName+" ("+archive.getLongVersionCode()+")\n\n包名及签名信息一致；最终校验由系统完成。\n更新会终止当前进程，请先结束正在执行的任务。安装后请手动重新打开；自动恢复任务尚未实现。")
      .setNegativeButton("取消",null).setPositiveButton("打开系统安装页",(d,w)->{
        try{launch.run();}catch(Exception error){Toast.makeText(context,"无法打开安装页："+error.getMessage(),Toast.LENGTH_LONG).show();}
      }).show();
  }
  static boolean sameSigners(PackageInfo a,PackageInfo b){
    if(a.signingInfo==null||b.signingInfo==null)return false;
    Signature[] x=a.signingInfo.getApkContentsSigners(),y=b.signingInfo.getApkContentsSigners();
    return x!=null&&y!=null&&x.length>0&&new HashSet<>(Arrays.asList(x)).equals(new HashSet<>(Arrays.asList(y)));
  }
}
