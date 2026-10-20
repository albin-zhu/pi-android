package dev.zfold.assistant;

import android.app.*;
import android.content.*;
import android.graphics.Rect;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

/** Actual touch streams and platform Back, not synthetic click-only navigation. Emulator only. */
public final class SwipeNavigationInstrumentation extends Instrumentation {
  private MainActivity chat;private Bundle report=new Bundle();private float scale;private String freeform;private boolean settingsSaved;
  @Override public void onCreate(Bundle args){if(!Build.HARDWARE.contains("ranchu")&&!Build.HARDWARE.contains("goldfish")){Bundle result=new Bundle();result.putString("error","Emulator only");finish(0,result);return;}PiRuntime.testMode=true;start();}
  @Override public void onStart(){String error="";
    try{
      EmulatorChecks.accessibility(this);EmulatorChecks.cancelPointer(this);
      scale=Settings.Global.getFloat(getTargetContext().getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,1);
      freeform=EmulatorChecks.shell(this,"settings get global enable_freeform_support").trim();
      settingsSaved=true;
      launch(false);fill();int task=chat.getTaskId();
      back();settle();require(MainActivity.current()==chat&&!chat.isFinishing()&&chat.getTaskId()==task,"Root Back exited chat");report.putBoolean("rootBackStaysInChat",true);
      Rect window=chat.getWindowManager().getCurrentWindowMetrics().getBounds();int y=rect(chat.scroll).centerY();
      for(boolean left:new boolean[]{true,false}){edgeBack(window,y,left);require(MainActivity.current()==chat&&!chat.isFinishing()&&!hasDrawer(),"System "+(left?"left":"right")+"-edge Back changed root chat");}report.putBoolean("bothSystemEdgesStayInChat",true);
      openBySwipe();report.putBoolean("rightSwipeOpensSessions",true);
      Rect drawer=rect(chat.swipeNavigation.currentPicker().drawer);long down=touch(MotionEvent.ACTION_DOWN,drawer.right-chat.dp(25),drawer.centerY(),0);
      touch(MotionEvent.ACTION_MOVE,drawer.right-chat.dp(100),drawer.centerY(),down);Thread.sleep(60);float progress=progress();require(progress>.15f&&progress<.98f,"Drawer did not track a partial drag: "+progress);
      touch(MotionEvent.ACTION_MOVE,drawer.right-chat.dp(45),drawer.centerY(),down);require(progress()>progress,"Reverse drag did not follow finger");touch(MotionEvent.ACTION_CANCEL,drawer.right-chat.dp(45),drawer.centerY(),down);settle();require(open(),"Cancelled close did not restore drawer: p="+progress()+" present="+hasDrawer());report.putBoolean("fingerTrackingReverseAndCancel",true);
      swipe(drawer.right-chat.dp(25),drawer.centerY(),drawer.left+chat.dp(12),drawer.centerY());settle();require(!hasDrawer(),"Left swipe did not close drawer");report.putBoolean("leftSwipeClosesSessions",true);
      for(boolean left:new boolean[]{true,false}){
        openBySwipe();float start=left?window.left+2:window.right-2,direction=left?1:-1;
        long edge=touch(MotionEvent.ACTION_DOWN,start,y,0);Thread.sleep(60);touch(MotionEvent.ACTION_MOVE,start+direction*chat.dp(65),y,edge);Thread.sleep(100);touch(MotionEvent.ACTION_MOVE,start+direction*3,y,edge);touch(MotionEvent.ACTION_CANCEL,start+direction*3,y,edge);settle();
        require(open(),"Cancelled "+(left?"left":"right")+"-edge Back dismissed drawer");edgeBack(window,y,left);require(!hasDrawer()&&!chat.isFinishing(),"Committed "+(left?"left":"right")+"-edge Back did not close drawer locally");
      }openBySwipe();report.putBoolean("bothSystemEdgesCloseDrawerAndCancelLocally",true);
      runOnMainSync(chat.swipeNavigation::close);Thread.sleep(50);drawer=rect(chat.swipeNavigation.currentPicker().drawer);down=touch(MotionEvent.ACTION_DOWN,drawer.right-chat.dp(20),drawer.centerY(),0);float grabbed=progress();touch(MotionEvent.ACTION_MOVE,drawer.right+chat.dp(35),drawer.centerY(),down);require(progress()>grabbed,"Moving drawer could not be grabbed and reversed");touch(MotionEvent.ACTION_MOVE,window.right-chat.dp(40),drawer.centerY(),down);touch(MotionEvent.ACTION_UP,window.right-chat.dp(40),drawer.centerY(),down);settle();require(open(),"Interrupted close did not reopen: "+progress());runOnMainSync(chat.swipeNavigation::close);settle();report.putBoolean("settlingAnimationCanBeReversed",true);
      Rect body=rect(chat.scroll);int before=chat.scroll.getScrollY();swipe(body.centerX(),body.bottom-chat.dp(30),body.centerX(),body.top+chat.dp(20));settle();require(chat.scroll.getScrollY()>before&&!hasDrawer(),"Vertical message scrolling opened drawer");report.putBoolean("verticalScrollPreserved",true);
      fill();TextView selectable=selectable(chat.answer);require(selectable!=null,"Selectable reply missing");Rect paragraph=rect(selectable);long press=touch(MotionEvent.ACTION_DOWN,paragraph.left+chat.dp(24),paragraph.top+chat.dp(12),0);Thread.sleep(750);touch(MotionEvent.ACTION_UP,paragraph.left+chat.dp(24),paragraph.top+chat.dp(12),press);settle();require(selectable.hasSelection(),"Long press did not select message text");swipe(paragraph.left+chat.dp(20),paragraph.top+chat.dp(65),paragraph.left+chat.dp(180),paragraph.top+chat.dp(65));settle();require(!hasDrawer(),"Text selection drag opened drawer");back();settle();require(!chat.isFinishing(),"Selection Back exited chat");report.putBoolean("longPressAndSelectionGesturePreserved",true);
      final HorizontalScrollView[] code={null};runOnMainSync(()->{chat.conversation.removeAllViews();code[0]=new HorizontalScrollView(chat);TextView text=new TextView(chat);text.setText("code ".repeat(100));text.setSingleLine();text.setTextSize(18);code[0].addView(text,new android.widget.FrameLayout.LayoutParams(chat.dp(1200),chat.dp(80)));chat.conversation.addView(code[0]);});settle();runOnMainSync(()->code[0].scrollTo(chat.dp(200),0));
      Rect horizontal=rect(code[0]);swipe(horizontal.left+chat.dp(40),horizontal.centerY(),horizontal.left+chat.dp(200),horizontal.centerY());settle();require(!hasDrawer()&&code[0].getScrollX()<chat.dp(200),"Horizontal content was hijacked");report.putBoolean("horizontalContentOwnsItsGesture",true);
      runOnMainSync(()->{chat.input.setText("手势测试草稿");chat.input.requestFocus();((InputMethodManager)chat.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(chat.input,InputMethodManager.SHOW_IMPLICIT);});
      for(int i=0;i<60&&EmulatorChecks.imeTop(this)<0;i++)Thread.sleep(100);require(EmulatorChecks.imeTop(this)>0,"IME did not open");EmulatorChecks.dismissKeyboardHint(this);back();settle();require(!chat.isFinishing()&&chat.input.getText().toString().equals("手势测试草稿"),"IME Back lost draft or exited");report.putBoolean("keyboardBackPreservesDraft",true);
      fill();openBySwipe();back();settle();require(!hasDrawer()&&!chat.isFinishing(),"Back did not close drawer first");
      runOnMainSync(chat.overflowButton::performClick);EmulatorChecks.waitText(this,"更多工具");back();settle();waitFocus(chat);require(!chat.isFinishing(),"Menu Back exited chat");report.putBoolean("drawerAndMenuBackAreLocal",true);
      ActivityMonitor monitor=addMonitor(WorkbenchActivity.class.getName(),null,false);runOnMainSync(()->WorkbenchActivity.open(chat));Activity work=waitForMonitorWithTimeout(monitor,6000);removeMonitor(monitor);require(work!=null,"Workbench not opened");settle();waitFocus(work);back();settle();waitFocus(chat);require(MainActivity.current()==chat&&!chat.isFinishing(),"Subpage Back did not return to chat");report.putBoolean("subpageBackReturnsToChat",true);
      setScale(0);openBySwipe();require(open(),"Reduced motion did not settle open");runOnMainSync(chat.swipeNavigation::close);require(!hasDrawer(),"Reduced motion close continued animating");setScale(scale);report.putBoolean("reducedMotion",true);
      runOnMainSync(chat::finish);settle();launch(true);fill();openBySwipe();EmulatorChecks.screenshot(this,"swipe-small-drawer.png");
      drawer=rect(chat.swipeNavigation.currentPicker().drawer);swipe(drawer.right-chat.dp(15),drawer.centerY(),drawer.left+chat.dp(10),drawer.centerY());settle();require(!hasDrawer()&&!chat.isFinishing(),"Small-window swipe failed");back();settle();require(!chat.isFinishing(),"Small-window Back removed task");EmulatorChecks.screenshot(this,"swipe-small-chat.png");report.putBoolean("smallWindowGesturesAndBack",true);
    }catch(Throwable failure){error=failure.toString();report.putString("error",error);try{EmulatorChecks.screenshot(this,"swipe-failure.png");}catch(Exception ignored){}}
    finally{EmulatorChecks.cancelPointer(this);if(settingsSaved)try{setScale(scale);EmulatorChecks.shell(this,"settings "+("null".equals(freeform)?"delete global enable_freeform_support":"put global enable_freeform_support "+freeform));}catch(Exception ignored){}}
    finish(error.isEmpty()?-1:0,report);
  }
  private void launch(boolean small)throws Exception{
    MainActivity previous=chat;
    if(small)EmulatorChecks.shell(this,"settings put global enable_freeform_support 1");
    EmulatorChecks.shell(this,"am start --windowingMode "+(small?5:1)+" -n dev.zfold.assistant/.MainActivity");
    for(int i=0;i<150;i++){MainActivity candidate=MainActivity.current();if(candidate!=null&&candidate!=previous&&candidate.swipeNavigation!=null&&candidate.swipeNavigation.isLaidOut()){chat=candidate;break;}Thread.sleep(100);}
    require(chat!=null&&chat!=previous,"Chat did not launch");for(int i=0;i<200&&!chat.runtime.ready;i++)Thread.sleep(100);runOnMainSync(()->chat.runtime.unlisten(chat));
    if(small){int w=chat.dp(239),h=chat.dp(282);EmulatorChecks.shell(this,"am task resize "+chat.getTaskId()+" 220 500 "+(220+w)+" "+(500+h));}
    settle();
  }
  private void fill()throws Exception{runOnMainSync(()->{chat.conversation.removeAllViews();chat.conversation.setGravity(Gravity.TOP);chat.bubble("上下滚动查看消息，右滑打开会话列表。\n".repeat(40),false);chat.scroll.scrollTo(0,0);chat.input.setText("手势测试草稿");chat.input.clearFocus();});settle();runOnMainSync(()->chat.scroll.scrollTo(0,0));settle();}
  private void openBySwipe()throws Exception{Rect body=rect(chat.scroll);int start=body.left+chat.dp(40),end=Math.min(body.right-chat.dp(20),start+chat.dp(260));swipe(start,body.centerY(),end,body.centerY());settle();require(open(),"Interior right swipe did not open drawer: "+progress());require(chat.input.getText().toString().equals("手势测试草稿"),"Swipe lost draft");}
  private void edgeBack(Rect window,int y,boolean left)throws Exception{int start=left?window.left+2:window.right-2;swipe(start,y,start+(left?1:-1)*chat.dp(140),y);settle();}
  private TextView selectable(View view){if(view instanceof TextView&&((TextView)view).isTextSelectable()&&((TextView)view).getText().length()>0)return (TextView)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){TextView found=selectable(((ViewGroup)view).getChildAt(i));if(found!=null)return found;}return null;}
  private boolean hasDrawer(){boolean[] value={false};runOnMainSync(()->value[0]=chat.swipeNavigation.hasDrawer());return value[0];}
  private boolean open(){boolean[] value={false};runOnMainSync(()->value[0]=chat.swipeNavigation.fullyOpen());return value[0];}
  private float progress(){float[] value={0};runOnMainSync(()->value[0]=chat.swipeNavigation.progress());return value[0];}
  private Rect rect(View view){Rect result=new Rect();runOnMainSync(()->{int[] xy=new int[2];view.getLocationOnScreen(xy);result.set(xy[0],xy[1],xy[0]+view.getWidth(),xy[1]+view.getHeight());});return result;}
  private long touch(int action,float x,float y,long down){long now=SystemClock.uptimeMillis();if(down==0)down=now;MotionEvent event=MotionEvent.obtain(down,now,action,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);require(getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).injectInputEvent(event,true),"Touch rejected");event.recycle();return down;}
  private void swipe(float x,float y,float endX,float endY)throws Exception{long down=touch(MotionEvent.ACTION_DOWN,x,y,0);for(int i=1;i<=12;i++){Thread.sleep(20);touch(MotionEvent.ACTION_MOVE,x+(endX-x)*i/12,y+(endY-y)*i/12,down);}touch(MotionEvent.ACTION_UP,endX,endY,down);}
  private void waitFocus(Activity expected)throws Exception{for(int i=0;i<120;i++){boolean[] focused={false};runOnMainSync(()->focused[0]=expected.hasWindowFocus());if(focused[0])return;Thread.sleep(50);}throw new IllegalStateException("Window did not receive focus: "+expected.getClass().getSimpleName()+" destroyed="+expected.isDestroyed()+" finishing="+expected.isFinishing());}
  private void back(){require(getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK),"System Back rejected");}
  private void settle()throws Exception{Thread.sleep(400);for(int i=0;i<80;i++){boolean[] running={false};runOnMainSync(()->running[0]=chat!=null&&chat.swipeNavigation!=null&&chat.swipeNavigation.isSettling());if(!running[0])break;Thread.sleep(50);}getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).waitForIdle(150,6000);}
  private void setScale(float value){UiAutomation automation=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");try{Settings.Global.putFloat(getTargetContext().getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,value);}finally{automation.dropShellPermissionIdentity();}}
  private static void require(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
}
