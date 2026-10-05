package dev.zfold.assistant;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import org.json.*;

/** Real scrolling and overview dragging, in emulator-only synthetic conversation data. */
public final class MinimapInstrumentation extends Instrumentation {
  private MainActivity chat;private float oldScale;private String oldFreeform;private boolean settingsSaved;
  @Override public void onCreate(Bundle args){if(!Build.HARDWARE.contains("ranchu")&&!Build.HARDWARE.contains("goldfish")){Bundle report=new Bundle();report.putString("error","Emulator only");finish(0,report);return;}PiRuntime.testMode=true;start();}
  @Override public void onStart(){Bundle report=new Bundle();String error="";
    try{
      oldScale=Settings.Global.getFloat(getTargetContext().getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,1);oldFreeform=EmulatorChecks.shell(this,"settings get global enable_freeform_support").trim();settingsSaved=true;
      EmulatorChecks.shell(this,"am start --windowingMode 1 -n dev.zfold.assistant/.MainActivity");awaitChat();fill();
      check(!visible(),"Minimap appeared just from history/layout");report.putBoolean("automaticLayoutStaysQuiet",true);
      int width=chat.conversation.getWidth();Rect body=rect(chat.scroll);swipe(body.centerX(),body.bottom-chat.dp(40),body.centerX(),body.top+chat.dp(40));Thread.sleep(130);
      main(()->chat.scroll.haltFling());check(visible(),"Manual scrolling did not reveal the minimap");check(chat.conversation.getWidth()==width,"Minimap reflowed message text");
      check(mapCount()==chat.conversation.getChildCount(),"Overview missed loaded blocks");EmulatorChecks.screenshot(this,"minimap-scroll.png");report.putBoolean("scrollRevealsOverviewWithoutReflow",true);
      Thread.sleep(1700);check(!visible(),"Idle minimap did not fade away");report.putBoolean("idleAutoHide",true);

      main(()->{chat.scroll.haltFling();chat.scroll.scrollTo(0,chat.minimap.range()/6);check(chat.scroll.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,null),"Accessible scroll action rejected");});Thread.sleep(180);check(visible(),"Accessible scrolling did not reveal overview: alpha="+chat.minimap.getAlpha()+", y="+chat.scroll.getScrollY());
      Bundle progress=new Bundle();progress.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,35);
      main(()->check(chat.minimap.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),progress),"Accessible range action rejected"));
      check(Math.abs(chat.scroll.getScrollY()-chat.minimap.range()*.35f)<chat.dp(3),"Accessible navigation landed at wrong position");report.putBoolean("accessiblePositionControl",true);

      Rect map=rect(chat.minimap);RectF track=mapTrack();float x=map.centerX(),center=map.top+track.top+(chat.scroll.getScrollY()+chat.scroll.getHeight()/2f)/(float)chat.conversation.getHeight()*track.height();
      long gesture=touch(MotionEvent.ACTION_DOWN,x,center,0);int start=chat.scroll.getScrollY();Thread.sleep(150);check(chat.scroll.getScrollY()==start,"Grab jumped to another position");
      touch(MotionEvent.ACTION_MOVE,x,center+track.height()*.15f,gesture);Thread.sleep(80);check(chat.scroll.getScrollY()>start&&!chat.swipeNavigation.hasDrawer(),"Minimap drag failed or opened sidebar");int held=chat.scroll.getScrollY();
      main(()->{chat.event(PiRuntime.json("type","assistant_start"));chat.event(PiRuntime.json("type","delta","text","新回复正在生成，但你仍在阅读之前的内容。\n".repeat(15)));chat.followBottom(true);});Thread.sleep(250);
      check(Math.abs(chat.scroll.getScrollY()-held)<chat.dp(2),"New content pulled an active minimap drag to the bottom");
      touch(MotionEvent.ACTION_MOVE,x,center-track.height()*.06f,gesture);Thread.sleep(60);check(chat.scroll.getScrollY()<held,"Reverse drag did not follow finger");
      int cancelledAt=chat.scroll.getScrollY();touch(MotionEvent.ACTION_CANCEL,x,center-track.height()*.06f,gesture);Thread.sleep(100);check(!chat.minimap.interacting()&&chat.scroll.getScrollY()==cancelledAt&&!chat.swipeNavigation.hasDrawer(),"Cancelled drag moved content or opened sidebar");report.putBoolean("dragGrabReverseCancelAndStreaming",true);

      main(()->chat.event(PiRuntime.json("type","delta","text","继续到来的回答不会改变历史阅读位置。\n".repeat(12))));Thread.sleep(200);check(chat.scroll.getScrollY()==cancelledAt,"Streaming pulled history reader to bottom");report.putBoolean("readingHistorySurvivesNewReply",true);
      main(()->{chat.scroll.fling(chat.dp(1800));chat.minimap.reveal();});Thread.sleep(80);map=rect(chat.minimap);track=mapTrack();x=map.centerX();center=map.top+track.top+(chat.scroll.getScrollY()+chat.scroll.getHeight()/2f)/(float)chat.conversation.getHeight()*track.height();
      gesture=touch(MotionEvent.ACTION_DOWN,x,center,0);int stopped=chat.scroll.getScrollY();Thread.sleep(220);check(Math.abs(chat.scroll.getScrollY()-stopped)<chat.dp(2),"Old fling continued under a grabbed minimap");touch(MotionEvent.ACTION_CANCEL,x,center,gesture);report.putBoolean("grabStopsInertia",true);

      main(chat::openSidebar);Thread.sleep(400);check(chat.swipeNavigation.hasDrawer()&&!visible(),"Sidebar did not suppress overview");main(chat.swipeNavigation::close);awaitAvailable();report.putBoolean("sidebarSuppressesMinimap",true);
      main(()->chat.minimap.reveal());Thread.sleep(160);check(visible(),"Overview did not recover after sidebar");
      ActionMode[] selection={null};main(()->selection[0]=chat.startActionMode(new ActionMode.Callback(){public boolean onCreateActionMode(ActionMode m,Menu menu){menu.add("选字测试");return true;}public boolean onPrepareActionMode(ActionMode m,Menu menu){return false;}public boolean onActionItemClicked(ActionMode m,MenuItem item){return false;}public void onDestroyActionMode(ActionMode m){}},ActionMode.TYPE_FLOATING));
      Thread.sleep(100);check(selection[0]!=null&&!visible(),"Selection did not suppress minimap");main(()->selection[0].finish());awaitAvailable();report.putBoolean("selectionSuppressesMinimap",true);

      setScale(0);main(()->chat.minimap.reveal());check(chat.minimap.getAlpha()==1,"Reduced-motion reveal animated");Thread.sleep(1350);check(!visible()&&chat.minimap.getAlpha()==0,"Reduced-motion fade continued animating");setScale(oldScale);report.putBoolean("reducedMotion",true);
      main(()->{chat.conversation.removeAllViews();chat.bubble("一段简短的对话。",false);chat.minimap.reset();});Thread.sleep(250);main(()->chat.minimap.reveal());check(!visible()&&mapCount()==0,"Short conversation retained old minimap");report.putBoolean("shortConversationAndReset",true);

      MainActivity previous=chat;main(chat::finishAndRemoveTask);chat=null;EmulatorChecks.shell(this,"settings put global enable_freeform_support 1");EmulatorChecks.shell(this,"am start --windowingMode 5 -n dev.zfold.assistant/.MainActivity");awaitChat(previous);
      int w=chat.dp(239),h=chat.dp(282);EmulatorChecks.shell(this,"am task resize "+chat.getTaskId()+" 30 260 "+(30+w)+" "+(260+h));Thread.sleep(500);fill();
      check(chat.isInMultiWindowMode(),"Small-window fixture is not freeform");Rect actual=chat.getWindowManager().getCurrentWindowMetrics().getBounds();check(Math.abs(actual.width()-w)<=1&&Math.abs(actual.height()-h)<=1,"Window size was not applied: "+actual);report.putString("smallWindowBounds",actual.toShortString());main(()->chat.scroll.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,null));Thread.sleep(200);main(()->chat.scroll.haltFling());check(visible(),"Small-window minimap missing");
      Rect bodySmall=rect(chat.scroll),mapSmall=rect(chat.minimap);RectF smallTrack=mapTrack();check(bodySmall.contains(mapSmall)&&mapSmall.width()>=chat.dp(48)&&smallTrack.height()>chat.dp(20),"Small-window map clipped or touch target shrank");
      EmulatorChecks.screenshot(this,"minimap-small.png");report.putBoolean("239x282Overview",true);
    }catch(Throwable failure){error=failure.toString();report.putString("error",error);try{EmulatorChecks.screenshot(this,"minimap-failure.png");}catch(Exception ignored){}}
    finally{
      EmulatorChecks.cancelPointer(this);if(chat!=null)main(()->{chat.minimap.hideNow();chat.finish();});
      if(settingsSaved)try{setScale(oldScale);EmulatorChecks.shell(this,oldFreeform.equals("null")?"settings delete global enable_freeform_support":"settings put global enable_freeform_support "+oldFreeform);}catch(Exception ignored){}
    }
    finish(error.isEmpty()?-1:0,report);
  }
  private void awaitChat()throws Exception{awaitChat(null);}
  private void awaitChat(MainActivity previous)throws Exception{for(int i=0;i<150;i++){MainActivity current=MainActivity.current();if(current!=null&&current!=previous&&!current.isFinishing()&&!current.isDestroyed()&&current.minimap!=null&&current.root.isLaidOut()){chat=current;break;}Thread.sleep(100);}check(chat!=null&&chat!=previous,"Chat not ready");for(int i=0;i<200&&!chat.runtime.ready;i++)Thread.sleep(100);main(()->chat.runtime.unlisten(chat));}
  private void fill()throws Exception{main(()->{
    chat.conversation.removeAllViews();chat.conversation.setGravity(Gravity.TOP);chat.toolViews.clear();chat.response.setLength(0);chat.answer=null;chat.minimap.reset();
    for(int i=1;i<=18;i++){
      chat.bubble("第 "+i+" 个问题：怎样把手机变成好用的 Agent？",true);
      chat.bubble("### 第 "+i+" 段回答\n把观察、执行与反馈放在同一个会话里。\n\n滚动查看历史时保持阅读位置，工具结果和图片也属于这条时间线。\n".repeat(3),false);
      if(i%4==0)chat.showTool(PiRuntime.json("id","map-tool-"+i,"name","device_info","status",i==8?"error":"success","args","{}","output","仅用于缩略导航的模拟记录"));
      if(i==9){Bitmap image=Bitmap.createBitmap(32,20,Bitmap.Config.ARGB_8888);image.eraseColor(0xff5599cc);chat.bubble("一张示意图片",true,PhoneAccessibility.encode(image),true);}
    }
    chat.scroll.scrollTo(0,0);chat.input.clearFocus();
  });Thread.sleep(500);main(()->{chat.scroll.haltFling();chat.scroll.scrollTo(0,0);chat.minimap.hideNow();});}
  private void main(Runnable action){Throwable[] failed={null};runOnMainSync(()->{try{action.run();}catch(Throwable failure){failed[0]=failure;}});if(failed[0]!=null)throw new IllegalStateException(failed[0]);}
  private int mapCount(){int[] count={0};main(()->count[0]=chat.minimap.segmentCount());return count[0];}
  private RectF mapTrack(){RectF[] rect={null};main(()->rect[0]=chat.minimap.trackBounds());return rect[0];}
  private boolean visible(){boolean[] visible={false};main(()->visible[0]=chat.minimap.getVisibility()==View.VISIBLE&&chat.minimap.getAlpha()>.5f);return visible[0];}
  private Rect rect(View view){Rect bounds=new Rect();main(()->{int[] at=new int[2];view.getLocationOnScreen(at);bounds.set(at[0],at[1],at[0]+view.getWidth(),at[1]+view.getHeight());});return bounds;}
  private long touch(int action,float x,float y,long start){long now=SystemClock.uptimeMillis();if(start==0)start=now;MotionEvent event=MotionEvent.obtain(start,now,action,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);check(getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).injectInputEvent(event,true),"Touch injection failed");event.recycle();return start;}
  private void swipe(float x,float y,float endX,float endY)throws Exception{long start=touch(MotionEvent.ACTION_DOWN,x,y,0);for(int i=1;i<=12;i++){Thread.sleep(20);touch(MotionEvent.ACTION_MOVE,x+(endX-x)*i/12,y+(endY-y)*i/12,start);}touch(MotionEvent.ACTION_UP,endX,endY,start);}
  private void awaitAvailable()throws Exception{for(int i=0;i<100;i++){boolean[] ready={false};main(()->ready[0]=chat.minimap.available());if(ready[0])return;Thread.sleep(25);}throw new IllegalStateException("Overview remained suppressed");}
  private void setScale(float scale){UiAutomation ui=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);ui.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");try{Settings.Global.putFloat(getTargetContext().getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,scale);}finally{ui.dropShellPermissionIdentity();}for(int i=0;i<80;i++){boolean[] applied={false};main(()->applied[0]=Ui.motion()==(scale!=0));if(applied[0])return;SystemClock.sleep(25);}throw new IllegalStateException("Animation setting did not propagate");}
  private static void check(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
