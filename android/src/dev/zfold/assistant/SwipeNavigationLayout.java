package dev.zfold.assistant;

import android.content.Context;
import android.graphics.Rect;
import android.view.*;
import android.widget.*;

/** In-window conversation drawer. Touch tracking never owns the system window or a terminal. */
final class SwipeNavigationLayout extends FrameLayout implements Choreographer.FrameCallback {
  private final MainActivity owner;private final int slop;
  private SessionPicker picker;private VelocityTracker tracker;
  private float downX,downY,startPosition,position,velocity,target;
  private boolean candidate,dragging,blocked,settling,initiallyOpen,selecting,touchOpening;
  private long lastFrame;
  private View conversationRegion;
  private Rect excluded=new Rect();
  SwipeNavigationLayout(MainActivity owner){super(owner);this.owner=owner;slop=ViewConfiguration.get(owner).getScaledTouchSlop();}
  void conversationRegion(View view){conversationRegion=view;}
  void selecting(boolean value){selecting=value;if(value)candidate=false;}
  boolean hasDrawer(){return picker!=null;}
  boolean fullyOpen(){return picker!=null&&position>.995f&&!settling;}
  float progress(){return position;}
  boolean isSettling(){return settling;}
  boolean isTouchOpening(){return touchOpening;}
  SessionPicker currentPicker(){return picker;}
  void show(SessionPicker next){
    if(picker!=null){if(picker==next){open();return;}removeDrawer();}
    picker=next;addView(next.frame,new FrameLayout.LayoutParams(-1,-1));next.resize(getWidth(),getHeight());
    owner.syncMinimapSuppression();
    getChildAt(0).setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    position=0;paint();next.frame.requestApplyInsets();next.frame.requestFocus();open();
  }
  void open(){if(picker!=null)settle(1,0);}
  void close(){if(picker!=null)settle(0,0);}
  private void removeDrawer(){
    cancelSpring();if(picker==null)return;SessionPicker old=picker;picker=null;removeView(old.frame);old.detached();position=0;
    owner.syncMinimapSuppression();
    if(getChildCount()>0)getChildAt(0).setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_AUTO);
  }
  private void paint(){if(picker!=null){picker.drawer.setTranslationX((position-1)*picker.drawerWidth);picker.scrim.setAlpha(position);}}
  private void cancelSpring(){settling=false;Choreographer.getInstance().removeFrameCallback(this);lastFrame=0;}
  private void settle(float destination,float releaseVelocity){
    cancelSpring();target=destination;velocity=releaseVelocity;
    if(!Ui.motion()){position=target;touchOpening=false;paint();if(target==0)removeDrawer();return;}
    settling=true;Choreographer.getInstance().postFrameCallback(this);
  }
  @Override public void doFrame(long now){
    if(!settling||picker==null)return;
    float elapsed=lastFrame==0?1f/60:Math.min(.08f,(now-lastFrame)/1_000_000_000f);lastFrame=now;
    int steps=Math.max(1,(int)Math.ceil(elapsed/.008f));float dt=elapsed/steps;
    for(int i=0;i<steps;i++){velocity+=(480*(target-position)-44*velocity)*dt;position+=velocity*dt;}
    position=Math.max(0,Math.min(1,position));paint();
    if(!Ui.motion()||(Math.abs(target-position)<.001f&&Math.abs(velocity)<.01f)){position=target;touchOpening=false;paint();settling=false;if(target==0)removeDrawer();return;}
    Choreographer.getInstance().postFrameCallback(this);
  }
  @Override public boolean dispatchTouchEvent(MotionEvent event){
    int action=event.getActionMasked();
    if(action==MotionEvent.ACTION_DOWN){
      if(tracker!=null)tracker.recycle();tracker=VelocityTracker.obtain();
      downX=event.getX();downY=event.getY();startPosition=position;initiallyOpen=settling?target==1:position>.5f;
      touchOpening=false;
      candidate=picker!=null||(!selecting&&inside(conversationRegion,downX,downY)&&!ownsHorizontal(getChildAt(0),event.getRawX(),event.getRawY()));
      blocked=false;dragging=false;if(candidate)cancelSpring();
    }
    if(tracker!=null)tracker.addMovement(event);
    boolean result=super.dispatchTouchEvent(event);
    if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){
      if(!dragging&&picker!=null&&!settling&&position>0&&position<1)settle(initiallyOpen?1:0,0);
      if(tracker!=null){tracker.recycle();tracker=null;}dragging=false;
      if(action==MotionEvent.ACTION_UP)touchOpening=false;
    }
    return result;
  }
  @Override public boolean onInterceptTouchEvent(MotionEvent event){
    if(dragging)return true;
    if(!candidate||blocked||selecting)return false;
    if(event.getPointerCount()>1){blocked=true;return false;}
    if(event.getActionMasked()==MotionEvent.ACTION_MOVE){
      float dx=event.getX()-downX,dy=event.getY()-downY;
      if(Math.abs(dy)>slop&&Math.abs(dx)<=Math.abs(dy)*1.35f){blocked=true;return false;}
      if(Math.abs(dx)>slop&&Math.abs(dx)>Math.abs(dy)*1.35f){
        if(picker==null&&dx<0){blocked=true;return false;}
        if(picker==null){owner.openSidebar();cancelSpring();position=0;startPosition=0;touchOpening=true;}
        if(picker==null)return false;dragging=true;drag(event.getX());getParent().requestDisallowInterceptTouchEvent(true);return true;
      }
    }
    return false;
  }
  @Override public void requestDisallowInterceptTouchEvent(boolean disallow){
    // Selectable message text requests this on DOWN, before an axis or long press is known.
    // Editors and horizontal scrollers are excluded at DOWN; vertical scrolling locks first.
    if(disallow&&candidate&&!blocked&&!selecting&&!dragging)return;
    super.requestDisallowInterceptTouchEvent(disallow);
  }
  @Override public boolean onTouchEvent(MotionEvent event){
    // The window edge can fall in content padding, with no child to own DOWN.
    if(!dragging)onInterceptTouchEvent(event);
    if(!dragging||picker==null)return candidate||super.onTouchEvent(event);
    switch(event.getActionMasked()){
      case MotionEvent.ACTION_MOVE:
        if(event.getPointerCount()>1){settle(initiallyOpen?1:0,0);dragging=false;blocked=true;return true;}
        drag(event.getX());return true;
      case MotionEvent.ACTION_UP:
        drag(event.getX());
        tracker.computeCurrentVelocity(1000,Ui.dp(getContext(),3000));float speed=tracker.getXVelocity();
        float destination=Math.abs(speed)>Ui.dp(getContext(),500)?(speed>0?1:0):(position+speed*.12f/picker.drawerWidth>.5f?1:0);
        settle(destination,speed/picker.drawerWidth);return true;
      case MotionEvent.ACTION_CANCEL:settle(initiallyOpen?1:0,0);return true;
      default:return true;
    }
  }
  private void drag(float x){float dx=x-downX,travel=dx-Math.signum(dx)*Math.min(slop,Math.abs(dx));position=Math.max(0,Math.min(1,startPosition+travel/picker.drawerWidth));paint();}
  private boolean inside(View view,float x,float y){if(view==null)return false;Rect rect=new Rect(0,0,view.getWidth(),view.getHeight());offsetDescendantRectToMyCoords(view,rect);return rect.contains((int)x,(int)y)||(x>=0&&x<Ui.dp(getContext(),24)&&y>=rect.top&&y<rect.bottom);}
  private boolean ownsHorizontal(View view,float x,float y){
    if(view.getVisibility()!=VISIBLE)return false;int[] at=new int[2];view.getLocationOnScreen(at);
    if(x<at[0]||x>=at[0]+view.getWidth()||y<at[1]||y>=at[1]+view.getHeight())return false;
    if(view instanceof ConversationMinimap&&((ConversationMinimap)view).ownsTouch(x-at[0],y-at[1]))return true;
    if(view instanceof EditText||view instanceof HorizontalScrollView||view.canScrollHorizontally(-1)||view.canScrollHorizontally(1))return true;
    if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=group.getChildCount()-1;i>=0;i--)if(ownsHorizontal(group.getChildAt(i),x,y))return true;}
    return false;
  }
  void beginBack(){if(picker!=null){cancelSpring();startPosition=position;}}
  void backProgress(float fraction){if(picker!=null){position=startPosition*(1-Math.max(0,Math.min(1,fraction)));paint();}}
  void backOpenProgress(float fraction){if(picker!=null){position=startPosition+(1-startPosition)*Math.max(0,Math.min(1,fraction));paint();}}
  void cancelBack(){if(picker!=null)open();}
  @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);if(picker!=null){picker.resize(w,h);paint();}}
  @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom){
    super.onLayout(changed,left,top,right,bottom);
    if(conversationRegion==null||conversationRegion.getHeight()==0)return;
    Rect body=new Rect(0,0,conversationRegion.getWidth(),conversationRegion.getHeight());offsetDescendantRectToMyCoords(conversationRegion,body);
    int reach=Math.min(Ui.dp(getContext(),200),body.height()),start=body.top+(body.height()-reach)/2;
    Rect next=new Rect(0,start,Ui.dp(getContext(),24),start+reach);
    if(!excluded.equals(next)){excluded=next;setSystemGestureExclusionRects(java.util.Collections.singletonList(next));}
  }
  void dispose(){if(tracker!=null){tracker.recycle();tracker=null;}removeDrawer();}
}
