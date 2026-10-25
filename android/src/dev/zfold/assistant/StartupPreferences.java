package dev.zfold.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;

/** Launcher destination and the environment of newly created interactive terminals. */
final class StartupPreferences {
  private static SharedPreferences preferences(Context context){return context.getSharedPreferences(PiRuntime.testMode?"test-startup":"startup",Context.MODE_PRIVATE);}
  static boolean ubuntu(Context context){return preferences(context).getBoolean("ubuntu",false);}
  static String ubuntuUnavailable(Context context){
    if(context.getApplicationInfo().targetSdkVersion!=28)return "PRoot Ubuntu 需要 compat28 运行时版本。当前可使用普通 Shell。";
    File runtime=new File(context.getFilesDir(),"runtime");
    if(!new File(runtime,"pkg/usr/bin/proot").canExecute())return "缺少 PRoot。请先在普通 Shell 中运行 pkg install proot。";
    if(!new File(runtime,"ubuntu/usr/bin/env").canExecute())return "Ubuntu 尚未安装。请先按 scripts/setup-ubuntu.py 安装 Ubuntu 环境。";
    return null;
  }
  static void show(Activity activity){
    showEnvironment(activity);
  }
  private static void showEnvironment(Activity activity){
    final int[] choice={ubuntu(activity)?1:0};
    AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("新工作区默认环境（已有工作区与终端不变）")
      .setSingleChoiceItems(new String[]{"Android（Fish / Bash / 系统 Shell）","Ubuntu（共用已安装环境）"},choice[0],(selected,which)->choice[0]=which)
      .setPositiveButton("保存",null).setNegativeButton("取消",null).create();
    dialog.show();
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{
      if(choice[0]==1){
        String reason=ubuntuUnavailable(activity);
        if(reason!=null){new AlertDialog.Builder(activity).setTitle("暂时无法使用 Ubuntu").setMessage(reason).setPositiveButton("知道了",null).show();return;}
      }
      preferences(activity).edit().putBoolean("ubuntu",choice[0]==1).apply();dialog.dismiss();
    });
  }
}
