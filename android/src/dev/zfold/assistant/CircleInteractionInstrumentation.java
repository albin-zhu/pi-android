package dev.zfold.assistant;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Capture another app and draw an actual touch path without application-overlay permission. */
public final class CircleInteractionInstrumentation extends Instrumentation {
  @Override public void onCreate(Bundle args){PiRuntime.testMode=true;start();}
  @Override public void onStart(){Bundle report=new Bundle();String error="";
    try{
      EmulatorChecks.accessibility(this);EmulatorChecks.cancelPointer(this);
      if(android.provider.Settings.canDrawOverlays(getTargetContext()))throw new IllegalStateException("Acceptance requires no separate overlay grant");
      getTargetContext().startActivity(new Intent().setComponent(new ComponentName("dev.zfold.fixture.screen","dev.zfold.fixture.screen.Target")).putExtra("page","blue").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));EmulatorChecks.waitText(this,"EXTERNAL SCREEN QA");
      CountDownLatch selected=new CountDownLatch(1);AtomicReference<String> data=new AtomicReference<>();
      runOnMainSync(()->CircleOverlay.begin(getTargetContext(),image->{data.set(image);selected.countDown();}));
      EmulatorChecks.waitText(this,"沿着内容画一圈");Thread.sleep(300);
      Rect display=((WindowManager)getTargetContext().getSystemService(Context.WINDOW_SERVICE)).getMaximumWindowMetrics().getBounds();float cx=display.width()*.5f,cy=display.height()*.32f,d=display.width()*.16f;
      float[][] points={{cx,cy-d},{cx+d,cy},{cx,cy+d},{cx-d,cy},{cx,cy-d}};long down=SystemClock.uptimeMillis();touch(MotionEvent.ACTION_DOWN,points[0][0],points[0][1],down);
      for(int edge=0;edge<4;edge++)for(int step=1;step<=6;step++){float t=step/6f;touch(MotionEvent.ACTION_MOVE,points[edge][0]+t*(points[edge+1][0]-points[edge][0]),points[edge][1]+t*(points[edge+1][1]-points[edge][1]),down);Thread.sleep(12);}
      touch(MotionEvent.ACTION_UP,points[4][0],points[4][1],down);EmulatorChecks.screenshot(this,"circle-accessibility-selection.png");EmulatorChecks.click(this,"使用当前选区");
      if(!selected.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Selection was not delivered");byte[] bytes=android.util.Base64.decode(data.get(),android.util.Base64.DEFAULT);Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);
      if(bitmap==null||bitmap.getWidth()<100||bitmap.getHeight()<100)throw new IllegalStateException("Selection was empty");if(bitmap.getPixel(bitmap.getWidth()/2,bitmap.getHeight()/2)!=0xff245dd6)throw new IllegalStateException("Selection did not contain the external app");if(Color.alpha(bitmap.getPixel(0,0))!=0)throw new IllegalStateException("Freehand outside region was not transparent");bitmap.recycle();
      report.putBoolean("noExtraOverlayPermission",true);report.putBoolean("externalAppCaptured",true);report.putBoolean("realFreehandMask",true);
    }catch(Throwable failure){error=failure.toString();report.putString("error",error);try{EmulatorChecks.screenshot(this,"circle-interaction-failure.png");}catch(Exception ignored){}}
    finally{EmulatorChecks.cancelPointer(this);}
    finish(error.isEmpty()?-1:0,report);
  }
  private void touch(int action,float x,float y,long down){MotionEvent.PointerProperties p=new MotionEvent.PointerProperties();p.id=0;p.toolType=MotionEvent.TOOL_TYPE_FINGER;MotionEvent.PointerCoords c=new MotionEvent.PointerCoords();c.x=x;c.y=y;c.pressure=1;c.size=1;MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,1,new MotionEvent.PointerProperties[]{p},new MotionEvent.PointerCoords[]{c},0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);boolean accepted=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).injectInputEvent(event,true);event.recycle();if(!accepted)throw new IllegalStateException("Freehand event rejected: "+action);}
}
