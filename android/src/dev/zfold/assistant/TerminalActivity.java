package dev.zfold.assistant;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import com.termux.terminal.TerminalSession;
import com.termux.view.*;

/** Real terminal surface: back detaches; ending a process is a named action. */
public final class TerminalActivity extends Activity implements TerminalViewClient,TerminalSessions.Listener {
  private boolean fromOverlay;
  static void open(Context context){boolean overlay=FloatingNavigation.suspend(context);SystemWindowNavigation.open(context,TerminalActivity.class,overlay);}
  @Override protected void onNewIntent(Intent intent){
    super.onNewIntent(intent);setIntent(intent);
    if(intent.hasExtra("from_overlay"))fromOverlay=intent.getBooleanExtra("from_overlay",false);
    if(isEnvironmentEntry())openRequestedSession();
  }
  private TerminalSessions sessions;private TerminalSessions.Job current;
  private TerminalView terminal;private TextView control,keyboard,installStatus;private boolean ctrl,tapToShowKeyboard;
  private TerminalTabs tabs;
  private float fontSize=14;private LinearLayout root;private boolean installing;
  private static final int INK=0xffe5e9ed,MUTED=0xff95a1ad,BG=0xff0c1117,SURFACE=0xff17212c;

  @Override protected void onCreate(Bundle state){
    super.onCreate(state);fromOverlay=getIntent().getBooleanExtra("from_overlay",false);getWindow().setDecorFitsSystemWindows(false);
    getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
    WindowManager.LayoutParams attributes=getWindow().getAttributes();attributes.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;getWindow().setAttributes(attributes);
    fontSize=getPreferences(MODE_PRIVATE).getFloat("fontSize",14);
    tapToShowKeyboard=getPreferences(MODE_PRIVATE).getBoolean("tapToShowKeyboard",false);
    sessions=TerminalSessions.get(this);sessions.observe(this);build();
    TerminalSessions.Job restored=state==null?null:sessions.jobs.get(state.getString("terminal_session"));
    if(restored!=null)attach(restored);else openRequestedSession();
  }
  private boolean isEnvironmentEntry(){
    String action=getIntent().getAction();
    return LauncherActivity.ACTION_UBUNTU_TERMINAL.equals(action)||LauncherActivity.ACTION_ANDROID_TERMINAL.equals(action);
  }
  private void openRequestedSession(){
    try{
      attach(isEnvironmentEntry()
        ?sessions.interactive(LauncherActivity.ACTION_UBUNTU_TERMINAL.equals(getIntent().getAction()))
        :sessions.current());
    }catch(Exception error){showStartError(error);}
  }
  @Override protected void onSaveInstanceState(Bundle state){
    super.onSaveInstanceState(state);
    if(current!=null)state.putString("terminal_session",current.session.mHandle);
  }
  private void build(){
    root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);
    root.setOnApplyWindowInsetsListener((view,insets)->{
      Insets safe=insets.getInsets(WindowInsets.Type.displayCutout()),ime=insets.getInsets(WindowInsets.Type.ime());
      // Fill the top edge, including the camera area; transient system bars overlay rather than resize.
      root.setPadding(safe.left,0,safe.right,Math.max(safe.bottom,ime.bottom));return insets;
    });
    tabs=new TerminalTabs(this,sessions,this::attach,this::showTabOptions,this::newSession,this::showOptions);root.addView(tabs);
    installStatus=Ui.text(this,"",12,MUTED);installStatus.setPadding(dp(16),dp(8),dp(16),dp(8));installStatus.setBackgroundColor(SURFACE);installStatus.setVisibility(View.GONE);root.addView(installStatus);
    terminal=new TerminalView(this,null);terminal.setTerminalViewClient(this);terminal.setTextSize(sp(fontSize));terminal.setTypeface(Typeface.MONOSPACE);
    terminal.setFocusable(true);terminal.setFocusableInTouchMode(true);
    terminal.setBackgroundColor(BG);terminal.setVerticalScrollBarEnabled(true);terminal.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
    FrameLayout terminalHost=new FrameLayout(this);terminalHost.setPadding(dp(12),dp(8),dp(12),dp(4));terminalHost.addView(terminal,new FrameLayout.LayoutParams(-1,-1));
    root.addView(terminalHost,new LinearLayout.LayoutParams(-1,0,1));
    HorizontalScrollView rail=new HorizontalScrollView(this);rail.setHorizontalScrollBarEnabled(false);rail.setBackgroundColor(SURFACE);
    LinearLayout keys=new LinearLayout(this);keys.setGravity(Gravity.CENTER_VERTICAL);keys.setPadding(dp(4),0,dp(4),0);
    control=key("Ctrl","切换 Ctrl 修饰键",()->{ctrl=!ctrl;updateControl();});keys.addView(control);
    keys.addView(key("⌃C","中断当前命令",()->{if(current!=null)current.session.writeCodePoint(false,3);clearCtrl();}));
    keys.addView(key("Esc","发送 Escape",()->sendKey(KeyEvent.KEYCODE_ESCAPE)));
    keys.addView(key("Tab","Tab 补全",()->sendKey(KeyEvent.KEYCODE_TAB)));
    keys.addView(key("回车","回车 / 执行当前输入",()->sendKey(KeyEvent.KEYCODE_ENTER)));
    keys.addView(key("↑","上一条命令或向上",()->sendKey(KeyEvent.KEYCODE_DPAD_UP)));
    keys.addView(key("↓","下一条命令或向下",()->sendKey(KeyEvent.KEYCODE_DPAD_DOWN)));
    keys.addView(key("←","光标向左",()->sendKey(KeyEvent.KEYCODE_DPAD_LEFT)));
    keys.addView(key("→","光标向右",()->sendKey(KeyEvent.KEYCODE_DPAD_RIGHT)));
    keys.addView(key("粘贴","粘贴到终端",()->{if(current!=null)paste(current);}));
    keyboard=key("键盘","点击终端弹出键盘",this::toggleKeyboardMode);updateKeyboardMode();keys.addView(keyboard);
    rail.addView(keys);root.addView(rail,new LinearLayout.LayoutParams(-1,dp(48)));
    setContentView(root);
  }
  private void enterFullscreen(){
    WindowInsetsController controller=getWindow().getInsetsController();
    if(controller==null)return;
    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    controller.setSystemBarsAppearance(0,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
    controller.hide(WindowInsets.Type.systemBars());
  }
  private TextView key(String label,String description,Runnable action){
    TextView value=Ui.text(this,label,13,INK);value.setGravity(Gravity.CENTER);value.setContentDescription(description);value.setFocusable(true);value.setMinimumWidth(dp(48));value.setMinimumHeight(dp(48));
    value.setPadding(dp(12),0,dp(12),0);value.setBackground(Ui.ripple(this,Color.TRANSPARENT,10,INK));
    value.setOnClickListener(v->{Ui.click(v);action.run();});return value;
  }
  private void attach(TerminalSessions.Job job){current=job;sessions.select(job);terminal.attachSession(job.session);terminal.requestFocus();tabs.sync(current);changed(job);}
  private void newSession(){try{attach(sessions.create());}catch(Exception error){showStartError(error);}}
  private void showStartError(Exception error){
    new AlertDialog.Builder(this).setTitle("无法启动新终端").setMessage(error.getMessage()).setPositiveButton("启动设置",(dialog,which)->StartupPreferences.show(this)).setNegativeButton("返回",null).show();
  }
  private void showTabOptions(TerminalSessions.Job job){
    if(sessions.find(job.session)!=job)return;
    new AlertDialog.Builder(this).setTitle(job.name).setItems(new String[]{"重命名","关闭会话及进程"},(dialog,which)->{
      if(which==0)renameSession(job);else job.close();
    }).setNegativeButton("返回",null).show();
  }
  private void renameSession(TerminalSessions.Job job){
    EditText name=new EditText(this);name.setSingleLine(true);name.setText(job.name);name.setSelectAllOnFocus(true);name.setContentDescription("会话名称");
    LinearLayout body=new LinearLayout(this);body.setPadding(dp(20),0,dp(20),0);body.addView(name,new LinearLayout.LayoutParams(-1,-2));
    AlertDialog dialog=new AlertDialog.Builder(this).setTitle("重命名会话").setView(body).setPositiveButton("保存",null).setNegativeButton("取消",null).create();dialog.show();
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String value=name.getText().toString().trim();if(value.isEmpty()||value.codePointCount(0,value.length())>80){name.setError("请输入 1–80 个字符");return;}sessions.rename(job,value);dialog.dismiss();});
    name.requestFocus();dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
  }
  private void showOptions(){
    String[] items={tapToShowKeyboard?"关闭点击弹键盘":"开启点击弹键盘","复制当前终端记录","放大文字","缩小文字","安装 / 更新 Bash","结束当前会话及进程","收起终端，保留会话","启动设置","打开 GUI 聊天"};
    new AlertDialog.Builder(this).setTitle(current==null?"终端选项":current.name+" · "+current.status+(current.cwd.isEmpty()?"":"\n"+current.cwd)).setItems(items,(dialog,which)->{
      switch(which){
        case 0:dialog.dismiss();terminal.post(this::toggleKeyboardMode);break;
        case 1:if(current!=null&&current.session.getEmulator()!=null){sessions.onCopyTextToClipboard(current.session,current.session.getEmulator().getScreen().getTranscriptText());Toast.makeText(this,"已复制终端记录",Toast.LENGTH_SHORT).show();}break;
        case 2:changeFont(1);break;
        case 3:changeFont(-1);break;
        case 4:installBash();break;
        case 5:if(current!=null)current.close();break;
        case 6:finish();break;
        case 7:StartupPreferences.show(this);break;
        case 8:startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));break;
      }
    }).setNegativeButton("返回",null).show();
  }
  private void installBash(){
    if(installing)return;
    if(getApplicationInfo().targetSdkVersion!=28){new AlertDialog.Builder(this).setTitle("当前是受限运行时").setMessage("安装的本地程序需要 compat28 运行时版本。当前仍可使用系统 Shell。").setPositiveButton("知道了",null).show();return;}
    installing=true;installStatus.setText("正在安装 Bash…");installStatus.setVisibility(View.VISIBLE);
    new Thread(()->{
      try{PiPackageManager.install(this,"bash",message->runOnUiThread(()->{if(!isFinishing()&&installing)installStatus.setText(message);}));runOnUiThread(()->{installing=false;installStatus.setVisibility(View.GONE);if(isFinishing())return;Toast.makeText(this,"Bash 已就绪，正在打开新会话",Toast.LENGTH_SHORT).show();newSession();});}
      catch(Exception error){runOnUiThread(()->{installing=false;installStatus.setVisibility(View.GONE);if(!isFinishing())new AlertDialog.Builder(this).setTitle("Bash 安装未完成").setMessage(error.getMessage()).setPositiveButton("重试",(d,w)->installBash()).setNegativeButton("返回",null).show();});}
    },"terminal-install-bash").start();
  }
  private void sendKey(int code){terminal.onKeyDown(code,new KeyEvent(0,0,KeyEvent.ACTION_DOWN,code,0,ctrl?KeyEvent.META_CTRL_ON:0));clearCtrl();}
  private void clearCtrl(){ctrl=false;updateControl();}
  private void updateControl(){control.setTextColor(ctrl?0xffa5e1b4:INK);control.setBackground(Ui.ripple(this,ctrl?0xff294437:Color.TRANSPARENT,10,INK));control.setSelected(ctrl);}
  private void showKeyboard(){terminal.requestFocus();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(terminal,InputMethodManager.SHOW_IMPLICIT);}
  private void toggleKeyboardMode(){
    tapToShowKeyboard=!tapToShowKeyboard;getPreferences(MODE_PRIVATE).edit().putBoolean("tapToShowKeyboard",tapToShowKeyboard).apply();updateKeyboardMode();
    if(tapToShowKeyboard)showKeyboard();else ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(terminal.getWindowToken(),0);
  }
  private void updateKeyboardMode(){
    keyboard.setSelected(tapToShowKeyboard);keyboard.setStateDescription(tapToShowKeyboard?"已开启":"已关闭");
    keyboard.setTextColor(tapToShowKeyboard?0xffa5e1b4:INK);keyboard.setBackground(Ui.ripple(this,tapToShowKeyboard?0xff294437:Color.TRANSPARENT,10,INK));
  }
  private void changeFont(float delta){fontSize=Math.max(10,Math.min(24,fontSize+delta));terminal.setTextSize(sp(fontSize));getPreferences(MODE_PRIVATE).edit().putFloat("fontSize",fontSize).apply();}
  private int dp(float value){return Ui.dp(this,value);}private int sp(float value){return Math.round(value*getResources().getDisplayMetrics().scaledDensity);}

  @Override public void changed(TerminalSessions.Job job){if(isFinishing())return;tabs.update(job,current);if(job==current)terminal.onScreenUpdated();}
  @Override public void removed(TerminalSessions.Job job,TerminalSessions.Job selected){
    if(isFinishing())return;
    if(!job.session.isRunning()&&job.session.getExitStatus()!=0)Toast.makeText(this,job.name+" · "+job.status,Toast.LENGTH_LONG).show();
    if(job==current){current=null;if(selected==null){tabs.sync(null);finish();}else attach(selected);}
    else tabs.sync(current);
  }
  @Override public void paste(TerminalSessions.Job job){ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(clipboard.hasPrimaryClip()&&clipboard.getPrimaryClip().getItemCount()>0&&job.session.getEmulator()!=null)job.session.getEmulator().paste(clipboard.getPrimaryClip().getItemAt(0).coerceToText(this).toString());}
  @Override protected void onResume(){super.onResume();if(!isEnvironmentEntry())StartupPreferences.rememberPage(this,StartupPreferences.TERMINAL);enterFullscreen();if(sessions!=null)sessions.observe(this);if(tabs!=null)tabs.sync(current);if(terminal!=null)terminal.setTerminalCursorBlinkerState(true,true);}
  @Override protected void onPause(){SystemWindowNavigation.remember(this);if(terminal!=null)terminal.setTerminalCursorBlinkerState(false,false);super.onPause();}
  @Override protected void onDestroy(){if(sessions!=null)sessions.detach(this);if(isFinishing()&&fromOverlay)FloatingNavigation.restore(getApplicationContext());super.onDestroy();}
  @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused)enterFullscreen();}
  @Override public void onBackPressed(){finish();}
  public float onScale(float scale){if(scale>1.1f){changeFont(1);return 1;}if(scale<.9f){changeFont(-1);return 1;}return scale;}
  public void onSingleTapUp(MotionEvent event){
    if(tapToShowKeyboard)showKeyboard();
  }
  public void onOpenLink(String target){
    try{OpenTargets.open(this,TerminalLinkTargets.androidTarget(target,ArtifactProvider.root(this).getPath()),"",false);}
    catch(Exception error){Toast.makeText(this,"无法打开："+error.getMessage(),Toast.LENGTH_LONG).show();}
  }
  public void onOpenImage(android.graphics.Bitmap bitmap){ChatImages.openTerminalImage(this,bitmap);}
  public boolean shouldBackButtonBeMappedToEscape(){return false;}
  public boolean shouldEnforceCharBasedInput(){return false;}
  public boolean shouldUseCtrlSpaceWorkaround(){return true;}
  public boolean isTerminalViewSelected(){return true;}
  public void copyModeChanged(boolean copying){}
  public boolean onKeyDown(int keyCode,KeyEvent event,TerminalSession session){return false;}
  public boolean onKeyUp(int keyCode,KeyEvent event){return super.onKeyUp(keyCode,event);}
  public boolean onLongPress(MotionEvent event){return false;}
  public boolean readControlKey(){return ctrl;}
  public boolean readAltKey(){return false;}
  public boolean readShiftKey(){return false;}
  public boolean readFnKey(){return false;}
  public boolean onCodePoint(int codePoint,boolean ctrlDown,TerminalSession session){if(ctrl)terminal.post(this::clearCtrl);return false;}
  public void onEmulatorSet(){}
  public void logError(String tag,String message){android.util.Log.e(tag,message);}
  public void logWarn(String tag,String message){android.util.Log.w(tag,message);}
  public void logInfo(String tag,String message){android.util.Log.i(tag,message);}
  public void logDebug(String tag,String message){android.util.Log.d(tag,message);}
  public void logVerbose(String tag,String message){}
  public void logStackTraceWithMessage(String tag,String message,Exception e){android.util.Log.w(tag,message,e);}
  public void logStackTrace(String tag,Exception e){android.util.Log.w(tag,"Terminal",e);}
}
