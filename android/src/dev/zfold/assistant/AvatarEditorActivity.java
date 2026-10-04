package dev.zfold.assistant;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/** Avatar picker stage with rotation, pan, and pinch cropping. */
public final class AvatarEditorActivity extends Activity {
  interface Encoded {void done(String data);}
  private static volatile Encoded pending;
  private CropView crop;private Bitmap source;private volatile boolean loaded;

  static void edit(Context context,Uri uri,Encoded callback){
    pending=callback;
    context.startActivity(new Intent(context,AvatarEditorActivity.class).setData(uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  }

  @Override protected void onCreate(Bundle state){
    super.onCreate(state);setTheme(R.style.AppTheme);
    LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(0xff0b0f14);root.setPadding(Ui.dp(this,16),Ui.dp(this,24),Ui.dp(this,16),Ui.dp(this,16));
    setContentView(root);
    crop=new CropView(this);root.addView(crop,new LinearLayout.LayoutParams(-1,0,1));
    LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);LinearLayout.LayoutParams actionParams=new LinearLayout.LayoutParams(-1,-2);actionParams.topMargin=Ui.dp(this,14);root.addView(actions,actionParams);
    actions.addView(new Ui.Action(this,R.drawable.ic_arrow_clockwise,"","向左旋转 90°",0xffe8edf4,Color.TRANSPARENT,()->crop.rotate(false)),new LinearLayout.LayoutParams(0,-2,1));
    actions.addView(new Ui.Action(this,R.drawable.ic_arrow_clockwise,"","向右旋转 90°",0xffe8edf4,Color.TRANSPARENT,()->crop.rotate(true)),new LinearLayout.LayoutParams(0,-2,1));
    actions.addView(new Ui.Action(this,R.drawable.ic_x,"","放弃头像裁剪",0xffff9696,Color.TRANSPARENT,()->finish()),new LinearLayout.LayoutParams(0,-2,1));
    actions.addView(new Ui.Action(this,R.drawable.ic_check,"使用裁剪","确认头像裁剪",0xffffffff,0xff2f6fed,this::save),new LinearLayout.LayoutParams(0,-2,1));
    load();
  }

  private void load(){
    Uri uri=getIntent().getData();if(uri==null){finish();return;}
    new Thread(()->{
      try{
        byte[] bytes;try(java.io.InputStream input=getContentResolver().openInputStream(uri);java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream()){bytes=input.readAllBytes();}
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=Math.max(1,Math.max(bounds.outWidth,bounds.outHeight)/2048);
        Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
        runOnUiThread(()->{if(bitmap!=null){crop.setBitmap(bitmap);loaded=true;}else finish();});
      }catch(Exception error){runOnUiThread(this::finish);}
    },"avatar-load").start();
  }

  private void save(){
    Bitmap output=crop.cropBitmap();
    if(output==null){finish();return;}
    java.io.ByteArrayOutputStream outputBytes=new java.io.ByteArrayOutputStream();output.compress(Bitmap.CompressFormat.PNG,94,outputBytes);
    Encoded callback=pending;pending=null;
    if(callback!=null)callback.done(android.util.Base64.encodeToString(outputBytes.toByteArray(),android.util.Base64.NO_WRAP));
    finish();
  }

  static final class CropView extends View {
    private Bitmap source,rotated;private final Paint image=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG),mask=new Paint(),frame=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF crop=new RectF(),imageRect=new RectF(),touch=new RectF();private float scale=1,startSpacing,startScale,startX,startY;
    CropView(Context context){super(context);mask.setColor(0xaa000000);frame.setColor(0xffffffff);frame.setStyle(Paint.Style.STROKE);frame.setStrokeWidth(Ui.dp(context,2));setClickable(true);setFocusable(true);}
    void setBitmap(Bitmap value){source=value;rotated=value;reset();invalidate();}
    void rotate(boolean right){
      if(rotated==null)return;Matrix matrix=new Matrix();matrix.postRotate(right?90:-90);
      Bitmap next=Bitmap.createBitmap(rotated,0,0,rotated.getWidth(),rotated.getHeight(),matrix,true);
      if(next!=rotated)rotated=next;reset();invalidate();
    }
    private void reset(){
      int width=getWidth(),height=getHeight();
      if(width==0||height==0||rotated==null)return;
      int pad=Ui.dp(getContext(),20),side=Math.min(width,height)-pad*2;
      float fit=side/(float)Math.max(rotated.getWidth(),rotated.getHeight());scale=fit;
      int imageWidth=Math.round(rotated.getWidth()*fit),imageHeight=Math.round(rotated.getHeight()*fit);
      imageRect.set((width-imageWidth)/2f,(height-imageHeight)/2f,(width+imageWidth)/2f,(height+imageHeight)/2f);
      int cropSide=Math.min(imageWidth,imageHeight);crop.set((width-cropSide)/2f,(height-cropSide)/2f,(width+cropSide)/2f,(height+cropSide)/2f);
    }
    @Override protected void onSizeChanged(int width,int height,int oldWidth,int oldHeight){super.onSizeChanged(width,height,oldWidth,oldHeight);reset();}
    @Override protected void onDraw(Canvas canvas){
      canvas.drawColor(0xff11161d);if(rotated==null)return;
      canvas.drawBitmap(rotated,null,imageRect,image);
      canvas.drawRect(imageRect.left,imageRect.top,imageRect.right,crop.top,mask);canvas.drawRect(imageRect.left,crop.bottom,imageRect.right,imageRect.bottom,mask);
      canvas.drawRect(imageRect.left,crop.top,crop.left,crop.bottom,mask);canvas.drawRect(crop.right,crop.top,imageRect.right,crop.bottom,mask);
      canvas.drawRect(crop,frame);canvas.drawRect(crop.left-6,crop.top-6,crop.left+6,crop.top+6,frame);canvas.drawRect(crop.right-6,crop.bottom-6,crop.right+6,crop.bottom+6,frame);
    }
    @Override public boolean onTouchEvent(MotionEvent event){
      if(rotated==null)return super.onTouchEvent(event);
      switch(event.getActionMasked()){
        case MotionEvent.ACTION_DOWN:startX=event.getX();startY=event.getY();break;
        case MotionEvent.ACTION_POINTER_DOWN:
          startSpacing=spacing(event);startScale=scale;break;
        case MotionEvent.ACTION_MOVE:
          if(event.getPointerCount()>=2&&startSpacing>0){
            float next=Math.max(imageRect.width()/rotated.getWidth(),Math.min(imageRect.width()*3/rotated.getWidth(),startScale*spacing(event)/startSpacing));
            scaleAround(next,event.getX(),event.getY());
          }else{
            float dx=event.getX()-startX,dy=event.getY()-startY;startX=event.getX();startY=event.getY();
            imageRect.offset(dx,dy);clampImage();syncCrop();
          }
          invalidate();return true;
        case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:startSpacing=0;break;
      }
      return true;
    }
    private float spacing(MotionEvent event){
      float dx=event.getX(0)-event.getX(1),dy=event.getY(0)-event.getY(1);return (float)Math.hypot(dx,dy);
    }
    private void scaleAround(float next,float focusX,float focusY){
      float ratio=next/scale;scale=next;
      float cx=imageRect.centerX(),cy=imageRect.centerY();
      float width=imageRect.width()*ratio,height=imageRect.height()*ratio;
      imageRect.set(focusX-(focusX-cx)*ratio,focusY-(focusY-cy)*ratio,focusX+(cx-focusX)*ratio,focusY+(cy-focusY)*ratio);
      clampImage();syncCrop();
    }
    private void clampImage(){
      float width=imageRect.width(),height=imageRect.height();
      float left=Math.min(Math.max(imageRect.left,getWidth()-width),Math.max(0,(getWidth()-width)/2));
      float top=Math.min(Math.max(imageRect.top,getHeight()-height),Math.max(0,(getHeight()-height)/2));
      imageRect.offsetTo(left,top);syncCrop();
    }
    private void syncCrop(){
      touch.set(crop);if(!touch.intersects(imageRect.left,imageRect.top,imageRect.right,imageRect.bottom))return;
      crop.intersect(imageRect);float side=Math.min(crop.width(),crop.height());crop.set(crop.centerX()-side/2,crop.centerY()-side/2,crop.centerX()+side/2,crop.centerY()+side/2);
    }
    Bitmap cropBitmap(){
      Matrix inverse=new Matrix();
      float imageScale=rotated.getWidth()/(float)imageRect.width();
      inverse.setTranslate(-imageRect.left,-imageRect.top);inverse.postScale(imageScale,imageScale);
      float[] corners={crop.left,crop.top,crop.right,crop.bottom};inverse.mapPoints(corners);
      RectF mapped=new RectF(Math.min(corners[0],corners[2]),Math.min(corners[1],corners[3]),Math.max(corners[0],corners[2]),Math.max(corners[1],corners[3]));
      int left=Math.max(0,Math.round(mapped.left)),top=Math.max(0,Math.round(mapped.top));
      int right=Math.min(rotated.getWidth(),Math.round(mapped.right)),bottom=Math.min(rotated.getHeight(),Math.round(mapped.bottom));
      if(right-left<8||bottom-top<8)return null;
      Bitmap cropped=Bitmap.createBitmap(rotated,left,top,right-left,bottom-top);
      int side=Math.min(512,Math.max(cropped.getWidth(),cropped.getHeight()));float scale=side/(float)Math.max(cropped.getWidth(),cropped.getHeight());
      int width=Math.max(1,Math.round(cropped.getWidth()*scale)),height=Math.max(1,Math.round(cropped.getHeight()*scale));
      Bitmap output=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(output);canvas.drawBitmap(cropped,null,new RectF(0,0,width,height),new Paint(Paint.FILTER_BITMAP_FLAG));return output;
    }
  }
}
