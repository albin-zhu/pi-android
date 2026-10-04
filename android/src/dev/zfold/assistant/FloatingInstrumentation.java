package dev.zfold.assistant;
import android.app.Instrumentation;
import android.os.Bundle;
import java.util.concurrent.*;
import org.json.JSONObject;

/** ADB-only test: Pi opens Settings while the assistant becomes an accessibility bubble. */
public final class FloatingInstrumentation extends Instrumentation {
  private Bundle options;private String error,result="";private boolean opened,read,expanded,collapsedAgain,voiceSource;
  private final CountDownLatch done=new CountDownLatch(1);
  @Override public void onCreate(Bundle arguments){options=arguments;voiceSource=Boolean.parseBoolean(arguments.getString("voice","false"));start();}
  @Override public void onStart(){
    runOnMainSync(()->{
      PiRuntime.testMode=true;PiRuntime runtime=PiRuntime.get(getTargetContext());
      runtime.listen(event->{
        try{
          switch(event.optString("type")){
            case "ready":runtime.send(PiRuntime.json("type","configure","config",PiRuntime.json("baseUrl",options.getString("endpoint","http://127.0.0.1:18870/v1"),"model","zfold-device-test","apiKey","fixture")));break;
            case "configured":new Thread(()->{
              for(int i=0;i<150&&PhoneAccessibility.instance==null;i++)try{Thread.sleep(200);}catch(InterruptedException ignored){}
              if(PhoneAccessibility.instance==null){error="Accessibility service did not bind";done.countDown();return;}
              if(voiceSource){for(int i=0;i<150&&!AssistantSessionService.isShowing();i++)try{Thread.sleep(200);}catch(InterruptedException ignored){}
                if(!AssistantSessionService.isShowing()){error="Assistant session did not appear";done.countDown();return;}}
              runOnMainSync(()->{if(!voiceSource)AssistantOverlay.show(getTargetContext());runtime.send(PiRuntime.json("type","prompt","id","floating-control","text","打开系统设置并查看页面。"));});
            },"wait-accessibility").start();break;
            case "tool_request":if("app_open".equals(event.optString("name")))opened=true;if("screen_read".equals(event.optString("name")))read=true;break;
            case "delta":result+=event.optString("text");break;
            case "turn_end":done.countDown();break;
            case "error":error=event.optString("message");done.countDown();break;
          }
        }catch(Exception e){error=e.getMessage();done.countDown();}
      });
    });
    try{if(!done.await(70,TimeUnit.SECONDS))error="Timed out";}catch(Exception e){error=e.getMessage();}
    boolean firstBubble=CircleOverlay.hasBubble()&&CircleOverlay.accessibilityBubble&&!AssistantOverlay.isShowing()&&!AssistantSessionService.isShowing();
    if(error==null&&firstBubble)runOnMainSync(()->{
      android.view.View view=CircleOverlay.bubble;long now=android.os.SystemClock.uptimeMillis();
      android.view.MotionEvent down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,12,12,0);
      android.view.MotionEvent up=android.view.MotionEvent.obtain(now,now+80,android.view.MotionEvent.ACTION_UP,12,12,0);
      view.dispatchTouchEvent(down);view.dispatchTouchEvent(up);down.recycle();up.recycle();
      expanded=AssistantOverlay.isAccessibilityPanel()&&!CircleOverlay.hasBubble();
      if(expanded){AssistantOverlay.minimizeForPhoneControl(getTargetContext());collapsedAgain=CircleOverlay.hasBubble()&&CircleOverlay.accessibilityBubble&&!AssistantOverlay.isShowing();}
    });
    Bundle outcome=new Bundle();outcome.putString("result",result);outcome.putString("error",error);outcome.putBoolean("opened",opened);outcome.putBoolean("read",read);outcome.putBoolean("voiceSource",voiceSource);
    outcome.putBoolean("bubble",firstBubble);outcome.putBoolean("expanded",expanded);outcome.putBoolean("collapsedAgain",collapsedAgain);
    finish(error==null&&opened&&read&&firstBubble&&expanded&&collapsedAgain&&result.contains("已打开设置并读取目标页面")?-1:0,outcome);
  }
}
