package dev.zfold.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.TextView;

/** Android-owned per-request consent; not exported and never auto-approved. */
public final class PhoneToolApprovalActivity extends Activity {
  private PhoneToolApproval.Request request;
  private AlertDialog dialog;
  @Override public void onCreate(Bundle state){
    super.onCreate(state);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    request=PhoneToolApproval.find(getIntent().getStringExtra("request"));
    if(request==null){finish();return;}
    request.activity=this;
    TextView description=new TextView(this);int padding=Math.round(20*getResources().getDisplayMetrics().density);
    description.setPadding(padding,padding,padding,padding);description.setTextIsSelectable(true);
    boolean x11=request.name.equals("x11_server");
    String operation=x11?(request.readOnly?"读取共享 X11 :9 服务状态":"启动共享 X11 :9 服务"):(request.readOnly?"读取敏感手机数据":"执行手机操作");
    description.setText("独立本机客户端请求"+operation+"。同一应用身份不代表获得了你的授权。\n\n只允许下列这一次请求，不授权后续请求。拒绝或断开连接后不会执行。"+
      (x11?"\n不会打开窗口管理器或手机控制浮层；取消等待不会停止共享服务。":"")+"\n\n工具："+request.name+"\n参数：\n"+request.arguments);
    ScrollView scroll=new ScrollView(this);scroll.addView(description);
    dialog=new AlertDialog.Builder(this).setTitle(x11?(request.readOnly?"允许查询 X11 服务？":"允许启动 X11 服务？"):(request.readOnly?"允许读取手机数据？":"允许这一次手机操作？")).setView(scroll)
      .setNegativeButton("拒绝",(d,w)->request.finish("用户拒绝了本次手机操作授权"))
      .setPositiveButton("仅允许这一次",(d,w)->request.finish(null))
      .setOnCancelListener(d->request.finish("用户取消了本次手机操作授权")).create();
    dialog.setCanceledOnTouchOutside(false);dialog.show();
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setFilterTouchesWhenObscured(true);
    dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
  }
  @Override protected void onDestroy(){
    if(dialog!=null)dialog.dismiss();
    if(request!=null){
      if(request.activity==this)request.activity=null;
      if(isFinishing())request.finish("手机授权界面已关闭，本次未执行");
    }
    super.onDestroy();
  }
}
