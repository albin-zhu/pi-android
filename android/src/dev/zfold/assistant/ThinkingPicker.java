package dev.zfold.assistant;

import android.app.AlertDialog;
import android.view.View;
import org.json.JSONArray;
import org.json.JSONObject;

/** Composer-adjacent control; displays confirmed SDK state, never an optimistic level. */
final class ThinkingPicker {
  static String label(String level){
    switch(level){case "off":return "关";case "minimal":return "最少";case "low":return "低";case "medium":return "中";case "high":return "高";case "xhigh":return "更高";case "max":return "最大";default:return "—";}
  }
  static void show(View anchor,PiRuntime runtime){
    JSONObject state=runtime.thinkingState;JSONArray levels=state==null?null:state.optJSONArray("available");
    if(state==null||!state.optBoolean("supported")||levels==null||levels.length()==0){
      new AlertDialog.Builder(anchor.getContext()).setTitle("思考强度").setMessage("当前模型未声明支持调整思考强度。自定义模型需先正确配置其能力。 ").setPositiveButton("知道了",null).show();return;
    }
    String sessionId=runtime.sessionId;String[] labels=new String[levels.length()];int checked=-1;
    for(int i=0;i<labels.length;i++){labels[i]=label(levels.optString(i));if(levels.optString(i).equals(state.optString("level")))checked=i;}
    new AlertDialog.Builder(anchor.getContext()).setTitle("思考强度")
      .setSingleChoiceItems(labels,checked,(dialog,index)->{
        runtime.send(PiRuntime.json("type","thinking_level","id",java.util.UUID.randomUUID().toString(),"sessionId",sessionId,"level",levels.optString(index)));dialog.dismiss();
      }).setNegativeButton("取消",null).show();
  }
}
