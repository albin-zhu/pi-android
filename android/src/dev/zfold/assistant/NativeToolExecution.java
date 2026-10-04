package dev.zfold.assistant;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Per-call cancellation and liveness; no Activity or voice-session ownership. */
final class NativeToolExecution {
  private static final ConcurrentHashMap<String,NativeToolExecution> active=new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String,Long> cancelledBeforeStart=new ConcurrentHashMap<>();
  private static final ThreadLocal<NativeToolExecution> current=new ThreadLocal<>();
  private static final ScheduledExecutorService clock=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"native-tool-liveness");t.setDaemon(true);return t;});
  final String id,turnId;
  private final AndroidTools.Result callback;
  private final AtomicBoolean finished=new AtomicBoolean();
  private volatile boolean cancelled;
  private ScheduledFuture<?> heartbeat;

  private NativeToolExecution(JSONObject request,AndroidTools.Result callback){
    id=request.optString("id");turnId=request.optString("turnId");this.callback=callback;
  }
  static NativeToolExecution register(JSONObject request,AndroidTools.Result callback,Runnable pulse){
    NativeToolExecution call=new NativeToolExecution(request,callback);
    if(!call.id.isEmpty()){
      if(active.putIfAbsent(call.id,call)!=null){callback.done(null,"重复的工具调用 ID");return null;}
      Long cancelledAt=cancelledBeforeStart.remove(call.id);
      if(cancelledAt!=null)call.cancelled=true;
    }
    if(pulse!=null)call.heartbeat=clock.scheduleAtFixedRate(()->{if(!call.finished.get())pulse.run();},0,2,TimeUnit.SECONDS);
    return call;
  }
  static void cancel(String id){
    if(id==null||id.isEmpty())return;
    NativeToolExecution call=active.get(id);
    if(call!=null){call.cancelled=true;return;}
    long now=android.os.SystemClock.elapsedRealtime();
    cancelledBeforeStart.entrySet().removeIf(entry->now-entry.getValue()>60_000);
    if(cancelledBeforeStart.size()<4096)cancelledBeforeStart.put(id,now);
    // Close the race where registration happened between lookup and tombstone.
    call=active.get(id);if(call!=null){call.cancelled=true;cancelledBeforeStart.remove(id);}
  }
  static void cancelTurn(String turnId){
    if(turnId==null||turnId.isEmpty())return;
    for(NativeToolExecution call:active.values())if(turnId.equals(call.turnId))call.cancelled=true;
  }
  static void checkpoint(){NativeToolExecution call=current.get();if(call!=null&&call.cancelled)throw new CancellationException("操作已停止");}
  void run(Runnable operation){
    NativeToolExecution previous=current.get();current.set(this);
    try{checkpoint();operation.run();}
    catch(Throwable error){complete(null,error.getMessage()==null?error.toString():error.getMessage());}
    finally{if(previous==null)current.remove();else current.set(previous);}
  }
  void complete(JSONObject value,String error){
    if(!finished.compareAndSet(false,true))return;
    if(heartbeat!=null)heartbeat.cancel(false);
    if(!id.isEmpty())active.remove(id,this);
    callback.done(value,error);
  }
}
