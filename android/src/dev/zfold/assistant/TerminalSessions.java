package dev.zfold.assistant;

import android.content.*;
import android.os.*;
import android.util.Log;
import com.termux.terminal.*;
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Process-owned terminal sessions; activities are replaceable viewers. */
final class TerminalSessions implements TerminalSessionClient {
  interface Listener {void changed(Job job);void paste(Job job);}
  private static TerminalSessions instance;
  final Context context;
  final Handler main=new Handler(Looper.getMainLooper());
  final LinkedHashMap<String,Job> jobs=new LinkedHashMap<>();
  private WeakReference<Listener> listener=new WeakReference<>(null);
  private String selected;
  private int nextOrdinal=1;
  static synchronized TerminalSessions get(Context context){if(instance==null)instance=new TerminalSessions(context.getApplicationContext());return instance;}
  private TerminalSessions(Context context){this.context=context;}
  void observe(Listener value){listener=new WeakReference<>(value);}
  void detach(Listener value){if(listener.get()==value)listener.clear();}
  Job current(){Job job=jobs.get(selected);if(job==null||!job.session.isRunning())for(Job candidate:jobs.values())if(candidate.session.isRunning())job=candidate;return job!=null&&job.session.isRunning()?job:create();}
  Job create(){
    if(liveCount()>=4)throw new IllegalStateException("已有 4 个运行中的终端，请先结束一个会话");
    while(jobs.size()>=8){String oldest=jobs.entrySet().stream().filter(entry->!entry.getValue().session.isRunning()).map(Map.Entry::getKey).findFirst().orElse(null);if(oldest==null)break;jobs.remove(oldest);}
    Job job=new Job(this,nextOrdinal++);jobs.put(job.session.mHandle,job);selected=job.session.mHandle;return job;
  }
  void select(Job job){selected=job.session.mHandle;}
  int liveCount(){int count=0;for(Job job:jobs.values())if(job.session.isRunning())count++;return count;}
  Job find(TerminalSession session){return jobs.get(session.mHandle);}
  void changed(Job job){Listener target=listener.get();if(target!=null)target.changed(job);}
  void closeAll(){for(Job job:new ArrayList<>(jobs.values()))job.close();}

