package dev.zfold.assistant;
import android.content.Context;

/** Leave one floating entry suspended while a native work screen owns focus. */
final class FloatingNavigation {
  private static boolean systemOrigin;
  static void restore(Context context){if(systemOrigin&&AssistantVoiceService.showCurrentSession()){systemOrigin=false;return;}systemOrigin=false;AssistantOverlay.show(context);}
  static boolean suspend(Context context){
    boolean floating=AssistantOverlay.isShowing()||AssistantSessionService.isShowing()||CircleOverlay.hasBubble();
    if(floating)systemOrigin=AssistantSessionService.isShowing();
    AssistantSessionService.yieldToOverlay();AssistantOverlay.minimizeForPhoneControl(context);CircleOverlay.hideBubble();UnifiedOverlay.forceHide();return floating;
  }
}
