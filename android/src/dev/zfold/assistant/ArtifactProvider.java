package dev.zfold.assistant;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Read-only, individually granted artifact links. Never exposes app-private files. */
public final class ArtifactProvider extends ContentProvider {
  static File root(Context c)throws IOException {
    return new File(android.os.Environment.getExternalStorageDirectory(),c.getPackageName().endsWith(".dev")?"pi-dev":"pi").getCanonicalFile();
  }
  static File checked(Context c,String path)throws IOException {
    File root=root(c),file=new File(path).getCanonicalFile();
    if(!file.getPath().startsWith(root.getPath()+File.separator)||!file.isFile())throw new FileNotFoundException("文件不存在或不在允许的目录内");
    return file;
  }
  static void open(Context c,String target)throws Exception {
    OpenTargets.open(c,target,"",false);
  }
  private File resolve(Uri uri)throws IOException {
    if(!uri.getAuthority().equals(getContext().getPackageName()+".artifacts")||uri.getPathSegments().size()!=1)throw new FileNotFoundException("无效文件链接");
    return checked(getContext(),new File(root(getContext()),uri.getPathSegments().get(0)).getPath());
  }
  static String mime(File file){
    String name=file.getName();int dot=name.lastIndexOf('.');
    String type=dot<0?null:android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot+1).toLowerCase(java.util.Locale.ROOT));
    return type==null?"application/octet-stream":type;
  }
  @Override public boolean onCreate(){return true;}
  @Override public String getType(Uri uri){try{return mime(resolve(uri));}catch(IOException e){return "application/octet-stream";}}
  @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){
    try{
      File file=resolve(uri);String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
      MatrixCursor cursor=new MatrixCursor(columns);Object[] row=new Object[columns.length];
      for(int i=0;i<columns.length;i++)row[i]=OpenableColumns.DISPLAY_NAME.equals(columns[i])?file.getName():OpenableColumns.SIZE.equals(columns[i])?file.length():null;
      cursor.addRow(row);return cursor;
    }catch(IOException e){throw new IllegalArgumentException(e.getMessage());}
  }
  @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException {
    if(!"r".equals(mode))throw new FileNotFoundException("只允许读取");
    try{return ParcelFileDescriptor.open(resolve(uri),ParcelFileDescriptor.MODE_READ_ONLY);}catch(IOException e){throw new FileNotFoundException(e.getMessage());}
  }
  @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException("read only");}
  @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException("read only");}
  @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException("read only");}
}
