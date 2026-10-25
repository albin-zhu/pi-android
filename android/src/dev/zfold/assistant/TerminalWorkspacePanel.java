package dev.zfold.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.*;
import java.io.File;
import java.util.function.Consumer;

/** Navigation changes only the next launch context; existing PTYs remain untouched. */
final class TerminalWorkspacePanel {
  private final Activity activity;
  private final TerminalWorkspaces workspaces;
  private final TerminalSessions sessions;
  private final Consumer<TerminalSessions.Job> attach;
  private final Runnable changed,create,preview;
  private final Ui.Theme theme;
  private final Dialog dialog;
  private final LinearLayout content;

  static Dialog show(Activity activity,TerminalWorkspaces workspaces,TerminalSessions sessions,
      Consumer<TerminalSessions.Job> attach,Runnable changed,Runnable create,Runnable preview){
    return new TerminalWorkspacePanel(activity,workspaces,sessions,attach,changed,create,preview).open();
  }

  private TerminalWorkspacePanel(Activity activity,TerminalWorkspaces workspaces,TerminalSessions sessions,
      Consumer<TerminalSessions.Job> attach,Runnable changed,Runnable create,Runnable preview){
    this.activity=activity;this.workspaces=workspaces;this.sessions=sessions;this.attach=attach;
    this.changed=changed;this.create=create;this.preview=preview;theme=new Ui.Theme(activity);
    dialog=new Dialog(activity);dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
    content=new LinearLayout(activity);content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(dp(20),dp(16),dp(20),dp(24));content.setBackgroundColor(theme.page);
  }

