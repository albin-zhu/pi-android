package dev.zfold.assistant;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.concurrent.*;

/** Actual Android freeform task resizing; isolated emulator data, never a personal phone. */
public final class SystemWindowInstrumentation extends Instrumentation {
  private MainActivity chat;private String error="";private int task;private String picture;
  @Override public void onCreate(Bundle args){PiRuntime.testMode=true;start();}
  @Override public void onStart(){Bundle report=new Bundle();
    try{
      EmulatorChecks.accessibility(this);
      EmulatorChecks.shell(this,"am start --windowingMode 5 -n dev.zfold.assistant/.MainActivity");
      for(int i=0;i<100&&MainActivity.current()==null;i++)Thread.sleep(100);
      chat=MainActivity.current();require(chat!=null,"Chat missing");task=chat.getTaskId();require(chat.isInMultiWindowMode(),"Not a real system window");
      for(int i=0;i<200&&!chat.runtime.ready;i++)Thread.sleep(100);
      resize(239,282);runOnMainSync(()->{
        chat.conversation.removeAllViews();chat.bubble("这个系统窗口只保留消息和输入。工具放进菜单，发送与停止始终可见。",false);
        chat.input.setText("保留这段草稿");chat.input.setSelection(3);
      });settle();checkControls();require(chat.scroll.getHeight()>=chat.dp(110),"Chrome left too little conversation space");
      EmulatorChecks.screenshot(this,"system-window-chat.png");report.putBoolean("239x282SendAndContentVisible",true);
      runOnMainSync(chat.overflowButton::performClick);EmulatorChecks.waitText(this,"更多工具");EmulatorChecks.screenshot(this,"system-window-menu.png");back();settle();report.putBoolean("nativeScrollableMenu",true);
      runOnMainSync(()->{Bitmap bitmap=Bitmap.createBitmap(24,24,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.BLUE);picture=PhoneAccessibility.encode(bitmap);chat.pendingImage=picture;chat.pendingImageLabel="测试附件";chat.updateDraftAttachment();});
      resize(400,600);require(chat==MainActivity.current(),"Resize recreated chat");resize(239,282);checkControls();
      runOnMainSync(()->{require(chat.input.getText().toString().equals("保留这段草稿")&&chat.input.getSelectionStart()==3,"Draft or selection lost on resize");require(picture.equals(chat.pendingImage),"Image lost on resize");chat.pendingImage=null;chat.updateDraftAttachment();});report.putBoolean("resizePreservesDraftSelectionAndImage",true);
      runOnMainSync(()->{chat.input.requestFocus();((InputMethodManager)chat.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(chat.input,InputMethodManager.SHOW_IMPLICIT);});
      for(int i=0;i<70&&EmulatorChecks.imeTop(this)<0;i++)Thread.sleep(100);require(EmulatorChecks.imeTop(this)>0,"Keyboard missing");EmulatorChecks.dismissKeyboardHint(this);settle();checkControls();
      Rect send=bounds(chat.sendButton);int ime=EmulatorChecks.imeTop(this);require(ime<0||send.bottom<=ime,"Send behind keyboard: "+send+" / "+ime);EmulatorChecks.screenshot(this,"system-window-keyboard.png");back();settle();report.putBoolean("keyboardKeepsInputAndSend",true);
      Activity work=open(WorkbenchActivity.class,()->WorkbenchActivity.open(chat));require(work.getTaskId()==task&&work.isInMultiWindowMode(),"Workbench escaped system task");
      Activity terminal=open(TerminalActivity.class,()->TerminalActivity.open(work));require(terminal.getTaskId()==task&&terminal.isInMultiWindowMode(),"Terminal escaped system task");runOnMainSync(terminal::finish);settle();
      Activity connections=open(ConnectorActivity.class,()->ConnectorActivity.open(work));require(connections.getTaskId()==task&&connections.isInMultiWindowMode(),"Connectors escaped system task");runOnMainSync(connections::finish);settle();runOnMainSync(work::finish);settle();
      require(chat==MainActivity.current()&&!chat.isFinishing()&&chat.input.getText().toString().equals("保留这段草稿"),"Back removed chat or draft");report.putBoolean("workbenchTerminalConnectorsStayInTask",true);
      Activity voice=open(VoiceCallActivity.class,()->VoiceCallActivity.show(chat));require(voice.getTaskId()==task&&voice.isInMultiWindowMode(),"Voice escaped system task");
      View avatar=find(voice.getWindow().getDecorView(),"点击头像，打断播报并说话"),end=find(voice.getWindow().getDecorView(),"结束实时通话");require(avatar!=null&&end!=null,"Voice controls missing");require(inside(voice.getWindow().getDecorView(),avatar)&&inside(voice.getWindow().getDecorView(),end),"Voice controls clipped");Rect ending=bounds(end);Rect expected=chat.getWindowManager().getCurrentWindowMetrics().getBounds();String geometry="voice="+bounds(voice.getWindow().getDecorView())+" metrics="+voice.getWindowManager().getCurrentWindowMetrics().getBounds()+" chat="+expected+" end="+ending+" config="+voice.getResources().getConfiguration();java.nio.file.Files.writeString(new java.io.File(getTargetContext().getCacheDir(),"voice-geometry.txt").toPath(),geometry);require(expected.contains(ending),"Voice end escaped original task: "+geometry);Bitmap drawn=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot();int x=ending.centerX(),y=ending.centerY()+chat.dp(12);require(drawn!=null&&x<drawn.getWidth()&&y<drawn.getHeight(),"Voice end outside actual display: "+ending);int pixel=drawn.getPixel(x,y);drawn.recycle();require(Color.red(pixel)>Color.green(pixel)+60,"Voice end is logically present but not actually drawn: "+ending+" color="+Integer.toHexString(pixel));EmulatorChecks.screenshot(this,"system-window-voice.png");runOnMainSync(voice::finish);settle();report.putBoolean("compactVoiceKeepsAvatarAndEnd",true);
      runOnMainSync(()->PhoneHud.show(chat,"正在操作旁边的应用"));require(!PhoneHud.showing()&&!CircleOverlay.hasBubble(),"System window spawned custom chrome");report.putBoolean("systemWindowDoesNotSpawnHud",true);
      EmulatorChecks.shell(this,"am start -n dev.zfold.fixture.screen/.Target --es page red");Thread.sleep(500);
      EmulatorChecks.shell(this,"am start --windowingMode 5 -n dev.zfold.assistant/.MainActivity");settle();
      CountDownLatch captured=new CountDownLatch(1);String[] capture={null,null};runOnMainSync(()->{ScreenContext.setEnabled(true);ScreenContext.capture(chat,(image,label,failure)->{capture[0]=image;capture[1]=failure;captured.countDown();});});
      require(captured.await(10,TimeUnit.SECONDS)&&capture[1]==null&&capture[0]!=null,"Native window screen capture failed: "+capture[1]);
      byte[] bytes=android.util.Base64.decode(capture[0],android.util.Base64.DEFAULT);Bitmap image=BitmapFactory.decodeByteArray(bytes,0,bytes.length);int color=image.getPixel(image.getWidth()/8,image.getHeight()/8);image.recycle();require(Color.red(color)>Color.blue(color)+40,"Captured own chat instead of red external app");report.putBoolean("capturesExternalWindowWithoutCustomOverlay",true);
      runOnMainSync(()->{ScreenContext.setEnabled(false);chat.input.setText("");chat.input.clearFocus();((InputMethodManager)chat.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(chat.input.getWindowToken(),0);});
      for(int i=0;i<40&&EmulatorChecks.imeTop(this)>=0;i++)Thread.sleep(100);
      resize(239,282);Rect original=chat.getWindowManager().getCurrentWindowMetrics().getBounds();
      runOnMainSync(()->{SystemWindowNavigation.remember(chat);chat.moveTaskToBack(true);});Thread.sleep(400);
      runOnMainSync(()->SystemWindowNavigation.showChat(getTargetContext(),false));settle();require(chat.getTaskId()==task&&original.equals(chat.getWindowManager().getCurrentWindowMetrics().getBounds()),"Existing task geometry changed on resume");
      require(original.equals(SystemWindowNavigation.restoredBounds(chat)),"Saved geometry differs from user bounds");report.putBoolean("resumesSameWindowAndPersistsGeometry",true);
      runOnMainSync(chat::finishAndRemoveTask);settle();runOnMainSync(()->SystemWindowNavigation.showChat(getTargetContext(),false));settle();
      MainActivity restored=MainActivity.current();for(int i=0;i<100&&(restored==null||restored==chat||restored.root==null||!restored.root.isLaidOut());i++){Thread.sleep(100);restored=MainActivity.current();}require(restored!=null&&restored!=chat,"Removed task did not reopen");
      boolean restoredWindow=restored.isInMultiWindowMode()&&original.equals(restored.getWindowManager().getCurrentWindowMetrics().getBounds());
      report.putBoolean("recreatesWindowAtSavedSizeAndPosition",restoredWindow);
      if(!restoredWindow){require(!getTargetContext().getPackageManager().hasSystemFeature("android.software.freeform_window_management"),"Freeform-capable host ignored restore bounds");require(original.equals(SystemWindowNavigation.restoredBounds(restored))&&!AssistantOverlay.isShowing()&&!CircleOverlay.hasBubble(),"Fallback overwrote geometry or spawned a custom window");report.putBoolean("unsupportedHostKeepsGeometryWithoutCustomWindow",true);report.putString("restoreLimitation","Emulator has no declared freeform feature; fresh-task launch bounds ignored. Samsung fresh-task restoration remains unverified.");}
    }catch(Throwable failure){error=failure.toString();report.putString("error",error);try{EmulatorChecks.screenshot(this,"system-window-failure.png");}catch(Exception ignored){}}
    finally{runOnMainSync(()->{ScreenContext.setEnabled(false);PhoneHud.forceHide();});}
    finish(error.isEmpty()?-1:0,report);
  }
  private void resize(int width,int height)throws Exception{int w=chat.dp(width),h=chat.dp(height);EmulatorChecks.shell(this,"am task resize "+task+" 10 220 "+(10+w)+" "+(220+h));settle();for(int i=0;i<40&&Math.abs(chat.root.getWidth()-w)>=5;i++)Thread.sleep(100);require(Math.abs(chat.root.getWidth()-w)<5,"System did not resize to requested width: "+chat.root.getWidth()+" / "+w);}
  private void settle()throws Exception{Thread.sleep(600);getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).waitForIdle(150,5000);}
  private void checkControls(){runOnMainSync(()->{require(inside(chat.root,chat.input)&&inside(chat.root,chat.sendButton)&&inside(chat.root,chat.overflowButton),"Input / send / menu clipped");require(chat.sendButton.getWidth()>=chat.dp(48)&&chat.sendButton.getHeight()>=chat.dp(48),"Send target too small");});}
  private Activity open(Class<?> type,Runnable action)throws Exception{ActivityMonitor monitor=addMonitor(type.getName(),null,false);runOnMainSync(action);Activity activity=waitForMonitorWithTimeout(monitor,5000);removeMonitor(monitor);require(activity!=null,"Destination missing: "+type.getSimpleName());settle();return activity;}
  private Rect bounds(View view){Rect value=new Rect();Runnable read=()->{int[] at=new int[2];view.getLocationOnScreen(at);value.set(at[0],at[1],at[0]+view.getWidth(),at[1]+view.getHeight());};if(Looper.myLooper()==Looper.getMainLooper())read.run();else runOnMainSync(read);return value;}
  private boolean inside(View root,View view){Rect whole=bounds(root),actual=bounds(view),visible=new Rect();return view.getGlobalVisibleRect(visible)&&whole.contains(actual)&&visible.width()==actual.width()&&visible.height()==actual.height();}
  private View find(View view,String description){if(description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;if(view instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)view).getChildCount();i++){View result=find(((android.view.ViewGroup)view).getChildAt(i),description);if(result!=null)return result;}return null;}
  private void back(){require(getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK),"Back rejected");}
  private static void require(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
}
