package dev.zfold.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;

/** Launcher destination and the environment of newly created interactive terminals. */
final class StartupPreferences {
  static final String TERMINAL="terminal",GUI="gui",LAST="last";
  private static SharedPreferences preferences(Context context){return context.getSharedPreferences(PiRuntime.testMode?"test-startup":"startup",Context.MODE_PRIVATE);}
  static String page(Context context){
    String value=preferences(context).getString("page",TERMINAL);
    return GUI.equals(value)||LAST.equals(value)?value:TERMINAL;
  }
  static Class<?> destination(Context context){
    String value=page(context);
    if(LAST.equals(value))value=preferences(context).getString("lastPage",TERMINAL);
    return GUI.equals(value)?MainActivity.class:TerminalActivity.class;
  }
  static void rememberPage(Context context,String page){
    SharedPreferences saved=preferences(context);
    if(!page.equals(saved.getString("lastPage",null)))saved.edit().putString("lastPage",page).apply();
  }
  static boolean ubuntu(Context context){return preferences(context).getBoolean("ubuntu",false);}
  static String ubuntuUnavailable(Context context){
    if(context.getApplicationInfo().targetSdkVersion!=28)return "PRoot Ubuntu 需要 compat28 运行时版本。当前可使用普通 Shell。";
    File runtime=new File(context.getFilesDir(),"runtime");
    if(!new File(runtime,"pkg/usr/bin/proot").canExecute())return "缺少 PRoot。请先在普通 Shell 中运行 pkg install proot。";
    if(!new File(runtime,"ubuntu/usr/bin/env").canExecute())return "Ubuntu 尚未安装。请先按 scripts/setup-ubuntu.py 安装 Ubuntu 环境。";
    if(!new File(runtime,"ubuntu.sh").isFile())return "当前运行时缺少 Ubuntu 启动脚本，请使用包含 Ubuntu 支持的安装包。";
    return null;
  }
  static void show(Activity activity){
    String selected=page(activity);
    String pageLabel=GUI.equals(selected)?"GUI 聊天":LAST.equals(selected)?"记住上次":"终端";
    new AlertDialog.Builder(activity).setTitle("启动设置")
      .setItems(new String[]{"点击图标进入 · "+pageLabel,"新终端环境 · "+(ubuntu(activity)?"PRoot Ubuntu":"普通 Shell")},(dialog,which)->{
        if(which==0)showPage(activity);else showEnvironment(activity);
      }).setNegativeButton("关闭",null).show();
  }
  private static void showPage(Activity activity){
    String selected=page(activity);
    int current=GUI.equals(selected)?1:LAST.equals(selected)?2:0;
    final int[] choice={current};
    new AlertDialog.Builder(activity).setTitle("点击图标进入")
      .setSingleChoiceItems(new String[]{"终端","GUI 聊天","记住上次使用的终端 / GUI"},current,(dialog,which)->choice[0]=which)
      .setPositiveButton("保存",(dialog,which)->preferences(activity).edit().putString("page",choice[0]==1?GUI:choice[0]==2?LAST:TERMINAL).apply())
      .setNegativeButton("取消",null).show();
  }
  private static void showEnvironment(Activity activity){
    final int[] choice={ubuntu(activity)?1:0};
    AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("新终端环境（已有会话不变）")
      .setSingleChoiceItems(new String[]{"普通 Shell（沿用 Fish / Bash / 系统 Shell）","PRoot Ubuntu（直接进入 Ubuntu）"},choice[0],(selected,which)->choice[0]=which)
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
