package dev.zfold.assistant;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

/** Compact, interruptible status for phone actions; the target app remains visible. */
final class PhoneHud {
  private static boolean takeover;
  static synchronized void show(Context context,String action){show(context,action,false);}
  static synchronized void takeover(Context context,String action){show(context,action,true);}
  static synchronized void forceHide(){takeover=false;hideLocked();}
  private static synchronized void show(Context context,String action,boolean takeoverMode){
    takeover=takeoverMode;
    if(takeoverMode)UnifiedOverlay.takeover(context,action);
    else UnifiedOverlay.task(context,action);
  }
  static synchronized void update(Context context,String action){show(context,action,false);}
  static synchronized void hide(){if(takeover)return;hideLocked();}
  private static void hideLocked(){UnifiedOverlay.hide(UnifiedOverlay.Mode.TASK);}
  static synchronized boolean showing(){return UnifiedOverlay.showing(UnifiedOverlay.Mode.TASK);}
  static synchronized boolean hasAction(String description){return UnifiedOverlay.hasAction(description);}
  static String action(JSONObject event){
    String name=event.optString("name");JSONObject args=event.optJSONObject("args");
    if("phone_task".equals(name)){
      JSONObject output=event.optJSONObject("output");JSONArray steps=output==null?null:output.optJSONArray("steps");
      JSONObject step=steps==null||steps.length()==0?null:steps.optJSONObject(steps.length()-1);
      String outcome=outcome(output);
      if(step!=null)return outcome+" · 第 "+steps.length()+" 步 · "+describe(step.optString("kind"),step.optString("label"),null);
      if(!outcome.isBlank())return outcome;
      return "Jev 正在操作手机";
    }
    if("error".equals(event.optString("status")))return ToolCallView.label(name)+" · 失败";
    return describe(name,args==null?"":args.optString("text",args.optString("packageName","")),args);
  }
  private static String outcome(JSONObject output){
    if(output==null)return "";
    String status=output.optString("status"),reason=output.optString("reason");
    switch(status){
      case "verified":return "手机任务已验证";
      case "unverified":return "手机任务未验证";
      case "blocked":return "手机任务被阻止"+(reason.isBlank()?"":" · "+reason(reason));
      case "cancelled":return "手机任务已取消";
      case "running":return "正在执行";
      default:return status.isBlank()?"":"手机任务"+status;
    }
  }
  private static String reason(String value){
    switch(value){
      case "low_confidence":return "目标置信度低";
      case "empty_accessibility_tree":return "界面没有可读节点";
      case "repeated_action":return "重复动作";
      case "stale_snapshot":return "页面已变化";
      case "unstable_screen":return "页面持续变化";
      case "step_limit":return "已达步数上限";
      case "action_rejected":return "系统拒绝动作";
      case "input_not_verified":return "输入未回读确认";
      case "model_blocked":return "选择器无法安全继续";
      default:return value;
    }
  }
  private static String describe(String kind,String target,JSONObject args){
    String value=target==null||target.isBlank()?"":" · "+target;
    switch(kind){
      case "open":case "app_open":return "打开应用"+value;
      case "tap":return "点击 "+target;
      case "input":return "输入 "+target;
      case "scroll_forward":return "向下滚动";
      case "scroll_backward":return "向上滚动";
      case "back":case "ui_back":return "返回上一页";
      case "split_screen":return args!=null&&"launch".equals(args.optString("action"))?"启动分屏应用":args!=null&&"state".equals(args.optString("action"))?"读取分屏状态":"切换分屏";
      case "screen_read":return "读取当前界面";
      case "screen_capture":return "查看屏幕";
      case "phone_task":return "Jev 操作手机"+value;
      case "read":return "读取文件";case "write":return "写入文件";case "edit":return "编辑文件";
      case "ls":return "列出目录";case "bash":return "执行命令";case "pty":return "打开终端";
      case "runtime_exec":return "执行工具";case "pkg":return "管理软件包";case "sandbox_status":return "检查运行时";
      default:return ToolCallView.label(kind)+(value.isBlank()?"":" · "+target);
    }
  }
}
