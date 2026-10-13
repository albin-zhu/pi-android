package dev.zfold.assistant;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;

/** Emulator-only synthetic events through the production mirror/UI, never provider or tool requests. */
public final class InterruptionInstrumentation extends Instrumentation {
  private MainActivity chat;private AssistantPanel panel;private Method dispatch;
  @Override public void onCreate(Bundle args){
    if(!Build.HARDWARE.contains("ranchu")&&!Build.HARDWARE.contains("goldfish")){Bundle result=new Bundle();result.putString("error","Emulator only");finish(Activity.RESULT_CANCELED,result);return;}
    PiRuntime.testMode=true;start();
  }
  @Override public void onStart(){Bundle result=new Bundle();int code=Activity.RESULT_OK;
    try{
      chat=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      await(()->chat.runtime.ready&&chat.root.isLaidOut(),"Chat not ready");
      check(chat.runtime.isTestRuntime()&&PiRuntime.agentRoot(chat).getName().equals("agent-smoke"),"Runtime is not isolated");
      dispatch=PiRuntime.class.getDeclaredMethod("dispatch",JSONObject.class);dispatch.setAccessible(true);
      JSONObject failure=interruption("error",1,"fetch failed: fixture transport disconnected");
      emit(history("no-body",new JSONArray().put(message("user","没有正文时也要显示原因")).put(failure),false,null));
      main(()->{check(chat.interruptionViews.size()==1&&chat.runtime.lastResponse.isEmpty(),"No-body error disappeared or became an answer");check(chat.interruptionViews.get(failure.optString("id")).isShown(),"No-body error card not visible");});
      JSONArray rows=new JSONArray().put(message("user","检查任务为何中断")).put(message("assistant","已经生成的部分回答。")).put(PiRuntime.json("role","tool","id","fixture-read","name","read","status","success","args","{\"path\":\"fixture.txt\"}","output","已读取 fixture 内容"));
      emit(history("interruptions",rows,true,null));
      emit(live(failure));
      main(()->{
        check(chat.runtime.busy,"Interruption ended a retryable turn");check(chat.interruptionViews.size()==1,"Live error card absent");
        check(textContains(chat.conversation,"已经生成的部分回答。"),"Partial prose was overwritten");
        check(chat.toolViews.size()==1&&chat.toolGroups.get("fixture-read").calls.size()==1,"Interruption changed normal tool grouping");
      });
      JSONObject retry=PiRuntime.json("type","retry_status","source","model","status","waiting","attempt",1,"maxAttempts",3,"delayMs",2000,"message","fetch failed","turnId","fixture-turn");
      emit(retry);main(()->check(textContains(chat.composerSurface,"自动重试 1 / 3"),"Retry is not visible by input"));
      EmulatorChecks.screenshot(this,"interruptions-retry.png");
      rows.put(failure);emit(history("interruptions",rows,true,retry));emit(live(failure));
      main(()->{
        check(chat.interruptionViews.size()==1,"History/live duplicated a card");check(chat.runtime.lastResponse.equals("已经生成的部分回答。"),"Interruption became a response");
        check(chat.toolViews.size()==1&&chat.toolGroups.get("fixture-read").calls.size()==1,"History lost normal tool records");
        InterruptionView card=chat.interruptionViews.get(failure.optString("id"));card.disclosure.performClick();card.copy.performClick();
        check(card.details.isShown()&&card.details.getText().toString().equals(failure.optString("message")),"Raw details unavailable");
        ClipboardManager clipboard=(ClipboardManager)chat.getSystemService(Context.CLIPBOARD_SERVICE);check(clipboard.getPrimaryClip().getItemAt(0).getText().toString().equals(failure.optString("message")),"Raw details copy failed");
      });
      emit(PiRuntime.json("type","retry_status","source","model","status","finished","attempt",1,"maxAttempts",3,"delayMs",0,"message","fetch failed","success",false));
      main(()->check(textContains(chat.composerSurface,"未恢复"),"Retry failure was called success"));
      emit(PiRuntime.json("type","error","message",failure.optString("message"),"interruptionId",failure.optString("id")));
      main(()->{check(chat.interruptionViews.size()==1,"Final error duplicated the card");check(chat.runtime.retryStatus==null&&!chat.runtime.busy,"Final error did not clear retry");});
      EmulatorChecks.screenshot(this,"interruptions-details.png");
      JSONObject length=interruption("length",2,"模型达到输出长度限制，回答可能不完整。"),stopped=interruption("aborted",3,"生成已停止，回答可能不完整。");
      rows.put(length).put(message("assistant","停止前的部分回答。")).put(stopped);
      emit(history("interruptions",rows,false,null));
      main(()->{check(chat.interruptionViews.size()==3,"Empty length/stop records disappeared");check(textContains(chat.conversation,"停止前的部分回答。"),"Stop lost partial prose");check(chat.interruptionViews.get(failure.optString("id")).expanded,"History lost detail disclosure");});
      result.putBoolean("liveHistoryDedupePartialLengthStopAndCopy",true);
      emit(PiRuntime.json("type","error","message","本地运行时 fixture 无对应助手记录"));
      main(()->check(chat.runtime.lastInterruptions.values().stream().anyMatch(item->item.optBoolean("runtimeOnly")),"Runtime-only boundary absent"));
      emit(history("interruptions",rows,false,null));main(()->check(chat.interruptionViews.size()==4,"History lost current-run runtime error"));
      JSONObject compaction=PiRuntime.json("type","retry_status","source","compaction","status","waiting","attempt",0,"maxAttempts",0,"delayMs",0,"message","正在压缩上下文");emit(compaction);
      main(()->{check(textContains(chat.composerSurface,"上下文压缩"),"Compaction progress absent");check(!textContains(chat.composerSurface,"0 / 0"),"Compaction invented a retry attempt");});
      MainActivity old=chat;main(old::recreate);await(()->MainActivity.current()!=old&&MainActivity.current().root.isLaidOut(),"Reentry not ready");chat=MainActivity.current();
      main(()->{check(textContains(chat.composerSurface,"上下文压缩"),"Reentry lost retry snapshot");check(chat.interruptionViews.size()==4,"Reentry lost interruption snapshot");});
      emit(history("interruptions",rows,true,compaction));
      main(()->{
        panel=new AssistantPanel(chat,new AssistantPanel.Host(){public void close(){}public void minimize(){}public void select(){}public void toggleExpansion(){}});
        check(panel.interruptionViews.size()==4,"Assistant panel lost interruption snapshot");check(panel.rawReply.contains("停止前的部分回答。"),"Assistant panel lost prose");check(panel.retryLabel.getText().toString().contains("上下文压缩"),"Assistant panel lost retry snapshot");
      });result.putBoolean("runtimeOnlyBoundaryReentryAndPanelSnapshot",true);
      JSONArray longRows=new JSONArray().put(message("user","阅读历史")).put(message("assistant","历史阅读内容，不应被拉到底部。\n\n".repeat(80))).put(failure);
      emit(history("reader",longRows,true,retry));Thread.sleep(350);main(()->chat.scroll.scrollTo(0,chat.dp(220)));int position=chat.scroll.getScrollY();
      emit(history("reader",longRows,true,retry));Thread.sleep(300);check(Math.abs(chat.scroll.getScrollY()-position)<chat.dp(2),"History changed reading position");
      emit(live(length));Thread.sleep(200);check(Math.abs(chat.scroll.getScrollY()-position)<chat.dp(2),"Live interruption pulled reader down");result.putBoolean("historyAndLivePreserveReadingPosition",true);
      emit(history("visible",rows,false,null));Thread.sleep(300);main(()->chat.scroll.fullScroll(View.FOCUS_DOWN));EmulatorChecks.screenshot(this,"interruptions-length-stop.png");
      emit(PiRuntime.json("type","session_changed","sessionId","empty"));
      main(()->{check(chat.interruptionViews.isEmpty()&&chat.runtime.lastInterruptions.isEmpty()&&panel.interruptionViews.isEmpty(),"Session switch leaked error cards");check(chat.runtime.retryStatus==null,"Session switch leaked retry");});
      emit(history("empty",new JSONArray(),false,null));main(()->check(chat.interruptionViews.isEmpty(),"New session rebuilt old cards"));result.putBoolean("sessionIsolation",true);
      result.putString("screenshots",getTargetContext().getCacheDir()+"/interruptions-{retry,details,length-stop}.png");
    }catch(Throwable failure){result.putString("failure",failure.toString());code=Activity.RESULT_CANCELED;try{EmulatorChecks.screenshot(this,"interruptions-failure.png");}catch(Exception ignored){}}
    finally{main(()->{if(panel!=null)panel.dispose();if(chat!=null)chat.finish();});}finish(code,result);
  }
  private void emit(JSONObject event)throws Exception{dispatch.invoke(chat.runtime,event);waitForIdleSync();main(()->{});}
  private static JSONObject history(String session,JSONArray items,boolean busy,JSONObject retry){return PiRuntime.json("type","history","sessionId",session,"items",items,"busy",busy,"retry",retry==null?JSONObject.NULL:retry);}
  private static JSONObject message(String role,String text){return PiRuntime.json("role",role,"text",text);}
  private static JSONObject interruption(String reason,long sequence,String message){long timestamp=1791792000000L+sequence;String provider="fixture",model="fixture-model";return PiRuntime.json("role","interruption","id","assistant:"+new JSONArray().put(provider).put(model).put(timestamp),"reason",reason,"message",message,"timestamp",timestamp,"provider",provider,"model",model);}
  private static JSONObject live(JSONObject item)throws JSONException{JSONObject event=new JSONObject(item.toString());event.put("type","assistant_interruption");event.put("turnId","fixture-turn");return event;}
  private static boolean textContains(View view,String text){if(view instanceof TextView&&((TextView)view).getText().toString().contains(text)&&view.getVisibility()==View.VISIBLE)return true;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(textContains(((ViewGroup)view).getChildAt(i),text))return true;return false;}
  private void main(Runnable action){Throwable[] error={null};runOnMainSync(()->{try{action.run();}catch(Throwable failure){error[0]=failure;}});if(error[0]!=null)throw new IllegalStateException(error[0]);}
  private void await(BooleanSupplier ready,String message)throws Exception{for(int i=0;i<200;i++){boolean[] yes={false};main(()->yes[0]=ready.getAsBoolean());if(yes[0])return;Thread.sleep(50);}throw new IllegalStateException(message);}
  private static void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
}
