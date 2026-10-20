package dev.zfold.assistant;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.provider.Settings;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Real gestures, navigation and Pi lifecycle; isolated emulator only. */
public final class InteractionInstrumentation extends Instrumentation implements PiRuntime.Listener {
  private PiRuntime runtime;private String error="";private float animationScale;
  private final CountDownLatch configured=new CountDownLatch(1),finished=new CountDownLatch(1);private volatile boolean aborted;
  @Override public void onCreate(Bundle args){PiRuntime.testMode=true;start();}
  public void event(JSONObject event){if(event.optString("type").equals("configured")&&event.optString("model").equals("interaction-test"))configured.countDown();if(event.optString("type").equals("turn_end")){aborted=event.optBoolean("aborted");finished.countDown();}}
  @Override public void onStart(){Bundle result=new Bundle();
    try{
      EmulatorChecks.accessibility(this);EmulatorChecks.cancelPointer(this);animationScale=Settings.Global.getFloat(getTargetContext().getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,1);
      runOnMainSync(()->{runtime=PiRuntime.get(getTargetContext());runtime.listen(this);CircleOverlay.ensureBubble(getTargetContext());});
      for(int i=0;i<100&&(CircleOverlay.bubble==null||!CircleOverlay.bubble.isLaidOut());i++)Thread.sleep(100);require(CircleOverlay.bubble!=null,"Bubble missing");
      View bubble=CircleOverlay.bubble;long[] first=grab(bubble);int[] before=position(bubble);long down=first[2];int[] at={(int)first[0],(int)first[1]};
      touch(MotionEvent.ACTION_MOVE,at[0]-150,at[1]+90,down);
      int[] moved=position(bubble);require(Math.abs(moved[0]-before[0]+150)<=2&&Math.abs(moved[1]-before[1]-90)<=2,"Drag jumped away from finger: "+before[0]+","+before[1]+" -> "+moved[0]+","+moved[1]);result.putBoolean("fingerRelativeDrag",true);
      touch(MotionEvent.ACTION_UP,at[0]-150,at[1]+90,down);Thread.sleep(45);
      long[] grabbed=grab(bubble);before=position(bubble);int reverse=before[0]<150?80:-80;touch(MotionEvent.ACTION_MOVE,grabbed[0]+reverse,grabbed[1],grabbed[2]);moved=position(bubble);require(Math.abs(moved[0]-before[0]-reverse)<=3,"Interrupted spring changed grab offset: "+before[0]+" -> "+moved[0]+", delta "+reverse);touch(MotionEvent.ACTION_UP,grabbed[0]+reverse,grabbed[1],grabbed[2]);settled(bubble);result.putBoolean("springInterruptAndDock",true);
      scale(0);for(int i=0;i<40&&Ui.motion();i++)Thread.sleep(50);require(!Ui.motion(),"Reduced motion not applied");long[] reduced=grab(bubble);at=new int[]{(int)reduced[0]-20,(int)reduced[1]-20};down=reduced[2];int travel=at[0]<200?220:-220;touch(MotionEvent.ACTION_MOVE,at[0]+20+travel,at[1]+20,down);touch(MotionEvent.ACTION_UP,at[0]+20+travel,at[1]+20,down);before=position(bubble);Thread.sleep(150);moved=position(bubble);require(before[0]==moved[0]&&before[1]==moved[1],"Motion continued with animations disabled");result.putBoolean("reducedMotion",true);scale(animationScale);
      runOnMainSync(()->{PhoneHud.show(getTargetContext(),"Fixture task");});require(!CircleOverlay.hasBubble()&&PhoneHud.showing(),"Duplicate compact entries");runOnMainSync(PhoneHud::hide);require(CircleOverlay.hasBubble(),"Bubble did not return after task status");
      runOnMainSync(()->{UnifiedOverlay.call(getTargetContext(),"Fixture call");PhoneHud.hide();});require(UnifiedOverlay.showing(UnifiedOverlay.Mode.CALL),"Task completion hid call entry");runOnMainSync(UnifiedOverlay::forceHide);result.putBoolean("singleCompactEntry",true);
      runOnMainSync(()->{AssistantOverlay.show(getTargetContext());AssistantOverlay.currentPanel().input.setText("Gesture draft");ScreenContext.setEnabled(true);AssistantOverlay.minimizeForPhoneControl(getTargetContext());});
      ActivityMonitor workbenchMonitor=addMonitor(WorkbenchActivity.class.getName(),null,false);
      bubble=CircleOverlay.bubble;long[] menu=grab(bubble);at=new int[]{(int)menu[0]-20,(int)menu[1]-20};down=menu[2];Thread.sleep(ViewConfiguration.getLongPressTimeout()+250);touch(MotionEvent.ACTION_UP,at[0]+20,at[1]+20,down);click("工作台");waitText("手机工作台页面");require(!AssistantOverlay.isShowing()&&!CircleOverlay.hasBubble(),"Work page covered by floating entry");result.putBoolean("longPressMenu",true);
      Activity workbench=waitForMonitorWithTimeout(workbenchMonitor,6000);removeMonitor(workbenchMonitor);require(workbench!=null&&workbench.isTaskRoot(),"Overlay workbench did not open as a root destination");
      click("终端");waitText("切换终端会话");click("收起终端，保留会话");waitText("手机工作台页面");
      click("连接 · MCP");waitText("添加连接");click("返回");waitText("手机工作台页面");
      click("Skills");waitText("本地 Skills");click("关闭 Skill 管理");waitText("手机工作台页面");
      click("模型与账号");waitText("连接你的模型");click("取消");waitText("手机工作台页面");
      click("语音");waitText("语音服务");back();waitText("手机工作台页面");result.putBoolean("speechBackReturnsToWorkbench",true);
      click("读屏与操作");for(int i=0;i<50;i++){AccessibilityNodeInfo root=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).getRootInActiveWindow();if(root!=null&&"com.android.settings".contentEquals(root.getPackageName()))break;Thread.sleep(100);if(i==49)throw new IllegalStateException("System accessibility settings missing");}back();waitText("手机工作台页面");
      EmulatorChecks.screenshot(this,"workbench-navigation.png");result.putBoolean("workbenchRoutes",true);
      click("工具目录");waitText("功能中心");back();waitText("手机工作台页面");result.putBoolean("catalogBackReturnsToWorkbench",true);
      back();waitText("手机工作台页面");require(!workbench.isFinishing()&&!AssistantOverlay.isShowing()&&!CircleOverlay.hasBubble(),"Root workbench Back exited or restored floating UI");click("返回");waitText("手机工作台页面");require(!workbench.isFinishing(),"Root workbench header exited");result.putBoolean("rootWorkbenchBackStaysOpen",true);runOnMainSync(workbench::finish);
      for(int i=0;i<50&&!AssistantOverlay.isShowing();i++)Thread.sleep(100);runOnMainSync(()->{});require(AssistantOverlay.isShowing()&&AssistantOverlay.currentPanel().input.getText().toString().equals("Gesture draft")&&ScreenContext.enabled(),"Work screen return lost floating draft");result.putBoolean("workScreenDraftReturn",true);
      for(int i=0;i<200&&!runtime.ready;i++)Thread.sleep(100);
      runOnMainSync(()->runtime.send(PiRuntime.json("type","configure","config",PiRuntime.json("baseUrl","http://127.0.0.1:18875/v1","model","interaction-test","apiKey","fixture","api","openai-completions"))));require(configured.await(20,TimeUnit.SECONDS),"Model fixture not configured");
      runOnMainSync(()->{ScreenContext.setEnabled(false);AssistantOverlay.currentPanel().send("CONTINUE_AFTER_CLOSE");AssistantOverlay.currentPanel().host.close();});
      require(finished.await(20,TimeUnit.SECONDS)&&!aborted&&runtime.lastResponse.contains("CONTINUED_AFTER_CLOSE"),"Closing panel interrupted actual Pi task");require(!AssistantOverlay.isShowing()&&!CircleOverlay.hasBubble(),"Close left an entry visible");result.putBoolean("closePreservesActualTask",true);
    }catch(Throwable failure){error=failure.toString();result.putString("error",error);try{EmulatorChecks.screenshot(this,"interaction-failure.png");}catch(Exception ignored){}}
    finally{EmulatorChecks.cancelPointer(this);try{scale(animationScale);}catch(Exception ignored){}runOnMainSync(()->{if(runtime!=null)runtime.unlisten(this);ScreenContext.setEnabled(false);AssistantOverlay.hide();CircleOverlay.hideBubble();UnifiedOverlay.forceHide();TerminalSessions.get(getTargetContext()).closeAll();});}
    finish(error.isEmpty()?-1:0,result);
  }
  private void scale(float value){UiAutomation automation=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");try{Settings.Global.putFloat(getTargetContext().getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,value);}finally{automation.dropShellPermissionIdentity();}}
  private int[] position(View view){int[] result=new int[2];runOnMainSync(()->{WindowManager.LayoutParams p=(WindowManager.LayoutParams)view.getLayoutParams();result[0]=p.x;result[1]=p.y;});return result;}
  private int[] location(View view){int[] result=new int[2];runOnMainSync(()->view.getLocationOnScreen(result));return result;}
  private void touch(int action,float x,float y,long down){MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);require(getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).injectInputEvent(event,true),"Gesture injection failed");event.recycle();}
  private long[] grab(View view)throws Exception{
    UiAutomation automation=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);long[] result=new long[3];
    for(int attempt=0;attempt<3;attempt++){
      runOnMainSync(()->{int[] at=new int[2];view.getLocationOnScreen(at);result[0]=at[0]+view.getWidth()/2;result[1]=at[1]+view.getHeight()/2;result[2]=SystemClock.uptimeMillis();MotionEvent event=MotionEvent.obtain(result[2],result[2],MotionEvent.ACTION_DOWN,result[0],result[1],0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);automation.injectInputEvent(event,false);event.recycle();});
      for(int i=0;i<8;i++){boolean[] pressed={false};runOnMainSync(()->pressed[0]=view.isPressed());if(pressed[0])return result;Thread.sleep(15);}
      touch(MotionEvent.ACTION_CANCEL,result[0],result[1],result[2]);
    }
    throw new IllegalStateException("Input dispatcher did not hit moving bubble");
  }
  private void settled(View view)throws Exception{int stable=0;int[] previous=position(view);for(int i=0;i<100;i++){Thread.sleep(50);int[] current=position(view);stable=current[0]==previous[0]&&current[1]==previous[1]?stable+1:0;if(stable>=3){require(current[0]<40||current[0]>getTargetContext().getResources().getDisplayMetrics().widthPixels-view.getWidth()-40,"Bubble did not dock at edge");return;}previous=current;}throw new IllegalStateException("Spring did not settle");}
  private void back(){require(getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK),"System back was not accepted");}
  private AccessibilityNodeInfo find(AccessibilityNodeInfo node,String text,boolean clickable){if(node==null)return null;if(node.isVisibleToUser()&&(text.contentEquals(node.getText()==null?"":node.getText())||text.contentEquals(node.getContentDescription()==null?"":node.getContentDescription()))){if(!clickable)return node;AccessibilityNodeInfo target=node;while(target!=null&&!target.isClickable())target=target.getParent();if(target!=null)return target;}for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=find(node.getChild(i),text,clickable);if(found!=null)return found;}return null;}
  private AccessibilityNodeInfo scrollable(AccessibilityNodeInfo node){if(node==null)return null;if(node.isScrollable())return node;for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=scrollable(node.getChild(i));if(found!=null)return found;}return null;}
  private void click(String text)throws Exception{android.util.Log.i("InteractionQA","Click "+text);for(int i=0;i<70;i++){AccessibilityNodeInfo root=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).getRootInActiveWindow(),node=find(root,text,true);if(node!=null&&node.performAction(AccessibilityNodeInfo.ACTION_CLICK)){Thread.sleep(200);return;}if(i%5==4){AccessibilityNodeInfo scroll=scrollable(root);if(scroll!=null)scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);}Thread.sleep(100);}throw new IllegalStateException("Missing action: "+text);}
  private void waitText(String text)throws Exception{for(int i=0;i<100;i++){if(find(getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).getRootInActiveWindow(),text,false)!=null)return;Thread.sleep(100);}throw new IllegalStateException("Missing page: "+text);}
  private static void require(boolean value,String message){if(!value)throw new IllegalStateException(message);}
}
