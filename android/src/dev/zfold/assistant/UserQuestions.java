package dev.zfold.assistant;

import android.app.Activity;
import android.content.*;
import android.os.*;
import java.util.*;
import org.json.*;

/** One pending interaction bound to the native call, model turn and conversation. */
final class UserQuestions {
  static final class Draft {final Set<Integer> selected=new LinkedHashSet<>();String text="";}
  static final class Request {
    final String id,turnId,sessionId,toolCallId;final JSONArray questions;final ArrayList<Draft> drafts=new ArrayList<>();final AndroidTools.Result callback;int focus=-1;
    Request(JSONObject request,JSONObject args,AndroidTools.Result callback)throws Exception{id=request.getString("id");turnId=request.getString("turnId");sessionId=args.getString("sessionId");toolCallId=args.getString("toolCallId");questions=new JSONArray(args.getJSONArray("questions").toString());this.callback=callback;validate(questions);for(int i=0;i<questions.length();i++)drafts.add(new Draft());}
  }
  private static final Handler main=new Handler(Looper.getMainLooper());private static final Set<Runnable> listeners=new HashSet<>();private static Request pending;
  static Request current(){return pending;}
  static boolean matchesTool(String id){return pending!=null&&pending.toolCallId.equals(id);}
  static void listen(Runnable listener){listeners.add(listener);listener.run();}
  static void unlisten(Runnable listener){listeners.remove(listener);}
  private static void changed(){for(Runnable listener:new ArrayList<>(listeners))listener.run();}
  static void ask(Context context,JSONObject request,JSONObject args,AndroidTools.Result result){main.post(()->{
    try{
      Request next=new Request(request,args,result);PiRuntime runtime=PiRuntime.get(context);
      if(NativeToolExecution.isCancelled(next.id))throw new IllegalStateException("提问已取消");
      if(!runtime.busy||!next.sessionId.equals(runtime.sessionId)||!next.turnId.equals(runtime.activeTurnId()))throw new IllegalStateException("提问所属任务已经改变");
      if(pending!=null)throw new IllegalStateException("已有待回答的问题，请等待用户回答");
      pending=next;QuestionSessionService.begin(context,next.turnId);changed();poll(context,next);
      Activity visible=VoiceCallActivity.foregroundActivity();if(visible!=null)show(visible);
    }catch(Exception error){result.done(null,error.getMessage());}
  });}
  private static void poll(Context context,Request request){main.postDelayed(()->{if(pending!=request)return;PiRuntime runtime=PiRuntime.get(context);if(NativeToolExecution.isCancelled(request.id)||!runtime.busy||!request.turnId.equals(runtime.activeTurnId())||!request.sessionId.equals(runtime.sessionId)){finish(context,request,null,"提问已随任务停止或切换而取消");return;}poll(context,request);},250);}
  static void show(Context context){if(pending!=null)UserQuestionActivity.show(context,pending.id);}
  static void submit(Context context,String id,boolean declined)throws Exception{
    Request request=pending;PiRuntime runtime=PiRuntime.get(context);
    if(request==null||!request.id.equals(id)||NativeToolExecution.isCancelled(id)||!runtime.busy||!request.turnId.equals(runtime.activeTurnId())||!request.sessionId.equals(runtime.sessionId))throw new IllegalStateException("这个问题已失效，请查看当前任务");
    JSONArray answers=new JSONArray();if(!declined)for(int i=0;i<request.questions.length();i++){
      JSONObject question=request.questions.getJSONObject(i);Draft draft=request.drafts.get(i);String text=draft.text.trim();
      if(text.isEmpty()&&draft.selected.isEmpty())throw new IllegalStateException("请回答第 "+(i+1)+" 个问题");
      if(text.codePointCount(0,text.length())>4000||(!question.optBoolean("multiSelect")&&draft.selected.size()>1))throw new IllegalStateException("第 "+(i+1)+" 个回答无效");
      JSONArray selected=new JSONArray(),options=question.optJSONArray("options");for(int index:draft.selected){if(options==null||index<0||index>=options.length())throw new IllegalStateException("选项已失效");selected.put(options.getJSONObject(index).getString("label"));}
      answers.put(PiRuntime.json("id",question.getString("id"),"selected",selected,"text",text));
    }
    finish(context,request,PiRuntime.json("status",declined?"declined":"answered","answers",answers),null);
  }
  private static void finish(Context context,Request request,JSONObject value,String error){if(pending!=request)return;pending=null;changed();QuestionSessionService.refresh(context);request.callback.done(value,error);}
  static void endTurn(Context context,String turnId){Request request=pending;if(request!=null&&(turnId==null||turnId.isEmpty()||turnId.equals(request.turnId)))finish(context,request,null,"任务已结束，问题已取消");QuestionSessionService.end(context,turnId);}
  static boolean offerVoice(Context context,String text){
    Request request=pending;if(request==null)return false;int index=request.questions.length()==1?0:request.focus;
    if(index<0){android.widget.Toast.makeText(context,"有多个问题，请先点选要填写的输入框",android.widget.Toast.LENGTH_LONG).show();show(context);return true;}
    Draft draft=request.drafts.get(index);String combined=draft.text.isEmpty()?text:draft.text+"\n"+text;if(combined.codePointCount(0,combined.length())<=4000){draft.text=combined;changed();}
    VoiceCallActivity.onControllerStatus("回答已填入问题卡片，确认后点提交");show(context);return true;
  }
  private static void validate(JSONArray questions)throws Exception{
    if(questions.length()<1||questions.length()>4)throw new IllegalArgumentException("问题数量需要 1–4 项");Set<String> ids=new HashSet<>();
    for(int i=0;i<questions.length();i++){JSONObject q=questions.getJSONObject(i);if(q.getString("id").isEmpty()||!ids.add(q.getString("id"))||q.getString("question").trim().isEmpty()||q.getString("question").codePointCount(0,q.getString("question").length())>800)throw new IllegalArgumentException("问题格式无效");JSONArray choices=q.optJSONArray("options");if(choices!=null&&(choices.length()==1||choices.length()>6))throw new IllegalArgumentException("选项格式无效");if(choices!=null)for(int j=0;j<choices.length();j++)if(choices.getJSONObject(j).optString("label").trim().isEmpty())throw new IllegalArgumentException("选项不能为空");}
  }
}
