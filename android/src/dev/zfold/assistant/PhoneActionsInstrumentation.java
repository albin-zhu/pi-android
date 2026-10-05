package dev.zfold.assistant;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.media.ExifInterface;
import android.os.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Isolated receipt/intent/image tests. Never invokes telephony, sends SMS, or reads contacts. */
public final class PhoneActionsInstrumentation extends Instrumentation {
  @Override public void onCreate(Bundle args){start();}
  @Override public void onStart(){Bundle report=new Bundle();File fixture=null;String error="";
    try{
      if(!Build.HARDWARE.contains("ranchu")&&!Build.HARDWARE.contains("goldfish"))throw new IllegalStateException("Emulator only");
      fixture=Files.createTempDirectory(getTargetContext().getCacheDir().toPath(),"phone-capabilities-").toFile();File root=fixture;
      Context context=new ContextWrapper(getTargetContext()){public File getNoBackupFilesDir(){return root;}};
      check(PhoneActions.callIntent("+86 (138) 0013-8000",false).getAction().equals(Intent.ACTION_DIAL),"Dial intent");
      check(PhoneActions.callIntent("123456789",true).getAction().equals(Intent.ACTION_CALL),"Call intent");
      Intent sms=PhoneActions.smsIntent("123456789","中文短信 & literal ? text");check(sms.getData().toString().equals("smsto:123456789")&&sms.getStringExtra("sms_body").contains("中文短信"),"SMS encoding");
      for(String bad:new String[]{"*123#","tel:123456789","123;456","123,456","someone"})try{PhoneActions.number(bad);throw new IllegalStateException("Unsafe number accepted");}catch(IllegalStateException badTest){throw badTest;}catch(Exception expected){}
      report.putBoolean("validatedCallAndSmsIntents",true);
      String digest=SmsReceiptStore.digest("123456789","fixture only",1);
      SmsReceiptStore.reserve(context,"fixture-message",digest,"123456789",1,2);
      check(SmsReceiptStore.reserve(context,"fixture-message",digest,"123456789",1,2).optString("status").equals("dispatching"),"Duplicate receipt did not reuse operation");
      try{SmsReceiptStore.reserve(context,"fixture-message","different","123456789",1,2);throw new IllegalStateException("Conflicting operation accepted");}catch(IOException expected){}
      SmsReceiptStore.part(context,"fixture-message",0,Activity.RESULT_OK);check(SmsReceiptStore.get(context,"fixture-message").optString("status").equals("dispatching"),"Premature multipart success");
      SmsReceiptStore.part(context,"fixture-message",1,Activity.RESULT_OK);JSONObject receipt=SmsReceiptStore.publicReceipt(SmsReceiptStore.get(context,"fixture-message"));check(receipt.optBoolean("sent")&&!receipt.optBoolean("deliveryConfirmed")&&!receipt.has("digest"),"Sending versus delivery receipt");
      SmsReceiptStore.part(context,"fixture-message",1,5);check(SmsReceiptStore.get(context,"fixture-message").optBoolean("sent"),"Duplicate callback overwrote terminal state");
      SmsReceiptStore.reserve(context,"partial",digest,"123456789",1,2);SmsReceiptStore.part(context,"partial",0,Activity.RESULT_OK);SmsReceiptStore.part(context,"partial",1,5);check(SmsReceiptStore.get(context,"partial").optString("status").equals("partial"),"Partial send claimed success");
      JSONObject unknown=SmsReceiptStore.reserve(context,"unknown",digest,"123456789",1,1);unknown.put("createdAt",System.currentTimeMillis()-31000);check(SmsReceiptStore.publicReceipt(unknown).optString("status").equals("unknown"),"Expired receipt claimed success");
      check(new String(Files.readAllBytes(new File(root,"sms-receipts/fixture-message.json").toPath())).indexOf("fixture only")<0,"Receipt saved full message content");
      report.putBoolean("durableIdempotentSmsReceipts",true);report.putBoolean("multipartAndUnknownResults",true);

      Bitmap bitmap=Bitmap.createBitmap(160,80,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.RED);Paint paint=new Paint();paint.setColor(Color.BLUE);canvas.drawRect(80,0,160,80,paint);
      File jpeg=new File(root,"oriented.jpg");try(FileOutputStream out=new FileOutputStream(jpeg)){bitmap.compress(Bitmap.CompressFormat.JPEG,95,out);}bitmap.recycle();
      ExifInterface exif=new ExifInterface(jpeg.getPath());exif.setAttribute(ExifInterface.TAG_ORIENTATION,"6");exif.saveAttributes();CameraFrame frame=CameraFrame.encode(Files.readAllBytes(jpeg.toPath()),"fixture","back",1,1);
      check(frame.width==80&&frame.height==160&&frame.jpeg.length<=CameraFrame.MAX_BYTES,"EXIF normalization");Bitmap decoded=BitmapFactory.decodeByteArray(frame.jpeg,0,frame.jpeg.length);check(Color.red(decoded.getPixel(40,20))>200&&Color.blue(decoded.getPixel(40,140))>200,"Image rotation reversed");decoded.recycle();
      Bitmap noisy=Bitmap.createBitmap(2000,1600,Bitmap.Config.ARGB_8888);int[] pixels=new int[2000*1600];Random random=new Random(123);for(int i=0;i<pixels.length;i++)pixels[i]=0xff000000|random.nextInt(0xffffff);noisy.setPixels(pixels,0,2000,0,0,2000,1600);ByteArrayOutputStream out=new ByteArrayOutputStream();noisy.compress(Bitmap.CompressFormat.JPEG,100,out);noisy.recycle();
      CameraFrame compressed=CameraFrame.encode(out.toByteArray(),"fixture","front",2,2);check(compressed.jpeg.length<=512*1024&&Math.max(compressed.width,compressed.height)<=1280,"Compression budget");check(compressed.attachment().getString("mime").equals("image/jpeg"),"JPEG MIME");
      report.putBoolean("jpegOrientationAndSizeBudget",true);report.putInt("compressedBytes",compressed.jpeg.length);
    }catch(Throwable failure){error=failure.toString();report.putString("error",error);}
    finally{if(fixture!=null)try(var paths=Files.walk(fixture.toPath())){paths.sorted(Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(Exception ignored){}});}catch(Exception ignored){}}
    finish(error.isEmpty()?-1:0,report);
  }
  private static void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
}
