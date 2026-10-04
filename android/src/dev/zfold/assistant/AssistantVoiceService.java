package dev.zfold.assistant;
import android.service.voice.VoiceInteractionService;
public final class AssistantVoiceService extends VoiceInteractionService {
  @Override public void onReady(){super.onReady();PiRuntime.get(this);}
}
