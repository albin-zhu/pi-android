package dev.zfold.assistant;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.*;
import org.json.*;

/** Native view of session-owned goal records. No autonomous continuation. */
final class GoalPanel {
  static String summary(JSONObject goal){
    if(goal==null)return "＋ 设置目标";
    JSONArray steps=goal.optJSONArray("steps");int done=0,total=steps==null?0:steps.length();
    for(int i=0;i<total;i++)if("done".equals(steps.optJSONObject(i).optString("status")))done++;
    String state=goal.optString("status");
    return (state.equals("accepted")?"已验收":state.equals("cancelled")?"已取消":done==total?"待用户验收":"目标 "+done+"/"+total)+" · "+goal.optString("title");
  }
  static void send(PiRuntime runtime,JSONObject command,String session,int revision){
    try{command.put("type","goal_manage");command.put("sessionId",session);command.put("revision",revision);}catch(JSONException e){throw new IllegalArgumentException(e);}
    runtime.send(command);
  }
  static EditText field(Context c,LinearLayout box,String hint,String value){
    EditText input=new EditText(c);input.setHint(hint);input.setText(value);input.setTextSize(14);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);box.addView(input);return input;
  }
  static void create(Context c,PiRuntime runtime){
    if(!runtime.configured){Toast.makeText(c,"请先连接模型",Toast.LENGTH_SHORT).show();return;}
    if(runtime.busy){Toast.makeText(c,"请先停止当前任务再设置目标",Toast.LENGTH_SHORT).show();return;}
    String session=runtime.sessionId;
    LinearLayout box=new LinearLayout(c);box.setOrientation(1);box.setPadding(Ui.dp(c,16),0,Ui.dp(c,16),0);
    EditText title=field(c,box,"目标名称",""),criteria=field(c,box,"验收条件：怎样才算完成",""),steps=field(c,box,"步骤：每行一项","");
    new AlertDialog.Builder(c).setTitle("设置目标 · 不自动开始执行").setView(box).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{
      JSONArray list=new JSONArray();for(String line:steps.getText().toString().split("\n"))if(!line.trim().isEmpty())list.put(line.trim());
      send(runtime,PiRuntime.json("action","create","title",title.getText().toString(),"criteria",criteria.getText().toString(),"steps",list),session,0);
    }).show();
  }
  static String label(String state){switch(state){case "in_progress":return "执行中";case "blocked":return "阻塞";case "verification":return "待验证";case "done":return "报告完成";default:return "待办";}}
  static void show(Context c,PiRuntime runtime){
    JSONObject goal=runtime.goalState;if(goal==null){create(c,runtime);return;}
    String session=runtime.sessionId;int revision=goal.optInt("revision");boolean active="active".equals(goal.optString("status"));
    LinearLayout box=new LinearLayout(c);box.setOrientation(1);box.setPadding(Ui.dp(c,16),Ui.dp(c,8),Ui.dp(c,16),Ui.dp(c,8));
    Ui.Theme theme=new Ui.Theme(c);TextView intro=Ui.text(c,"验收条件\n"+goal.optString("criteria")+"\n\n步骤完成由模型/用户报告；证据尚需核实。点步骤可修改状态。",14,theme.ink);box.addView(intro);
    JSONArray steps=goal.optJSONArray("steps");if(steps!=null)for(int i=0;i<steps.length();i++){
      JSONObject step=steps.optJSONObject(i);if(step==null)continue;
      TextView row=Ui.text(c,label(step.optString("status"))+" · "+step.optString("text")+"\n"+step.optString("evidence"),14,theme.ink);row.setPadding(0,Ui.dp(c,12),0,Ui.dp(c,12));row.setMinHeight(Ui.dp(c,48));box.addView(row);
      if(active)row.setOnClickListener(v->{
        String[] statuses={"pending","in_progress","blocked","verification","done"};String[] labels={"待办","执行中","阻塞","待验证","报告完成（需证据）"};
        new AlertDialog.Builder(c).setTitle(step.optString("text")).setItems(labels,(d,index)->{
          LinearLayout edit=new LinearLayout(c);edit.setOrientation(1);EditText evidence=field(c,edit,"证据或阻塞原因",step.optString("evidence"));
          new AlertDialog.Builder(c).setTitle(labels[index]).setView(edit).setNegativeButton("取消",null).setPositiveButton("保存",(a,b)->send(runtime,PiRuntime.json("action","update","stepId",step.optString("id"),"status",statuses[index],"evidence",evidence.getText().toString()),session,revision)).show();
        }).show();
      });
    }
    ScrollView scroll=new ScrollView(c);scroll.addView(box);
    AlertDialog.Builder dialog=new AlertDialog.Builder(c).setTitle(summary(goal)).setView(scroll).setNegativeButton("关闭",null);
    if(active){
      dialog.setPositiveButton("验收",(d,w)->new AlertDialog.Builder(c).setTitle("确认验收？").setMessage("请核实验收条件和步骤证据。此操作不会替你运行测试。").setNegativeButton("返回",null).setPositiveButton("确认",(a,b)->send(runtime,PiRuntime.json("action","accept"),session,revision)).show());
      dialog.setNeutralButton("取消目标",(d,w)->new AlertDialog.Builder(c).setTitle("取消目标记录？").setMessage("不删除文件或回滚操作。请先停止正在执行的任务。").setNegativeButton("返回",null).setPositiveButton("确认取消",(a,b)->send(runtime,PiRuntime.json("action","cancel"),session,revision)).show());
    }else dialog.setPositiveButton("新目标",(d,w)->create(c,runtime));
    dialog.show();
  }
}
