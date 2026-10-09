package dev.zfold.assistant;

import android.content.*;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import java.io.*;
import java.util.UUID;

/** User-triggered copy of original image bytes to Pictures/橙橙. No broad storage permission. */
final class ImageDownloads {
  static final long MAX_BYTES=64L*1024*1024;
  static Uri save(Context c,String path,String encoded)throws Exception {
    File file=path==null?null:ChatImages.checked(c,path);byte[] bytes=null;
    BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
    if(file!=null){if(file.length()<=0||file.length()>MAX_BYTES)throw new IOException("图片过大或为空");BitmapFactory.decodeFile(file.getPath(),bounds);}
    else{if(encoded==null||encoded.length()>MAX_BYTES*4/3+16)throw new IOException("图片过大或为空");bytes=Base64.decode(encoded,Base64.DEFAULT);if(bytes.length==0||bytes.length>MAX_BYTES)throw new IOException("图片过大或为空");BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);}
    String mime=bounds.outMimeType;
    if(bounds.outWidth<=0||bounds.outHeight<=0||mime==null||!mime.startsWith("image/"))throw new IOException("不是可保存的图片");
    String extension=android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);if(extension==null)throw new IOException("暂不支持该图片格式");
    String name="橙橙-"+System.currentTimeMillis()+"-"+UUID.randomUUID().toString().substring(0,8)+"."+extension;
    ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,name);values.put(MediaStore.Images.Media.MIME_TYPE,mime);values.put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/橙橙");values.put(MediaStore.Images.Media.IS_PENDING,1);
    ContentResolver resolver=c.getContentResolver();Uri uri=resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),values);if(uri==null)throw new IOException("无法创建相册图片");
    try{
      try(InputStream input=file!=null?new FileInputStream(file):new ByteArrayInputStream(bytes);OutputStream output=resolver.openOutputStream(uri,"w")){
        if(output==null)throw new IOException("无法写入图片");byte[] buffer=new byte[32768];long copied=0;int n;
        while((n=input.read(buffer))!=-1){copied+=n;if(copied>MAX_BYTES)throw new IOException("图片过大");output.write(buffer,0,n);}output.flush();if(copied==0)throw new IOException("图片为空");
      }
      values.clear();values.put(MediaStore.Images.Media.IS_PENDING,0);if(resolver.update(uri,values,null,null)!=1)throw new IOException("图片保存未完成");return uri;
    }catch(Exception error){try{resolver.delete(uri,null,null);}catch(Exception ignored){}throw error;}
  }
}
