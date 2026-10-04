package dev.zfold.assistant;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;

/** Shared, expandable tool record for the main chat and both assistant panels. */
final class ToolCallView extends LinearLayout {
  final Ui.Theme theme;
  final TextView title, status, details;
  final Ui.Action toggle;
  JSONObject record;
  boolean expanded;

  ToolCallView(Context context, JSONObject data) {
    super(context);theme=new Ui.Theme(context);setOrientation(VERTICAL);
    setBackground(Ui.shape(context,theme.field,12,theme.line));
    LinearLayout header=new LinearLayout(context);header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(Ui.dp(context,12),0,0,0);header.setMinimumHeight(Ui.dp(context,48));
    title=Ui.text(context,"",13,theme.ink);header.addView(title,new LayoutParams(0,-2,1));
    status=Ui.text(context,"",11,theme.muted);header.addView(status);
    toggle=new Ui.Action(context,R.drawable.ic_caret_down,"","展开工具详情",theme.muted,Color.TRANSPARENT,this::toggle);
    header.addView(toggle,new LayoutParams(Ui.dp(context,48),Ui.dp(context,48)));
    header.setOnClickListener(v->toggle());addView(header);
    details=Ui.text(context,"",12,theme.muted);details.setTextIsSelectable(true);
    details.setPadding(Ui.dp(context,12),0,Ui.dp(context,12),Ui.dp(context,12));
    details.setVisibility(GONE);addView(details,new LayoutParams(-1,-2));update(data);
  }
  void toggle(){expanded=!expanded;details.setVisibility(expanded?VISIBLE:GONE);toggle.setRotation(expanded?180:0);toggle.setContentDescription((expanded?"收起":"展开")+title.getText()+"详情");}
  static String label(String name){
    switch(name){
      case "read":return "读取文件";case "write":return "写入文件";case "edit":return "编辑文件";
      case "ls":return "列出文件";case "bash":return "执行 Shell";case "reload_skills":return "重载 Skills";case "phone_task":return "Jev 操作手机";case "device_info":return "读取手机状态";
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
    record=data;String state=data.optString("status"),name=data.optString("name");
    String stateLabel=state.equals("running")?"执行中":state.equals("error")?"失败":state.equals("blocked")?"需重新判断":state.equals("unverified")?"待验证":state.equals("cancelled")?"已停止":state.equals("interrupted")?"已中断":state.equals("not_performed")?"未执行":"已返回";
    title.setText(label(name));status.setText(stateLabel);status.setTextColor(state.equals("error")?theme.error:state.equals("running")?theme.accent:theme.muted);
    String output=data.optString("output");
    details.setText(name+"\n\n参数\n"+pretty(data.optString("args","{}"))+"\n\n结果\n"+(output.isEmpty()?(state.equals("running")?"等待工具返回…":"没有返回内容"):pretty(output)));
    setContentDescription(label(name)+" · "+stateLabel);
  }
}
