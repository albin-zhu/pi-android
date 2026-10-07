package dev.zfold.assistant;

import android.animation.ValueAnimator;
import android.view.View;

/** Short, non-layout motion; never moves the reader's scroll position. */
final class MicroMotion {
  static void reveal(View view){
    view.animate().cancel();view.setAlpha(1f);view.setTranslationY(0);
    if(!ValueAnimator.areAnimatorsEnabled()||!view.isAttachedToWindow())return;
    view.setAlpha(.35f);view.setTranslationY(5*view.getResources().getDisplayMetrics().density);
    view.animate().alpha(1f).translationY(0).setDuration(180).start();
  }
  static void rotate(View view,float target){
    view.animate().cancel();
    if(ValueAnimator.areAnimatorsEnabled())view.animate().rotation(target).setDuration(160).start();
    else view.setRotation(target);
  }
}
