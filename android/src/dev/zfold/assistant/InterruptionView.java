package dev.zfold.assistant;

import android.content.*;
import android.graphics.Typeface;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.text.DateFormat;
import java.util.Date;

/** A session record, separate from assistant prose; raw provider details stay copyable. */
final class InterruptionView extends LinearLayout {
  final JSONObject record;
  final TextView title,details;
  final Ui.Action disclosure,copy;
  boolean expanded;
  InterruptionView(Context context,JSONObject item,Runnable changed){
    super(context);record=item;Ui.Theme theme=new Ui.Theme(context);
    setOrientation(VERTICAL);setPadding(Ui.dp(context,12),Ui.dp(context,8),Ui.dp(context,12),Ui.dp(context,8));
    setBackground(Ui.shape(context,theme.field,14,theme.line));setTag(R.id.conversation_minimap_kind,ConversationMinimap.ERROR);
    title=Ui.text(context,label(item.optString("reason")),14,theme.error);title.setTypeface(Typeface.create("sans-serif-medium",0));addView(title);
    long time=item.optLong("timestamp");String model=item.optString("provider");if(!item.optString("model").isEmpty())model+=(model.isEmpty()?"":" / ")+item.optString("model");
    String metadata=(time>0?DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.MEDIUM).format(new Date(time)):"时间未提供")+" · "+(model.isEmpty()?"本地运行时":model);
    TextView meta=Ui.text(context,metadata,11,theme.muted);meta.setPadding(0,Ui.dp(context,5),0,Ui.dp(context,5));addView(meta);
    String raw=item.optString("message");TextView summary=Ui.text(context,raw.isEmpty()?label(item.optString("reason")):Ui.errorMessage(raw),13,theme.ink);summary.setTextIsSelectable(true);summary.setMaxLines(3);addView(summary);
    if(item.optBoolean("runtimeOnly")){TextView boundary=Ui.text(context,"仅本次运行可见：没有对应的助手历史记录，重启后不会保留。",11,theme.muted);boundary.setPadding(0,Ui.dp(context,5),0,0);addView(boundary);}
    LinearLayout actions=new LinearLayout(context);actions.setGravity(Gravity.CENTER_VERTICAL);
    disclosure=new Ui.Action(context,R.drawable.ic_file,"原始详情","展开原始中断详情",theme.accent,android.graphics.Color.TRANSPARENT,()->{setExpanded(!expanded);if(changed!=null)changed.run();});actions.addView(disclosure,new LayoutParams(0,Ui.dp(context,48),1));
    copy=new Ui.Action(context,R.drawable.ic_copy,"复制","复制原始中断详情",theme.muted,android.graphics.Color.TRANSPARENT,this::copyDetails);actions.addView(copy,new LayoutParams(-2,Ui.dp(context,48)));addView(actions);
    details=Ui.text(context,raw.isEmpty()?"没有提供原始错误详情。":raw,12,theme.ink);details.setTextIsSelectable(true);details.setTypeface(Typeface.MONOSPACE);details.setVisibility(GONE);addView(details,new LayoutParams(-1,-2));
  }
  void copyDetails(){((ClipboardManager)getContext().getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Pi 中断详情",record.optString("message")));copy.setContentDescription("已复制原始中断详情");}
  void setExpanded(boolean value){expanded=value;details.setVisibility(value?VISIBLE:GONE);disclosure.setText(value?"收起详情":"原始详情");disclosure.setContentDescription(value?"收起原始中断详情":"展开原始中断详情");}
  static String label(String reason){switch(reason){case "length":return "输出中断 · 达到模型输出长度限制";case "aborted":return "输出中断 · 已停止";default:return "输出中断 · 请求出错";}}
  static String retryText(JSONObject retry){
    if(retry==null)return "";
    boolean compaction="compaction".equals(retry.optString("source"));String action=compaction?"上下文压缩":"自动重试";
    if("waiting".equals(retry.optString("status"))){String attempt=retry.optInt("maxAttempts")>0?" "+retry.optInt("attempt")+" / "+retry.optInt("maxAttempts"):"";String delay=retry.optLong("delayMs")>0?" · 等待 "+((retry.optLong("delayMs")+999)/1000)+" 秒":"";return action+attempt+delay+"\n"+retry.optString("message");}
    return retry.optBoolean("success")?action+"结束 · 继续处理（不代表回答已完成）":action+"结束 · 未恢复\n"+retry.optString("message");
  }
}
