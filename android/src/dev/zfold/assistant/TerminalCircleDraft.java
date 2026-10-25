package dev.zfold.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.FutureTask;

/** One durable, explicitly consumed circle/share draft. Never starts a runtime or a shell. */
final class TerminalCircleDraft {
  private TerminalCircleDraft(){}
  static final class Draft {
    final String id,question,targetHandle;
    final File file;
    Draft(String id,File file,String question,String targetHandle){this.id=id;this.file=file;this.question=question;this.targetHandle=targetHandle;}
  }
  private static SharedPreferences prefs(Context context){return context.getSharedPreferences(PiRuntime.testMode?"test-terminal-circle":"terminal-circle",Context.MODE_PRIVATE);}
  static Draft capture(Context context,Bitmap image)throws IOException {return capture(context,image,"");}
  static Draft capture(Context context,Bitmap image,String question)throws IOException {
    FutureTask<String> target=new FutureTask<>(()->{TerminalSessions.Job job=TerminalSessions.get(context).selected();return job==null?null:job.session.mHandle;});
    if(Looper.myLooper()==Looper.getMainLooper())target.run();else new Handler(Looper.getMainLooper()).post(target);
    String handle;
    try{handle=target.get();}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IOException("圈选保存已中断",error);}catch(java.util.concurrent.ExecutionException error){throw new IOException("无法读取终端目标",error);}
    return saveCapture(context,image,question,handle);
  }
  private static Draft saveCapture(Context context,Bitmap image,String question,String handle)throws IOException {
    synchronized(TerminalCircleDraft.class){if(prefs(context).contains("id"))throw new IOException("已有待处理草稿，请先在终端插入或丢弃，再重试分享");}
    TerminalAttachmentFiles.draftText(question);
    if(image==null){
      Draft draft=new Draft(UUID.randomUUID().toString(),null,question,handle);
      synchronized(TerminalCircleDraft.class){
        if(prefs(context).contains("id"))throw new IOException("已有待处理草稿，请先在终端插入或丢弃，再重试分享");
        persist(context,draft);
      }
      return draft;
    }
    if(image.isRecycled())throw new IOException("图片已不可用");
    String id=UUID.randomUUID().toString();File root=PiRuntime.agentRoot(context),batch=TerminalAttachmentFiles.newBatch(root);
    File temporary=new File(batch,"circle-"+id+".pending"),file=new File(batch,"circle-"+id+".png");
    try{
      try(FileOutputStream output=new FileOutputStream(temporary)){
        if(!image.compress(Bitmap.CompressFormat.PNG,100,output))throw new IOException("圈选图片保存失败");
        output.getFD().sync();
      }
      Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE);
      TerminalAttachmentFiles.ownedCircle(root,file,id);
      Draft draft=new Draft(id,file,question,handle);
      synchronized(TerminalCircleDraft.class){
        if(prefs(context).contains("id"))throw new IOException("已有待处理圈选，请先在终端插入或丢弃");
        persist(context,draft);
      }
      return draft;
    }catch(IOException|RuntimeException error){
      try{Files.deleteIfExists(temporary.toPath());Files.deleteIfExists(file.toPath());Files.deleteIfExists(batch.toPath());}catch(IOException cleanup){error.addSuppressed(cleanup);}
      throw error;
    }
  }
  static synchronized Draft load(Context context)throws IOException {
    SharedPreferences saved=prefs(context);String id=saved.getString("id",null);if(id==null)return null;
    String path=saved.getString("file",null);
    File file=path==null?null:TerminalAttachmentFiles.ownedCircle(PiRuntime.agentRoot(context),new File(path),id);
    return new Draft(id,file,saved.getString("question",""),saved.getString("target",null));
  }
  private static void persist(Context context,Draft draft)throws IOException {
    SharedPreferences saved=prefs(context);java.util.Map<String,?> before=saved.getAll();
    SharedPreferences.Editor edit=saved.edit().clear();
    if(draft!=null){
      edit.putString("id",draft.id).putString("question",draft.question).putString("target",draft.targetHandle);
      if(draft.file!=null)edit.putString("file",draft.file.getAbsolutePath());
    }
    if(!edit.commit()){
      // commit also changes the in-memory view on failure; restore it so callers cannot replay/lose a draft.
      SharedPreferences.Editor restore=saved.edit().clear();for(java.util.Map.Entry<String,?> entry:before.entrySet())restore.putString(entry.getKey(),(String)entry.getValue());restore.commit();
      throw new IOException("无法持久保存圈选草稿，请重试");
    }
  }
  static synchronized Draft update(Context context,String id,String question,String targetHandle)throws IOException {
    if(id==null||!id.equals(prefs(context).getString("id",null)))throw new IOException("圈选草稿已改变，请重新打开");
    Draft current=load(context);String value=question==null?"":question;
    TerminalAttachmentFiles.draftText(value);
    Draft next=new Draft(id,current.file,value,targetHandle);persist(context,next);return next;
  }
  static synchronized boolean consume(Context context,String id)throws IOException {
    if(id==null||!id.equals(prefs(context).getString("id",null)))return false;
    load(context);persist(context,null);return true;
  }
  static synchronized boolean discard(Context context,String id)throws IOException {
    SharedPreferences saved=prefs(context);if(id==null||!id.equals(saved.getString("id",null)))return false;
    String path=saved.getString("file",null);
    if(path!=null){
      File file=TerminalAttachmentFiles.ownedCircle(PiRuntime.agentRoot(context),new File(path),id);
      Files.deleteIfExists(file.toPath());
      try{Files.deleteIfExists(file.getParentFile().toPath());}catch(java.nio.file.DirectoryNotEmptyException ignored){} // Never delete neighboring attachments.
    }
    persist(context,null);return true;
  }
  static String insertion(Context context,Draft draft,boolean ubuntu)throws IOException {
    if(draft.file==null)return TerminalAttachmentFiles.draftText(draft.question);
    TerminalAttachmentFiles.ownedCircle(PiRuntime.agentRoot(context),draft.file,draft.id);
    return TerminalAttachmentFiles.circleText(TerminalAttachmentFiles.path(draft.file,PiRuntime.agentRoot(context),Environment.getExternalStorageDirectory(),ubuntu),draft.question);
  }
}