  private Dialog open(){
    ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(true);scroll.addView(content);
    dialog.setContentView(scroll);dialog.show();Window window=dialog.getWindow();
    if(window!=null){
      window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));window.setGravity(Gravity.START);
      window.setLayout(Math.min(activity.getResources().getDisplayMetrics().widthPixels,dp(400)),-1);
      window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
    render();return dialog;
  }

  private void render(){
    content.removeAllViews();
    LinearLayout header=new LinearLayout(activity);header.setGravity(Gravity.CENTER_VERTICAL);
    TextView heading=Ui.text(activity,"工作区",23,theme.ink);heading.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
    header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
    header.addView(new Ui.Action(activity,R.drawable.ic_caret_down,"","收起工作区",theme.ink,Color.TRANSPARENT,dialog::dismiss),new LinearLayout.LayoutParams(dp(48),dp(48)));
    content.addView(header);
    TerminalWorkspaces.Entry selected=null;
    try{selected=workspaces.selected();}catch(Exception error){note(error.getMessage(),theme.error);}
    note("切换工作区只改变新终端的启动位置，不会 cd 或重启正在运行的终端。",theme.muted);
    section("最近工作区");
    for(TerminalWorkspaces.Entry entry:workspaces.list()){
      boolean current=selected!=null&&selected.id.equals(entry.id);
      row(entry.name+(current?" · 已选":""),(entry.ubuntu?"Ubuntu":"Android")+" · "+entry.path,current,()->{
        try{workspaces.select(entry.id);changed.run();render();}catch(Exception error){error(error);}
      });
    }
    row("选择 / 新建工作区","复用现有目录，只保存少量导航信息",false,
      ()->configure(activity,workspaces,false,entry->{changed.run();render();}));
    row("新建终端","确认目录和环境后启动",true,()->{dialog.dismiss();create.run();});
    section("全部终端 · "+sessions.liveCount()+" / 4");
    if(sessions.jobs.isEmpty())note("没有运行中的终端。",theme.muted);
    for(TerminalSessions.Job job:sessions.jobs.values()){
      row(job.name,job.workspace.name+" · "+(job.ubuntu?"Ubuntu":"Android")+" · "+job.status,
        sessions.selected()==job,()->{dialog.dismiss();attach.accept(job);});
    }
    section("文件与运行结果");
    row("预览本地文件","Markdown / 图片 · 返回继续当前终端",false,()->{dialog.dismiss();preview.run();});
    row("全部 X11 窗口","共享 :9 · 不按项目推测窗口归属",false,()->{dialog.dismiss();com.termux.x11.MainActivity.open(activity);});
    row("图形应用与 Wine","Ubuntu 图形应用 / Windows 程序 · 共用 :9",false,()->{dialog.dismiss();activity.startActivity(new Intent(activity,GraphicsRuntimeActivity.class));});
    section("环境与辅助功能");
    row("运行环境与软件包","所有工作区共用安装，不自动下载 Ubuntu",false,()->{dialog.dismiss();activity.startActivity(new Intent(activity,RuntimePackageManagerActivity.class));});
    row("终端启动设置","默认运行环境与新工作区",false,()->StartupPreferences.show(activity));
    row("设置与手机工具","授权、模型配置与设备工具",false,()->{dialog.dismiss();activity.startActivity(new Intent(activity,SettingsActivity.class));});
  }

  private void section(String title){TextView text=Ui.text(activity,title,12,theme.muted);text.setPadding(0,dp(20),0,dp(8));content.addView(text);}
  private void note(String text,int color){TextView view=Ui.text(activity,text==null?"操作未完成":text,13,color);view.setPadding(0,dp(6),0,dp(6));view.setLineSpacing(dp(3),1);content.addView(view);}
  private void row(String title,String detail,boolean selected,Runnable action){
    LinearLayout row=new LinearLayout(activity);row.setOrientation(LinearLayout.VERTICAL);row.setPadding(dp(12),dp(12),dp(12),dp(12));row.setMinimumHeight(dp(64));
    row.setBackground(Ui.ripple(activity,selected?theme.accentSoft:theme.surface,12,theme.ink));
    TextView name=Ui.text(activity,title,15,selected?theme.accent:theme.ink);name.setTypeface(Typeface.DEFAULT,Typeface.BOLD);row.addView(name);
    TextView summary=Ui.text(activity,detail,12,theme.muted);summary.setPadding(0,dp(5),0,0);summary.setMaxLines(3);summary.setEllipsize(android.text.TextUtils.TruncateAt.END);row.addView(summary);
    row.setFocusable(true);row.setContentDescription(title+"，"+detail);row.setOnClickListener(v->{Ui.click(v);action.run();});
    LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(6);content.addView(row,params);
  }
  private void error(Exception error){new AlertDialog.Builder(activity).setTitle("工作区不可用").setMessage(error.getMessage()).setPositiveButton("知道了",null).show();}
  private int dp(float value){return Ui.dp(activity,value);}

  static void configure(Activity activity,TerminalWorkspaces workspaces,boolean launch,Consumer<TerminalWorkspaces.Entry> accepted){
    final TerminalWorkspaces.Entry initial;
    try{initial=workspaces.selected();}catch(Exception error){new AlertDialog.Builder(activity).setTitle("无法读取工作区").setMessage(error.getMessage()).setPositiveButton("返回",null).show();return;}
    Ui.Theme theme=new Ui.Theme(activity);File[] directory={new File(initial.path)};
    LinearLayout form=new LinearLayout(activity);form.setOrientation(LinearLayout.VERTICAL);int padding=Ui.dp(activity,20);form.setPadding(padding,Ui.dp(activity,8),padding,Ui.dp(activity,12));
    form.setFocusableInTouchMode(true);
    TextView path=Ui.text(activity,initial.path,13,theme.muted);path.setTextIsSelectable(true);path.setContentDescription("启动目录");form.addView(path);
    EditText name=new EditText(activity);name.setSingleLine(true);name.setText(initial.name);name.setHint("工作区名称");name.setContentDescription("工作区名称");
    Button choose=new Button(activity);choose.setText("选择 / 新建目录");choose.setMinHeight(Ui.dp(activity,48));
    form.addView(choose,new LinearLayout.LayoutParams(-1,-2));form.addView(name,new LinearLayout.LayoutParams(-1,-2));
    TextView heading=Ui.text(activity,"启动环境（已有标签不变）",13,theme.ink);heading.setPadding(0,Ui.dp(activity,12),0,Ui.dp(activity,4));form.addView(heading);
    RadioGroup environments=new RadioGroup(activity);RadioButton android=new RadioButton(activity),ubuntu=new RadioButton(activity);
    android.setId(View.generateViewId());ubuntu.setId(View.generateViewId());
    android.setText("Android · Fish / Bash / 系统 Shell");ubuntu.setText("Ubuntu · 共用已安装的 Linux 环境");
    android.setMinHeight(Ui.dp(activity,48));ubuntu.setMinHeight(Ui.dp(activity,48));environments.addView(android);environments.addView(ubuntu);environments.check(initial.ubuntu?ubuntu.getId():android.getId());form.addView(environments);
    TextView status=Ui.text(activity,"",13,theme.muted);status.setPadding(0,Ui.dp(activity,8),0,0);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);form.addView(status);
    Runnable environmentStatus=()->{String unavailable=ubuntu.isChecked()?StartupPreferences.ubuntuUnavailable(activity):null;status.setTextColor(unavailable==null?theme.muted:theme.error);status.setText(unavailable==null?"只启动 Shell，不自动执行 omp 或历史命令；不会复制项目或运行环境。":unavailable+"\n不会自动改用 Android。可明确选择 Android 环境。");};
    environments.setOnCheckedChangeListener((group,id)->environmentStatus.run());environmentStatus.run();
    choose.setOnClickListener(v->DirectoryPicker.show(activity,directory[0].getPath(),value->{
      directory[0]=new File(value);path.setText(value);
      TerminalWorkspaces.Entry saved=null;
      for(TerminalWorkspaces.Entry entry:workspaces.list())if(entry.path.equals(value)){saved=entry;break;}
      name.setText(saved==null?directory[0].getName():saved.name);
      environments.check((saved==null?StartupPreferences.ubuntu(activity):saved.ubuntu)?ubuntu.getId():android.getId());
    }));
    ScrollView scroll=new ScrollView(activity);scroll.addView(form);
    AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(launch?"新建终端":"选择工作区").setView(scroll)
      .setNegativeButton("取消",null).setPositiveButton(launch?"创建终端":"保存工作区",null).create();
    dialog.show();form.requestFocus();dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
      String label=name.getText().toString().trim();
      if(label.isEmpty()||label.codePointCount(0,label.length())>80){name.setError("请输入 1–80 个字符");return;}
      try{
        if(launch&&ubuntu.isChecked()){String reason=StartupPreferences.ubuntuUnavailable(activity);if(reason!=null)throw new IllegalStateException(reason);}
        TerminalWorkspaces.Entry saved=workspaces.upsert(label,directory[0],ubuntu.isChecked());
        accepted.accept(saved);dialog.dismiss();
      }catch(Exception error){status.setTextColor(theme.error);status.setText(error.getMessage());}
    });
  }
}
