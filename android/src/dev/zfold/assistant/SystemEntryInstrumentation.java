package dev.zfold.assistant;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;

/** Actual Android assistant role, with reversible emulator-only setup. */
public final class SystemEntryInstrumentation extends Instrumentation {
  private String originalRole,originalVoice;private boolean roleChanged;private String error="";
  @Override public void onCreate(Bundle args){PiRuntime.testMode=true;start();}
  @Override public void onStart(){Bundle report=new Bundle();
    try{
      EmulatorChecks.accessibility(this);
      originalRole=EmulatorChecks.shell(this,"cmd role get-role-holders android.app.role.ASSISTANT").trim();
      originalVoice=EmulatorChecks.shell(this,"settings get secure voice_interaction_service").trim();
      report.putString("roleSetup",EmulatorChecks.shell(this,"cmd role add-role-holder android.app.role.ASSISTANT dev.zfold.assistant").trim());roleChanged=true;
      EmulatorChecks.shell(this,"settings put secure voice_interaction_service dev.zfold.assistant/dev.zfold.assistant.AssistantVoiceService");
      report.putString("voiceService",EmulatorChecks.shell(this,"settings get secure voice_interaction_service").trim());
      getTargetContext().startActivity(new Intent().setComponent(new ComponentName("dev.zfold.fixture.screen","dev.zfold.fixture.screen.Target")).putExtra("page","blue").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(500);
      showSystem();AssistantPanel panel=waitPanel(true);
      require(!AssistantOverlay.isShowing()&&!CircleOverlay.hasBubble(),"System assistant overlapped another entry");report.putBoolean("systemRoleEntry",true);
      runOnMainSync(panel::toggleScreenShare);for(int i=0;i<150;i++){boolean[] ready={false};runOnMainSync(()->ready[0]=!panel.capturingScreen&&panel.image!=null);if(ready[0])break;Thread.sleep(100);}
      byte[] captured=android.util.Base64.decode(panel.image,android.util.Base64.DEFAULT);Bitmap screen=android.graphics.BitmapFactory.decodeByteArray(captured,0,captured.length);int color=screen.getPixel(screen.getWidth()/8,screen.getHeight()*3/4);screen.recycle();require(color==0xff245dd6,"System entry captured its own window");report.putBoolean("systemCapturesExternalApp",true);
      Bitmap pixel=Bitmap.createBitmap(12,12,Bitmap.Config.ARGB_8888);pixel.eraseColor(0xff31a27c);String image=PhoneAccessibility.encode(pixel);
      runOnMainSync(()->{panel.input.setText("系统入口草稿");panel.attachImage(image,"草稿图片");ScreenContext.setEnabled(true);});
      ActivityMonitor monitor=addMonitor(WorkbenchActivity.class.getName(),null,false);
      runOnMainSync(()->{View action=find(panel,"打开工作台");require(action!=null,"System panel workbench action missing");action.performClick();});
      Activity workbench=waitForMonitorWithTimeout(monitor,15000);removeMonitor(monitor);require(workbench!=null,"Workbench did not open from system assistant");
      for(int i=0;i<50&&AssistantSessionService.isShowing();i++)Thread.sleep(100);require(!AssistantSessionService.isShowing()&&!AssistantOverlay.isShowing()&&!CircleOverlay.hasBubble(),"Assistant stayed over workbench");
      runOnMainSync(workbench::onBackPressed);AssistantPanel returned=waitPanel(true);
      require(returned.input.getText().toString().equals("系统入口草稿")&&image.equals(returned.image)&&ScreenContext.enabled(),"System-origin return lost the draft or changed entry type");report.putBoolean("systemWorkbenchReturn",true);
      runOnMainSync(()->AssistantOverlay.show(getTargetContext()));AssistantPanel floating=waitPanel(false);
      require(floating.input.getText().toString().equals("系统入口草稿")&&image.equals(floating.image)&&!AssistantSessionService.isShowing(),"System-to-overlay handoff lost content or duplicated entry");report.putBoolean("systemToOverlay",true);
      showSystem();AssistantPanel again=waitPanel(true);Thread.sleep(800);
      require(!AssistantOverlay.isShowing()&&again.input.getText().toString().equals("系统入口草稿")&&image.equals(again.image),"System reinvocation replaced the saved attachment");report.putBoolean("overlayToSystemPreservesAttachment",true);
      EmulatorChecks.screenshot(this,"system-assistant-draft.png");runOnMainSync(again.host::select);EmulatorChecks.click(this,"取消圈选");AssistantPanel afterSelection=waitPanel(true);require(image.equals(afterSelection.image)&&afterSelection.input.getText().toString().equals("系统入口草稿"),"Cancelling system selection lost the draft");report.putBoolean("systemSelectionWithoutExtraOverlayPermission",true);runOnMainSync(afterSelection.host::close);
      for(int i=0;i<50&&AssistantSessionService.isShowing();i++)Thread.sleep(100);require(!AssistantSessionService.isShowing()&&!CircleOverlay.hasBubble()&&!ScreenContext.enabled(),"System close did not close the entry");report.putBoolean("systemClose",true);
    }catch(Throwable failure){error=failure.toString();report.putString("error",error);try{EmulatorChecks.screenshot(this,"system-entry-failure.png");}catch(Exception ignored){}}
    finally{runOnMainSync(()->{ScreenContext.setEnabled(false);AssistantOverlay.hide();AssistantSessionService.yieldToOverlay();CircleOverlay.hideBubble();UnifiedOverlay.forceHide();});if(roleChanged)try{if(originalRole!=null&&originalRole.matches("[a-zA-Z0-9_.]+"))EmulatorChecks.shell(this,"cmd role add-role-holder android.app.role.ASSISTANT "+originalRole);else EmulatorChecks.shell(this,"cmd role clear-role-holders android.app.role.ASSISTANT");if(originalVoice!=null&&originalVoice.matches("[a-zA-Z0-9_./]+")&&!originalVoice.equals("null"))EmulatorChecks.shell(this,"settings put secure voice_interaction_service "+originalVoice);else EmulatorChecks.shell(this,"settings delete secure voice_interaction_service");}catch(Exception failure){error=failure.toString();report.putString("restoreError",error);}}
    finish(error.isEmpty()?-1:0,report);
  }
  private void showSystem()throws Exception{for(int i=0;i<150;i++){boolean[] shown={false};runOnMainSync(()->shown[0]=AssistantVoiceService.showCurrentSession());if(shown[0])return;Thread.sleep(100);}throw new IllegalStateException("Active voice service did not become ready");}
  private AssistantPanel waitPanel(boolean system)throws Exception{for(int i=0;i<200;i++){AssistantPanel[] found={null};runOnMainSync(()->{AssistantPanel candidate=system?AssistantSessionService.currentPanel():AssistantOverlay.currentPanel();if(candidate!=null&&!candidate.disposed&&candidate.isAttachedToWindow()&&candidate.isLaidOut())found[0]=candidate;});if(found[0]!=null)return found[0];Thread.sleep(100);}throw new IllegalStateException("Assistant entry not ready: "+(system?"system":"overlay"));}
  private static View find(View root,String description){if(description.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View match=find(((ViewGroup)root).getChildAt(i),description);if(match!=null)return match;}return null;}
  private static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
}
