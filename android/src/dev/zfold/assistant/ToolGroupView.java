package dev.zfold.assistant;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.*;

/** One disclosure for adjacent calls; records keep their position as results arrive. */
final class ToolGroupView extends LinearLayout {
  final String key;
  final LinearLayout header, steps;
  final TextView title, summary;
  final Ui.Action toggle;
  final ArrayList<ToolCallView> calls=new ArrayList<>();
  private final Ui.Theme theme;
  private final Runnable beforeDisclosure;
  boolean expanded;

  ToolGroupView(Context context,String key,Runnable beforeDisclosure){
    super(context);this.key=key;this.beforeDisclosure=beforeDisclosure;theme=new Ui.Theme(context);
    setOrientation(VERTICAL);setBackground(Ui.shape(context,theme.field,14,theme.line));
    header=new LinearLayout(context);header.setGravity(Gravity.CENTER_VERTICAL);header.setMinimumHeight(Ui.dp(context,64));header.setPadding(Ui.dp(context,12),Ui.dp(context,6),0,Ui.dp(context,6));
    LinearLayout copy=new LinearLayout(context);copy.setOrientation(VERTICAL);
    title=Ui.text(context,"",13,theme.ink);copy.addView(title);
    summary=Ui.text(context,"",12,theme.muted);summary.setPadding(0,Ui.dp(context,3),0,0);copy.addView(summary);
    header.addView(copy,new LayoutParams(0,-2,1));
    toggle=new Ui.Action(context,R.drawable.ic_caret_down,"","展开工具调用",theme.muted,Color.TRANSPARENT,this::toggle);
    // The whole header is one accessible 48dp+ disclosure target.
    toggle.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);toggle.setFocusable(false);
    header.addView(toggle,new LayoutParams(Ui.dp(context,48),Ui.dp(context,48)));
    header.setFocusable(true);header.setOnClickListener(v->toggle());addView(header,new LayoutParams(-1,-2));
    steps=new LinearLayout(context);steps.setOrientation(VERTICAL);steps.setPadding(Ui.dp(context,8),0,Ui.dp(context,8),Ui.dp(context,8));steps.setVisibility(GONE);addView(steps,new LayoutParams(-1,-2));
  }
  void add(ToolCallView call){
    calls.add(call);call.beforeDisclosure=beforeDisclosure;
    LayoutParams params=new LayoutParams(-1,-2);params.topMargin=Ui.dp(getContext(),6);steps.addView(call,params);refresh();
  }
  void toggle(){beforeDisclosure.run();setExpanded(!expanded);}
  void setExpanded(boolean value){expanded=value;steps.setVisibility(value?VISIBLE:GONE);toggle.setRotation(value?180:0);refreshAccessibility();}
  void finish(boolean cancelled){for(ToolCallView call:calls)call.finishPending(cancelled);refresh();}
  void refresh(){
    int running=0,failed=0,blocked=0,unverified=0,stopped=0,interrupted=0,skipped=0;String current="";boolean waiting=false;
    for(ToolCallView call:calls){
      String state=call.record.optString("status");
      switch(state){
        case "running":running++;current=ToolCallView.label(call.record.optString("name"));waiting|="ask_user_question".equals(call.record.optString("name"));break;
        case "error":failed++;break;case "blocked":blocked++;break;case "unverified":unverified++;break;
        case "cancelled":stopped++;break;case "interrupted":interrupted++;break;case "not_performed":skipped++;break;
      }
    }
    ArrayList<String> states=new ArrayList<>();
    if(waiting)states.add("等你回答");else if(running>0)states.add(running+" 项执行中");else states.add("已结束");
    if(failed>0)states.add(failed+" 项失败");if(blocked>0)states.add(blocked+" 项需重新判断");if(unverified>0)states.add(unverified+" 项待验证");
    if(stopped>0)states.add(stopped+" 项已停止");if(interrupted>0)states.add(interrupted+" 项中断");if(skipped>0)states.add(skipped+" 项未执行");
    if(current.length()>48)current=current.substring(0,48)+"…";
    title.setText(calls.size()+" 次工具调用");summary.setText(String.join(" · ",states)+(running>0&&!waiting?"\n正在："+current:""));
    summary.setTextColor(failed>0?theme.error:running>0||blocked>0||unverified>0?theme.accent:theme.muted);
    ConversationMinimap.mark(this,failed>0?ConversationMinimap.ERROR:ConversationMinimap.TOOL);refreshAccessibility();
  }
  private void refreshAccessibility(){
    String action=expanded?"收起":"展开";header.setContentDescription(title.getText()+"，"+summary.getText()+"，"+action);
    header.setStateDescription(expanded?"已展开":"已折叠");toggle.setContentDescription(action+title.getText().toString());
  }
}
