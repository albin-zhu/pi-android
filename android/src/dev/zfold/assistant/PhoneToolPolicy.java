package dev.zfold.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;

/** Explicit user opt-in for same-UID independent clients, never a system permission grant. */
final class PhoneToolPolicy {
  private static SharedPreferences prefs(Context context){
    return context.getSharedPreferences(PiRuntime.testMode?"test-phone-tools":"phone-tools",Context.MODE_PRIVATE);
  }
  static boolean alwaysAllow(Context context){return prefs(context).getBoolean("always_allow",false);}
  static void setAlwaysAllow(Context context,boolean value){prefs(context).edit().putBoolean("always_allow",value).apply();}
  static void show(Activity activity){
    String[] choices={"每次确认（默认）","始终允许"};
    new AlertDialog.Builder(activity).setTitle("独立客户端手机工具")
      .setSingleChoiceItems(choices,alwaysAllow(activity)?1:0,(dialog,index)->{
        dialog.dismiss();
        if(index==0){setAlwaysAllow(activity,false);return;}
        new AlertDialog.Builder(activity).setTitle("始终允许手机工具？")
          .setMessage("Ubuntu 中的 phone call、omp，以及同一应用身份运行的其他脚本，将不再逐次请求确认。\n\n范围包括读屏、点击、输入、相机、联系人、电话和短信。仅运行你信任的代码。\n\nAndroid 系统权限仍需单独授权；工具白名单、任务互斥、取消和用户接管保持有效。可以随时在手机能力中改回每次确认；不撤回已经开始的操作。")
          .setNegativeButton("取消",null).setPositiveButton("启用始终允许",(d,w)->setAlwaysAllow(activity,true)).show();
      }).setNegativeButton("关闭",null).show();
  }
}
