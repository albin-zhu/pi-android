package dev.zfold.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import java.io.IOException;
import java.io.InputStream;

/** System text/image share target: persist a terminal draft before releasing URI access. */
public final class ProcessTextActivity extends Activity {
  private boolean importing;
  @Override public void onCreate(Bundle state){super.onCreate(state);TextView status=new TextView(this);status.setText("正在保存终端草稿…");status.setPadding(32,48,32,32);setContentView(status);importShare();}
  private void importShare(){
    if(importing)return;importing=true;
    Intent intent=getIntent();CharSequence selected=intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
    if(selected==null)selected=intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
    String text=selected==null?"":selected.toString();
    Uri image=Intent.ACTION_SEND.equals(intent.getAction())&&intent.getType()!=null&&intent.getType().startsWith("image/")?intent.getParcelableExtra(Intent.EXTRA_STREAM):null;
    if(image==null&&intent.getClipData()!=null&&intent.getType()!=null&&intent.getType().startsWith("image/")&&intent.getClipData().getItemCount()>0)image=intent.getClipData().getItemAt(0).getUri();
    Uri source=image;
    new Thread(()->{
      Bitmap bitmap=null;String failure=null;
      try{
        if(source==null&&text.isEmpty())throw new IOException("分享内容没有可读取的文字或图片");
        if(source!=null)bitmap=decode(source);
        TerminalCircleDraft.capture(this,bitmap,text);
      }catch(Exception error){failure=error.getMessage();}
      finally{if(bitmap!=null)bitmap.recycle();}
      String message=failure;
      runOnUiThread(()->{
        importing=false;if(isFinishing()||isDestroyed())return;
        if(message==null){TerminalActivity.open(getApplicationContext());finish();return;}
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("尚未保存分享").setMessage(message+"\n本次分享未发送；可先处理已有草稿，再回到此页面重试。")
          .setPositiveButton("重试",(d,w)->importShare()).setNeutralButton("打开终端",null).setNegativeButton("取消分享",(d,w)->finish()).create();
        dialog.setCancelable(false);dialog.show();dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->TerminalActivity.open(getApplicationContext()));
      });
    },"terminal-share-import").start();
  }
  private Bitmap decode(Uri uri)throws IOException {
    BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
    try(InputStream input=getContentResolver().openInputStream(uri)){if(input==null)throw new IOException("无法读取分享图片");BitmapFactory.decodeStream(input,null,bounds);}
    if(bounds.outWidth<=0||bounds.outHeight<=0)throw new IOException("分享内容不是可解码图片");
    BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
    while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>2048)options.inSampleSize*=2;
    try(InputStream input=getContentResolver().openInputStream(uri)){
      if(input==null)throw new IOException("无法读取分享图片");Bitmap result=BitmapFactory.decodeStream(input,null,options);
      if(result==null)throw new IOException("分享图片解码失败");return result;
    }
  }
}
