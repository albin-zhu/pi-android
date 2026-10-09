package dev.zfold.assistant;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;

/** Expandable record; details are formatted only when the user opens them. */
final class ToolCallView extends LinearLayout {
  final Ui.Theme theme;
  final TextView title, status, details;
  final Ui.Action toggle;
  final LinearLayout preview;
  final TextView rawToggle;
  boolean rawExpanded;
  JSONObject record;
  boolean expanded;
  Runnable beforeDisclosure=()->{};

  ToolCallView(Context context, JSONObject data) {
    super(context);theme=new Ui.Theme(context);setOrientation(VERTICAL);
    setBackground(Ui.shape(context,theme.surface,12,theme.line));
    LinearLayout header=new LinearLayout(context);header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(Ui.dp(context,12),0,0,0);header.setMinimumHeight(Ui.dp(context,48));
    title=Ui.text(context,"",13,theme.ink);title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);header.addView(title,new LayoutParams(0,-2,1));
    status=Ui.text(context,"",11,theme.muted);header.addView(status);
    toggle=new Ui.Action(context,R.drawable.ic_caret_down,"","展开工具详情",theme.muted,Color.TRANSPARENT,this::toggle);
    header.addView(toggle,new LayoutParams(Ui.dp(context,48),Ui.dp(context,48)));
    toggle.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);toggle.setFocusable(false);
    header.setFocusable(true);header.setContentDescription("展开工具详情");header.setOnClickListener(v->toggle());addView(header);
    preview=new LinearLayout(context);preview.setOrientation(VERTICAL);preview.setPadding(Ui.dp(context,12),0,Ui.dp(context,12),Ui.dp(context,8));preview.setVisibility(GONE);addView(preview,new LayoutParams(-1,-2));
    rawToggle=Ui.text(context,"原始参数 / 结果 ▾",12,theme.accent);rawToggle.setMinHeight(Ui.dp(context,48));rawToggle.setGravity(Gravity.CENTER_VERTICAL);rawToggle.setPadding(Ui.dp(context,12),0,0,0);rawToggle.setVisibility(GONE);
    rawToggle.setOnClickListener(v->toggleRaw());
    addView(rawToggle,new LayoutParams(-1,-2));
    details=Ui.text(context,"",12,theme.muted);details.setTextIsSelectable(true);
    details.setPadding(Ui.dp(context,12),0,Ui.dp(context,12),Ui.dp(context,12));
    details.setVisibility(GONE);addView(details,new LayoutParams(-1,-2));update(data);
  }
  void toggle(){if(record!=null&&UserQuestions.matchesTool(record.optString("id"))){UserQuestions.show(getContext());return;}beforeDisclosure.run();setExpanded(!expanded);}
  void toggleRaw(){beforeDisclosure.run();rawExpanded=!rawExpanded;details.setVisibility(rawExpanded?VISIBLE:GONE);rawToggle.setText(rawExpanded?"收起原始日志 ▴":"原始参数 / 结果 ▾");}
  void setExpanded(boolean value){expanded=value;if(value)renderDetails();preview.setVisibility(value?VISIBLE:GONE);rawToggle.setVisibility(value?VISIBLE:GONE);details.setVisibility(value&&rawExpanded?VISIBLE:GONE);MicroMotion.rotate(toggle,value?180:0);if(value)MicroMotion.reveal(preview);toggle.setContentDescription((expanded?"收起":"展开")+title.getText()+"详情");}
  void finishPending(boolean cancelled){if("running".equals(record.optString("status")))update(PiRuntime.json("status",cancelled?"cancelled":"interrupted"));}
  static String label(String name){
    switch(name){
      case "ask_user_question":return "向用户提问";
      case "phone_capabilities":return "电话与相机能力";case "phone_call":return "拨打电话";case "phone_sms":return "短信与回执";case "contact_search":return "查找联系人";case "camera_capture":return "拍照并获取图片";
      case "read":return "读取文件";case "write":return "写入文件";case "edit":return "编辑文件";
      case "subagent":return "子Agent · 独立上下文";case "ls":return "列出文件";case "bash":return "执行 Shell";case "reload_skills":return "重载 Skills";case "phone_task":return "Jev 操作手机";case "device_info":return "读取手机状态";
      case "screen_read":return "读取当前界面";case "screen_capture":return "截取屏幕";
      case "ui_select":return "选择界面目标";case "ui_tap":return "点击界面";
      case "ui_input":return "输入文字";case "ui_swipe":return "滑动界面";
      case "ui_back":return "返回上一页";case "app_open":return "打开应用";
      case "open":return "打开 URL/文件";case "tool_install":return "安装脚本工具";case "pty":return "连接 Runtime PTY";
      case "app_list":return "列出应用";case "split_screen":return "折叠屏分屏";default:return name;
    }
  }
  static String pretty(String value){try{return new JSONObject(value).toString(2);}catch(Exception ignored){return value;}}
  void update(JSONObject data){
    if(record!=null&&!"running".equals(record.optString("status"))&&"running".equals(data.optString("status")))return;
    JSONObject merged=new JSONObject();try{if(record!=null)for(java.util.Iterator<String> keys=record.keys();keys.hasNext();){String key=keys.next();merged.put(key,record.get(key));}for(java.util.Iterator<String> keys=data.keys();keys.hasNext();){String key=keys.next();merged.put(key,data.get(key));}}catch(Exception ignored){}
    String previousState=record==null?"":record.optString("status");
    record=merged;String state=record.optString("status"),name=record.optString("name");
    String stateLabel=state.equals("running")?"执行中":state.equals("error")?"失败":state.equals("blocked")?"需重新判断":state.equals("unverified")?"待验证":state.equals("cancelled")?"已停止":state.equals("interrupted")?"已中断":state.equals("not_performed")?"未执行":"已返回";
    if(name.equals("ask_user_question")&&state.equals("running"))stateLabel="等你回答";
    JSONObject args=arguments();String path=args.optString("path");
    title.setText(label(name)+(path.isEmpty()?"":" · "+new java.io.File(path).getName()));
    if("running".equals(previousState)&&!"running".equals(state))MicroMotion.reveal(status);
    status.setText(stateLabel);status.setTextColor(state.equals("error")?theme.error:state.equals("running")||state.equals("blocked")||state.equals("unverified")?theme.accent:theme.muted);
    if(expanded)renderDetails();
    setContentDescription(label(name)+" · "+stateLabel);
  }
  private JSONObject arguments(){try{return new JSONObject(record.optString("args","{}"));}catch(Exception ignored){return new JSONObject();}}
  private void block(String heading,String content,int color){
    TextView label=Ui.text(getContext(),heading,11,theme.muted);label.setPadding(0,Ui.dp(getContext(),8),0,Ui.dp(getContext(),4));preview.addView(label);
    android.widget.HorizontalScrollView scroll=new android.widget.HorizontalScrollView(getContext());
    TextView code=Ui.text(getContext(),content.length()>6000?content.substring(0,6000)+"\n…预览截断，完整内容见原始日志":content,12,color);code.setTypeface(android.graphics.Typeface.MONOSPACE);code.setTextIsSelectable(true);code.setMaxLines(12);code.setEllipsize(android.text.TextUtils.TruncateAt.END);code.setPadding(Ui.dp(getContext(),8),Ui.dp(getContext(),8),Ui.dp(getContext(),8),Ui.dp(getContext(),8));
    scroll.setBackground(Ui.shape(getContext(),theme.field,8,0));scroll.addView(code);preview.addView(scroll,new LayoutParams(-1,-2));
  }
  private String numbered(String text,String prefix){String[] lines=text.split("\n",-1);StringBuilder out=new StringBuilder();for(int i=0;i<Math.min(lines.length,60);i++)out.append(prefix).append(i+1).append("  ").append(lines[i]).append('\n');if(lines.length>60)out.append("…更多内容见原始日志");return out.toString();}
  private void renderDetails(){
    String name=record.optString("name"),output=record.optString("output"),state=record.optString("status");JSONObject args=arguments();preview.removeAllViews();
    String path=args.optString("path");
    if(!path.isEmpty()){
      TextView file=Ui.text(getContext(),path+"  ↗",12,theme.accent);file.setMinHeight(Ui.dp(getContext(),48));file.setGravity(Gravity.CENTER_VERTICAL);preview.addView(file);
      file.setOnClickListener(v->{try{
        java.io.File target=new java.io.File(path);if(!target.isAbsolute())target=new java.io.File(RuntimeEnvironment.workingDirectory(getContext(),null),path);
        ArtifactProvider.open(getContext(),android.net.Uri.fromFile(target).toString());
      }catch(Exception e){android.widget.Toast.makeText(getContext(),"无法打开："+e.getMessage(),android.widget.Toast.LENGTH_LONG).show();}});
    }
    if(name.equals("subagent")){
      org.json.JSONArray chain=args.optJSONArray("chain"),tasks=args.optJSONArray("tasks");
      String mode=chain!=null?"链式委托 · "+chain.length()+" 步":tasks!=null?"并行委托 · "+tasks.length()+" 项":args.optString("agent")+" · 单个委托";
      block(mode,"只读文件 · 沿用当前模型\n主聊天不传给子Agent · 停止按钮联动全部子任务",theme.muted);
      if(chain==null&&tasks==null)block("分配任务",args.optString("task"),theme.ink);
    }else if(name.equals("edit")){
      org.json.JSONArray edits=args.optJSONArray("edits");
      if(edits==null){edits=new org.json.JSONArray();edits.put(args);}
      block("修改预览 · "+edits.length()+" 处 · 以下为片段行号，不代表已应用","状态："+status.getText(),theme.muted);
      for(int i=0;i<Math.min(edits.length(),8);i++){
        JSONObject edit=edits.optJSONObject(i);if(edit==null)continue;
        block("− 原文 · 第 "+(i+1)+" 处",numbered(edit.optString("oldText"),"− "),theme.error);
        block("+ 替换为",numbered(edit.optString("newText"),"+ "),theme.success);
      }
    }else if(name.equals("bash"))block("$ 命令",args.optString("command"),theme.ink);
    else if(name.equals("write"))block("写入内容预览 · 不代表已写入",args.optString("content"),theme.ink);
    else if(name.equals("read"))block("读取范围","起始行 "+args.optInt("offset",1)+(args.has("limit")?" · 最多 "+args.optInt("limit")+" 行":""),theme.muted);
    if(!record.optString("imagePath").isEmpty())preview.addView(ChatImages.preview(getContext(),record.optString("imagePath")),new LayoutParams(-1,-2));
    block(state.equals("error")?"失败原因 / 返回结果":"返回结果",output.isEmpty()?(state.equals("running")?"等待工具返回…":"没有返回内容"):output,state.equals("error")?theme.error:theme.ink);
    details.setText(name+"\n\n参数\n"+pretty(record.optString("args","{}"))+"\n\n结果\n"+pretty(output));
  }
}
