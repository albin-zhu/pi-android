package dev.zfold.assistant;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;

/** One searchable catalog for skills, agent tools, MCP connections, and local commands. */
final class ToolCenter implements PiRuntime.Listener {
  interface Host {
    void openModelSettings();void openAgentSettings();void openCapabilities();void openSpeech();
    void openPackages();void newSession();void stopTask();void toggleVoice();void toggleScreenShare();
    void prompt(String text);
    void openSkills();void openProviderAuth();void openRuntimePacks();
    void openTerminal();
  }

  private final View anchor;private final PiRuntime runtime;private final Host host;private final Ui.Theme theme;
  private EditText search;private LinearLayout chips,list;private TextView footer;private PopupWindow popup;
  private JSONArray tools=new JSONArray(),skills=new JSONArray();private JSONObject mcp=new JSONObject();
  private int section=0;private boolean ready;

  interface Opener {void open();}

  private ToolCenter(View anchor,PiRuntime runtime,Host host){
    this.anchor=anchor;this.runtime=runtime;this.host=host;theme=new Ui.Theme(anchor.getContext());
  }

  static void show(View anchor,PiRuntime runtime,Host host){new ToolCenter(anchor,runtime,host).open(0);}
  static void show(View anchor,PiRuntime runtime,Host host,int initial){new ToolCenter(anchor,runtime,host).open(initial);}

