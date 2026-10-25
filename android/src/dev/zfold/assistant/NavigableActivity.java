package dev.zfold.assistant;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.ActionMode;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

/** System Back leaves one UI level; explicit close actions still own their own lifetime. */
public abstract class NavigableActivity extends Activity {
  private Object backCallback;
  private ActionMode selection;
  private boolean returningToMenu;

  @Override protected void onCreate(Bundle state){
    super.onCreate(state);
    if(Build.VERSION.SDK_INT>=33)backCallback=Api33.register(this);
  }

  @Override protected void onDestroy(){
    if(Build.VERSION.SDK_INT>=33&&backCallback!=null)Api33.unregister(this,backCallback);
    super.onDestroy();
  }

  @Override public void onActionModeStarted(ActionMode mode){super.onActionModeStarted(mode);selection=mode;}
  @Override public void onActionModeFinished(ActionMode mode){if(selection==mode)selection=null;super.onActionModeFinished(mode);}

  private void dispatchBack(){
    // Keep IME dismissal ahead of navigation even when an OEM delivers Back to the activity.
    View decor=getWindow().getDecorView();
    WindowInsets insets=decor.getRootWindowInsets();
    if(insets!=null&&insets.isVisible(WindowInsets.Type.ime())){
      WindowInsetsController controller=getWindow().getInsetsController();
      if(controller!=null)controller.hide(WindowInsets.Type.ime());
      else ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(decor.getWindowToken(),0);
      return;
    }
    if(selection!=null){selection.finish();return;}
    onBackPressed();
  }

  @Override public void onBackPressed(){navigateUp();}

  protected final void navigateUp(){
    if(isFinishing()||isDestroyed()||returningToMenu)return;
    if(!isTaskRoot()){finish();return;}
    Intent menu=new Intent(this,TerminalActivity.class)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
    startActivity(menu);
    returningToMenu=true;
    finish();
  }


  private static class Api33 {
    static Object register(NavigableActivity activity){
      android.window.OnBackInvokedCallback callback=activity::dispatchBack;
      activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,callback);
      return callback;
    }
    static void unregister(NavigableActivity activity,Object callback){
      activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback)callback);
    }
  }
}
