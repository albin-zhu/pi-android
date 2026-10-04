package dev.zfold.assistant;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.*;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import com.termux.view.TerminalView;
import java.io.*;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises actual IME input, terminal emulation and detach/reattach on a device. */
public final class TerminalInteractionInstrumentation extends Instrumentation {
  private TerminalActivity activity;private TerminalView view;private TerminalSessions.Job job;
  @Override public void onCreate(Bundle args){PiRuntime.testMode=true;start();}
  @Override public void onStart(){
    Bundle result=new Bundle();String error="";File shellHome=null;
    try{
      activity=(TerminalActivity)startActivitySync(new Intent(getTargetContext(),TerminalActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      runOnMainSync(()->view=find(activity.getWindow().getDecorView()));
      check(view!=null,"Terminal view missing");
      for(int i=0;i<100;i++){runOnMainSync(()->job=TerminalSessions.get(getTargetContext()).find(view.getCurrentSession()));if(job!=null&&job.id>0)break;Thread.sleep(100);}
      check(job!=null&&job.id>0,"Shell did not open automatically");
      waitFor("workspace $");
      send("printf '\\033[31mVT_%s\\033[0m\\n' READY; printf '\\344'; sleep 0.1; printf '\\270\\255\\346\\226\\207\\n'\n");
      waitFor("VT_READY");waitFor("中文");result.putBoolean("imeAndUnicode",true);
      check(view.getCurrentSession().getEmulator().mColumns!=120,"PTY retained a fixed column count");
      send("bash\n");Thread.sleep(500);send("printf 'NESTED_%s\\n' OK; exit\n");waitFor("NESTED_OK");
      shellHome=java.nio.file.Files.createTempDirectory(getTargetContext().getCacheDir().toPath(),"shell-home-").toFile();
      java.nio.file.Files.writeString(new File(shellHome,".bashrc").toPath(),"printf 'HOME_RC_%s\\n' OK\n");
      java.nio.file.Files.writeString(new File(shellHome,".bash_profile").toPath(),"printf 'HOME_LOGIN_%s\\n' OK\n");
      send("HOME='"+shellHome.getPath()+"' bash\n");waitFor("HOME_RC_OK");send("exit\n");
      send("HOME='"+shellHome.getPath()+"' bash --login -c 'exit'\n");waitFor("HOME_LOGIN_OK");
      boolean denied=transcript().toLowerCase().contains("permission denied");result.putBoolean("nestedBashPermissionError",denied);result.putBoolean("ownHomeStartup",true);
      send("sleep 15; printf 'BAD_%s\\n' FINISH\n");Thread.sleep(250);runOnMainSync(()->job.session.writeCodePoint(false,3));
      for(int i=0;i<80&&!transcript().trim().endsWith("workspace $");i++)Thread.sleep(100);
      check(transcript().trim().endsWith("workspace $"),"Shell did not return to its prompt after Ctrl+C");
      send("printf 'AFTER_%s\\n' INTERRUPT\n");waitFor("AFTER_INTERRUPT");check(!transcript().contains("BAD_FINISH"),"Ctrl+C did not interrupt command");result.putBoolean("controlC",true);
      int id=job.id;send("sleep 1; printf 'DETACHED_%s\\n' DONE\n");runOnMainSync(activity::finish);Thread.sleep(1400);
      activity=(TerminalActivity)startActivitySync(new Intent(getTargetContext(),TerminalActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      runOnMainSync(()->view=find(activity.getWindow().getDecorView()));
      check(view.getCurrentSession()==job.session&&job.id==id,"Returning created a different shell");waitFor("DETACHED_DONE");result.putBoolean("detachReattach",true);
      File edited=new File(PiRuntime.agentRoot(getTargetContext()),"workspace/terminal-vim-check.txt");edited.delete();
      send("vim -Nu NONE -n terminal-vim-check.txt\n");boolean alternate=false;
      for(int i=0;i<100;i++){boolean[] shown={false};runOnMainSync(()->shown[0]=job.session.getEmulator().isAlternateBufferActive());if(shown[0]){alternate=true;break;}Thread.sleep(100);}
      check(alternate,"Vim did not enter full-screen terminal mode");
      send("i");waitFor("-- INSERT --");send("VIM_EDIT_OK");runOnMainSync(()->job.session.writeCodePoint(false,27));send(":wq\n");
      for(int i=0;i<60&&!edited.exists();i++)Thread.sleep(100);
      check(edited.exists()&&java.nio.file.Files.readString(edited.toPath()).contains("VIM_EDIT_OK"),"Vim editing did not reach the file");edited.delete();result.putBoolean("vimEditing",true);
      for(int i=0;i<80&&!transcript().trim().endsWith("workspace $");i++)Thread.sleep(100);
      check(transcript().trim().endsWith("workspace $"),"Terminal did not return cleanly from Vim");
      runOnMainSync(()->{view.requestFocus();((android.view.inputmethod.InputMethodManager)activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).showSoftInput(view,0);});
      boolean keyboard=false;
      for(int i=0;i<60;i++){boolean[] shown={false};runOnMainSync(()->shown[0]=view.getRootWindowInsets()!=null&&view.getRootWindowInsets().isVisible(WindowInsets.Type.ime()));if(shown[0]){keyboard=true;break;}Thread.sleep(100);}
      check(keyboard,"Software keyboard was not shown");result.putBoolean("keyboardVisible",true);
      android.graphics.Bitmap shot=getUiAutomation().takeScreenshot();if(shot!=null)try(FileOutputStream output=new FileOutputStream(new File(getTargetContext().getCacheDir(),"terminal-interaction.png"))){shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);shot.recycle();}
      result.putInt("columns",view.getCurrentSession().getEmulator().mColumns);result.putString("transcript",transcript());
      check(!denied,"Nested bash still has a permission error");
    }catch(Throwable failure){error=failure.toString();result.putString("error",error);result.putString("transcript",transcript());}
    finally{runOnMainSync(()->{if(job!=null)job.close();if(activity!=null)activity.finish();});if(shellHome!=null){File[] files=shellHome.listFiles();if(files!=null)for(File file:files)file.delete();shellHome.delete();}}
    finish(error.isEmpty()?-1:0,result);
  }
  private void send(String text){runOnMainSync(()->view.onCreateInputConnection(new EditorInfo()).commitText(text,1));}
  private String transcript(){if(job==null||job.session.getEmulator()==null)return "";AtomicReference<String> value=new AtomicReference<>("");runOnMainSync(()->value.set(job.session.getEmulator().getScreen().getTranscriptText()));return value.get();}
  private void waitFor(String text)throws Exception{for(int i=0;i<80;i++){if(transcript().contains(text))return;Thread.sleep(100);}throw new IllegalStateException("Missing terminal output: "+text);}
  private static TerminalView find(View root){if(root instanceof TerminalView)return (TerminalView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TerminalView found=find(((ViewGroup)root).getChildAt(i));if(found!=null)return found;}return null;}
  private static void check(boolean condition,String text){if(!condition)throw new IllegalStateException(text);}
}
