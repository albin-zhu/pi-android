package dev.zfold.assistant;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** ADB-only probe: toggles explicit screen-share chat and verifies a fresh screenshot handoff. */
public final class ScreenShareInstrumentation extends Instrumentation {
  @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
  @Override public void onStart(){
    Bundle result=new Bundle();
    try{
      Context context=getTargetContext();
      for(int i=0;i<100&&PhoneAccessibility.instance==null;i++)Thread.sleep(100);
      if(PhoneAccessibility.instance==null)throw new IllegalStateException("无障碍服务未连接");
      runOnMainSync(()->AssistantOverlay.hide());
      runOnMainSync(()->AssistantOverlay.show(context,null));
      AssistantPanel panel=null;
      for(int i=0;i<50&&panel==null;i++){panel=AssistantOverlay.currentPanel();if(panel==null)Thread.sleep(100);}
      if(panel==null)throw new IllegalStateException("助手面板未展开");
      AssistantPanel active=panel;
      runOnMainSync(active::toggleScreenShare);
      boolean captured=false;
      for(int i=0;i<100&&!captured;i++){
        captured=active.screenSharing&&!active.capturingScreen&&active.image!=null&&"共享屏幕".equals(active.imageLabel);
        if(!captured)Thread.sleep(100);
      }
      result.putBoolean("captured",captured);
      result.putString("state",active.state.getText().toString());
      if(!captured)throw new IllegalStateException("共享屏幕截图未就绪");
      final AssistantPanel closing=active;
      final CountDownLatch closed=new CountDownLatch(1);
      runOnMainSync(()->{
        try{closing.toggleScreenShare();}finally{AssistantOverlay.hide();closed.countDown();}
      });
      boolean closedSuccessfully=closed.await(3,TimeUnit.SECONDS);
      result.putBoolean("closed",closedSuccessfully);
      finish(closedSuccessfully?-1:0,result);
    }catch(Throwable error){
      result.putString("failure",error.toString());
      runOnMainSync(()->AssistantOverlay.hide());
      finish(0,result);
    }
  }
}
