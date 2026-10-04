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
  static void open(Context context){boolean overlay=FloatingNavigation.suspend(context);context.startActivity(new Intent(context,TerminalActivity.class).putExtra("from_overlay",overlay).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
  @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(intent.hasExtra("from_overlay"))fromOverlay=intent.getBooleanExtra("from_overlay",false);}
  private TerminalSessions sessions;private TerminalSessions.Job current;
  private TerminalView terminal;private TextView title,status,control;private boolean ctrl;
  private float fontSize=14;private LinearLayout root,setup,header,compactActions;private boolean compactChrome,imeVisible;private boolean installing;private Ui.Action setupAction;
  private static final int INK=0xffe5e9ed,MUTED=0xff95a1ad,BG=0xff0c1117,SURFACE=0xff17212c;

  @Override protected void onCreate(Bundle state){
    super.onCreate(state);fromOverlay=getIntent().getBooleanExtra("from_overlay",false);getWindow().setDecorFitsSystemWindows(false);
    getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);getWindow().getDecorView().setSystemUiVisibility(0);
    fontSize=getPreferences(MODE_PRIVATE).getFloat("fontSize",14);
    sessions=TerminalSessions.get(this);sessions.observe(this);build();
    try{attach(sessions.current());}catch(Exception error){status.setText(error.getMessage());}
  }
  private void build(){
    root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);
    root.setOnApplyWindowInsetsListener((view,insets)->{
      Insets bars=insets.getInsets(WindowInsets.Type.systemBars()),ime=insets.getInsets(WindowInsets.Type.ime());
      root.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,ime.bottom));imeVisible=insets.isVisible(WindowInsets.Type.ime());updateChrome();return insets;
    });
    LinearLayout head=new LinearLayout(this);header=head;head.setGravity(Gravity.CENTER_VERTICAL);head.setPadding(dp(8),dp(4),dp(8),0);
    head.addView(icon(R.drawable.ic_caret_down,"收起终端，保留会话",this::finish),new LinearLayout.LayoutParams(dp(48),dp(48)));
    LinearLayout labels=new LinearLayout(this);labels.setOrientation(LinearLayout.VERTICAL);
    title=Ui.text(this,"终端",18,INK);title.setTypeface(Typeface.create("sans-serif-medium",0));title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);labels.addView(title);
    status=Ui.text(this,"正在连接…",11,MUTED);status.setSingleLine(true);status.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);labels.addView(status);
    head.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
    head.addView(icon(R.drawable.ic_sidebar,"切换终端会话",this::showSessions),new LinearLayout.LayoutParams(dp(48),dp(48)));
    head.addView(icon(R.drawable.ic_dots_three,"终端选项",this::showOptions),new LinearLayout.LayoutParams(dp(48),dp(48)));root.addView(head);
    setup=new LinearLayout(this);setup.setGravity(Gravity.CENTER_VERTICAL);setup.setPadding(dp(16),dp(4),dp(12),dp(4));setup.setBackgroundColor(SURFACE);setup.setVisibility(View.GONE);
    TextView hint=Ui.text(this,getApplicationInfo().targetSdkVersion==28?"系统 Shell 已可使用\n安装 Bash，获得完整脚本环境":"系统 Shell · 受限运行时版本",12,MUTED);setup.addView(hint,new LinearLayout.LayoutParams(0,-2,1));
    setupAction=new Ui.Action(this,R.drawable.ic_arrow_up,"安装 Bash","安装 Bash 并打开新终端",INK,0xff284739,this::installBash);setup.addView(setupAction);root.addView(setup);
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
    keys.addView(key("↑","上一条命令或向上",()->sendKey(KeyEvent.KEYCODE_DPAD_UP)));
    keys.addView(key("↓","下一条命令或向下",()->sendKey(KeyEvent.KEYCODE_DPAD_DOWN)));
    keys.addView(key("←","光标向左",()->sendKey(KeyEvent.KEYCODE_DPAD_LEFT)));
    keys.addView(key("→","光标向右",()->sendKey(KeyEvent.KEYCODE_DPAD_RIGHT)));
    keys.addView(key("粘贴","粘贴到终端",()->{if(current!=null)paste(current);}));
    keys.addView(key("键盘","显示或隐藏键盘",this::toggleKeyboard));
    compactActions=new LinearLayout(this);compactActions.setVisibility(View.GONE);compactActions.addView(key("会话","切换终端会话",this::showSessions));compactActions.addView(key("选项","终端选项",this::showOptions));compactActions.addView(key("收起","收起终端，保留会话",this::finish));keys.addView(compactActions);
    rail.addView(keys);root.addView(rail,new LinearLayout.LayoutParams(-1,dp(48)));
    root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->updateChrome());setContentView(root);
  }
  private void updateChrome(){if(header==null||compactActions==null||root.getHeight()==0)return;boolean compact=imeVisible&&root.getHeight()-root.getPaddingTop()-root.getPaddingBottom()<dp(220);if(compact!=compactChrome){compactChrome=compact;header.setVisibility(compact?View.GONE:View.VISIBLE);compactActions.setVisibility(compact?View.VISIBLE:View.GONE);}if(setup!=null)setup.setVisibility(current!=null&&current.shell.equals("系统 Shell")&&!compactChrome?View.VISIBLE:View.GONE);}
  private View icon(int resource,String label,Runnable action){return new Ui.Action(this,resource,"",label,INK,Color.TRANSPARENT,action);}
  private TextView key(String label,String description,Runnable action){
    TextView value=Ui.text(this,label,13,INK);value.setGravity(Gravity.CENTER);value.setContentDescription(description);value.setFocusable(true);value.setMinimumWidth(dp(48));value.setMinimumHeight(dp(48));
    value.setPadding(dp(12),0,dp(12),0);value.setBackground(Ui.ripple(this,Color.TRANSPARENT,10,INK));
    value.setOnClickListener(v->{Ui.click(v);action.run();});return value;
  }
  private void attach(TerminalSessions.Job job){current=job;sessions.select(job);terminal.attachSession(job.session);terminal.requestFocus();changed(job);}
  private void newSession(){try{attach(sessions.create());}catch(Exception error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();}}
  private void showSessions(){
    java.util.ArrayList<TerminalSessions.Job> entries=new java.util.ArrayList<>(sessions.jobs.values());
    String[] labels=new String[entries.size()+1];
    for(int i=0;i<entries.size();i++){TerminalSessions.Job job=entries.get(i);labels[i]=(job==current?"✓ ":"")+job.name+" · "+(job.session.isRunning()?job.shell.isEmpty()?"准备中":job.shell:"已结束");}
    labels[labels.length-1]="＋ 新建终端";
    new AlertDialog.Builder(this).setTitle("终端会话").setItems(labels,(dialog,which)->{if(which==entries.size())newSession();else attach(entries.get(which));}).setNegativeButton("返回",null).show();
  }
  private void showOptions(){
    String[] items={"显示键盘","复制当前终端记录","放大文字","缩小文字","安装 / 更新 Bash","结束当前会话及进程"};
    new AlertDialog.Builder(this).setTitle("终端选项").setItems(items,(dialog,which)->{
      switch(which){
        case 0:showKeyboard();break;
        case 1:if(current!=null&&current.session.getEmulator()!=null){sessions.onCopyTextToClipboard(current.session,current.session.getEmulator().getScreen().getTranscriptText());Toast.makeText(this,"已复制终端记录",Toast.LENGTH_SHORT).show();}break;
        case 2:changeFont(1);break;
        case 3:changeFont(-1);break;
        case 4:installBash();break;
        case 5:if(current!=null)current.close();break;
      }
    }).setNegativeButton("返回",null).show();
  }
  private void installBash(){
    if(installing)return;
    if(getApplicationInfo().targetSdkVersion!=28){new AlertDialog.Builder(this).setTitle("当前是受限运行时").setMessage("安装的本地程序需要 compat28 运行时版本。当前仍可使用系统 Shell。").setPositiveButton("知道了",null).show();return;}
    installing=true;setupAction.setEnabled(false);status.setText("正在安装 Bash…");
    new Thread(()->{
      try{PiPackageManager.install(this,"bash",message->runOnUiThread(()->{if(!isFinishing()&&installing)status.setText(message);}));runOnUiThread(()->{installing=false;setupAction.setEnabled(true);if(isFinishing())return;Toast.makeText(this,"Bash 已就绪，正在打开新会话",Toast.LENGTH_SHORT).show();newSession();});}
      catch(Exception error){runOnUiThread(()->{installing=false;setupAction.setEnabled(true);if(!isFinishing())new AlertDialog.Builder(this).setTitle("Bash 安装未完成").setMessage(error.getMessage()).setPositiveButton("重试",(d,w)->installBash()).setNegativeButton("返回",null).show();});}
    },"terminal-install-bash").start();
  }
  private void sendKey(int code){terminal.onKeyDown(code,new KeyEvent(0,0,KeyEvent.ACTION_DOWN,code,0,ctrl?KeyEvent.META_CTRL_ON:0));clearCtrl();}
  private void clearCtrl(){ctrl=false;updateControl();}
  private void updateControl(){control.setTextColor(ctrl?0xffa5e1b4:INK);control.setBackground(Ui.ripple(this,ctrl?0xff294437:Color.TRANSPARENT,10,INK));control.setSelected(ctrl);}
  private void showKeyboard(){terminal.requestFocus();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(terminal,InputMethodManager.SHOW_IMPLICIT);}
  private void toggleKeyboard(){if(root.getRootWindowInsets()!=null&&root.getRootWindowInsets().isVisible(WindowInsets.Type.ime()))((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(terminal.getWindowToken(),0);else showKeyboard();}
  private void changeFont(float delta){fontSize=Math.max(10,Math.min(24,fontSize+delta));terminal.setTextSize(sp(fontSize));getPreferences(MODE_PRIVATE).edit().putFloat("fontSize",fontSize).apply();}
  private int dp(float value){return Ui.dp(this,value);}private int sp(float value){return Math.round(value*getResources().getDisplayMetrics().scaledDensity);}

  @Override public void changed(TerminalSessions.Job job){if(isFinishing()||job!=current)return;title.setText(job.session.getTitle()==null||job.session.getTitle().isBlank()?job.name:job.session.getTitle());status.setText(installing?"正在安装 Bash…":job.status+(job.cwd.isEmpty()?"":" · "+job.cwd.replace(PiRuntime.agentRoot(this).getPath(),"项目")));updateChrome();terminal.onScreenUpdated();}
  @Override public void paste(TerminalSessions.Job job){ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(clipboard.hasPrimaryClip()&&clipboard.getPrimaryClip().getItemCount()>0&&job.session.getEmulator()!=null)job.session.getEmulator().paste(clipboard.getPrimaryClip().getItemAt(0).coerceToText(this).toString());}
  @Override protected void onResume(){super.onResume();if(sessions!=null)sessions.observe(this);if(terminal!=null)terminal.setTerminalCursorBlinkerState(true,true);}
  @Override protected void onPause(){if(terminal!=null)terminal.setTerminalCursorBlinkerState(false,false);super.onPause();}
  @Override protected void onDestroy(){if(sessions!=null)sessions.detach(this);if(isFinishing()&&fromOverlay)FloatingNavigation.restore(getApplicationContext());super.onDestroy();}
  @Override public void onBackPressed(){finish();}
  public float onScale(float scale){if(scale>1.1f){changeFont(1);return 1;}if(scale<.9f){changeFont(-1);return 1;}return scale;}
  public void onSingleTapUp(MotionEvent event){showKeyboard();}
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
