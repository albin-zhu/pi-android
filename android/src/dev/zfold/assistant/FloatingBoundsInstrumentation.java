package dev.zfold.assistant;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;

/** Real touches at display edges and a real display rotation; emulator only. */
public final class FloatingBoundsInstrumentation extends Instrumentation {
  private String error="",autoRotate,rotation;private View bubble;
  @Override public void onCreate(Bundle args){PiRuntime.testMode=true;start();}
  @Override public void onStart(){Bundle report=new Bundle();
    try{
      EmulatorChecks.accessibility(this);EmulatorChecks.cancelPointer(this);
      autoRotate=EmulatorChecks.shell(this,"settings get system accelerometer_rotation").trim();rotation=EmulatorChecks.shell(this,"settings get system user_rotation").trim();
      EmulatorChecks.shell(this,"settings put system accelerometer_rotation 0");EmulatorChecks.shell(this,"settings put system user_rotation 0");
      getTargetContext().startActivity(new Intent().setComponent(new ComponentName("dev.zfold.fixture.screen","dev.zfold.fixture.screen.Target")).putExtra("page","blue").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));EmulatorChecks.waitText(this,"EXTERNAL SCREEN QA");
      runOnMainSync(()->{CircleOverlay.ensureBubble(getTargetContext());bubble=CircleOverlay.bubble;});
      for(int i=0;i<100&&!bubble.isLaidOut();i++)Thread.sleep(100);
      drag(1,1);checkBounds("portrait top-left");report.putBoolean("portraitTopLeft",true);
      Rect display=display();drag(display.right-1,display.bottom-1);checkBounds("portrait bottom-right");report.putBoolean("portraitBottomRight",true);
      EmulatorChecks.shell(this,"settings put system user_rotation 1");
      for(int i=0;i<100&&display().width()<display().height();i++)Thread.sleep(100);require(display().width()>display().height(),"Display did not rotate");Thread.sleep(700);checkBounds("after rotation");report.putBoolean("rotationReclamps",true);
      drag(1,1);checkBounds("landscape cutout edge");report.putBoolean("landscapeTopLeft",true);
      display=display();drag(display.right-1,display.bottom-1);checkBounds("landscape navigation edge");report.putBoolean("landscapeBottomRight",true);EmulatorChecks.screenshot(this,"floating-safe-area.png");
      long[] point=press();touch(MotionEvent.ACTION_UP,point[0],point[1],point[2]);for(int i=0;i<80&&!AssistantOverlay.isShowing();i++)Thread.sleep(100);require(AssistantOverlay.isShowing(),"Docked bubble could not be reopened");report.putBoolean("edgeStillClickable",true);
    }catch(Throwable failure){error=failure.toString();report.putString("error",error);try{EmulatorChecks.screenshot(this,"floating-boundary-failure.png");}catch(Exception ignored){}}
    finally{EmulatorChecks.cancelPointer(this);runOnMainSync(()->{AssistantOverlay.hide();CircleOverlay.hideBubble();});try{restore("accelerometer_rotation",autoRotate);restore("user_rotation",rotation);}catch(Exception ignored){}}
    finish(error.isEmpty()?-1:0,report);
  }
  private void restore(String name,String value)throws Exception{if(value==null)return;EmulatorChecks.shell(this,value.equals("null")?"settings delete system "+name:"settings put system "+name+" "+value);}
  private Rect display(){return ((WindowManager)getTargetContext().getSystemService(Context.WINDOW_SERVICE)).getMaximumWindowMetrics().getBounds();}
  private long[] press()throws Exception{long[] point=new long[3];UiAutomation automation=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);for(int attempt=0;attempt<4;attempt++){
    runOnMainSync(()->{int[] location=new int[2];bubble.getLocationOnScreen(location);point[0]=location[0]+bubble.getWidth()/2;point[1]=location[1]+bubble.getHeight()/2;point[2]=SystemClock.uptimeMillis();MotionEvent event=MotionEvent.obtain(point[2],point[2],MotionEvent.ACTION_DOWN,point[0],point[1],0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);automation.injectInputEvent(event,false);event.recycle();});
    for(int i=0;i<10;i++){boolean[] pressed={false};runOnMainSync(()->pressed[0]=bubble.isPressed());if(pressed[0])return point;Thread.sleep(15);}touch(MotionEvent.ACTION_CANCEL,point[0],point[1],point[2]);
  }throw new IllegalStateException("Touch did not reach bubble");}
  private void drag(int x,int y)throws Exception{long[] start=press();for(int i=1;i<=8;i++){float t=i/8f;touch(MotionEvent.ACTION_MOVE,start[0]+(x-start[0])*t,start[1]+(y-start[1])*t,start[2]);Thread.sleep(18);}touch(MotionEvent.ACTION_UP,x,y,start[2]);Thread.sleep(800);}
  private void touch(int action,float x,float y,long down){MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);boolean accepted=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).injectInputEvent(event,true);event.recycle();require(accepted,"Touch injection failed");}
  private void checkBounds(String phase){Rect safe=new Rect(),actual=new Rect();runOnMainSync(()->{WindowManager wm=(WindowManager)getTargetContext().getSystemService(Context.WINDOW_SERVICE);WindowMetrics metrics=wm.getMaximumWindowMetrics();safe.set(metrics.getBounds());Insets bars=metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());safe.inset(bars);int[] point=new int[2];bubble.getLocationOnScreen(point);actual.set(point[0],point[1],point[0]+bubble.getWidth(),point[1]+bubble.getHeight());});require(safe.contains(actual),phase+": bubble="+actual+", safe="+safe);}
  private static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
}
