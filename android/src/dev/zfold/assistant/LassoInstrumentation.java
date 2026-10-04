package dev.zfold.assistant;

import android.app.Instrumentation;
import android.graphics.*;
import android.os.Bundle;

/** Checks the pixels Pi receives from a genuine freehand selection. */
public final class LassoInstrumentation extends Instrumentation {
  @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
  @Override public void onStart(){
    Bundle result=new Bundle();int code=0;
    try{
      Bitmap screenshot=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);new Canvas(screenshot).drawColor(0xffb64d30);
      Path ring=new Path();ring.moveTo(50,10);ring.lineTo(90,50);ring.lineTo(50,90);ring.lineTo(10,50);ring.close();
      Bitmap selected=CircleOverlay.Selection.maskedCrop(screenshot,ring,new RectF(10,10,90,90),new RectF(0,0,100,100));
      if(selected.getWidth()!=80||selected.getHeight()!=80)throw new AssertionError("wrong crop size");
      if(Color.alpha(selected.getPixel(0,0))!=0||Color.alpha(selected.getPixel(79,0))!=0)throw new AssertionError("outer rectangle was not transparent");
      if(selected.getPixel(40,40)!=0xffb64d30)throw new AssertionError("content inside lasso missing");
      selected.recycle();screenshot.recycle();result.putString("shape","diamond cutout: transparent corners, opaque center");
      Bitmap wide=Bitmap.createBitmap(200,100,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(wide);canvas.drawColor(Color.BLUE);Paint dot=new Paint();dot.setColor(Color.RED);canvas.drawRect(98,48,102,52,dot);
      Path smaller=new Path();smaller.moveTo(200,130);smaller.lineTo(350,200);smaller.lineTo(200,270);smaller.lineTo(50,200);smaller.close();
      Bitmap mapped=CircleOverlay.Selection.maskedCrop(wide,smaller,new RectF(50,130,350,270),new RectF(0,100,400,300));
      if(mapped.getPixel(mapped.getWidth()/2,mapped.getHeight()/2)!=Color.RED)throw new AssertionError("letterboxed screen mapped wrong source pixels");
      if(Color.alpha(mapped.getPixel(0,0))!=0)throw new AssertionError("letterbox mask failed");
      mapped.recycle();wide.recycle();result.putString("coordinates","letterboxed screen: central source pixel retained");code=-1;
    }catch(Throwable error){result.putString("error",error.toString());}
    finish(code,result);
  }
}
