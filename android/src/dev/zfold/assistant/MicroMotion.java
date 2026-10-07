package dev.zfold.assistant;

import android.animation.ValueAnimator;
import android.view.Choreographer;
import android.view.View;
import java.util.WeakHashMap;

/** Presentation-value springs. Retargeting carries velocity; never blocks input. */
final class MicroMotion {
  private static final WeakHashMap<View,Spring> rotations=new WeakHashMap<>();
  private static final WeakHashMap<View,Spring> translations=new WeakHashMap<>();
  static void reveal(View view){
    if(!ValueAnimator.areAnimatorsEnabled()||!view.isAttachedToWindow()){view.setAlpha(1);view.setTranslationY(0);return;}
    view.animate().cancel();view.setAlpha(1);
    if(!translations.containsKey(view))view.setTranslationY(4*view.getResources().getDisplayMetrics().density);
    target(view,0,false);
  }
  static void rotate(View view,float target){target(view,target,true);}
  private static void target(View view,float value,boolean rotation){
    WeakHashMap<View,Spring> map=rotation?rotations:translations;
    Spring spring=map.get(view);
    if(!ValueAnimator.areAnimatorsEnabled()){if(spring!=null)spring.cancel();if(rotation)view.setRotation(value);else view.setTranslationY(value);return;}
    if(spring==null){spring=new Spring(view,rotation,map);map.put(view,spring);}
    spring.target=value;spring.start();
  }
  private static final class Spring implements Choreographer.FrameCallback {
    final java.lang.ref.WeakReference<View> view;final boolean rotation;final WeakHashMap<View,Spring> map;
    double velocity;float target;long last;boolean running;
    Spring(View view,boolean rotation,WeakHashMap<View,Spring> map){this.view=new java.lang.ref.WeakReference<>(view);this.rotation=rotation;this.map=map;}
    void start(){if(running)return;running=true;last=0;Choreographer.getInstance().postFrameCallback(this);}
    void cancel(){running=false;Choreographer.getInstance().removeFrameCallback(this);View v=view.get();if(v!=null)map.remove(v);}
    public void doFrame(long now){
      View v=view.get();if(v==null||!v.isAttachedToWindow()){cancel();return;}
      double dt=last==0?1.0/60:Math.min(.032,(now-last)/1e9);last=now;
      double x=rotation?v.getRotation():v.getTranslationY();
      // Critically damped analytical integration, stable across display refresh rates.
      double[] state=SpringMath.step(x,velocity,target,dt);x=state[0];velocity=state[1];
      if(Math.abs(x-target)<.02&&Math.abs(velocity)<.2){x=target;if(rotation)v.setRotation((float)x);else v.setTranslationY((float)x);cancel();return;}
      if(rotation)v.setRotation((float)x);else v.setTranslationY((float)x);
      Choreographer.getInstance().postFrameCallback(this);
    }
  }
}
