package dev.zfold.assistant;

import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;

/** Direct work destinations; advanced tool schemas remain a separate catalog. */
public final class WorkbenchActivity extends NavigableActivity implements PiRuntime.Listener {
  private static WorkbenchActivity active;
  static void capabilitiesChanged(){if(active!=null)active.render();}
  private PiRuntime runtime;private Ui.Theme theme;private LinearLayout content;private TextView status;
  private boolean fromOverlay;private Runnable pendingAction;
  static void open(Context context){
    boolean overlay=FloatingNavigation.suspend(context);
    SystemWindowNavigation.open(context,WorkbenchActivity.class,overlay);
  }
  static void returnFromSettings(Context context){context.startActivity(new Intent(context,WorkbenchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));}
  @Override protected void onCreate(Bundle saved){super.onCreate(saved);fromOverlay=getIntent().getBooleanExtra("from_overlay",false);theme=new Ui.Theme(this);runtime=PiRuntime.get(this);build();runtime.listen(this);}
  @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(intent.hasExtra("from_overlay"))fromOverlay=intent.getBooleanExtra("from_overlay",false);}
  @Override protected void onResume(){super.onResume();active=this;if(content!=null)render();}
  @Override protected void onPause(){SystemWindowNavigation.remember(this);if(active==this)active=null;pendingAction=null;super.onPause();}
  @Override protected void onDestroy(){if(runtime!=null)runtime.unlisten(this);if(isFinishing()&&fromOverlay&&!isReturningToMenu())FloatingNavigation.restore(getApplicationContext());super.onDestroy();}
  private void build(){
    getWindow().setDecorFitsSystemWindows(false);getWindow().setStatusBarColor(theme.page);getWindow().setNavigationBarColor(theme.page);getWindow().getDecorView().setSystemUiVisibility(theme.dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(theme.page);
    root.setOnApplyWindowInsetsListener((view,insets)->{Insets bars=insets.getInsets(WindowInsets.Type.systemBars());root.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
    LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(8),0,dp(12),0);header.addView(new Ui.Action(this,R.drawable.ic_caret_down,"","返回",theme.ink,Color.TRANSPARENT,this::navigateUp),new LinearLayout.LayoutParams(dp(48),dp(48)));
    TextView title=Ui.text(this,"工作台",23,theme.ink);title.setTypeface(Typeface.create("sans-serif-medium",0));header.addView(title,new LinearLayout.LayoutParams(0,-2,1));root.addView(header);
    status=Ui.text(this,"",13,theme.muted);status.setPadding(dp(4),0,dp(4),dp(8));
    ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(18),0,dp(18),dp(24));scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));root.setContentDescription("手机工作台页面");setContentView(root);render();
  }
  private LinearLayout section(String title){TextView label=Ui.text(this,title,13,theme.muted);label.setPadding(dp(5),dp(18),0,dp(9));content.addView(label);LinearLayout group=new LinearLayout(this);group.setOrientation(LinearLayout.VERTICAL);group.setPadding(dp(4),dp(4),dp(4),dp(4));group.setBackground(Ui.shape(this,theme.surface,20,0));content.addView(group);return group;}
  private void row(LinearLayout group,int icon,String title,String detail,Runnable action){
    LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setMinimumHeight(dp(76));row.setPadding(dp(13),dp(13),dp(13),dp(13));row.setBackground(Ui.ripple(this,Color.TRANSPARENT,16,theme.accent));
    ImageView mark=new ImageView(this);mark.setImageDrawable(Ui.icon(this,icon,theme.accent));row.addView(mark,new LinearLayout.LayoutParams(dp(24),dp(24)));
    LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);labels.setPadding(dp(15),0,dp(10),0);TextView name=Ui.text(this,title,17,theme.ink);name.setTypeface(Typeface.create("sans-serif-medium",0));labels.addView(name);TextView description=Ui.text(this,detail,12,theme.muted);description.setPadding(0,dp(4),0,0);description.setLineSpacing(dp(2),1);labels.addView(description);row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
    ImageView arrow=new ImageView(this);arrow.setImageDrawable(Ui.icon(this,R.drawable.ic_arrow_square_out,theme.muted));row.addView(arrow,new LinearLayout.LayoutParams(dp(18),dp(18)));row.setContentDescription(title+"，"+detail);row.setFocusable(true);row.setOnClickListener(v->{Ui.click(v);action.run();});group.addView(row,new LinearLayout.LayoutParams(-1,-2));
  }
  private void render(){
    content.removeAllViews();content.addView(status);status.setText(!runtime.ready?"正在准备本机运行时…":runtime.busy?"Agent 正在执行任务，离开页面仍会继续":runtime.configured?"本机运行时已就绪 · "+runtime.currentModel:"本机工具已就绪 · 对话前连接模型");
    LinearLayout local=section("本机工作");
    row(local,R.drawable.ic_terminal,"终端","直接输入命令，离开后保留会话",()->TerminalActivity.open(this));
    row(local,R.drawable.ic_file,"软件包","安装 Bash、Node.js、Python 等工具",()->startActivity(new Intent(this,RuntimePackageManagerActivity.class)));
    row(local,R.drawable.ic_screen_share,"图形应用","X11 显示、Linux 图形程序与可选 Wine",()->startActivity(new Intent(this,GraphicsRuntimeActivity.class)));
    row(local,R.drawable.ic_arrow_square_out,"虚拟屏","只读查看 Agent 在 Ubuntu 中启动的 Xvfb",()->startActivity(new Intent(this,VirtualScreenActivity.class)));
    LinearLayout extend=section("连接与扩展");
    row(extend,R.drawable.ic_arrows_out_simple,"连接 · MCP","添加远程服务、本机命令，导入配置",()->ConnectorActivity.open(this));
    row(extend,R.drawable.ic_file,"Skills","管理 Agent 的操作说明与项目技能",()->whenReady(()->SkillManager.show(content,runtime)));
    row(extend,R.drawable.ic_chat_circle_text,"模型与账号",runtime.configured?"当前 "+runtime.currentModel:"配置你自己的模型或登录供应商",()->openMain("open_models"));
    row(extend,R.drawable.ic_gear_six,"Agent 设置","名称、头像与系统提示",()->whenReady(()->AgentSettings.show(content,runtime)));
    LinearLayout phone=section("手机能力");
    row(phone,R.drawable.ic_screen_share,"读屏与操作",PhoneAccessibility.instance!=null?"已连接 · 可读取和操作其他应用":"未开启 · 前往系统设置",()->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
    row(phone,R.drawable.ic_arrows_out_simple,"系统小窗","进入系统弹出视图，恢复上次大小与位置",()->SystemWindowNavigation.openSmallWindow(this));
    row(phone,R.drawable.ic_microphone,"语音","分别选择识别与播报服务、音色和试听",()->openMain("open_speech"));
    LinearLayout advanced=section("高级");
    row(advanced,R.drawable.ic_gear_six,"启动设置","点击图标进入终端 / GUI，新终端的默认环境",()->StartupPreferences.show(this));
    row(advanced,R.drawable.ic_sidebar,"工具目录","查询工具能力、参数和命令",()->openMain("open_tools"));
    row(advanced,R.drawable.ic_gear_six,"Runtime Packs","管理可选运行环境",()->openMain("open_runtime_packs"));
  }
  private void whenReady(Runnable action){if(runtime.ready)action.run();else{pendingAction=action;status.setText("正在准备本机运行时，准备好后打开…");}}
  private void openMain(String extra){startActivity(new Intent(this,MainActivity.class).putExtra(extra,true).putExtra("return_workbench",true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP));}
  public void event(JSONObject event){String type=event.optString("type");if(type.equals("ready")||type.equals("configured")||type.equals("turn_start")||type.equals("turn_end")||type.equals("error"))render();if(type.equals("ready")&&pendingAction!=null&&active==this){Runnable next=pendingAction;pendingAction=null;next.run();}else if(type.equals("error")&&!runtime.ready){pendingAction=null;status.setText("运行时未能启动："+event.optString("message"));}}
  private int dp(float value){return Ui.dp(this,value);}
}
