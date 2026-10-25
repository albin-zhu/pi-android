package dev.zfold.assistant;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.*;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import com.termux.view.TerminalView;
import java.io.*;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;

/** Real PTY output and renderer viewport: repaint, new history, bottom follow. Emulator only. */
public final class TerminalScrollInstrumentation extends Instrumentation {
  private TerminalActivity activity;private TerminalView view;private TerminalSessions.Job job;private File fixture;
  private Bundle launchOptions;
  @Override public void onCreate(Bundle args){PiRuntime.testMode=true;launchOptions=TerminalWorkspaceInstrumentation.launchOptions(args);start();}
  @Override public void onStart(){
    Bundle report=new Bundle();String failure="";
    try{
      check(Build.HARDWARE.contains("ranchu")||Build.HARDWARE.contains("goldfish"),"Emulator only");
      runOnMainSync(()->job=TerminalSessions.get(getTargetContext()).create());
      activity=(TerminalActivity)startActivitySync(new Intent(getTargetContext(),TerminalActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),launchOptions);
      runOnMainSync(()->view=find(activity.getWindow().getDecorView()));
      for(int i=0;i<100;i++){runOnMainSync(()->job=TerminalSessions.get(getTargetContext()).find(view.getCurrentSession()));if(job!=null&&job.id>0)break;Thread.sleep(100);}
      check(job!=null&&job.id>0,"Missing real shell");
      fixture=Files.createTempDirectory(getTargetContext().getCacheDir().toPath(),"terminal-scroll-").toFile();
      File script=new File(fixture,"output.sh");
      Files.writeString(script.toPath(),"#!/system/bin/sh\ni=0; while [ $i -lt 100 ]; do printf 'HISTORY_%03d\\n' $i; i=$((i+1)); done\nprintf 'SCROLL_READY\\n'\nwhile [ ! -f '"+fixture+"/paint' ]; do sleep 0.05; done\nprintf '\\033[?2026h\\033[s\\033[1;1H\\033[2KPAINTED\\033[u\\033[?2026l'\nwhile [ ! -f '"+fixture+"/append' ]; do sleep 0.05; done\nprintf 'NEW_HISTORY\\n'\nwhile [ ! -f '"+fixture+"/bottom' ]; do sleep 0.05; done\nprintf 'FOLLOW_BOTTOM\\n'\n");
      runOnMainSync(()->view.onCreateInputConnection(new EditorInfo()).commitText("/system/bin/sh '"+script+"'\n",1));
      waitText("SCROLL_READY");
      runOnMainSync(()->{for(int i=0;i<4;i++)wheel(true);});
      check(top()<0,"Wheel did not enter history");String anchor=firstRow();
      Files.writeString(new File(fixture,"paint").toPath(),"go");waitText("PAINTED");
      check(top()<0&&firstRow().equals(anchor),"TUI repaint forced history viewport to bottom");
      report.putBoolean("historySurvivesTuiRepaint",true);
      Files.writeString(new File(fixture,"append").toPath(),"go");waitText("NEW_HISTORY");
      check(top()<0&&firstRow().equals(anchor),"New output changed the visible history anchor");
      report.putBoolean("historyAnchorSurvivesNewOutput",true);
      runOnMainSync(()->{for(int i=0;i<100;i++)wheel(false);});check(top()==0,"Cannot return to bottom");
      Files.writeString(new File(fixture,"bottom").toPath(),"go");waitText("FOLLOW_BOTTOM");
      check(top()==0,"Bottom-follow not restored");report.putBoolean("bottomFollowRestored",true);
    }catch(Throwable error){failure=error.toString();report.putString("error",failure);}
    finally{runOnMainSync(()->{if(job!=null)job.close();if(activity!=null)activity.finish();});if(fixture!=null){for(File f:fixture.listFiles())f.delete();fixture.delete();}}
    finish(failure.isEmpty()?-1:0,report);
  }
  private void wheel(boolean up){
    MotionEvent.PointerProperties properties=new MotionEvent.PointerProperties();properties.id=0;properties.toolType=MotionEvent.TOOL_TYPE_MOUSE;
    MotionEvent.PointerCoords coords=new MotionEvent.PointerCoords();coords.x=view.getWidth()/2f;coords.y=view.getHeight()/2f;coords.setAxisValue(MotionEvent.AXIS_VSCROLL,up?1:-1);
    long now=SystemClock.uptimeMillis();MotionEvent event=MotionEvent.obtain(now,now,MotionEvent.ACTION_SCROLL,1,new MotionEvent.PointerProperties[]{properties},new MotionEvent.PointerCoords[]{coords},0,0,1,1,0,0,InputDevice.SOURCE_MOUSE,0);
    try{view.onGenericMotionEvent(event);}finally{event.recycle();}
  }
  private int top(){int[] row={0};runOnMainSync(()->row[0]=view.getTopRow());return row[0];}
  private String firstRow(){AtomicReference<String> row=new AtomicReference<>();runOnMainSync(()->row.set(job.session.getEmulator().getScreen().getSelectedText(0,view.getTopRow(),job.session.getEmulator().mColumns-1,view.getTopRow())));return row.get();}
  private void waitText(String text)throws Exception{for(int i=0;i<100;i++){AtomicReference<String> value=new AtomicReference<>();runOnMainSync(()->value.set(job.session.getEmulator().getScreen().getTranscriptText()));if(value.get().contains(text))return;Thread.sleep(100);}throw new IllegalStateException("Missing real PTY output: "+text);}
  private static TerminalView find(View root){if(root instanceof TerminalView)return (TerminalView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TerminalView v=find(((ViewGroup)root).getChildAt(i));if(v!=null)return v;}return null;}
  private static void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
}
