package dev.zfold.assistant;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Only traverses child metadata returned by the explicitly granted DocumentsProvider tree. */
final class PreviewTree {
  static final class Entry {
    final String id,name,mime,relative;
    final Uri tree,uri;
    Entry(Uri tree,String id,String name,String mime,String relative){
      this.tree=tree;this.id=id;this.name=name;this.relative=relative;
      // Providers may label every file application/octet-stream. Within an explicitly
      // granted tree, suffixes select the reader; decoding still rejects binary Markdown.
      if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)||PreviewMarkdownPaths.image(mime))this.mime=mime;
      else{
        String inferred=ArtifactProvider.mime(new File(name));
        this.mime=PreviewMarkdownPaths.image(inferred)?inferred:mime;
      }
      uri=DocumentsContract.buildDocumentUriUsingTree(tree,id);
    }
    boolean directory(){return DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);}
    boolean markdown(){return PreviewFiles.kind(new File(name))==PreviewFiles.Kind.MARKDOWN||PreviewFiles.markdownMime(mime);}
  }
  /** Android owns the grant list; preferences never substitute for actual permission. */
  static List<Uri> savedFolders(Context context){
    ArrayList<UriPermission> permissions=new ArrayList<>(context.getContentResolver().getPersistedUriPermissions());
    permissions.sort((a,b)->Long.compare(b.getPersistedTime(),a.getPersistedTime()));
    ArrayList<Uri> folders=new ArrayList<>();
    for(UriPermission permission:permissions)
      if(permission.isReadPermission()&&DocumentsContract.isTreeUri(permission.getUri()))folders.add(permission.getUri());
    return folders;
  }
  static boolean remember(Context context,Uri tree,int flags){
    if((flags&Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)==0)return false;
    try{
      context.getContentResolver().takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION);
      return true;
    }catch(SecurityException|IllegalArgumentException|UnsupportedOperationException unavailable){return false;}
  }
  static Entry rememberedDocument(Context context,Uri original,CancellationSignal cancellation){
    for(Uri granted:savedFolders(context)){
      cancellation.throwIfCanceled();
      if(original==null||!granted.getAuthority().equals(original.getAuthority()))continue;
      try{
        Entry entry=new PreviewTree(context,granted).identify(context,original,cancellation);
        if(entry!=null&&!entry.directory()&&entry.markdown())return entry;
      }catch(OperationCanceledException cancelled){throw cancelled;}
      catch(IOException|SecurityException|IllegalArgumentException|UnsupportedOperationException unavailable){
        // A stale or unavailable provider must not prevent reading the incoming single-file grant.
      }
    }
    return null;
  }

  final Uri uri;
  private final ContentResolver resolver;
  PreviewTree(Context context,Uri uri)throws IOException {
    if(uri==null||!"content".equals(uri.getScheme())||!DocumentsContract.isTreeUri(uri))throw new IOException("请选择可读取的文档文件夹");
    this.uri=uri;resolver=context.getContentResolver();
  }
  Entry root(){return new Entry(uri,DocumentsContract.getTreeDocumentId(uri),"授权文件夹",DocumentsContract.Document.MIME_TYPE_DIR,"");}
  List<Entry> children(Entry parent,CancellationSignal cancellation)throws IOException {
    cancellation.throwIfCanceled();
    ArrayList<Entry> result=new ArrayList<>();
    Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(uri,parent.id);
    String[] columns={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE};
    try(Cursor cursor=resolver.query(children,columns,null,null,null,cancellation)){
      if(cursor==null)throw new IOException("文件夹无法读取；授权可能已撤销，请重新授权文件夹");
      while(cursor.moveToNext()){
        cancellation.throwIfCanceled();
        String id=cursor.getString(0),name=cursor.getString(1),mime=cursor.getString(2);
        if(id==null||name==null)continue;
        try{PreviewMarkdownPaths.segment(name);}catch(IOException invalid){continue;}
        result.add(new Entry(uri,id,name,mime,parent.relative.isEmpty()?name:parent.relative+"/"+name));
      }
    }
    return result;
  }
  Entry resolve(String relative,CancellationSignal cancellation)throws IOException {
    Entry current=root();
    if(relative.isEmpty())return current;
    for(String name:relative.split("/",-1)){
      PreviewMarkdownPaths.segment(name);
      if(!current.directory())throw new IOException("资源路径不是文件夹");
      Entry found=null;
      for(Entry child:children(current,cancellation))if(child.name.equals(name)){
        if(found!=null)throw new IOException("文件提供方返回了同名文件，请重新选择唯一名称的文件");
        found=child;
      }
      if(found==null)throw new IOException("授权文件夹内找不到资源："+name);
      current=found;
    }
    return current;
  }
  /** Provider identity only: never compare display names or reinterpret opaque IDs as paths. */
  Entry identify(Context context,Uri original,CancellationSignal cancellation)throws IOException {
    if(original==null||!uri.getAuthority().equals(original.getAuthority())||!DocumentsContract.isDocumentUri(context,original))return null;
    String id=DocumentsContract.getDocumentId(original);
    for(Entry child:children(root(),cancellation))if(child.id.equals(id))return child;
    cancellation.throwIfCanceled();
    DocumentsContract.Path path;
    try{path=DocumentsContract.findDocumentPath(resolver,DocumentsContract.buildDocumentUriUsingTree(uri,id));}
    catch(java.io.FileNotFoundException|UnsupportedOperationException|IllegalArgumentException|SecurityException unsupported){return null;}
    cancellation.throwIfCanceled();
    if(path==null)return null;
    List<String> ids=path.getPath();int start=ids.indexOf(root().id);
    if(start<0)return null;
    Entry current=root();
    for(int i=start+1;i<ids.size();i++){
      Entry found=null;
      for(Entry child:children(current,cancellation))if(child.id.equals(ids.get(i))){found=child;break;}
      if(found==null)return null;
      current=found;
    }
    return current.id.equals(id)?current:null;
  }
  InputStream open(Entry entry,CancellationSignal cancellation)throws IOException {
    cancellation.throwIfCanceled();
    AssetFileDescriptor descriptor=resolver.openAssetFileDescriptor(entry.uri,"r",cancellation);
    if(descriptor==null)throw new IOException("文件提供方未返回可读内容");
    try{return descriptor.createInputStream();}catch(IOException error){descriptor.close();throw error;}
  }
  String read(Entry entry,CancellationSignal cancellation)throws IOException {
    try(InputStream input=open(entry,cancellation)){
      cancellation.setOnCancelListener(()->{try{input.close();}catch(IOException ignored){}});
      try{return PreviewFiles.readText(input);}finally{cancellation.setOnCancelListener(null);}
    }
  }
}
