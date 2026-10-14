package dev.zfold.assistant;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Process;
import android.system.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** Independent abstract AF_UNIX transport. The kernel peer UID is checked outside PRoot. */
final class PhoneToolServer {
  static final int MAX_FRAME=4*1024*1024;
  private static final Map<String,PhoneToolServer> servers=new HashMap<>();
  private static final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"phone-socket-deadline");t.setDaemon(true);return t;});
  private final Context context;
  private final LocalServerSocket server;
  final String name;
  private final ExecutorService connections=new ThreadPoolExecutor(0,4,30,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{Thread t=new Thread(r,"phone-socket-reader");t.setDaemon(true);return t;});

  static synchronized String ensure(Context context)throws IOException {
    String root=context.getFilesDir().getCanonicalPath();PhoneToolServer existing=servers.get(root);
    if(existing!=null)return existing.name;
    PhoneToolServer created=new PhoneToolServer(context.getApplicationContext(),"zfold-phone-"+Process.myUid()+"-"+Integer.toHexString(root.hashCode()));
    servers.put(root,created);return created.name;
  }
  private PhoneToolServer(Context context,String name)throws IOException {
    this.context=context;this.name=name;server=new LocalServerSocket(name);
    Thread thread=new Thread(this::accept,"phone-socket-accept");thread.setDaemon(true);thread.start();
  }
  private void accept(){
    for(;;){
      LocalSocket socket=null;
      try{
        socket=server.accept();
        if(socket.getPeerCredentials().getUid()!=Process.myUid()){socket.close();continue;}
        Os.setsockoptTimeval(socket.getFileDescriptor(),OsConstants.SOL_SOCKET,OsConstants.SO_SNDTIMEO,StructTimeval.fromMillis(5000));
        Connection client=new Connection(socket);
        try{connections.execute(client::serve);}catch(RejectedExecutionException busy){client.close();}
      }catch(IOException|ErrnoException failure){if(socket!=null)try{socket.close();}catch(IOException ignored){}android.util.Log.w("ZfoldPhone","Phone socket accept failed",failure);}
    }
  }
  private final class Connection implements PhoneToolDispatcher.ExternalOwner {
    final LocalSocket socket;
    final String namespace="phone-socket-"+UUID.randomUUID()+":";
    final AtomicBoolean closed=new AtomicBoolean();
    final Map<String,Request> pending=new HashMap<>();
    final ThreadPoolExecutor writer=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{Thread t=new Thread(r,"phone-socket-writer");t.setDaemon(true);return t;});
    volatile boolean halted;
    long sequence;
    DataOutputStream output;
    Connection(LocalSocket socket){this.socket=socket;}
    public boolean available(){return !closed.get()&&!halted;}
    public void takenOver(){halted=true;}
    void serve(){
      try{
        output=new DataOutputStream(socket.getOutputStream());
        send(PiRuntime.json("type","bridge_ready","protocol",1,"tools",PhoneToolDispatcher.tools(),
          "workspaceRoot",PiRuntime.agentRoot(context).getCanonicalPath(),"workspace",RuntimeEnvironment.workingDirectory(context,null).getCanonicalPath()));
        InputStream input=socket.getInputStream();
        for(;;){
          // Idle connections are normal. Once a frame starts, its entire remainder
          // has one deadline, so a trickling peer cannot hold a frame indefinitely.
          int first=input.read();if(first<0)break;
          ScheduledFuture<?> deadline=deadlines.schedule(this::close,5,TimeUnit.SECONDS);
          JSONObject message;
          try{
            byte[] header=new byte[3];readFully(input,header);
            long size=((long)first<<24)|((long)(header[0]&255)<<16)|((long)(header[1]&255)<<8)|(header[2]&255);
            if(size==0||size>MAX_FRAME)throw new IOException("无效手机工具帧长度");
            byte[] bytes=new byte[(int)size];readFully(input,bytes);
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JSONTokener parser=new JSONTokener(text);Object value=parser.nextValue();
            if(!(value instanceof JSONObject)||parser.nextClean()!=0)throw new IOException("手机工具消息必须是 JSON 对象");
            message=(JSONObject)value;
          }finally{deadline.cancel(false);}
          receive(message);
        }
      }catch(IOException|JSONException disconnected){
        // EOF never means success. In-flight calls are cancelled, never replayed.
      }finally{close();}
    }
    void receive(JSONObject message)throws JSONException {
      Object rawId=message.opt("id");String id=rawId instanceof String?(String)rawId:"";
      if(id.isEmpty()||id.length()>256){error("","工具调用需要 1–256 字符的字符串 ID");return;}
      String type=message.optString("type");
      if(type.equals("tool_cancel")){
        Request request;synchronized(pending){request=pending.get(id);}
        if(request!=null){request.cancelled=true;PhoneToolDispatcher.cancel(request.internal);}
        return;
      }
      if(!type.equals("tool_request")){error(id,"不支持的手机工具消息类型");return;}
      synchronized(pending){if(pending.containsKey(id)){close();return;}}
      if(!(message.opt("name") instanceof String)||!PhoneToolDispatcher.allowed(message.optString("name"))){error(id,"不支持的手机工具："+message.optString("name"));return;}
      if(!(message.opt("args") instanceof JSONObject)){error(id,"手机工具 args 必须是 JSON 对象");return;}
      if(message.has("turnId")&&!message.isNull("turnId")&&(!(message.opt("turnId") instanceof String)||message.optString("turnId").length()>256)){error(id,"无效 turnId");return;}
      if(message.getJSONObject("args").toString().getBytes(StandardCharsets.UTF_8).length>65536){error(id,"手机工具参数超过 64 KiB");return;}
      if(!available()){error(id,"独立客户端已停止，请在确认手机状态后重新连接");return;}
      Request request=new Request(id,namespace+(++sequence));
      synchronized(pending){
        if(pending.size()>=8){error(id,"手机工具请求过多");return;}
        pending.put(id,request);
      }
      JSONObject nativeRequest=PiRuntime.json("type","tool_request","id",request.internal,"name",message.getString("name"),"args",message.getJSONObject("args"));
      if(message.opt("turnId") instanceof String)nativeRequest.put("turnId",namespace+message.getString("turnId"));
      PhoneToolDispatcher.external(context,nativeRequest,this,()->request.cancelled,
        (value,error)->{
          synchronized(pending){pending.remove(id,request);}
          send(error==null?PiRuntime.json("type","tool_result","id",id,"result",value):PiRuntime.json("type","tool_result","id",id,"error",error));
        },()->send(PiRuntime.json("type","tool_heartbeat","id",id)));
    }
    void error(String id,String error){send(PiRuntime.json("type","tool_result","id",id,"error",error));}
    void send(JSONObject message){
      if(closed.get())return;
      try{writer.execute(()->{
        if(closed.get())return;
        ScheduledFuture<?> deadline=deadlines.schedule(this::close,5,TimeUnit.SECONDS);
        try{
          byte[] bytes=message.toString().getBytes(StandardCharsets.UTF_8);
          if(bytes.length>MAX_FRAME)bytes=PiRuntime.json("type","tool_result","id",message.optString("id"),"error","手机工具结果超过 4 MiB").toString().getBytes(StandardCharsets.UTF_8);
          output.writeInt(bytes.length);output.write(bytes);output.flush();
        }catch(IOException disconnected){close();}finally{deadline.cancel(false);}
      });}catch(RejectedExecutionException slowPeer){close();}
    }
    void close(){
      if(!closed.compareAndSet(false,true))return;
      synchronized(pending){for(Request request:pending.values()){request.cancelled=true;NativeToolExecution.cancel(request.internal);}pending.clear();}
      // LocalSocket.close can defer the actual fd close while another thread is
      // blocked in read/write. Shutdown first so frame deadlines really unblock I/O.
      try{socket.shutdownInput();}catch(IOException ignored){}
      try{socket.shutdownOutput();}catch(IOException ignored){}
      try{socket.close();}catch(IOException ignored){}
      writer.shutdownNow();PhoneToolDispatcher.disconnect(this);
    }
  }
  private static final class Request {
    final String id,internal;volatile boolean cancelled;
    Request(String id,String internal){this.id=id;this.internal=internal;}
  }
  private static void readFully(InputStream input,byte[] bytes)throws IOException {
    int offset=0;while(offset<bytes.length){int count=input.read(bytes,offset,bytes.length-offset);if(count<0)throw new EOFException();if(count==0)throw new IOException("手机工具帧读取无进展");offset+=count;}
  }
}
