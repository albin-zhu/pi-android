package dev.zfold.assistant;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Process;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Same-UID local bridge; never exposes package mutations over TCP or exported components. */
final class PackageCliServer {
  private static final Map<String,PackageCliServer> servers=new HashMap<>();
  private final Context context;
  private final LocalServerSocket server;
  final String name;
  private final ExecutorService workers=new ThreadPoolExecutor(0,4,30,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{Thread t=new Thread(r,"pkg-cli-worker");t.setDaemon(true);return t;});
  static synchronized String ensure(Context context)throws IOException {
    String root=context.getFilesDir().getCanonicalPath();
    PackageCliServer existing=servers.get(root);
    if(existing!=null)return existing.name;
    File directory=new File(root,"runtime/bin");directory.mkdirs();
    Path link=new File(directory,"pkg").toPath();
    Path target=new File(context.getApplicationInfo().nativeLibraryDir,"libzfold_pkg.so").toPath();
    if(!Files.isExecutable(target))throw new IOException("缺少内置 pkg 客户端");
    if(!Files.isSymbolicLink(link)||!Files.readSymbolicLink(link).equals(target)){
      Path temporary=new File(directory,".pkg-new").toPath();Files.deleteIfExists(temporary);
      Files.createSymbolicLink(temporary,target);
      Files.move(temporary,link,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    PackageCliServer created=new PackageCliServer(context,"zfold-pkg-"+Process.myUid()+"-"+Integer.toHexString(root.hashCode()));
    servers.put(root,created);return created.name;
  }
  static boolean isClient(Context context,File executable)throws IOException {
    return executable.getCanonicalFile().equals(new File(context.getApplicationInfo().nativeLibraryDir,"libzfold_pkg.so").getCanonicalFile());
  }
  private PackageCliServer(Context context,String name)throws IOException {
    this.context=context;this.name=name;server=new LocalServerSocket(name);
    Thread thread=new Thread(this::accept,"pkg-cli-accept");thread.setDaemon(true);thread.start();
  }
  private void accept(){
    for(;;){
      LocalSocket socket=null;
      try{
        socket=server.accept();socket.setSoTimeout(5000);
        if(socket.getPeerCredentials().getUid()!=Process.myUid()){socket.close();continue;}
        LocalSocket client=socket;
        try{workers.execute(()->serve(client));}
        catch(RejectedExecutionException busy){
          new Reply(client.getOutputStream()).finish(75,"包管理请求过多，请稍后重试");client.close();
        }
      }catch(IOException failure){if(socket!=null)try{socket.close();}catch(IOException ignored){}android.util.Log.w("ZfoldPkg","Package client disconnected",failure);}
    }
  }
  private void serve(LocalSocket socket){
    AtomicBoolean done=new AtomicBoolean();
    try(LocalSocket client=socket){
      Reply reply=new Reply(client.getOutputStream());
      DataInputStream input=new DataInputStream(client.getInputStream());
      List<String> args=new ArrayList<>();
      try{
        if(input.readInt()!=0x5a504b31)throw new IOException("无效 pkg 协议");
        int count=input.readInt(),total=0;if(count<0||count>64)throw new IOException("参数数量超限");
        for(int i=0;i<count;i++){
          int size=input.readInt();if(size<0||size>4096||(total+=size)>16384)throw new IOException("参数大小超限");
          byte[] bytes=new byte[size];input.readFully(bytes);String value=new String(bytes,StandardCharsets.UTF_8);
          if(value.indexOf('\0')>=0)throw new IOException("无效参数");args.add(value);
        }
      }catch(IOException malformed){reply.finish(2,malformed.getMessage());return;}
      // Once the finite request is read, silence is normal: the client only
      // receives progress. Monitor actual EOF, not Android's receive timeout.
      client.setSoTimeout(0);
      try{android.system.Os.setsockoptTimeval(client.getFileDescriptor(),android.system.OsConstants.SOL_SOCKET,
        android.system.OsConstants.SO_SNDTIMEO,android.system.StructTimeval.fromMillis(5000));}
      catch(android.system.ErrnoException failure){throw new IOException(failure);}
      String id="pkg-cli-"+UUID.randomUUID();
      NativeToolExecution call=NativeToolExecution.register(PiRuntime.json("id",id),(value,error)->{
        done.set(true);reply.finish(error==null?value.optInt("exitCode",1):error.equals("操作已停止")?130:1,error);
      },null);
      if(call==null)return;
      Thread monitor=new Thread(()->{
        try{
          while(!done.get()){
            input.read();break;
          }
        }catch(IOException disconnected){}
        if(!done.get())NativeToolExecution.cancel(id);
      },"pkg-cli-disconnect");monitor.setDaemon(true);monitor.start();
      call.run(()->{
        try{call.complete(PiRuntime.json("exitCode",PackageCli.run(context,args,reply::text)),null);}
        catch(Exception error){call.complete(null,error.getMessage()==null?error.toString():error.getMessage());}
      });
    }catch(IOException disconnected){}finally{done.set(true);}
  }
  private static final class Reply {
    private final DataOutputStream out;private boolean finished;
    Reply(OutputStream output){out=new DataOutputStream(output);}
    synchronized void text(boolean error,String text){
      if(finished)return;
      byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
      try{out.writeByte(error?2:1);out.writeInt(bytes.length);out.write(bytes);out.flush();}
      catch(IOException disconnected){throw new UncheckedIOException(disconnected);}
    }
    synchronized void finish(int code,String error){
      if(finished)return;
      try{if(error!=null)text(true,"pkg: "+error+"\n");out.writeByte(3);out.writeInt(4);out.writeInt(code);out.flush();}
      catch(IOException|UncheckedIOException disconnected){}finally{finished=true;}
    }
  }
}
