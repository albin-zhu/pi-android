package dev.zfold.assistant;

import android.app.Instrumentation;
import android.os.Bundle;

/** ADB-only check for TTS echo rejection before real-mic barge-in tests. */
public final class BargeEchoInstrumentation extends Instrumentation {
  @Override public void onCreate(Bundle arguments){start();}
  @Override public void onStart(){
    Bundle result=new Bundle();
    VoiceBargeIn monitor=new VoiceBargeIn(getTargetContext(),new VoiceBargeIn.Callback(){
      public void speech(String partial){}public void result(String text){}public void failed(String reason){}
    });
    monitor.setSpoken("现在电池电量是百分之七十九，系统版本是 Android 十六。");
    VoiceBargeIn.Classified echo=monitor.classify("现在电池电量是百分之七十九");
    VoiceBargeIn.Classified follow=monitor.classify("现在电池电量是百分之七十九，打开设置");
    VoiceBargeIn.Classified stop=monitor.classify("停止");
    VoiceBargeIn.Classified unrelated=monitor.classify("搜索联系人张三");
    boolean echoRejected=echo.echo&&echo.text.isEmpty();
    boolean followAccepted=!follow.echo&&follow.text.contains("打开设置");
    boolean stopAccepted=!stop.echo&&stop.text.equals("停止");
    boolean unrelatedAccepted=!unrelated.echo&&unrelated.text.contains("张三");
    result.putString("echoText",echo.text);result.putBoolean("echoRejected",echo.echo&&echo.text.isEmpty());
    result.putString("followText",follow.text);result.putBoolean("followAccepted",!follow.echo&&follow.text.contains("打开设置"));
    result.putBoolean("stopAccepted",!stop.echo&&stop.text.equals("停止"));
    result.putBoolean("unrelatedAccepted",!unrelated.echo&&unrelated.text.contains("张三"));
    finish(echoRejected&&followAccepted&&stopAccepted&&unrelatedAccepted?-1:0,result);
  }
}