  static final class Job implements TerminalSession.Transport {
    final TerminalSessions owner;final TerminalSession session;
    final ExecutorService writer=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"terminal-input");t.setDaemon(true);return t;});
    volatile int id;volatile boolean closing;String name,status="正在启动…",cwd="",shell="";
    Job(TerminalSessions owner,int ordinal){this.owner=owner;name="终端 "+ordinal;session=new TerminalSession(this,owner);session.mSessionName=name;}
    public void start(TerminalSession ignored,int columns,int rows){
      if(closing){session.finish(143);return;}
      try{owner.context.startForegroundService(new Intent(owner.context,TerminalKeepAliveService.class));}catch(RuntimeException error){Log.w("ZfoldTerminal","Foreground terminal service",error);}
      writer.execute(()->{
        try{
          System.loadLibrary("zfold");
          File bash=PiPackageManager.prepareBash(owner.context);
          File fish=null;
          try{fish=PiPackageManager.executable(owner.context,"fish");}catch(IOException unreachable){fish=null;}
          boolean modern=owner.context.getApplicationInfo().targetSdkVersion==28;
          boolean useFish=modern&&fish!=null&&fish.canExecute();
          boolean useBash=!useFish&&modern&&bash!=null&&bash.canExecute();
          File executable=useFish?fish:useBash?bash:new File("/system/bin/sh");
          shell=useFish?"Fish":useBash?"Bash":"系统 Shell";
          String[] argv=useBash?new String[]{executable.getPath(),"--noprofile"}:new String[]{executable.getPath()};
          Map<String,String> environment=new ProcessBuilder("true").environment();
          RuntimeEnvironment.apply(environment,RuntimeEnvironment.forExecutable(owner.context,executable),null);
          if(!useBash&&!useFish)environment.putIfAbsent("ENV",new File(environment.get("HOME"),".mkshrc").getPath());
          environment.put("TERM","xterm-256color");environment.put("COLORTERM","truecolor");
          if(!useFish)environment.put("PS1",useBash?"\\[\\e[38;5;114m\\]\\W\\[\\e[0m\\] $ ":"$ ");
          cwd=RuntimeEnvironment.workingDirectory(owner.context,null).getPath();
          id=PiRuntime.ptyOpen(argv,cwd,environment.entrySet().stream().map(entry->entry.getKey()+"="+entry.getValue()).toArray(String[]::new),rows,columns);
          if(closing){PiRuntime.ptyClose(id);session.finish(143);writer.shutdown();return;}
          owner.main.post(()->{status=shell+" · 运行中";owner.changed(this);TerminalKeepAliveService.refresh(owner.context);});
          Thread reader=new Thread(this::read,"terminal-output-"+id);reader.setDaemon(true);reader.start();
        }catch(Throwable error){owner.main.post(()->{status="启动失败："+error.getMessage();owner.changed(this);});session.finish(127);writer.shutdown();}
      });
    }
    private void read(){
      int code=0;
      try{
        while(!closing){
          String encoded=PiRuntime.ptyRead(id,150);
          if(encoded!=null&&!encoded.isEmpty())session.acceptOutput(Base64.getDecoder().decode(encoded));
          else{int state=PiRuntime.ptyStatus(id);if(state>=0){code=state;break;}if(state==-2){code=255;break;}}
        }
      }catch(Throwable error){if(!closing){code=255;String message=error.getMessage();owner.main.post(()->{status="读取中断："+message;owner.changed(this);});}}
      finally{boolean requested=closing;closing=true;if(!requested)try{PiRuntime.ptyClose(id);}catch(Throwable ignored){}session.finish(requested?143:code);writer.shutdown();}
    }
    public void write(byte[] bytes){
      if(closing||writer.isShutdown())return;
      try{writer.execute(()->{if(closing||id<=0)return;try{PiRuntime.ptyWrite(id,bytes);}catch(Throwable error){owner.main.post(()->{if(!closing){status="输入失败："+error.getMessage();owner.changed(this);}});}});}catch(RejectedExecutionException ignored){}
    }
    public void resize(int columns,int rows){
      if(closing||writer.isShutdown())return;
      try{writer.execute(()->{if(!closing&&id>0)try{PiRuntime.ptyResize(id,rows,columns);}catch(Throwable ignored){}});}catch(RejectedExecutionException ignored){}
    }
    public void close(){
      if(closing)return;closing=true;status="正在结束…";owner.changed(this);
      if(id>0)new Thread(()->{try{PiRuntime.ptyClose(id);}catch(Throwable ignored){}finally{session.finish(143);writer.shutdown();}},"terminal-close").start();
      else if(session.getEmulator()==null){session.finish(143);writer.shutdown();}
    }
  }
  public void onTextChanged(TerminalSession session){Job job=find(session);if(job!=null)changed(job);}
  public void onTitleChanged(TerminalSession session){onTextChanged(session);}
  public void onSessionFinished(TerminalSession session){Job job=find(session);if(job!=null){job.status="已结束 · 退出码 "+session.getExitStatus();changed(job);}TerminalKeepAliveService.refresh(context);}
  public void onCopyTextToClipboard(TerminalSession session,String text){((ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("终端",text));}
  public void onPasteTextFromClipboard(TerminalSession session){Job job=find(session);Listener target=listener.get();if(job!=null&&target!=null)target.paste(job);}
  public void onBell(TerminalSession session){}
  public void onColorsChanged(TerminalSession session){onTextChanged(session);}
  public void onTerminalCursorStateChange(boolean state){}
  public Integer getTerminalCursorStyle(){return 0;}
  public void logError(String tag,String message){Log.e(tag,message);}
  public void logWarn(String tag,String message){Log.w(tag,message);}
  public void logInfo(String tag,String message){Log.i(tag,message);}
  public void logDebug(String tag,String message){Log.d(tag,message);}
  public void logVerbose(String tag,String message){}
  public void logStackTraceWithMessage(String tag,String message,Exception e){Log.w(tag,message,e);}
  public void logStackTrace(String tag,Exception e){Log.w(tag,"Terminal",e);}
}
