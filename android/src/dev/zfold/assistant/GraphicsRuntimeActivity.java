package dev.zfold.assistant;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;

/** Linux X11 display and optional Windows programs, independent of chat runs. */
public final class GraphicsRuntimeActivity extends Activity {
  private Ui.Theme theme;
  private LinearLayout content;
  private EditText executable,arguments;
  private TextView status;

  @Override public void onCreate(Bundle state){
    super.onCreate(state);theme=new Ui.Theme(this);
    ScrollView scroll=new ScrollView(this);content=new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(20),dp(20),dp(20),dp(24));
    content.setBackgroundColor(theme.page);scroll.addView(content);setContentView(scroll);
    TextView title=Ui.text(this,"图形应用 · X11 / Wine",22,theme.ink);title.setTypeface(Typeface.create("sans-serif-medium",0));content.addView(title);
    text("X11 显示独立于终端和聊天。先打开显示，再回到终端启动 Linux 图形程序或 Windows 程序；离开显示页面不会结束会话。");
    button("打开 X11 显示",()->com.termux.x11.MainActivity.open(this));
    button("查看 Agent 虚拟屏（Xvfb）",()->startActivity(new Intent(this,VirtualScreenActivity.class)));
    text("新 Ubuntu 终端自动连接 DISPLAY=:9。已有终端请重新进入 ubuntu，以加载显示 socket、授权文件和 windows 命令。");
    status=Ui.text(this,"",13,theme.muted);content.addView(status);
    text("Wine 为可选下载：固定版本 Box64 + Wine WoW64，支持尝试运行 32/64 位 Windows 程序。不是安全沙箱；只运行你信任的程序。首次安装会在新终端展示来源并请求确认，不包含游戏文件。");
    button("在终端安装 Wine",()->terminal("安装 Wine","install"));
    button("查看 Wine 状态",()->terminal("Wine 状态","status"));
    button("打开 Wine 设置",()->terminal("Wine 设置","winecfg"));
    executable=field("Windows 程序的 Ubuntu 路径，例如 /workspace/workspace/app.exe",true);
    arguments=field("程序参数：每行一个，原样传递，不解析 Shell",false);
    button("运行 Windows 程序",()->{
      String path=executable.getText().toString().trim();
      if(path.isEmpty()){executable.setError("填写 Ubuntu 内的 .exe 路径或 Wine 内置程序名");return;}
      ArrayList<String> argv=new ArrayList<>();argv.add("run");argv.add(path);
      String options=arguments.getText().toString();if(!options.isEmpty())argv.addAll(Arrays.asList(options.split("\n",-1)));
      terminal("Windows 程序",argv.toArray(new String[0]));
    });
    button("结束默认 Wine 环境内的所有程序",()->new android.app.AlertDialog.Builder(this)
      .setTitle("结束 Windows 程序？").setMessage("结束默认 Wine prefix 内的所有程序，未保存内容可能丢失。其他终端和 X11 显示不受影响。")
      .setNegativeButton("取消",null).setPositiveButton("结束",(dialog,which)->terminal("结束 Wine","stop")).show());
    text("终端命令：\nwindows install\nwindows winecfg\nwindows run notepad.exe\nwindows run '/workspace/workspace/My App/app.exe'\nwindows --prefix /root/my-prefix run app.exe\nwindows stop\n\n显示、键鼠与 Windows 兼容层分开提供；GPU 加速、声音和具体应用兼容性取决于其驱动与运行配置。");
    if(state!=null){executable.setText(state.getString("executable",""));arguments.setText(state.getString("arguments",""));}
  }
  @Override protected void onResume(){super.onResume();
    boolean ubuntu=new File(getFilesDir(),"runtime/ubuntu/usr/bin/env").isFile();
    status.setText(getApplicationInfo().targetSdkVersion!=28?"Windows / Ubuntu 执行需要 compat28 通道；X11 显示可单独使用。":ubuntu?"Ubuntu 已安装。打开 X11 后可在新终端运行图形程序。":"Ubuntu 尚未安装。请先按 scripts/setup-ubuntu.py 安装现有 Ubuntu 环境；X11 显示可单独使用。");
  }
  @Override protected void onSaveInstanceState(Bundle state){state.putString("executable",executable.getText().toString());state.putString("arguments",arguments.getText().toString());super.onSaveInstanceState(state);}
  private void terminal(String title,String... arguments){
    if(getApplicationInfo().targetSdkVersion!=28){Toast.makeText(this,"需要 compat28 执行通道",Toast.LENGTH_LONG).show();return;}
    File ubuntu=new File(getFilesDir(),"runtime/bin/ubuntu");
    if(!new File(getFilesDir(),"runtime/ubuntu/usr/bin/env").isFile()){Toast.makeText(this,"请先安装 Ubuntu 运行环境",Toast.LENGTH_LONG).show();return;}
    // Positional arguments, never interpolated shell text. Keep the terminal open
    // after completion so installer errors and the actual exit code remain visible.
    ArrayList<String> argv=new ArrayList<>(Arrays.asList(ubuntu.getPath(),"/bin/bash","-c",
      "windows \"$@\"; code=$?; printf '\\nWindows 命令退出码：%s\\n' \"$code\"; exec /bin/bash -l","windows"));
    argv.addAll(Arrays.asList(arguments));
    try{TerminalSessions.get(this).createCommand(argv.toArray(new String[0]),title);TerminalActivity.open(this);}
    catch(RuntimeException error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();}
  }
  private void text(String value){TextView label=Ui.text(this,value,14,theme.muted);label.setPadding(0,dp(12),0,dp(8));label.setLineSpacing(dp(3),1);label.setTextIsSelectable(true);content.addView(label);}
  private EditText field(String hint,boolean single){EditText field=new EditText(this);field.setHint(hint);field.setTextColor(theme.ink);field.setHintTextColor(theme.muted);field.setSingleLine(single);if(!single){field.setMinLines(2);field.setGravity(Gravity.TOP);}content.addView(field,new LinearLayout.LayoutParams(-1,-2));return field;}
  private void button(String title,Runnable action){Button button=new Button(this);button.setText(title);button.setAllCaps(false);button.setOnClickListener(view->action.run());content.addView(button,new LinearLayout.LayoutParams(-1,-2));}
  private int dp(int value){return Ui.dp(this,value);}
}
