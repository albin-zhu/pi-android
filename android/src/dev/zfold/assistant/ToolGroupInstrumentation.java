package dev.zfold.assistant;

import android.app.*;
import android.os.*;
import android.view.*;
import org.json.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Native rendering only: synthetic records cannot invoke tools, models or accounts. */
public final class ToolGroupInstrumentation extends Instrumentation {
  private MainActivity chat;private String freeform;private boolean saved;
  @Override public void onCreate(Bundle args){if(!Build.HARDWARE.contains("ranchu")&&!Build.HARDWARE.contains("goldfish")){Bundle r=new Bundle();r.putString("error","Emulator only");finish(0,r);return;}PiRuntime.testMode=true;start();}
  @Override public void onStart(){Bundle result=new Bundle();int code=Activity.RESULT_OK;
    try{
      freeform=EmulatorChecks.shell(this,"settings get global enable_freeform_support").trim();saved=true;
      EmulatorChecks.shell(this,"am start --windowingMode 1 -n dev.zfold.assistant/.MainActivity");await(()->MainActivity.current()!=null&&MainActivity.current().runtime.ready&&MainActivity.current().root.isLaidOut(),"Chat not ready");chat=MainActivity.current();main(()->chat.runtime.unlisten(chat));
      main(()->{
        history("fixture",new JSONArray().put(message("user","帮我检查这个项目")),true);
        chat.event(PiRuntime.json("type","assistant_start"));chat.event(PiRuntime.json("type","delta","text","先看看文件结构。"));
        event(tool("a","read","running",""));event(tool("b","ls","running",""));
        chat.event(PiRuntime.json("type","assistant_start"));chat.event(PiRuntime.json("type","delta","text","\n"));event(tool("c","bash","running",""));
        ToolGroupView group=chat.toolGroups.get("a");check(group==chat.toolGroups.get("c")&&group.calls.size()==3,"Empty assistant start split adjacent calls");
        check(!group.expanded&&group.steps.getVisibility()==View.GONE,"Tool group not collapsed initially");check(chat.toolViews.get("a").details.getText().length()==0,"Hidden details formatted eagerly");
        group.header.performClick();chat.toolViews.get("a").toggle();event(tool("a","read","success","读取完成：src/main.java"));
        check(group.expanded&&chat.toolViews.get("a").expanded&&chat.toolViews.get("a").details.getText().toString().contains("src/main.java"),"Result lost open details");
        chat.event(PiRuntime.json("type","assistant_start"));chat.event(PiRuntime.json("type","delta","text","现在检查构建配置。"));check(chat.response.toString().equals("现在检查构建配置。"),"Earlier assistant prose repeated");
        Ui.RichBlock activeAnswer=chat.answer;event(tool("b","ls","success","目录已列出"));chat.event(PiRuntime.json("type","delta","text","确认后继续。"));check(chat.answer==activeAnswer,"Late tool update split the current answer");
        event(tool("d","read","running",""));event(tool("c","bash","error","构建失败：缺少依赖"));
        check(chat.toolGroups.get("d")!=group&&group.calls.size()==3&&chat.toolViews.size()==4,"Tool completion moved or duplicated a group");
        event(tool("c","bash","running",""));check(chat.toolViews.get("c").record.optString("status").equals("error"),"Replay resurrected failed tool");
        check(group.summary.getText().toString().contains("失败")&&group.getTag(R.id.conversation_minimap_kind).equals(ConversationMinimap.ERROR),"Failure hidden in group/minimap");
      });result.putBoolean("adjacentGroupingAndOrderedUpdates",true);result.putBoolean("lazyDetailsAndMonotonicReplay",true);result.putBoolean("separateAssistantTextDoesNotRepeat",true);
      main(()->{
        JSONArray items=new JSONArray().put(message("user","帮我检查这个项目")).put(message("assistant","先看看文件结构。"));
        for(String id:new String[]{"a","b","c"}){items.put(chat.toolViews.get(id).record);if(id.equals("a"))items.put(message("assistant","\n"));}
        items.put(message("assistant","现在检查构建配置。")).put(chat.toolViews.get("d").record);history("fixture",items,true);
        check(chat.toolGroups.get("a").expanded&&chat.toolViews.get("a").expanded,"History reset disclosures");check(chat.toolGroups.get("a")==chat.toolGroups.get("c"),"Whitespace history split consecutive tools");check(!chat.toolGroups.get("d").expanded,"History expanded an untouched group");
        event(tool("q","ask_user_question","running",""));check(chat.toolGroups.get("q").summary.getText().toString().contains("等你回答"),"Pending question hidden");
        chat.event(PiRuntime.json("type","turn_end","aborted",true));check(chat.toolGroups.get("q").summary.getText().toString().contains("已停止"),"Stopped turn still running");
        history("another-session",items,false);check(!chat.toolGroups.get("a").expanded&&!chat.toolViews.get("a").expanded,"Disclosure leaked across sessions");check(chat.toolGroups.get("d").summary.getText().toString().contains("中断"),"Non-live history reported running");
      });result.putBoolean("historyRetainsExpansionWithinSession",true);result.putBoolean("questionStopAndInterruptedHistoryVisible",true);
      main(()->{
        JSONArray rows=new JSONArray().put(message("user","整理一下项目，并检查运行状态。"));
        rows.put(message("assistant","我会检查代码和配置，下面是执行记录。"));
        for(int i=0;i<12;i++)rows.put(tool("compact-"+i,i%2==0?"read":"bash",i==8?"error":"success",i==8?"缺少配置文件，等待补充。":"检查完成。"));
        rows.put(message("assistant","已完成检查。有一项配置缺失，需要补充后再继续。"));history("compact",rows,false);
      });Thread.sleep(400);main(()->chat.scroll.scrollTo(0,0));EmulatorChecks.screenshot(this,"tool-groups-collapsed.png");
      ToolGroupView compact=chat.toolGroups.get("compact-0");check(compact.getHeight()<chat.dp(110),"Collapsed group grew with call count");
      main(()->{compact.header.performClick();chat.toolViews.get("compact-8").toggle();});Thread.sleep(200);main(()->chat.scroll.scrollTo(0,chat.toolViews.get("compact-8").getTop()+compact.getTop()));EmulatorChecks.screenshot(this,"tool-groups-expanded.png");result.putBoolean("failureDetailsRemainAccessible",true);
      main(()->{
        JSONArray rows=new JSONArray().put(message("assistant","历史内容，需要保持当前位置。\n\n".repeat(45))).put(tool("reader","read","running",""));history("reader",rows,true);
      });Thread.sleep(250);main(()->chat.scroll.scrollTo(0,chat.dp(240)));int position=chat.scroll.getScrollY();
      main(()->event(tool("reader","read","success","完成")));Thread.sleep(200);check(Math.abs(chat.scroll.getScrollY()-position)<chat.dp(2),"Tool update pulled reader to bottom");result.putBoolean("updatesPreserveReadingPosition",true);
      EmulatorChecks.shell(this,"settings put global enable_freeform_support 1");EmulatorChecks.shell(this,"am start --windowingMode 5 -n dev.zfold.assistant/.MainActivity");int w=chat.dp(239),h=chat.dp(282);EmulatorChecks.shell(this,"am task resize "+chat.getTaskId()+" 100 350 "+(100+w)+" "+(350+h));await(()->chat.isInMultiWindowMode()&&chat.hasWindowFocus()&&chat.root.getWidth()==w&&chat.getWindowManager().getCurrentWindowMetrics().getBounds().height()==h,"Small window resize not applied");Thread.sleep(800);
      main(()->{JSONArray rows=new JSONArray();for(int i=0;i<9;i++)rows.put(tool("small-"+i,"read",i==8?"blocked":"success","文件检查结果"));history("small",rows,false);});Thread.sleep(300);main(()->chat.scroll.scrollTo(0,0));
      ToolGroupView small=chat.toolGroups.get("small-0");await(()->small.isShown()&&small.getHeight()>0&&small.getBottom()<=chat.scroll.getHeight(),"Collapsed group not visible in small window");check(small.toggle.getWidth()>=chat.dp(48)&&small.header.getWidth()<=chat.scroll.getWidth(),"Small window disclosure clipped");check(small.summary.getText().toString().contains("需重新判断"),"Blocked action hidden");EmulatorChecks.screenshot(this,"tool-groups-small.png");
      main(small.header::performClick);Thread.sleep(200);check(small.expanded&&small.calls.get(0).isShown(),"Small window cannot expand");EmulatorChecks.screenshot(this,"tool-groups-small-expanded.png");result.putBoolean("239x282Disclosure",true);
      main(()->{chat.event(PiRuntime.json("type","session_changed","sessionId","empty"));check(chat.toolViews.isEmpty()&&chat.toolGroups.isEmpty(),"Session switch retained tools");});result.putBoolean("sessionSwitchClearsGroups",true);
    }catch(Throwable failure){result.putString("failure",failure.toString());code=Activity.RESULT_CANCELED;try{EmulatorChecks.screenshot(this,"tool-groups-failure.png");}catch(Exception ignored){}}
    finally{if(chat!=null)main(chat::finish);if(saved)try{EmulatorChecks.shell(this,freeform.equals("null")?"settings delete global enable_freeform_support":"settings put global enable_freeform_support "+freeform);}catch(Exception error){result.putString("restoreFailure",error.toString());code=Activity.RESULT_CANCELED;}}
    finish(code,result);
  }
  private void history(String session,JSONArray rows,boolean busy){chat.showHistory(PiRuntime.json("sessionId",session,"items",rows,"busy",busy));}
  private static JSONObject message(String role,String text){return PiRuntime.json("role",role,"text",text);}
  private static JSONObject tool(String id,String name,String state,String output){return PiRuntime.json("role","tool","id",id,"name",name,"status",state,"args","{\"path\":\"src/main.java\"}","output",output);}
  private void event(JSONObject record){try{record.put("type","tool_execution");}catch(Exception ignored){}chat.event(record);}
  private void main(Runnable action){Throwable[] error={null};runOnMainSync(()->{try{action.run();}catch(Throwable failure){error[0]=failure;}});if(error[0]!=null)throw new IllegalStateException(error[0]);}
  private void await(BooleanSupplier ready,String error)throws Exception{for(int i=0;i<200;i++){boolean[] yes={false};main(()->yes[0]=ready.getAsBoolean());if(yes[0])return;Thread.sleep(50);}throw new IllegalStateException(error);}
  private static void check(boolean value,String error){if(!value)throw new IllegalStateException(error);}
}
