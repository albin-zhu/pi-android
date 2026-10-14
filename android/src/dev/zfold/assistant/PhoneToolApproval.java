package dev.zfold.assistant;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Consent is for exactly one immutable request, never for a socket or a UID. */
final class PhoneToolApproval {
  private static final Handler main=new Handler(Looper.getMainLooper());
  private static final Map<String,Request> pending=new HashMap<>();
  static void request(Context context,String id,String name,JSONObject args,boolean readOnly,Consumer<String> result){
    Request request=new Request(id,name,args,readOnly,result);pending.put(id,request);
    request.unavailable=()->{if(pending.get(id)==request&&request.activity==null)request.finish("Android 无法显示授权界面，请先在手机上打开助手后重试");};
    request.expired=()->request.finish("手机操作授权已超时，本次未执行");
    main.postDelayed(request.unavailable,3000);main.postDelayed(request.expired,60000);
    try{
      // A temporary task returns focus to the caller's previous app. Reusing
      // our main task would leave the assistant in front of the screen target.
      context.startActivity(new Intent(context,PhoneToolApprovalActivity.class).putExtra("request",id)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
    }catch(RuntimeException unavailable){request.finish("Android 无法显示授权界面，请先在手机上打开助手后重试");}
  }
  static Request find(String id){return pending.get(id);}
  static void cancel(String id){Request request=pending.remove(id);if(request!=null)request.dispose();}
  static final class Request {
    final String id,name,arguments;final boolean readOnly;final Consumer<String> result;Activity activity;
    Runnable unavailable,expired;
    Request(String id,String name,JSONObject args,boolean readOnly,Consumer<String> result){this.id=id;this.name=name;this.arguments=args.toString();this.readOnly=readOnly;this.result=result;}
    void finish(String error){
      if(!pending.remove(id,this))return;
      dispose();
      result.accept(error);
    }
    void dispose(){
      main.removeCallbacks(unavailable);main.removeCallbacks(expired);
      if(activity!=null&&!activity.isFinishing())activity.finishAndRemoveTask();
    }
  }
}
