package dev.zfold.assistant;

import android.app.Activity;
import android.os.Bundle;
import android.content.pm.PackageManager;

/** Permission continuation for one live native tool request. */
public final class PhonePermissionActivity extends Activity {
  private PhoneActions.Request request;private boolean handled;
  @Override public void onCreate(Bundle state){super.onCreate(state);request=PhoneActions.find(getIntent().getStringExtra("request"));if(request==null||request.cancelled()){finish();return;}request.activity=this;requestPermissions(new String[]{request.permission},1);}
  @Override public void onRequestPermissionsResult(int code,String[] names,int[] results){super.onRequestPermissionsResult(code,names,results);handled=true;
    if(request!=null&&!request.cancelled()){if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)request.permissionGranted();else request.finish(PiRuntime.json("performed",false,"status","permission_denied","permission",request.permission));}finish();}
  @Override protected void onDestroy(){if(isFinishing()&&!handled&&request!=null)request.finish(PiRuntime.json("performed",false,"status","cancelled"));super.onDestroy();}
}