  private void open(int initial){
    Context context=anchor.getContext();section=initial;
    FrameLayout frame=new FrameLayout(context);frame.setFocusableInTouchMode(true);
    frame.setOnKeyListener((v,key,event)->event.getAction()==KeyEvent.ACTION_UP&&key==KeyEvent.KEYCODE_BACK);
    View scrim=new View(context);scrim.setBackgroundColor(0x74000000);scrim.setContentDescription("关闭工具中心");scrim.setClickable(true);scrim.setOnClickListener(v->close());
    frame.addView(scrim,new FrameLayout.LayoutParams(-1,-1));
    LinearLayout drawer=new LinearLayout(context);drawer.setOrientation(LinearLayout.VERTICAL);drawer.setBackgroundColor(theme.surface);drawer.setElevation(Ui.dp(context,18));drawer.setClickable(true);
    int width=Math.min(Ui.dp(context,480),context.getResources().getDisplayMetrics().widthPixels-Ui.dp(context,32));
    frame.addView(drawer,new FrameLayout.LayoutParams(width,-1,Gravity.END));
    int statusId=context.getResources().getIdentifier("status_bar_height","dimen","android");
    int top=statusId>0?context.getResources().getDimensionPixelSize(statusId):Ui.dp(context,24);
    frame.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars());drawer.setPadding(Ui.dp(context,16)+bars.left,bars.top+Ui.dp(context,16),Ui.dp(context,16)+bars.right,bars.bottom+Ui.dp(context,16));return insets;});
    LinearLayout brand=new LinearLayout(context);brand.setGravity(Gravity.CENTER_VERTICAL);
    TextView title=Ui.text(context,"功能中心",19,theme.ink);title.setTypeface(Typeface.create("sans-serif-medium",0));brand.addView(title,new LinearLayout.LayoutParams(0,-2,1));
    brand.addView(new Ui.Action(context,R.drawable.ic_arrow_clockwise,"","刷新工具目录",theme.muted,Color.TRANSPARENT,this::refresh),new LinearLayout.LayoutParams(Ui.dp(context,44),Ui.dp(context,44)));
    brand.addView(new Ui.Action(context,R.drawable.ic_x,"","关闭工具中心",theme.muted,Color.TRANSPARENT,this::close),new LinearLayout.LayoutParams(Ui.dp(context,44),Ui.dp(context,44)));
    drawer.addView(brand);
    TextView help=Ui.text(context,"会话、Agent、供应商、工具和运行时的统一入口。列表只展示能力；执行仍受权限和确认控制。",12,theme.muted);
    help.setLineSpacing(Ui.dp(context,3),1f);LinearLayout.LayoutParams helpParams=new LinearLayout.LayoutParams(-1,-2);helpParams.setMargins(0,Ui.dp(context,9),0,0);drawer.addView(help,helpParams);
    search=field(context,"搜索名称、用途或命令");drawer.addView(search,new LinearLayout.LayoutParams(-1,-2));
    search.addTextChangedListener(new android.text.TextWatcher(){
      public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void onTextChanged(CharSequence s,int a,int b,int c){}
      public void afterTextChanged(android.text.Editable value){render();}
    });
    chips=new LinearLayout(context);chips.setHorizontalScrollBarEnabled(false);LinearLayout.LayoutParams chipParams=new LinearLayout.LayoutParams(-1,-2);chipParams.setMargins(0,Ui.dp(context,13),0,Ui.dp(context,7));drawer.addView(chips,chipParams);
    ScrollView scroll=new ScrollView(context);scroll.setVerticalScrollBarEnabled(false);list=new LinearLayout(context);list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);drawer.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    footer=Ui.text(context,"正在读取工具目录…",11,theme.muted);footer.setPadding(0,Ui.dp(context,13),0,0);drawer.addView(footer);
    popup=new PopupWindow(frame,-1,-1,true);popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));popup.setOutsideTouchable(true);popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);popup.setOnDismissListener(()->runtime.unlisten(this));
    try{
      ((InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(anchor.getWindowToken(),0);
      popup.showAtLocation(anchor,Gravity.TOP|Gravity.END,0,0);frame.requestFocus();runtime.listen(this);refresh();renderChips();
    }catch(RuntimeException error){runtime.unlisten(this);Toast.makeText(context,"工具中心无法打开",Toast.LENGTH_SHORT).show();}
  }

  private EditText field(Context context,String hint){
    EditText value=new EditText(context);value.setSingleLine(true);value.setTextSize(14);value.setTextColor(theme.ink);value.setHintTextColor(theme.muted);value.setHint(hint);
    value.setBackground(Ui.shape(context,theme.field,14,theme.line));value.setPadding(Ui.dp(context,13),Ui.dp(context,13),Ui.dp(context,13),Ui.dp(context,13));
    LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.setMargins(0,Ui.dp(context,14),0,0);value.setLayoutParams(params);return value;
  }

  private void refresh(){runtime.send(PiRuntime.json("type","tools_catalog"));}
  private void close(){if(popup!=null)popup.dismiss();}

  @Override public void event(JSONObject event){
    if(popup==null||!popup.isShowing())return;
    String type=event.optString("type");
    if(type.equals("tools_catalog")){
      tools=event.optJSONArray("tools")==null?new JSONArray():event.optJSONArray("tools");
      skills=event.optJSONArray("skills")==null?new JSONArray():event.optJSONArray("skills");
      mcp=event.optJSONObject("mcp")==null?new JSONObject():event.optJSONObject("mcp");
      ready=true;footer.setText("Agent 工具 "+tools.length()+" · Skills "+skills.length()+" · MCP "+(mcp.optBoolean("connected")?"已连接":"未连接"));
      render();
    }else if(type.equals("error")){
      ready=true;footer.setText("读取失败："+Ui.errorMessage(event.optString("message")));render();
    }
  }

  private void renderChips(){
    Context context=anchor.getContext();chips.removeAllViews();
    addChip(context,0,"全部");addChip(context,1,"Skills");addChip(context,2,"Agent 工具");addChip(context,3,"命令");addChip(context,4,"MCP");
  }

  private void addChip(Context context,int value,String label){
    TextView chip=Ui.text(context,label,12,value==section?theme.onAccent:theme.ink);chip.setTypeface(Typeface.create("sans-serif-medium",0));
    chip.setPadding(Ui.dp(context,13),Ui.dp(context,8),Ui.dp(context,13),Ui.dp(context,8));
    chip.setBackground(Ui.ripple(context,value==section?theme.accent:theme.field,value==section?18:14,theme.ink));
    chip.setOnClickListener(v->{section=value;renderChips();render();});
    LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-2,-2);params.rightMargin=Ui.dp(context,7);chip.setLayoutParams(params);
    chips.addView(chip);
  }

  private void render(){
    Context context=anchor.getContext();list.removeAllViews();String query=search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
    if(!ready){empty("正在读取工具目录…");return;}
    if(section==0)renderQuickActions(context,query);
    if(section==0||section==1)renderSkills(query);
    if(section==0||section==2)renderTools(query);
    if(section==0||section==3)renderCommands(query);
    if(section==0||section==4)renderMcp(query);
    if(list.getChildCount()==0)empty("没有匹配的能力");
    if(section==0)heading(context,"快捷命令");
    if(section==0)commandRows(query);
  }

  private void renderSkills(String query){
    heading(anchor.getContext(),"Skills · 决定 Agent 什么时候读说明");
    int added=0;
    for(int i=0;i<skills.length();i++){
      JSONObject item=skills.optJSONObject(i);if(item==null)continue;
      String name=item.optString("name"),description=item.optString("description","没有描述"),scope=item.optString("scope","project");
      if(!matches(name+" "+description+" "+scope,query))continue;
      String badge=scope.equals("builtin")?"内置":scope.equals("global")?"全局":"工作区";
      ArrayList<Opener> ignored=new ArrayList<>();
      card(name,description,badge,"Skill",R.drawable.ic_file,ignored.isEmpty()?null:null,()->host.prompt(SlashCommands.skillPrompt(name)));
      added++;
    }
    if(added==0)minor("没有匹配 Skill");
  }

  private void renderQuickActions(Context context,String query){
    heading(context,"功能入口");
    if(matches("模型 provider model api key 供应商 接口",query))actionCard(context,"模型与供应商","选择模型、配置 endpoint / API Key / 消息格式",R.drawable.ic_chat_circle_text,host::openModelSettings);
    if(matches("oauth xai openai anthropic openrouter 登录 provider auth 供应商",query))actionCard(context,"供应商登录","OAuth、设备码、浏览器授权和 provider-owned key",R.drawable.ic_arrow_square_out,host::openProviderAuth);
    if(matches("skills skill 技能 本地技能 管理创建",query))actionCard(context,"Skills 管理","创建、编辑和删除工作区 / 全局 Skill",R.drawable.ic_file,host::openSkills);
    if(matches("手机 无障碍 悬浮窗 语音 jev 权限 storage",query))actionCard(context,"手机与语音","无障碍、悬浮窗、存储、语音和 Jev",R.drawable.ic_microphone,host::openCapabilities);
    if(matches("runtime pack 运行时 包管理 package software 安装",query)){
      actionCard(context,"Runtime Packs","安装受确认的 Android/bionic 工具包",R.drawable.ic_arrows_out_simple,host::openRuntimePacks);
      actionCard(context,"软件包管理","搜索、安装和删除 Termux 官方仓库 CLI",R.drawable.ic_file,host::openPackages);
    }
    if(matches("terminal pty 终端 shell bash 命令行",query))actionCard(context,"终端","直接输入、全屏编辑与可保留的会话",R.drawable.ic_gear_six,host::openTerminal);
    if(matches("agent 身份 名字 头像 system prompt",query))actionCard(context,"Agent 身份","名字、头像和系统提示词",R.drawable.ic_chat_circle_text,host::openAgentSettings);
  }

  private void actionCard(Context context,String title,String detail,int icon,Runnable action){
    LinearLayout card=new LinearLayout(context);card.setGravity(Gravity.CENTER_VERTICAL);card.setPadding(Ui.dp(context,13),Ui.dp(context,11),Ui.dp(context,9),Ui.dp(context,11));
    card.setBackground(Ui.card(context,theme.surface,16,theme.line));card.setClickable(true);card.setFocusable(true);
    ImageView mark=new ImageView(context);mark.setImageDrawable(Ui.icon(context,icon,theme.accent));card.addView(mark,new LinearLayout.LayoutParams(Ui.dp(context,22),Ui.dp(context,22)));
    LinearLayout labels=new LinearLayout(context);labels.setOrientation(LinearLayout.VERTICAL);LinearLayout.LayoutParams labelParams=new LinearLayout.LayoutParams(0,-2,1);labelParams.leftMargin=Ui.dp(context,10);card.addView(labels,labelParams);
    TextView titleView=Ui.text(context,title,14,theme.ink);titleView.setTypeface(Typeface.create("sans-serif-medium",0));labels.addView(titleView);
    TextView detailView=Ui.text(context,detail,11,theme.muted);detailView.setSingleLine(true);detailView.setEllipsize(TextUtils.TruncateAt.END);LinearLayout.LayoutParams detailParams=new LinearLayout.LayoutParams(-1,-2);detailParams.topMargin=Ui.dp(context,3);labels.addView(detailView,detailParams);
    card.addView(new Ui.Action(context,R.drawable.ic_arrow_up,"","打开 "+title,theme.accent,Color.TRANSPARENT,action),new LinearLayout.LayoutParams(Ui.dp(context,44),Ui.dp(context,44)));
    card.setOnClickListener(v->{Ui.click(card);action.run();});
    card.setContentDescription(title+"，"+detail);
    list.addView(card,new LinearLayout.LayoutParams(-1,-2));LinearLayout.LayoutParams params=(LinearLayout.LayoutParams)card.getLayoutParams();params.topMargin=list.getChildCount()==1?0:Ui.dp(context,8);card.setLayoutParams(params);
  }

  private void renderTools(String query){
    heading(anchor.getContext(),"Agent 工具 · 模型可调用的动作");
    int added=0;
    for(int i=0;i<tools.length();i++){
      JSONObject item=tools.optJSONObject(i);if(item==null)continue;
      String name=item.optString("name"),label=item.optString("label",name),description=item.optString("description","没有描述");
      if(!matches(name+" "+label+" "+description,query))continue;
      boolean enabled=item.optBoolean("enabled",true);String reason=item.optString("reason");
      String badge=enabled?"已注册":item.optString("availability").equals("configuration_required")?"待配置":"不支持";
      card(label,description+(reason.isEmpty()?"":" · "+reason),badge,name,R.drawable.ic_gear_six,null,
        enabled?()->host.prompt("请使用 "+name+" 工具完成一个最小验证步骤；执行前说明参数。"):null);
      added++;
    }
    if(added==0)minor("没有匹配工具");
  }

  private void renderCommands(String query){
    heading(anchor.getContext(),"Slash Commands · 本地直达入口");
    commandRows(query);
  }

  private void renderMcp(String query){
    heading(anchor.getContext(),"MCP · 外部工具连接");
    boolean connected=mcp.optBoolean("connected");String message=mcp.optString("message","MCP server 尚未接入。");
    if(matches("mcp model context protocol "+message,query)){
      card(connected?"MCP 已连接":"MCP 未接入",message,connected?"已连接":"未连接","MCP",R.drawable.ic_arrows_out_simple,null,null);
    }
    if(mcp.optJSONArray("servers")!=null)for(int i=0;i<mcp.optJSONArray("servers").length();i++){
      JSONObject server=mcp.optJSONArray("servers").optJSONObject(i);if(server==null)continue;
      String name=server.optString("name"),description=server.optString("description");
      if(matches(name+" "+description,query))card(name,description,server.optBoolean("connected")?"已连接":"未连接","MCP",R.drawable.ic_arrows_out_simple,null,null);
    }
  }

  private void commandRows(String query){
    for(SlashCommands.Command command:SlashCommands.catalog()){
      if(!matches(command.name+" "+command.title+" "+command.detail+" "+command.category,query))continue;
      card("/"+command.name,command.title+" · "+command.detail,command.category,"命令",R.drawable.ic_arrow_square_out,null,()->{
        String[] parsed=new String[2];
        if(!SlashCommands.parse(command.invocation(),parsed))return;
        if(command.wantsArgument){Toast.makeText(anchor.getContext(),"请补全参数，例如 "+command.invocation(),Toast.LENGTH_SHORT).show();return;}
        runCommand(parsed[0],parsed[1]);
      });
    }
  }

  private void runCommand(String name,String argument){
    switch(name){
      case "tools":close();break;
      case "skills":section=1;renderChips();render();break;
      case "mcp":section=4;renderChips();render();break;
      case "packages":close();host.openPackages();break;
      case "model":close();host.openModelSettings();break;
      case "agent":close();host.openAgentSettings();break;
      case "phone":close();host.openCapabilities();break;
      case "voice":close();host.toggleVoice();break;
      case "share":close();host.toggleScreenShare();break;
      case "new":close();host.newSession();break;
      case "stop":close();host.stopTask();break;
      case "skill":close();host.prompt(SlashCommands.skillPrompt(argument));break;
      case "pkg":close();host.prompt(SlashCommands.packagePrompt(argument));break;
      case "runtime":close();host.prompt(SlashCommands.runtimePrompt());break;
    }
  }

  private void heading(Context context,String value){
    TextView view=Ui.text(context,value,12,theme.muted);view.setLetterSpacing(.04f);
    LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.setMargins(Ui.dp(context,2),Ui.dp(context,18),0,Ui.dp(context,7));view.setLayoutParams(params);list.addView(view);
  }

  private void card(String title,String detail,String badge,String category,int icon,Runnable secondary,Runnable primaryAction){
    Context context=anchor.getContext();LinearLayout card=new LinearLayout(context);card.setOrientation(LinearLayout.VERTICAL);
    card.setPadding(Ui.dp(context,14),Ui.dp(context,12),Ui.dp(context,10),Ui.dp(context,10));card.setBackground(Ui.card(context,theme.surface,16,theme.line));
    LinearLayout head=new LinearLayout(context);head.setGravity(Gravity.CENTER_VERTICAL);
    ImageView mark=new ImageView(context);mark.setImageDrawable(Ui.icon(context,icon,theme.accent));head.addView(mark,new LinearLayout.LayoutParams(Ui.dp(context,20),Ui.dp(context,20)));
    TextView titleView=Ui.text(context,title,14,theme.ink);titleView.setTypeface(Typeface.create("sans-serif-medium",0));titleView.setSingleLine(true);titleView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
    LinearLayout.LayoutParams titleParams=new LinearLayout.LayoutParams(0,-2,1);titleParams.leftMargin=Ui.dp(context,10);head.addView(titleView,titleParams);
    TextView badgeView=Ui.text(context,badge,10,category.equals("MCP")&&badge.equals("未连接")?theme.muted:theme.accent);head.addView(badgeView);
    card.addView(head);
    TextView detailView=Ui.text(context,detail,12,theme.muted);detailView.setMaxLines(3);detailView.setEllipsize(TextUtils.TruncateAt.END);detailView.setLineSpacing(Ui.dp(context,3),1f);
    LinearLayout.LayoutParams detailParams=new LinearLayout.LayoutParams(-1,-2);detailParams.setMargins(0,Ui.dp(context,6),0,0);card.addView(detailView,detailParams);
    if(secondary!=null||primaryAction!=null){
      LinearLayout actions=new LinearLayout(context);actions.setGravity(Gravity.END);
      if(secondary!=null)actions.addView(new Ui.Action(context,R.drawable.ic_gear_six,"设置","打开设置",theme.muted,Color.TRANSPARENT,secondary),new LinearLayout.LayoutParams(Ui.dp(context,44),Ui.dp(context,44)));
      if(primaryAction!=null)actions.addView(new Ui.Action(context,R.drawable.ic_arrow_up,"运行","运行 "+title,theme.accent,theme.accentSoft,primaryAction),new LinearLayout.LayoutParams(-2,-2));
      card.addView(actions,new LinearLayout.LayoutParams(-1,-2));
    }
    if(card.getParent()!=null)return;
    list.addView(card,new LinearLayout.LayoutParams(-1,-2));LinearLayout.LayoutParams params=(LinearLayout.LayoutParams)card.getLayoutParams();params.topMargin=list.getChildCount()==1?0:Ui.dp(context,8);card.setLayoutParams(params);
  }

  private void empty(String message){TextView view=Ui.text(anchor.getContext(),message,13,theme.muted);view.setPadding(Ui.dp(anchor.getContext(),2),Ui.dp(anchor.getContext(),18),0,0);list.addView(view);}
  private void minor(String message){TextView view=Ui.text(anchor.getContext(),message,12,theme.muted);view.setPadding(Ui.dp(anchor.getContext(),2),Ui.dp(anchor.getContext(),5),0,0);list.addView(view);}
  private boolean matches(String value,String query){return query.isEmpty()||value.toLowerCase(java.util.Locale.ROOT).contains(query);}
}
