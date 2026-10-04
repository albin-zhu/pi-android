package dev.zfold.assistant;

import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import org.json.JSONObject;

/** A one-shot process group with bounded output and acknowledged termination. */
final class RuntimeProcess {
  static final int OUTPUT_LIMIT=128*1024;
  static {System.loadLibrary("zfold");}
  private static native int[] start(String[] argv,String cwd,String[] environment);
  private static native int poll(int pid);
  private static native void signalGroup(int pid,int signal);

  static JSONObject execute(ProcessBuilder builder,long timeoutMs)throws Exception {
    NativeToolExecution.checkpoint();
    ArrayList<String> env=new ArrayList<>();
    for(Map.Entry<String,String> entry:builder.environment().entrySet())env.add(entry.getKey()+"="+entry.getValue());
    int[] process=start(builder.command().toArray(new String[0]),builder.directory().getAbsolutePath(),env.toArray(new String[0]));
    int pid=process[0];boolean reaped=false;
    Output stdout=new Output(process[1],"runtime-stdout"),stderr=new Output(process[2],"runtime-stderr");
    stdout.thread.start();stderr.thread.start();
    try {
      long deadline=SystemClock.elapsedRealtime()+timeoutMs;int exit;
      while(true){
        NativeToolExecution.checkpoint();
        exit=poll(pid);
        if(exit>=0){reaped=true;break;}
        if(exit==-2){reaped=true;throw new IllegalStateException("进程退出状态不可用");}
        if(SystemClock.elapsedRealtime()>=deadline)throw new IllegalStateException("runtime_exec 超时，进程已停止");
        Thread.sleep(40);
      }
      stdout.thread.join(1000);stderr.thread.join(1000);
      return PiRuntime.json("exitCode",exit,"stdout",stdout.text(),"stderr",stderr.text(),
        "stdoutTruncated",stdout.truncated,"stderrTruncated",stderr.truncated,"outputLimitBytes",OUTPUT_LIMIT,
        "stdoutComplete",stdout.complete,"stderrComplete",stderr.complete);
    }finally{
      boolean terminationConfirmed=true;
      if(!reaped){
        // Do not reap the leader before both signals: its PID must not be reused
        // while we still address the group containing its children.
        signalGroup(pid,OsConstants.SIGTERM);
        SystemClock.sleep(150);
        signalGroup(pid,OsConstants.SIGKILL);
        long until=SystemClock.elapsedRealtime()+2000;
        int state;
        while((state=poll(pid))==-1&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(20);
        terminationConfirmed=state!=-1;
      }
      stdout.stop=true;stderr.stop=true;
      stdout.thread.join(1200);stderr.thread.join(1200);
      if(!terminationConfirmed)throw new IllegalStateException("停止尚未确认，进程尚未退出");
    }
  }

  private static final class Output implements Runnable {
    private final ParcelFileDescriptor descriptor;
    private final ByteArrayOutputStream captured=new ByteArrayOutputStream();
    final Thread thread;
    volatile boolean stop,truncated,complete;
    Output(int fd,String name){descriptor=ParcelFileDescriptor.adoptFd(fd);thread=new Thread(this,name);thread.setDaemon(true);}
    public void run(){
      try(ParcelFileDescriptor handle=descriptor){
        StructPollfd watched=new StructPollfd();watched.fd=handle.getFileDescriptor();watched.events=(short)OsConstants.POLLIN;
        byte[] bytes=new byte[8192];
        while(!stop){
          if(Os.poll(new StructPollfd[]{watched},250)==0)continue;
          if((watched.revents&(OsConstants.POLLIN|OsConstants.POLLHUP))==0)break;
          int count=Os.read(watched.fd,bytes,0,bytes.length);
          if(count<=0){complete=true;break;}
          synchronized(captured){
            int keep=Math.min(count,OUTPUT_LIMIT-captured.size());
            if(keep>0)captured.write(bytes,0,keep);
            if(keep<count)truncated=true;
          }
        }
      }catch(Exception ignored){}
    }
    String text(){synchronized(captured){return new String(captured.toByteArray(),StandardCharsets.UTF_8)+(truncated?"\n[输出已截断]":"");}}
  }
}
