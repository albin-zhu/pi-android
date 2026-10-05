package dev.zfold.assistant;

import android.graphics.*;
import android.media.ExifInterface;
import android.util.Base64;
import java.io.*;
import java.util.UUID;
import org.json.JSONObject;

/** A request-bound still image, never a screen-coordinate observation. */
final class CameraFrame {
  static final int MAX_BYTES=512*1024,MAX_EDGE=1280;
  final byte[] jpeg;final String id,cameraId,facing;final long capturedAt,generation;
  final int width,height;
  private CameraFrame(byte[] jpeg,int width,int height,String cameraId,String facing,long at,long generation){
    this.jpeg=jpeg;this.width=width;this.height=height;this.cameraId=cameraId;this.facing=facing;capturedAt=at;this.generation=generation;id=UUID.randomUUID().toString();
  }
  static CameraFrame encode(byte[] source,String cameraId,String facing,long at,long generation)throws IOException {
    Bitmap bitmap=BitmapFactory.decodeByteArray(source,0,source.length);
    if(bitmap==null)throw new IOException("无法解码相机画面");
    try{
      int orientation=new ExifInterface(new ByteArrayInputStream(source)).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL);
      Matrix rotation=new Matrix();
      switch(orientation){
        case 2:rotation.setScale(-1,1);break;case 3:rotation.setRotate(180);break;case 4:rotation.setScale(1,-1);break;
        case 5:rotation.setRotate(90);rotation.postScale(-1,1);break;case 6:rotation.setRotate(90);break;
        case 7:rotation.setRotate(-90);rotation.postScale(-1,1);break;case 8:rotation.setRotate(-90);break;
      }
      if(!rotation.isIdentity()){Bitmap next=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),rotation,true);if(next!=bitmap)bitmap.recycle();bitmap=next;}
      int edge=Math.max(bitmap.getWidth(),bitmap.getHeight());
      if(edge>MAX_EDGE){Bitmap next=Bitmap.createScaledBitmap(bitmap,Math.max(1,bitmap.getWidth()*MAX_EDGE/edge),Math.max(1,bitmap.getHeight()*MAX_EDGE/edge),true);bitmap.recycle();bitmap=next;}
      for(int attempt=0;attempt<4;attempt++){
        for(int quality:new int[]{80,68,55}){
          ByteArrayOutputStream out=new ByteArrayOutputStream();if(!bitmap.compress(Bitmap.CompressFormat.JPEG,quality,out))throw new IOException("相机画面压缩失败");
          if(out.size()<=MAX_BYTES)return new CameraFrame(out.toByteArray(),bitmap.getWidth(),bitmap.getHeight(),cameraId,facing,at,generation);
        }
        Bitmap next=Bitmap.createScaledBitmap(bitmap,Math.max(1,bitmap.getWidth()*3/4),Math.max(1,bitmap.getHeight()*3/4),true);bitmap.recycle();bitmap=next;
      }
      throw new IOException("画面压缩后仍超过 512 KiB");
    }finally{bitmap.recycle();}
  }
  String base64(){return Base64.encodeToString(jpeg,Base64.NO_WRAP);}
  JSONObject metadata(){return PiRuntime.json("source","camera","frameId",id,"cameraId",cameraId,"facing",facing,"capturedAt",capturedAt,"generation",generation,"width",width,"height",height,"mime","image/jpeg","bytes",jpeg.length,"mirrored",false);}
  JSONObject attachment(){JSONObject value=metadata();try{value.put("data",base64());}catch(Exception impossible){throw new IllegalStateException(impossible);}return value;}
}
