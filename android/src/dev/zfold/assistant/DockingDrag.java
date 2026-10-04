package dev.zfold.assistant;

import android.content.*;
import android.content.res.Configuration;
import android.graphics.*;
import android.os.*;
import android.view.*;

/** Finger-relative drag with a cancellable spring; the window is always the position authority. */
final class DockingDrag implements View.OnTouchListener,Choreographer.FrameCallback,ComponentCallbacks {
  interface Position {void save(int x,int y);}
  private final View view;private final WindowManager manager;private final WindowManager.LayoutParams params;private final Position position;
  private final Handler main=new Handler(Looper.getMainLooper());private final Context app;
  private VelocityTracker velocity;private float downX,downY,originX,originY,x,y,vx,vy,targetX,targetY;
  private int minX,minY,maxX,maxY;private boolean moving,longPressed,settling,disposed;private long frameAt;private int frames;
  private final Runnable longPress=this::longPress;
  private void longPress(){if(!moving&&!disposed&&view.isPressed()){longPressed=view.performLongClick();view.setPressed(false);}}
  DockingDrag(View view,WindowManager manager,WindowManager.LayoutParams params,Position position){
    this.view=view;this.manager=manager;this.params=params;this.position=position;app=view.getContext().getApplicationContext();app.registerComponentCallbacks(this);bounds();
  }
  private void bounds(){WindowMetrics metrics=manager.getMaximumWindowMetrics();Rect rect=metrics.getBounds();Insets bars=metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());minX=bars.left;minY=bars.top;maxX=Math.max(minX,rect.width()-bars.right-params.width);maxY=Math.max(minY,rect.height()-bars.bottom-params.height);}
  private int edge(){return Math.min(Ui.dp(view.getContext(),8),(maxX-minX)/2);}
  void place(){bounds();set(params.x<0?maxX-edge():params.x,params.y);}
  private void set(float nx,float ny){x=Math.max(minX,Math.min(maxX,nx));y=Math.max(minY,Math.min(maxY,ny));params.x=Math.round(x);params.y=Math.round(y);if(view.isAttachedToWindow())try{manager.updateViewLayout(view,params);}catch(IllegalArgumentException ignored){}position.save(params.x,params.y);}
  private void cancel(){settling=false;Choreographer.getInstance().removeFrameCallback(this);main.removeCallbacks(longPress);}
  public boolean onTouch(View ignored,MotionEvent event){
    if(disposed)return false;
    if(event.getActionMasked()==MotionEvent.ACTION_DOWN){cancel();bounds();originX=params.x;originY=params.y;downX=event.getRawX();downY=event.getRawY();moving=false;longPressed=false;view.drawableHotspotChanged(event.getX(),event.getY());view.setPressed(true);velocity=VelocityTracker.obtain();main.postDelayed(longPress,ViewConfiguration.getLongPressTimeout());}
    if(velocity!=null){MotionEvent raw=MotionEvent.obtain(event);raw.offsetLocation(event.getRawX()-event.getX(),event.getRawY()-event.getY());velocity.addMovement(raw);raw.recycle();}
    switch(event.getActionMasked()){
      case MotionEvent.ACTION_MOVE:
        float dx=event.getRawX()-downX,dy=event.getRawY()-downY;
        if(!longPressed&&Math.hypot(dx,dy)>ViewConfiguration.get(view.getContext()).getScaledTouchSlop()){moving=true;main.removeCallbacks(longPress);view.setPressed(false);}
        if(moving)set(originX+dx,originY+dy);return true;
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        main.removeCallbacks(longPress);view.setPressed(false);boolean tap=event.getActionMasked()==MotionEvent.ACTION_UP&&!moving&&!longPressed;
        vx=vy=0;if(velocity!=null){velocity.computeCurrentVelocity(1000,Ui.dp(view.getContext(),3000));vx=velocity.getXVelocity();vy=velocity.getYVelocity();velocity.recycle();velocity=null;}
        if(moving)dock();else if(tap)view.performClick();return true;
      default:return true;
    }
  }
  private void dock(){
    targetX=x+vx*.12f<(minX+maxX)*.5f?minX+edge():maxX-edge();float reach=Ui.dp(view.getContext(),72);targetY=Math.max(minY,Math.min(maxY,y+Math.max(-reach,Math.min(reach,vy*.07f))));
    if(!Ui.motion()){set(targetX,targetY);return;}
    settling=true;frameAt=0;frames=0;Choreographer.getInstance().postFrameCallback(this);
  }
  public void doFrame(long now){
    if(!settling||disposed)return;
    if(!Ui.motion()){settling=false;set(targetX,targetY);return;}
    float elapsed=frameAt==0?1f/60:Math.min(.12f,(now-frameAt)/1_000_000_000f);frameAt=now;
    int steps=Math.max(1,(int)Math.ceil(elapsed/.008f));float dt=elapsed/steps;
    for(int i=0;i<steps;i++){vx+=(500*(targetX-x)-42*vx)*dt;vy+=(500*(targetY-y)-42*vy)*dt;x+=vx*dt;y+=vy*dt;}set(x,y);
    if(++frames>120||(Math.hypot(targetX-x,targetY-y)<.6&&Math.hypot(vx,vy)<8)){settling=false;set(targetX,targetY);return;}
    Choreographer.getInstance().postFrameCallback(this);
  }
  public void onConfigurationChanged(Configuration configuration){main.post(()->{if(disposed)return;cancel();boolean right=params.x>(minX+maxX)/2;bounds();set(right?maxX-edge():minX+edge(),params.y);});}
  public void onLowMemory(){}
  void dispose(){disposed=true;cancel();if(velocity!=null){velocity.recycle();velocity=null;}app.unregisterComponentCallbacks(this);}
}
