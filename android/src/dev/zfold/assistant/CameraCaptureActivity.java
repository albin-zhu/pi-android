package dev.zfold.assistant;

import android.app.Activity;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

/** Visible one-shot camera tool surface, sharing the video call's camera owner. */
public final class CameraCaptureActivity extends Activity {
  private CameraCoordinator camera;private PhoneActions.Request request;private boolean requested;private String previousLens;
  @Override public void onCreate(Bundle state){super.onCreate(state);request=PhoneActions.find(getIntent().getStringExtra("request"));if(request==null||request.cancelled()){finish();return;}request.activity=this;camera=CameraCoordinator.get(this);
    LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(0xff070b12);root.setPadding(Ui.dp(this,16),Ui.dp(this,24),Ui.dp(this,16),Ui.dp(this,16));
    TextView status=Ui.text(this,"正在准备拍照…",16,0xffeef4ff);root.addView(status,new LinearLayout.LayoutParams(-1,-2));
    TextureView preview=new TextureView(this);root.addView(preview,new LinearLayout.LayoutParams(-1,0,1));Button cancel=new Button(this);cancel.setText("取消拍照");cancel.setOnClickListener(v->request.finish(PiRuntime.json("performed",false,"status","cancelled")));root.addView(cancel,new LinearLayout.LayoutParams(-1,Ui.dp(this,48)));setContentView(root);
    previousLens=camera.facing();camera.choose(request.args.optString("facing","back"));camera.bind(this,preview,(mode,message)->{
      status.setText(message);if(mode.equals("preview")&&!requested&&!request.cancelled()){requested=true;preview.postDelayed(()->{if(!request.cancelled())camera.capture(request.id,request::photoResult);},350);}
      else if(mode.equals("unavailable"))request.fail(message);
    },"",false);
  }
  @Override protected void onStop(){if(camera!=null)camera.pause(this);super.onStop();}
  @Override protected void onDestroy(){if(camera!=null&&camera.owns(this)){camera.detach(this);if(previousLens!=null)camera.choose(previousLens);}if(request!=null&&!request.done)request.finish(PiRuntime.json("performed",false,"status","cancelled"));super.onDestroy();}
}
