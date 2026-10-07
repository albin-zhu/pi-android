package dev.zfold.assistant;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.inputmethod.InputMethodManager;
import java.io.InputStream;

/** System text/image selection share target: tiny overlay, no chat takeover. */
public final class ProcessTextActivity extends Activity {
  @Override public void onCreate(Bundle state){
    super.onCreate(state);
    Intent intent=getIntent();
    CharSequence selected=intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
    if(selected!=null&&selected.length()>0){
      String text=selected.toString();
      if(text.length()>4000)text=text.substring(0,4000);
      finish();overridePendingTransition(0,0);
      SelectionAskOverlay.showText(getApplicationContext(),text);
      return;
    }
    if(Intent.ACTION_SEND.equals(intent.getAction())&&intent.getType()!=null&&intent.getType().startsWith("image/")){
      Uri uri=intent.getParcelableExtra(Intent.EXTRA_STREAM);
      String image=encode(uri);
      finish();overridePendingTransition(0,0);
      if(image!=null)SelectionAskOverlay.showImage(getApplicationContext(),image);
      return;
    }
    finish();overridePendingTransition(0,0);
  }

  private String encode(Uri uri){
    if(uri==null)return null;
    try(InputStream in=getContentResolver().openInputStream(uri)){
      if(in==null)return null;
      Bitmap bitmap=BitmapFactory.decodeStream(in);
      if(bitmap==null)return null;
      return PhoneAccessibility.encode(bitmap);
    }catch(Exception ignored){return null;}
  }
}
