package dev.zfold.assistant;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.os.*;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.provider.Settings;
import com.termux.view.TerminalView;
import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

/** Fresh install, actual selection/clipboard, background lifetime, orientation and session controls. */
public final class TerminalAcceptanceInstrumentation extends Instrumentation implements Application.ActivityLifecycleCallbacks {
  private volatile TerminalActivity activity;private TerminalView view;private TerminalSessions.Job job;private boolean fresh;
  private ClipData clipboard;private float scale=1;private String error="";
  @Override public void onCreate(Bundle args){PiRuntime.testMode=true;fresh=Boolean.parseBoolean(args.getString("fresh","false"));start();}
  @Override public void onActivityCreated(Activity value,Bundle state){if(value instanceof TerminalActivity)activity=(TerminalActivity)value;}
  @Override public void onActivityResumed(Activity value){if(value instanceof TerminalActivity)activity=(TerminalActivity)value;}
  @Override public void onActivityDestroyed(Activity value){if(value==activity)activity=null;}
  public void onActivityStarted(Activity value){}public void onActivityPaused(Activity value){}public void onActivityStopped(Activity value){}public void onActivitySaveInstanceState(Activity value,Bundle state){}
  @Override public void onStart(){Bundle report=new Bundle();Application app=(Application)getTargetContext().getApplicationContext();app.registerActivityLifecycleCallbacks(this);
    try{
      require(Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"),"Emulator only");getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);EmulatorChecks.cancelPointer(this);
      scale=Settings.System.getFloat(getTargetContext().getContentResolver(),Settings.System.FONT_SCALE,1);
      File bash=new File(PiPackageManager.root(getTargetContext()),"usr/bin/bash");if(fresh)require(!bash.exists(),"Fresh acceptance must start without Bash");
      launch();waitView();job=current();
      runOnMainSync(()->{
        TerminalSessions sessions=TerminalSessions.get(getTargetContext());
        TerminalSessions.Job interactive=sessions.current();
        TerminalSessions.Job command=sessions.createCommand(new String[]{"/system/bin/sh"},"shortcut boundary");
        try{
          require(sessions.interactive(interactive.ubuntu)==interactive,"Shortcut selected a command job instead of the live interactive shell");
          int count=sessions.jobs.size();
          require(sessions.interactive(interactive.ubuntu)==interactive&&sessions.jobs.size()==count,"Repeated shortcut created a duplicate session");
        }finally{command.close();}
      });
      report.putBoolean("interactiveShortcutSelection",true);
      if(fresh){require(job.shell.equals("系统 Shell"),"Fresh install did not fall back to system shell");send("printf 'FRESH_%s\\n' SYSTEM\n");waitFor("FRESH_SYSTEM");report.putBoolean("systemShellFirstLaunch",true);
        int systemId=job.id;click("安装 Bash 并打开新终端");long until=System.currentTimeMillis()+600000;
        while(System.currentTimeMillis()<until){TerminalSessions.Job candidate=current();if(candidate!=null&&candidate.id>0&&candidate.id!=systemId&&candidate.shell.equals("Bash")){job=candidate;break;}Thread.sleep(250);}
        require(job.id!=systemId&&job.shell.equals("Bash"),"Bash install did not open a new shell");report.putBoolean("installBashFromUI",true);
      }
      send("bash\n");Thread.sleep(250);send("printf 'BARE_%s\\n' BASH; exit\n");waitFor("BARE_BASH");require(!transcript().toLowerCase().contains("permission denied"),"Bare Bash still has a startup permission error");report.putBoolean("bareBash",true);
      int id=job.id;send("sleep 3; printf 'BACKGROUND_%s\\n' OK\n");getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);Thread.sleep(3800);launch();waitView();require(current().id==id,"Home/return replaced the live terminal");waitFor("BACKGROUND_OK");report.putBoolean("ordinaryBackground",true);
      send("printf '\\033[3J\\033[2J\\033[HSELECT_ME_42\\n'\n");waitFor("SELECT_ME_42");
      for(int i=0;i<100;i++){AtomicReference<String> row=new AtomicReference<>("");runOnMainSync(()->row.set(job.session.getEmulator().getScreen().getSelectedText(0,0,job.session.getEmulator().mColumns-1,0).trim()));if(row.get().equals("SELECT_ME_42"))break;Thread.sleep(100);if(i==99)throw new IllegalStateException("Selection target did not reach first screen row");}
      Thread.sleep(500);
      runOnMainSync(()->clipboard=((ClipboardManager)getTargetContext().getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip());
      int[] location=new int[2];runOnMainSync(()->view.getLocationOnScreen(location));float x=location[0]+view.getWidth()*4f/job.session.getEmulator().mColumns,y=location[1]+view.getHeight()*.5f/job.session.getEmulator().mRows;
      long down=SystemClock.uptimeMillis();touch(MotionEvent.ACTION_DOWN,x,y,down);Thread.sleep(ViewConfiguration.getLongPressTimeout()+400);boolean[] selecting={false};runOnMainSync(()->selecting[0]=view.isSelectingText());require(selecting[0],"Long press did not begin terminal selection at "+x+","+y);touch(MotionEvent.ACTION_UP,x,y,down);click("Copy");
      AtomicReference<String> copied=new AtomicReference<>("");runOnMainSync(()->{ClipData clip=((ClipboardManager)getTargetContext().getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip();if(clip!=null)copied.set(clip.getItemAt(0).coerceToText(getTargetContext()).toString());});require(copied.get().contains("SELECT_ME_42"),"Selection did not copy the touched terminal word");report.putBoolean("selectionCopy",true);
      int columns=job.session.getEmulator().mColumns;runOnMainSync(()->activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));waitColumns(columns,true);require(current().id==id,"Rotation replaced the PTY");send("printf 'ROTATE_%s\\n' OK\n");waitFor("ROTATE_OK");Thread.sleep(3000);Rect visible=new Rect();boolean[] readable={false};runOnMainSync(()->readable[0]=view.getGlobalVisibleRect(visible)&&visible.height()>=Ui.dp(getTargetContext(),30));require(readable[0],"Landscape keyboard left no readable terminal viewport");require((job.session.getEmulator().getCursorRow()+1)*view.mRenderer.getFontLineSpacing()<=view.getHeight(),"Shell cursor fell below visible landscape viewport");EmulatorChecks.screenshot(this,"terminal-landscape.png");report.putBoolean("rotation",true);
      runOnMainSync(()->activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));waitColumns(columns,false);int before=job.session.getEmulator().mColumns;
      EmulatorChecks.shell(this,"settings put system font_scale 1.3");Thread.sleep(800);waitView();job=current();for(int i=0;i<60&&job.session.getEmulator().mColumns>=before;i++)Thread.sleep(100);require(job.id==id&&job.session.getEmulator().mColumns<before,"Large font did not resize the same terminal");EmulatorChecks.screenshot(this,"terminal-large-font.png");report.putBoolean("largeFont",true);
      click("切换终端会话");click("＋ 新建终端");TerminalSessions.Job original=job;for(int i=0;i<60&&current().id==id;i++)Thread.sleep(100);job=current();require(job.id!=id,"Session picker did not create a terminal");TerminalSessions.Job additional=job;
      click("切换终端会话");click(original.name+" · Bash");job=current();require(job.id==id,"Session picker did not reattach the selected PTY");
      click("终端选项");click("结束当前会话及进程");for(int i=0;i<60&&job.session.isRunning();i++)Thread.sleep(100);require(!job.session.isRunning()&&additional.session.isRunning(),"Explicit close did not isolate the chosen session");report.putBoolean("sessionPickerAndClose",true);
    }catch(Throwable failure){error=failure.toString();report.putString("error",error);try{EmulatorChecks.screenshot(this,"terminal-acceptance-failure.png");}catch(Exception ignored){}}
    finally{EmulatorChecks.cancelPointer(this);try{EmulatorChecks.shell(this,"settings put system font_scale "+scale);}catch(Exception ignored){}runOnMainSync(()->{ClipboardManager manager=(ClipboardManager)getTargetContext().getSystemService(Context.CLIPBOARD_SERVICE);if(clipboard!=null)manager.setPrimaryClip(clipboard);TerminalSessions.get(getTargetContext()).closeAll();if(activity!=null)activity.finishAndRemoveTask();});app.unregisterActivityLifecycleCallbacks(this);}
    finish(error.isEmpty()?-1:0,report);
  }
  private void launch(){getTargetContext().startActivity(new Intent(getTargetContext(),TerminalActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
  private void waitView()throws Exception{for(int i=0;i<150;i++){runOnMainSync(()->{if(activity!=null)view=find(activity.getWindow().getDecorView());});if(activity!=null&&view!=null&&view.isAttachedToWindow()&&view.isLaidOut()&&current()!=null&&current().id>0)return;Thread.sleep(100);}throw new IllegalStateException("Terminal not ready");}
  private TerminalSessions.Job current(){AtomicReference<TerminalSessions.Job> result=new AtomicReference<>();runOnMainSync(()->{if(view!=null&&view.getCurrentSession()!=null)result.set(TerminalSessions.get(getTargetContext()).find(view.getCurrentSession()));});return result.get();}
  private String transcript(){AtomicReference<String> value=new AtomicReference<>("");runOnMainSync(()->{if(job!=null&&job.session.getEmulator()!=null)value.set(job.session.getEmulator().getScreen().getTranscriptText());});return value.get();}
  private void waitFor(String text)throws Exception{for(int i=0;i<100;i++){if(transcript().contains(text))return;Thread.sleep(100);}throw new IllegalStateException("Terminal output missing: "+text);}
  private void waitColumns(int portrait,boolean landscape)throws Exception{for(int i=0;i<100;i++){waitView();int value=job.session.getEmulator().mColumns;if(landscape?value>portrait:value==portrait)return;Thread.sleep(100);}throw new IllegalStateException("Terminal dimensions did not follow orientation");}
  private void send(String text){runOnMainSync(()->view.onCreateInputConnection(new EditorInfo()).commitText(text,1));}
  private void touch(int action,float x,float y,long down){MotionEvent.PointerProperties pointer=new MotionEvent.PointerProperties();pointer.id=0;pointer.toolType=MotionEvent.TOOL_TYPE_FINGER;MotionEvent.PointerCoords coordinates=new MotionEvent.PointerCoords();coordinates.x=x;coordinates.y=y;coordinates.pressure=1;coordinates.size=1;MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,1,new MotionEvent.PointerProperties[]{pointer},new MotionEvent.PointerCoords[]{coordinates},0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);require(getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).injectInputEvent(event,true),"Touch injection failed");event.recycle();}
  private void click(String text)throws Exception{EmulatorChecks.click(this,text);}
  private static TerminalView find(View root){if(root instanceof TerminalView)return (TerminalView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TerminalView found=find(((ViewGroup)root).getChildAt(i));if(found!=null)return found;}return null;}
  private static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
}
