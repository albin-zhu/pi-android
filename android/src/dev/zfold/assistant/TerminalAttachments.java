package dev.zfold.assistant;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.OperationCanceledException;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Consumer;

/** Turn explicit user document grants into paths, never guessed provider filesystem IDs. */
final class TerminalAttachments {
  static final int MAX_FILES=LocalFiles.MAX_FILES;
  static final class Result {
    final String text;
    final int importedCount,selectedCount;
    Result(String text,int importedCount,int selectedCount){this.text=text;this.importedCount=importedCount;this.selectedCount=selectedCount;}
  }

  static Result prepare(Context context,List<Uri> selected,boolean ubuntu,CancellationSignal cancellation,Consumer<String> progress)throws IOException {
    if(selected==null)throw new IOException("没有选择文件");
    List<Uri> uris=new ArrayList<>(new LinkedHashSet<>(selected));
    if(uris.isEmpty()||uris.size()>MAX_FILES)throw new IOException("请选择 1–"+MAX_FILES+" 个文件");
    for(Uri uri:uris)if(uri==null||!"content".equals(uri.getScheme()))throw new IOException("请选择系统文件选择器提供的文件");
    File workspace=PiRuntime.agentRoot(context).getCanonicalFile();
    File storage=Environment.getExternalStorageDirectory();
    File batch=null;List<File> copies=new ArrayList<>();StringBuilder text=new StringBuilder();
    try{
      for(int i=0;i<uris.size();i++){
        cancellation.throwIfCanceled();
        if(progress!=null)progress.accept("正在准备文件 "+(i+1)+" / "+uris.size());
        Uri uri=uris.get(i);String path=directPath(context,uri,workspace,storage,ubuntu,cancellation);
        if(path==null){
          String name=displayName(context,uri,cancellation);
          if(batch==null)batch=TerminalAttachmentFiles.newBatch(workspace);
          File imported;
          try(AssetFileDescriptor descriptor=context.getContentResolver().openAssetFileDescriptor(uri,"r",cancellation)){
            if(descriptor==null)throw new IOException("文件提供方未返回可读取的内容");
            try(InputStream input=descriptor.createInputStream()){
              cancellation.setOnCancelListener(()->{try{input.close();}catch(IOException ignored){}});
              try{imported=TerminalAttachmentFiles.copy(input,batch,name,i+1,cancellation::throwIfCanceled);copies.add(imported);}
              finally{cancellation.setOnCancelListener(null);}
            }
          }
          path=TerminalAttachmentFiles.path(imported,workspace,storage,ubuntu);
        }
        text.append(TerminalAttachmentFiles.quote(path)).append(' ');
      }
      cancellation.throwIfCanceled();
      return new Result(text.toString(),copies.size(),uris.size());
    }catch(IOException|RuntimeException error){
      try{TerminalAttachmentFiles.rollback(batch,copies);}catch(IOException cleanup){error.addSuppressed(cleanup);}
      throw error;
    }
  }

  private static String directPath(Context context,Uri uri,File workspace,File storage,boolean ubuntu,CancellationSignal cancellation){
    // Only the system external-storage provider defines primary:<relative path> IDs.
    // Media/download/cloud IDs are opaque: import their granted stream instead of querying _data.
    if(!"com.android.externalstorage.documents".equals(uri.getAuthority()))return null;
    try{
      cancellation.throwIfCanceled();
      if(!DocumentsContract.isDocumentUri(context,uri))return null;
      File file=TerminalAttachmentFiles.primaryDocument(storage,DocumentsContract.getDocumentId(uri));
      if(file==null)return null;
      try(FileInputStream input=new FileInputStream(file)){input.read();}
      return TerminalAttachmentFiles.path(file,workspace,storage,ubuntu);
    }catch(OperationCanceledException cancelled){throw cancelled;}
    catch(IOException|SecurityException|IllegalArgumentException|UnsupportedOperationException unavailable){return null;}
  }

  private static String displayName(Context context,Uri uri,CancellationSignal cancellation){
    try(Cursor cursor=context.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null,cancellation)){
      if(cursor!=null&&cursor.moveToFirst()&&!cursor.isNull(0))return cursor.getString(0);
    }catch(OperationCanceledException cancelled){throw cancelled;}
    catch(SecurityException|IllegalArgumentException|UnsupportedOperationException unavailable){}
    String extension=null;
    try{extension=MimeTypeMap.getSingleton().getExtensionFromMimeType(context.getContentResolver().getType(uri));}
    catch(SecurityException|IllegalArgumentException|UnsupportedOperationException unavailable){}
    return extension==null?"file":"file."+extension;
  }
}
