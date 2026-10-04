package dev.zfold.assistant;

import android.accessibilityservice.*;
import android.graphics.*;
import android.os.*;
import android.view.Display;
import android.view.accessibility.*;
import android.util.Base64;
import org.json.*;
import java.util.*;
import java.io.ByteArrayOutputStream;

public final class PhoneAccessibility extends AccessibilityService {
  public static volatile PhoneAccessibility instance;
  private final Map<String,AccessibilityNodeInfo> nodes=new HashMap<>();
  private final Map<String,String> identities=new HashMap<>();
  private int observedWindow=-1;private long observedAt;private String observedPackage="";
  private long generation=0;
  private long frameSequence=0;long inputEpoch=0;private VisualFrame visualFrame;private FocusedInput focusedInput;
  private volatile long injectedUntil;
  @Override protected void onServiceConnected(){instance=this;focusedInput=new FocusedInput(this);CircleOverlay.accessibilityConnected(this);}
  @Override public android.accessibilityservice.InputMethod onCreateInputMethod(){return new android.accessibilityservice.InputMethod(this){
    @Override public void onStartInput(android.view.inputmethod.EditorInfo editor,boolean restarting){super.onStartInput(editor,restarting);inputEpoch++;}
    @Override public void onFinishInput(){super.onFinishInput();inputEpoch++;}
  };}
  @Override public void onDestroy(){instance=null;if(focusedInput!=null)focusedInput.close();AssistantOverlay.accessibilityDisconnected(getApplicationContext());CircleOverlay.accessibilityDisconnected(getApplicationContext());clearNodes();super.onDestroy();}
  @Override public void onInterrupt(){}
  @Override public void onAccessibilityEvent(AccessibilityEvent event){
    if(event.getEventType()==AccessibilityEvent.TYPE_TOUCH_INTERACTION_START&&SystemClock.elapsedRealtime()>=injectedUntil&&PiRuntime.phoneBusy())
      PiRuntime.takeoverPhone(this);
  }
  private void clearNodes(){for(AccessibilityNodeInfo node:nodes.values())node.recycle();nodes.clear();identities.clear();observedWindow=-1;observedPackage="";observedAt=0;generation++;}
  void execute(String name,JSONObject args,AndroidTools.Result result)throws Exception {
    if(args.has("expectedPackageName")&&!args.optString("expectedPackageName").equals(foregroundPackage()))throw new Exception("当前应用已离开本轮目标，已停止操作；请回到目标应用");
    if(name.equals("screen_read")) {
      clearNodes();AccessibilityNodeInfo root=applicationRoot();if(root==null)throw new Exception("当前界面不可读取");
      observedWindow=root.getWindowId();observedPackage=String.valueOf(root.getPackageName());observedAt=SystemClock.uptimeMillis();
      JSONArray tree=new JSONArray();walk(root,tree,0,null,"0");
      result.done(PiRuntime.json("generation",generation,"packageName",observedPackage,"observedAt",System.currentTimeMillis(),"nodes",tree,"readable",tree.length()>0,"reason",tree.length()>0?null:"empty_accessibility_tree","nextTool",tree.length()>0?null:"screen_capture","width",getResources().getDisplayMetrics().widthPixels,"height",getResources().getDisplayMetrics().heightPixels),null);return;
    }
    if(name.equals("ui_act")){act(args,result);return;}
    if(name.equals("screen_capture")){captureTool(result);return;}
    if(name.equals("ui_back")){result.done(PiRuntime.json("performed",performGlobalAction(GLOBAL_ACTION_BACK)),null);return;}
    if(name.equals("ui_tap")) {
      if(args.has("nodeId")){
        AccessibilityNodeInfo n=node(args.getString("nodeId"));
        requireUsable(n,AccessibilityNodeInfo.ACTION_CLICK);
        boolean ok=n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        result.done(PiRuntime.json("performed",ok,"status",ok?"dispatched":"not_dispatched","code",ok?null:"dispatch_rejected"),null);return;
      }
      visualGesture(args,false,result);return;
    }
    if(name.equals("ui_input")){
      if(!args.has("nodeId")){
        if(args.has("screenshotId"))visualGesture(args,false,(value,error)->{if(error!=null)result.done(null,error);else focusedInput.replace(args,result);});
        else focusedInput.replace(args,result);
        return;
      }
      AccessibilityNodeInfo n=node(args.getString("nodeId"));if(!n.isEditable()||n.isPassword())throw new Exception("该节点不可编辑，或是密码字段");
      Bundle b=new Bundle();b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,args.getString("text"));
      boolean ok=n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b);boolean verified=false;
      if(ok&&n.refresh()&&!n.isPassword())verified=args.optString("text").contentEquals(n.getText());
      result.done(PiRuntime.json("performed",ok,"verified",verified,"status",!ok?"not_dispatched":verified?"verified":"unverified","code",ok?null:"dispatch_rejected"),null);return;
    }
    if(name.equals("ui_swipe")){visualGesture(args,true,result);return;}
    throw new Exception("尚未支持这个手机工具");
  }
  void splitState(AndroidTools.Result result){
    Display display=currentDisplay();Point size=new Point();display.getRealSize(size);
    JSONArray windows=new JSONArray();int applications=0;boolean left=false,right=false,top=false,bottom=false;
    for(AccessibilityWindowInfo window:getWindows()){
      if(window.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION)continue;
      AccessibilityNodeInfo root=window.getRoot();if(root==null)continue;
      Rect bounds=new Rect();root.getBoundsInScreen(bounds);String pkg=String.valueOf(root.getPackageName());root.recycle();
      if(bounds.isEmpty()||bounds.width()<=1||bounds.height()<=1||!bounds.intersects(0,0,size.x,size.y))continue;
      applications++;
      long area=(long)bounds.width()*bounds.height();if(area>=size.x*(long)size.y*.12){
        if(bounds.centerX()<size.x/2)left=true;else right=true;
        if(bounds.centerY()<size.y/2)top=true;else bottom=true;
      }
      windows.put(PiRuntime.json("window",window.getId(),"package",pkg,"focused",window.isFocused(),"active",window.isActive(),
        "left",bounds.left,"top",bounds.top,"right",bounds.right,"bottom",bounds.bottom,
        "width",bounds.width(),"height",bounds.height(),"orientation",(bounds.width()>bounds.height())?"horizontal":"vertical"));
    }
    boolean sideBySide=left&&right,stacked=top&&bottom;
    result.done(PiRuntime.json("displayWidth",size.x,"displayHeight",size.y,"applicationWindows",applications,
      "windows",windows,"split",sideBySide||stacked,"mode",sideBySide?"side_by_side":stacked?"stacked":applications>1?"multiple":"single"),null);
  }
  void toggleSplitScreen(AndroidTools.Result result){
    JSONObject before=splitSnapshot();boolean accepted=performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN);
    if(!accepted){result.done(PiRuntime.json("performed",false,"status","not_dispatched","code","dispatch_rejected"),null);return;}
    Handler handler=new Handler(Looper.getMainLooper());long deadline=SystemClock.uptimeMillis()+2500;
    Runnable check=new Runnable(){public void run(){
      if(PhoneAccessibility.instance!=PhoneAccessibility.this){result.done(null,"无障碍服务已断开");return;}
      JSONObject after=splitSnapshot();
      if(SystemClock.uptimeMillis()>=deadline||!after.toString().equals(before.toString())){
        result.done(PiRuntime.json("performed",true,"status","dispatched","changed",!after.toString().equals(before.toString()),"state",after),null);return;
      }
      handler.postDelayed(this,120);
    }};handler.postDelayed(check,180);
  }
  void awaitSplitLaunch(String packageName,AndroidTools.Result result){
    Handler handler=new Handler(Looper.getMainLooper());long deadline=SystemClock.uptimeMillis()+3500;
    Runnable check=new Runnable(){public void run(){
      if(PhoneAccessibility.instance!=PhoneAccessibility.this){result.done(null,"无障碍服务已断开");return;}
      final JSONObject[] stateHolder={new JSONObject()};splitState(new AndroidTools.Result(){@Override public void done(org.json.JSONObject value,String error){try{stateHolder[0].put("state",value.optJSONObject("state"));stateHolder[0].put("split",value.optBoolean("split"));stateHolder[0].put("applicationWindows",value.optInt("applicationWindows"));stateHolder[0].put("windows",value.optJSONArray("windows"));}catch(org.json.JSONException ignored){}}});
      JSONObject state=stateHolder[0],stateValue=state.optJSONObject("state");
      boolean target=false;
      if(stateValue!=null)for(int i=0;i<stateValue.optJSONArray("windows").length();i++){
        org.json.JSONObject window=stateValue.optJSONArray("windows").optJSONObject(i);
        if(window!=null&&packageName.equals(window.optString("package")))target=true;
      }
      if(state.optBoolean("split")&&target){result.done(PiRuntime.json("launched",true,"ready",true,"split",true,"state",stateValue,"position",stateValue.optString("mode")),null);return;}
      if(SystemClock.uptimeMillis()>=deadline){result.done(PiRuntime.json("launched",true,"ready",false,"split",state.optBoolean("split"),"state",stateValue,"reason",state.optBoolean("split")?"target_not_visible":"split_not_confirmed"),null);return;}
      handler.postDelayed(this,140);
    }};handler.postDelayed(check,180);
  }
  private JSONObject splitSnapshot(){
    JSONArray rows=new JSONArray();
    for(AccessibilityWindowInfo window:getWindows()){
      if(window.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION)continue;
      AccessibilityNodeInfo root=window.getRoot();if(root==null)continue;
      Rect bounds=new Rect();root.getBoundsInScreen(bounds);String pkg=String.valueOf(root.getPackageName());root.recycle();
      rows.put(window.getId()+":"+pkg+":"+bounds.toShortString()+":"+window.isFocused());
    }
    return PiRuntime.json("windows",rows);
  }
  String foregroundPackage(){AccessibilityNodeInfo root=applicationRoot();if(root==null)return "";try{return String.valueOf(root.getPackageName());}finally{root.recycle();}}
  String screenApplicationPackage(){
    String best="";int layer=Integer.MIN_VALUE;
    for(AccessibilityWindowInfo window:getWindows()){
      if(window.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION||window.getLayer()<layer)continue;
      AccessibilityNodeInfo root=window.getRoot();if(root==null)continue;
      try{if(window.isActive()||window.isFocused())return String.valueOf(root.getPackageName());best=String.valueOf(root.getPackageName());layer=window.getLayer();}finally{root.recycle();}
    }
    return best;
  }
  private Display currentDisplay(){return ((android.hardware.display.DisplayManager)getSystemService(DISPLAY_SERVICE)).getDisplay(Display.DEFAULT_DISPLAY);}
  private void visualGesture(JSONObject args,boolean swipe,AndroidTools.Result result)throws Exception{
    VisualFrame frame=visualFrame;if(frame==null)throw new Exception("请先读取截图，使用 screenshotId 和图片像素坐标");
    AccessibilityNodeInfo root=getRootInActiveWindow();String pkg=root==null?"":String.valueOf(root.getPackageName());int window=root==null?-1:root.getWindowId();if(root!=null)root.recycle();
    Display display=currentDisplay();Point size=new Point();display.getRealSize(size);frame.validate(args,pkg,window,size.x,size.y,display.getRotation());
    float x1=frame.x(args.getDouble(swipe?"x1":"x")),y1=frame.y(args.getDouble(swipe?"y1":"y"));
    float x2=swipe?frame.x(args.getDouble("x2")):x1,y2=swipe?frame.y(args.getDouble("y2")):y1;
    if(swipe&&Math.hypot(x2-x1,y2-y1)<8)throw new Exception("滑动起终点必须不同；点击请使用 ui_tap");
    frame.consumed=true;injectedUntil=SystemClock.elapsedRealtime()+1200;gesture(x1,y1,x2,y2,swipe?400:80,result);
  }
  private void captureTool(AndroidTools.Result result){
    String pkg=foregroundPackage();AccessibilityNodeInfo root=applicationRoot();int window=root==null?-1:root.getWindowId();if(root!=null)root.recycle();
    int rotation=currentDisplay().getRotation();
    capture((bitmap,error)->{
      if(error!=null){result.done(null,error);return;}
      int w=bitmap.getWidth(),h=bitmap.getHeight();float ratio=Math.min(1,1400f/Math.max(w,h));int iw=Math.round(w*ratio),ih=Math.round(h*ratio);
      Bitmap scaled=ratio<1?Bitmap.createScaledBitmap(bitmap,iw,ih,true):bitmap;
      ByteArrayOutputStream out=new ByteArrayOutputStream();scaled.compress(Bitmap.CompressFormat.JPEG,82,out);if(scaled!=bitmap)scaled.recycle();bitmap.recycle();
      visualFrame=new VisualFrame("screen-"+(++frameSequence),pkg,window,w,h,iw,ih,rotation);
      result.done(PiRuntime.json("image",Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP),"mimeType","image/jpeg","frame",visualFrame.metadata()),null);
    });
  }
  private AccessibilityNodeInfo node(String id)throws Exception{
    AccessibilityNodeInfo root=contextRoot();if(root==null)throw new Exception("页面已变化，请重新读取界面再操作");
    try{
      AccessibilityNodeInfo n=nodes.get(id);String identity=identities.get(id);
      if(n==null||identity==null||!n.refresh()||!identity(n).equals(identity)){
        AccessibilityNodeInfo found[]=new AccessibilityNodeInfo[1];int count[]={0};findUnique(root,identity,count,found,0,new int[]{0});
        if(count[0]!=1||found[0]==null)throw new Exception("目标已消失或有歧义，请重新读取界面再操作");
        if(n!=null)n.recycle();nodes.put(id,found[0]);n=found[0];
      }
      return n;
    }finally{if(root!=null&&root!=nodes.get(id))root.recycle();}
  }
  private String identity(AccessibilityNodeInfo n){
    return PiRuntime.json("package",String.valueOf(n.getPackageName()),"window",n.getWindowId(),"class",String.valueOf(n.getClassName()),"resourceId",n.getViewIdResourceName(),
      "text",n.isPassword()||n.isEditable()||n.isShowingHintText()?"":String.valueOf(n.getText()==null?"":n.getText()),"hint",String.valueOf(n.getHintText()==null?"":n.getHintText()),
      "description",String.valueOf(n.getContentDescription()==null?"":n.getContentDescription()),"editable",n.isEditable(),"checkable",n.isCheckable(),"multiLine",n.isMultiLine(),"inputType",n.getInputType()).toString();
  }
  private void findUnique(AccessibilityNodeInfo n,String identity,int[] count,AccessibilityNodeInfo found[],int depth,int visited[]){
    if(count[0]>1||visited[0]>=180||depth>18||!n.isVisibleToUser())return;
    visited[0]++;
    if(identity(n).equals(identity)){count[0]++;found[0]=count[0]==1?n:null;return;}
    for(int i=0;i<n.getChildCount()&&count[0]<2;i++){AccessibilityNodeInfo child=n.getChild(i);if(child==null)continue;
      findUnique(child,identity,count,found,depth+1,visited);if(found[0]!=child)child.recycle();
    }
  }
  private AccessibilityNodeInfo applicationRoot(){
    AccessibilityNodeInfo fallback=getRootInActiveWindow();
    if(fallback!=null&&focusedWindowId()==fallback.getWindowId())return fallback;
    for(AccessibilityWindowInfo window:getWindows()){
      if(window.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION||!window.isFocused())continue;
      AccessibilityNodeInfo root=window.getRoot();
      if(root!=null){if(fallback!=null&&fallback!=root)fallback.recycle();return root;}
    }
    return fallback;
  }
  private int focusedWindowId(){
    for(AccessibilityWindowInfo window:getWindows())if(window.isFocused())return window.getId();
    return -1;
  }
  private AccessibilityNodeInfo contextRoot(){
    if(observedAt==0||SystemClock.uptimeMillis()-observedAt>30000)return null;
    AccessibilityNodeInfo root=getRootInActiveWindow();
    if(root!=null&&contextMatches(root))return root;
    if(root!=null)root.recycle();
    for(AccessibilityWindowInfo window:getWindows()){
      if(window.getId()!=observedWindow)continue;
      root=window.getRoot();
      if(root!=null){if(contextMatches(root))return root;root.recycle();}
    }
    return null;
  }
  private boolean contextMatches(AccessibilityNodeInfo root){
    return root!=null&&observedWindow==root.getWindowId()&&observedPackage.equals(String.valueOf(root.getPackageName()));
  }
  private void requireUsable(AccessibilityNodeInfo n,int code){
    Rect bounds=new Rect();n.getBoundsInScreen(bounds);Point size=new Point();currentDisplay().getRealSize(size);
    if(!n.isEnabled()||!n.isVisibleToUser()||n.isPassword()||bounds.isEmpty()||bounds.width()<=1||bounds.height()<=1||
      !bounds.intersects(0,0,size.x,size.y))throw new IllegalArgumentException("目标控件不可操作");
    for(AccessibilityNodeInfo.AccessibilityAction action:n.getActionList())if(action.getId()==code)return;
    throw new IllegalArgumentException("目标控件未声明支持该动作");
  }
  private void act(JSONObject args,AndroidTools.Result result)throws Exception{
    if(args.optLong("generation",-1)!=generation||!observedPackage.equals(args.optString("packageName"))||contextRoot()==null){result.done(PiRuntime.json("performed",false,"code","stale_snapshot"),null);return;}
    String action=args.optString("action");
    if(action.equals("back")){result.done(PiRuntime.json("performed",performGlobalAction(GLOBAL_ACTION_BACK)),null);return;}
    int code=action.equals("tap")?AccessibilityNodeInfo.ACTION_CLICK:action.equals("input")?AccessibilityNodeInfo.ACTION_SET_TEXT:action.equals("scroll_forward")?AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:action.equals("scroll_backward")?AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD:0;
    if(code==0)throw new Exception("目标控件未声明支持该动作");
    String id=args.optString("nodeId");AccessibilityNodeInfo n;
    try{n=node(id);}catch(Exception failure){result.done(PiRuntime.json("performed",false,"code","stale_snapshot","reason",failure.getMessage()),null);return;}
    try{requireUsable(n,code);}catch(IllegalArgumentException failure){result.done(PiRuntime.json("performed",false,"code","target_not_operable","reason",failure.getMessage()),null);return;}
    Bundle input=null;if(action.equals("input")){if(!n.isEditable())throw new Exception("目标不可编辑");input=new Bundle();input.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,args.getString("text"));}
    // No coordinate fallback or mutation retry after an uncertain result.
    boolean accepted=n.performAction(code,input);JSONObject receipt=PiRuntime.json("performed",accepted,"status",accepted?"dispatched":"not_dispatched","code",accepted?null:"dispatch_rejected");
    if(accepted&&action.equals("input")){
      boolean verified=n.refresh()&&!n.isPassword()&&args.optString("text").contentEquals(n.getText());
      receipt.put("verified",verified);receipt.put("status",verified?"verified":"unverified");receipt.put("reason",verified?"text_read_back":"input_readback_unavailable");
    }
    result.done(receipt,null);
  }
  private void walk(AccessibilityNodeInfo n,JSONArray tree,int depth,String parentId,String path){
    if(tree.length()>=180||depth>18){n.recycle();return;}
    if(n.isVisibleToUser()){
      String id=generation+":"+nodes.size();nodes.put(id,n);identities.put(id,identity(n));Rect b=new Rect();n.getBoundsInScreen(b);
      String text=n.isPassword()?"[密码字段]":n.isShowingHintText()?"":String.valueOf(n.getText()==null?"":n.getText());
      JSONArray actions=new JSONArray();for(AccessibilityNodeInfo.AccessibilityAction action:n.getActionList())actions.put(action.getId());
      tree.put(PiRuntime.json("nodeId",id,"parentId",parentId,"path",path,"text",text,"description",n.isPassword()?"":String.valueOf(n.getContentDescription()==null?"":n.getContentDescription()),"resourceId",n.getViewIdResourceName(),"class",String.valueOf(n.getClassName()),"package",String.valueOf(n.getPackageName()),"clickable",n.isClickable(),"editable",n.isEditable(),"enabled",n.isEnabled(),"checkable",n.isCheckable(),"checked",n.isChecked(),"selected",n.isSelected(),"focused",n.isFocused(),"scrollable",n.isScrollable(),"password",n.isPassword(),"hint",n.isPassword()?"":String.valueOf(n.getHintText()==null?"":n.getHintText()),"stateDescription",n.isPassword()?"":String.valueOf(n.getStateDescription()==null?"":n.getStateDescription()),"error",n.isPassword()?"":String.valueOf(n.getError()==null?"":n.getError()),"multiLine",n.isMultiLine(),"inputType",n.getInputType(),"actions",actions,"bounds",new JSONArray().put(b.left).put(b.top).put(b.right).put(b.bottom)));
      for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo child=n.getChild(i);if(child!=null)walk(child,tree,depth+1,id,path+"."+i);}
    } else n.recycle();
  }
  private void gesture(float x1,float y1,float x2,float y2,long duration,AndroidTools.Result result)throws Exception{
    int w=getResources().getDisplayMetrics().widthPixels,h=getResources().getDisplayMetrics().heightPixels;
    if(!Float.isFinite(x1)||!Float.isFinite(y1)||!Float.isFinite(x2)||!Float.isFinite(y2)||x1<0||x2<0||y1<0||y2<0||x1>=w||x2>=w||y1>=h||y2>=h)throw new Exception("坐标超出当前屏幕范围");
    Path path=new Path();path.moveTo(x1,y1);if(x1!=x2||y1!=y2)path.lineTo(x2,y2);
    boolean accepted=dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path,0,duration)).build(),new GestureResultCallback(){
      @Override public void onCompleted(GestureDescription d){result.done(PiRuntime.json("performed",true),null);}
      @Override public void onCancelled(GestureDescription d){result.done(null,"手势已取消");}
    },null);
    if(!accepted)result.done(null,"系统未接受手势");
  }
  interface Capture {void done(Bitmap bitmap,String error);}
  void capture(Capture callback){takeScreenshot(Display.DEFAULT_DISPLAY,getMainExecutor(),screenshotCallback(callback));}
  void captureApplication(String app,Capture callback){
    if(Build.VERSION.SDK_INT<34){capture(callback);return;}
    int id=-1,layer=Integer.MIN_VALUE;
    for(AccessibilityWindowInfo window:getWindows()){
      if(window.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION||window.getLayer()<layer)continue;
      AccessibilityNodeInfo root=window.getRoot();if(root==null)continue;
      try{if(app.equals(String.valueOf(root.getPackageName()))){id=window.getId();layer=window.getLayer();}}finally{root.recycle();}
    }
    if(id<0){callback.done(null,"目标应用窗口已关闭，请重新发送");return;}
    takeScreenshotOfWindow(id,getMainExecutor(),screenshotCallback(callback));
  }
  private TakeScreenshotCallback screenshotCallback(Capture callback){
    return new TakeScreenshotCallback(){
      @Override public void onSuccess(ScreenshotResult result){
        Bitmap hardware=Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(),result.getColorSpace());
        Bitmap image=hardware.copy(Bitmap.Config.ARGB_8888,false);hardware.recycle();result.getHardwareBuffer().close();callback.done(image,null);
      }
      @Override public void onFailure(int code){callback.done(null,"屏幕无法截取（"+code+"），可能是受保护页面或请求过于频繁");}
    };
  }
  static String encode(Bitmap bitmap){
    int limit=1400;Bitmap image=bitmap;
    if(Math.max(bitmap.getWidth(),bitmap.getHeight())>limit){float ratio=limit/(float)Math.max(bitmap.getWidth(),bitmap.getHeight());image=Bitmap.createScaledBitmap(bitmap,Math.round(bitmap.getWidth()*ratio),Math.round(bitmap.getHeight()*ratio),true);}
    ByteArrayOutputStream out=new ByteArrayOutputStream();image.compress(Bitmap.CompressFormat.PNG,100,out);if(image!=bitmap)image.recycle();bitmap.recycle();return Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP);
  }
}
