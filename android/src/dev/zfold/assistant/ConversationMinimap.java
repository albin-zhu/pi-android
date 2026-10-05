package dev.zfold.assistant;

import android.content.Context;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.util.*;

/** Transient overview of rendered conversation blocks. No conversation-sized bitmap or second UI. */
final class ConversationMinimap extends View {
  static final int ASSISTANT=0,USER=1,TOOL=2,IMAGE=3,ERROR=4;
  static void mark(View view,int kind){view.setTag(R.id.conversation_minimap_kind,kind);}
  private final Scroll scroll;private final LinearLayout content;private final Ui.Theme theme;
  private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG),label=new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path[] paths={new Path(),new Path(),new Path(),new Path(),new Path()};
  private final ArrayList<Segment> segments=new ArrayList<>();
  private final RectF panel=new RectF(),track=new RectF(),window=new RectF();
  private final int slop;private int measuredContent,measuredViewport,measuredWidth,measuredCount,visibilityToken;
  private boolean dirty=true,suppressed,tracking,dragging,disposed,fadingOut;
  private float downY,grabOffset;private boolean grabbedWindow;
  private int frozenContent,frozenViewport,lastPercent=-1,lastSegment=-1,lastCount=-1;
  private final Runnable hideLater=()->{if(!tracking&&!hasFocus()&&!isAccessibilityFocused())fade();};
  private final ViewTreeObserver.OnGlobalLayoutListener layoutListener=this::layoutChanged;

  ConversationMinimap(Context context,Scroll scroll,LinearLayout content){
    super(context);this.scroll=scroll;this.content=content;theme=new Ui.Theme(context);slop=ViewConfiguration.get(context).getScaledTouchSlop();
    setVisibility(INVISIBLE);setAlpha(0);setFocusable(true);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    label.setTypeface(Typeface.create("sans-serif-medium",0));label.setTextAlign(Paint.Align.CENTER);label.setTextSize(Ui.dp(context,11));
    scroll.map=this;
    scroll.setOnScrollChangeListener((v,x,y,oldX,oldY)->{
      layoutChanged();if(scroll.manualScrolling()&&y!=oldY)reveal();invalidate();describe();
    });
  }
  private int dp(float value){return Ui.dp(getContext(),value);}
  int range(){return Math.max(0,content.getHeight()-scroll.getHeight());}
  boolean interacting(){return tracking;}
  int segmentCount(){layoutChanged();return segments.size();}
  RectF trackBounds(){layoutChanged();return new RectF(track);}
  boolean available(){return !suppressed&&scroll.getHeight()>=dp(80)&&content.getHeight()>scroll.getHeight()*1.5f;}
  boolean ownsTouch(float x,float y){return getVisibility()==VISIBLE&&getAlpha()>.15f&&available()&&x>=0&&x<getWidth()&&y>=panel.top&&y<=panel.bottom;}
  void changed(){dirty=true;postInvalidateOnAnimation();}
  void suppress(boolean value){suppressed=value;if(value)hideNow();}
  void reset(){hideNow();scroll.clearManualIntent();dirty=true;segments.clear();for(Path path:paths)path.reset();lastPercent=-1;lastSegment=-1;}
  void reveal(){
    layoutChanged();if(!available()||disposed)return;
    boolean opening=getVisibility()!=VISIBLE||fadingOut;visibilityToken++;
    if(opening){animate().cancel();fadingOut=false;setVisibility(VISIBLE);updateExclusion();if(Ui.motion())animate().alpha(1).setDuration(100).start();}
    if(!Ui.motion()){animate().cancel();setAlpha(1);}
    scheduleHide();describe();
  }
  private void scheduleHide(){removeCallbacks(hideLater);postDelayed(hideLater,1200);}
  private void fade(){
    final int token=++visibilityToken;animate().cancel();fadingOut=true;
    if(!Ui.motion()){hideNow();return;}
    animate().alpha(0).setDuration(180).withEndAction(()->{if(token==visibilityToken&&!tracking){fadingOut=false;setVisibility(INVISIBLE);updateExclusion();}}).start();
  }
  void hideNow(){
    boolean wasTracking=tracking;visibilityToken++;animate().cancel();removeCallbacks(hideLater);tracking=false;dragging=false;fadingOut=false;setPressed(false);setAlpha(0);setVisibility(INVISIBLE);
    if(wasTracking&&getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);updateExclusion();
  }
  private void updateExclusion(){setSystemGestureExclusionRects(getVisibility()==VISIBLE&&available()?Collections.singletonList(new Rect(0,(int)panel.top,getWidth(),(int)panel.bottom)):Collections.emptyList());}
  private void layoutChanged(){
    if(tracking)return;
    int height=content.getHeight(),viewport=scroll.getHeight(),width=getWidth(),count=content.getChildCount();
    if(!dirty&&height==measuredContent&&viewport==measuredViewport&&width==measuredWidth&&count==measuredCount)return;
    measuredContent=height;measuredViewport=viewport;measuredWidth=width;measuredCount=count;dirty=false;
    if(!available()){hideNow();segments.clear();return;}
    float panelHeight=Math.min(dp(192),getHeight()-dp(12));float top=(getHeight()-panelHeight)/2;
    boolean compact=scroll.getWidth()<dp(360)||viewport<dp(220);float widthDp=compact?14:24;
    label.setTextSize(dp(compact?8.5f:10));
    panel.set(getWidth()-dp(widthDp+10),top,getWidth()-dp(2),top+panelHeight);
    track.set(panel.left+dp(4),panel.top+dp(21),panel.right-dp(4),panel.bottom-dp(6));
    segments.clear();for(Path path:paths)path.reset();
    for(int i=0;i<count;i++){
      View child=content.getChildAt(i);if(child.getVisibility()!=VISIBLE||child.getHeight()==0)continue;
      Object tag=child.getTag(R.id.conversation_minimap_kind);int kind=tag instanceof Integer?(int)tag:ASSISTANT;
      if(child instanceof ToolCallView){kind="error".equals(((ToolCallView)child).record.optString("status"))?ERROR:TOOL;}
      if(kind<0||kind>=paths.length)kind=ASSISTANT;
      segments.add(new Segment(child.getTop(),child.getBottom(),kind));
      float y=track.top+child.getTop()/(float)height*track.height(),bottom=track.top+child.getBottom()/(float)height*track.height();
      float gap=Math.min(dp(1),Math.max(0,(bottom-y)/5));y+=gap;bottom-=gap;
      if(kind==USER)paths[kind].addRoundRect(new RectF(track.left+track.width()*.25f,y,track.right,Math.max(y+1,bottom)),dp(1),dp(1),Path.Direction.CW);
      else if(kind==IMAGE)paths[kind].addRect(track.left+track.width()*.2f,y,track.right,Math.max(y+1,bottom),Path.Direction.CW);
      else{
        int lines=kind==ASSISTANT?Math.min(12,Math.max(1,(int)((bottom-y)/dp(3)))):1;
        for(int line=0;line<lines;line++){float at=y+(bottom-y)*line/lines;float end=track.right-(line==lines-1?track.width()*.22f:0);paths[kind].addRect(track.left,at,end,Math.min(track.bottom,at+Math.max(1,dp(kind==ASSISTANT?.65f:1.2f))),Path.Direction.CW);}
      }
    }
    updateExclusion();invalidate();describe();
  }
  private int total(){return tracking?frozenContent:content.getHeight();}
  private int viewport(){return tracking?frozenViewport:scroll.getHeight();}
  private void windowBounds(){
    float total=Math.max(1,total()),center=track.top+(scroll.getScrollY()+viewport()/2f)/total*track.height();
    float height=Math.max(dp(6),viewport()/total*track.height());height=Math.min(height,track.height());
    float top=Math.max(track.top,Math.min(track.bottom-height,center-height/2));window.set(track.left-dp(2),top,track.right+dp(2),top+height);
  }
  @Override protected void onDraw(Canvas canvas){
    super.onDraw(canvas);layoutChanged();if(!available()||track.isEmpty())return;
    paint.setStyle(Paint.Style.FILL);paint.setColor(theme.surface);paint.setAlpha(240);canvas.drawRoundRect(panel,dp(7),dp(7),paint);
    for(int kind=0;kind<paths.length;kind++){paint.setColor(kind==USER?theme.accent:kind==ERROR?theme.error:theme.muted);paint.setAlpha(kind==USER||kind==ERROR?200:kind==TOOL?160:110);paint.setStyle(kind==IMAGE?Paint.Style.STROKE:Paint.Style.FILL);paint.setStrokeWidth(dp(.8f));canvas.drawPath(paths[kind],paint);}
    windowBounds();paint.setStyle(Paint.Style.FILL);paint.setColor(theme.accent);paint.setAlpha(tracking?65:32);canvas.drawRoundRect(window,dp(2),dp(2),paint);
    paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(tracking?1.5f:1));paint.setAlpha(220);canvas.drawRoundRect(window,dp(2),dp(2),paint);
    label.setColor(tracking?theme.accent:theme.muted);canvas.drawText(percent()+"%",panel.centerX(),panel.top+dp(14),label);
  }
  private int percent(){return Math.round(Math.max(0,Math.min(1,scroll.getScrollY()/(float)Math.max(1,total()-viewport())))*100);}
  private void navigate(float y){
    float center=Math.max(0,Math.min(1,(y-grabOffset-track.top)/Math.max(1,track.height())));
    int position=Math.round(center*total()-viewport()/2f);scroll.scrollTo(0,Math.max(0,Math.min(total()-viewport(),position)));invalidate();describe();
  }
  @Override public boolean onTouchEvent(MotionEvent event){
    switch(event.getActionMasked()){
      case MotionEvent.ACTION_DOWN:
        if(!ownsTouch(event.getX(),event.getY()))return false;
        scroll.haltFling();frozenContent=content.getHeight();frozenViewport=scroll.getHeight();windowBounds();
        downY=event.getY();grabbedWindow=downY>=window.top-dp(6)&&downY<=window.bottom+dp(6);
        grabOffset=grabbedWindow?downY-(track.top+(scroll.getScrollY()+frozenViewport/2f)/Math.max(1,frozenContent)*track.height()):0;
        tracking=true;dragging=false;fadingOut=false;setPressed(true);visibilityToken++;animate().cancel();setAlpha(1);removeCallbacks(hideLater);getParent().requestDisallowInterceptTouchEvent(true);invalidate();return true;
      case MotionEvent.ACTION_MOVE:
        if(!tracking)return false;if(event.getPointerCount()>1){endInteraction();return true;}
        if(!dragging&&Math.abs(event.getY()-downY)>slop)dragging=true;
        if(dragging)navigate(event.getY());return true;
      case MotionEvent.ACTION_UP:
        if(!tracking)return false;if(!dragging){if(!grabbedWindow)navigate(event.getY());performClick();}else navigate(event.getY());endInteraction();return true;
      case MotionEvent.ACTION_POINTER_DOWN:case MotionEvent.ACTION_CANCEL:
        if(tracking){endInteraction();return true;}return false;
      default:return tracking;
    }
  }
  private void endInteraction(){tracking=false;dragging=false;setPressed(false);getParent().requestDisallowInterceptTouchEvent(false);dirty=true;layoutChanged();scheduleHide();}
  @Override public boolean performClick(){super.performClick();return true;}
  private int currentSegment(){int at=scroll.getScrollY()+scroll.getHeight()/2;int lo=0,hi=segments.size()-1;while(lo<=hi){int mid=(lo+hi)>>>1;if(segments.get(mid).bottom<at)lo=mid+1;else hi=mid-1;}return Math.min(lo,Math.max(0,segments.size()-1));}
  private void describe(){
    int percent=percent(),at=currentSegment();if(percent==lastPercent&&at==lastSegment&&lastCount==segments.size())return;lastPercent=percent;lastSegment=at;lastCount=segments.size();
    String kind=segments.isEmpty()?"":new String[]{"回答","提问","工具记录","图片","失败的工具记录"}[segments.get(at).kind];
    setContentDescription("对话缩略导航，"+percent+"%，第 "+(segments.isEmpty()?0:at+1)+" / "+segments.size()+" 段已加载内容，"+kind+"。可拖动定位");
  }
  @Override public CharSequence getAccessibilityClassName(){return SeekBar.class.getName();}
  @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,0,100,percent()));info.setScrollable(true);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);}
  @Override public boolean performAccessibilityAction(int action,Bundle args){
    if(available()&&(action==AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()||action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD||action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)){
      float target=action==AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()?(args==null?Float.NaN:args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,Float.NaN))/100*range():scroll.getScrollY()+(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD?1:-1)*scroll.getHeight()*.8f;
      if(!Float.isFinite(target))return false;scroll.haltFling();scroll.scrollTo(0,Math.round(Math.max(0,Math.min(range(),target))));reveal();return true;
    }
    boolean result=super.performAccessibilityAction(action,args);if(action==AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)scheduleHide();return result;
  }
  @Override protected void onFocusChanged(boolean focused,int direction,Rect previous){super.onFocusChanged(focused,direction,previous);if(!focused)scheduleHide();}
  @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);if(tracking)endInteraction();dirty=true;}
  @Override protected void onAttachedToWindow(){super.onAttachedToWindow();scroll.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);}
  @Override protected void onDetachedFromWindow(){if(scroll.getViewTreeObserver().isAlive())scroll.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);hideNow();super.onDetachedFromWindow();}
  void dispose(){disposed=true;hideNow();if(scroll.getViewTreeObserver().isAlive())scroll.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);}
  private static final class Segment {final int top,bottom,kind;Segment(int top,int bottom,int kind){this.top=top;this.bottom=bottom;this.kind=kind;}}

  /** Distinguishes intentional scrolling from streamed replies and layout changes. */
  static final class Scroll extends ScrollView {
    ConversationMinimap map;private float downX,downY;private boolean touching,moving,automatic,flinging;private long manualUntil;
    private final int slop;
    private final Runnable flingIdle=()->flinging=false;
    Scroll(Context context){super(context);slop=ViewConfiguration.get(context).getScaledTouchSlop();}
    boolean touching(){return touching||flinging;}
    boolean manualScrolling(){return !automatic&&(flinging||moving&&touching||SystemClock.uptimeMillis()<manualUntil);}
    void clearManualIntent(){manualUntil=0;moving=false;flinging=false;removeCallbacks(flingIdle);}
    @Override public void fling(int velocity){flinging=velocity!=0;super.fling(velocity);}
    @Override protected void onScrollChanged(int x,int y,int oldX,int oldY){super.onScrollChanged(x,y,oldX,oldY);if(flinging){removeCallbacks(flingIdle);postDelayed(flingIdle,120);}}
    @Override public boolean dispatchTouchEvent(MotionEvent event){
      switch(event.getActionMasked()){
        case MotionEvent.ACTION_DOWN:touching=true;moving=false;flinging=false;downX=event.getX();downY=event.getY();break;
        case MotionEvent.ACTION_MOVE:if(Math.abs(event.getY()-downY)>slop&&Math.abs(event.getY()-downY)>Math.abs(event.getX()-downX)){moving=true;manualUntil=SystemClock.uptimeMillis()+1800;}break;
        case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:touching=false;break;
      }
      return super.dispatchTouchEvent(event);
    }
    @Override public boolean onGenericMotionEvent(MotionEvent event){if(event.getAction()==MotionEvent.ACTION_SCROLL)manualUntil=SystemClock.uptimeMillis()+1800;return super.onGenericMotionEvent(event);}
    @Override public boolean performAccessibilityAction(int action,Bundle args){if(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD||action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)manualUntil=SystemClock.uptimeMillis()+1800;return super.performAccessibilityAction(action,args);}
    @Override public boolean onKeyDown(int code,KeyEvent event){if(code==KeyEvent.KEYCODE_DPAD_DOWN||code==KeyEvent.KEYCODE_DPAD_UP||code==KeyEvent.KEYCODE_PAGE_DOWN||code==KeyEvent.KEYCODE_PAGE_UP)manualUntil=SystemClock.uptimeMillis()+1800;return super.onKeyDown(code,event);}
    void followLatest(){if(touching()||map!=null&&map.interacting())return;automatic=true;clearManualIntent();try{scrollTo(0,getChildCount()==0?0:getChildAt(0).getHeight());}finally{automatic=false;}}
    void haltFling(){
      clearManualIntent();long now=SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,1,1,0),cancel=MotionEvent.obtain(now,now,MotionEvent.ACTION_CANCEL,1,1,0);
      try{super.onTouchEvent(down);super.onTouchEvent(cancel);}finally{down.recycle();cancel.recycle();}
    }
    @Override protected void onDetachedFromWindow(){removeCallbacks(flingIdle);super.onDetachedFromWindow();}
  }
}
