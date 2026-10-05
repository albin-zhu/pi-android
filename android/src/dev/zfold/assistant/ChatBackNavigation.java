package dev.zfold.assistant;

import android.os.Build;

/** Register the platform Back contract while preserving the legacy path on Android 11/12. */
final class ChatBackNavigation {
  static Object register(MainActivity activity){return Build.VERSION.SDK_INT>=33?Api33.register(activity):null;}
  static void unregister(MainActivity activity,Object callback){if(Build.VERSION.SDK_INT>=33&&callback!=null)Api33.unregister(activity,callback);}
  private static class Api33 {
    static Object register(MainActivity activity){
      android.window.OnBackInvokedCallback callback=Build.VERSION.SDK_INT>=34?Api34.callback(activity):activity::handleBack;
      activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,callback);return callback;
    }
    static void unregister(MainActivity activity,Object callback){activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback)callback);}
  }
  private static class Api34 {
    static android.window.OnBackInvokedCallback callback(MainActivity activity){return new android.window.OnBackAnimationCallback(){
      private boolean drawer,opening;
      @Override public void onBackStarted(android.window.BackEvent event){
        boolean allowed=activity.canNavigateDrawerBack();
        boolean showing=activity.swipeNavigation.hasDrawer();
        opening=allowed&&event.getSwipeEdge()==android.window.BackEvent.EDGE_LEFT&&(!showing||activity.swipeNavigation.isTouchOpening());
        drawer=allowed&&showing&&!opening;
        if(opening)activity.openSidebar();if(drawer||opening)activity.swipeNavigation.beginBack();
      }
      @Override public void onBackProgressed(android.window.BackEvent event){if(opening)activity.swipeNavigation.backOpenProgress(event.getProgress());else if(drawer)activity.swipeNavigation.backProgress(event.getProgress());}
      @Override public void onBackCancelled(){if(opening)activity.swipeNavigation.close();else if(drawer)activity.swipeNavigation.cancelBack();drawer=opening=false;}
      @Override public void onBackInvoked(){if(opening)activity.swipeNavigation.open();else if(drawer)activity.swipeNavigation.close();else activity.handleBack();drawer=opening=false;}
    };}
  }
}
