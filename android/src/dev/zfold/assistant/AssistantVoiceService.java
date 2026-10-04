package dev.zfold.assistant;
import android.service.voice.VoiceInteractionService;
public final class AssistantVoiceService extends VoiceInteractionService {
  private static AssistantVoiceService active;
  @Override public void onReady(){super.onReady();active=this;PiRuntime.get(this);}
  @Override public void onShutdown(){if(active==this)active=null;super.onShutdown();}
  static boolean showCurrentSession(){if(active==null)return false;try{active.showSession(new android.os.Bundle(),android.service.voice.VoiceInteractionSession.SHOW_SOURCE_APPLICATION);return true;}catch(RuntimeException error){return false;}}
}
