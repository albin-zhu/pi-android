package dev.zfold.assistant;
import android.app.Activity;
import android.os.Bundle;
import android.content.pm.PackageManager;
import android.Manifest;
import android.widget.Toast;
public final class MicrophonePermissionActivity extends Activity {
  @Override public void onCreate(Bundle state){super.onCreate(state);requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},1);}
  @Override public void onRequestPermissionsResult(int request,String[] names,int[] results){super.onRequestPermissionsResult(request,names,results);Toast.makeText(this,results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED?"麦克风已就绪，点“说话”开始":"暂未允许麦克风，可继续输入文字",Toast.LENGTH_SHORT).show();finish();}
}
