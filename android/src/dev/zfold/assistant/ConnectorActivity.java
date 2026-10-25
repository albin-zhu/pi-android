package dev.zfold.assistant;

import android.app.AlertDialog;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.*;

/** Connection management is a direct workflow, independent of model configuration. */
public final class ConnectorActivity extends NavigableActivity implements PiRuntime.Listener {
  private PiRuntime runtime;private Ui.Theme theme;private LinearLayout list;private TextView summary;
  private JSONObject state=new JSONObject();private JSONArray recipes=new JSONArray();
  private final Map<String,String> pending=new HashMap<>();private final Set<String> loginRequests=new HashSet<>();
  private AlertDialog editor;private TextView editorError;private String editorRequest;
  static void open(Context context){SystemWindowNavigation.open(context,ConnectorActivity.class);}
  static void returnFromOAuth(Context context){context.startActivity(new Intent(context,ConnectorActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP));}
  @Override protected void onCreate(Bundle saved){
    super.onCreate(saved);theme=new Ui.Theme(this);runtime=PiRuntime.get(this);build();runtime.listen(this);refresh();
  }
  @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);refresh();}
  @Override protected void onResume(){super.onResume();if(runtime!=null)refresh();}
  @Override protected void onPause(){SystemWindowNavigation.remember(this);super.onPause();}
  @Override protected void onDestroy(){runtime.unlisten(this);super.onDestroy();}
  private void build(){
    getWindow().setDecorFitsSystemWindows(false);getWindow().setStatusBarColor(theme.page);getWindow().setNavigationBarColor(theme.page);
    getWindow().getDecorView().setSystemUiVisibility(theme.dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(theme.page);
    root.setOnApplyWindowInsetsListener((view,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());root.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
    LinearLayout header=row();header.setPadding(dp(12),dp(8),dp(12),dp(6));
    header.addView(new Ui.Action(this,R.drawable.ic_caret_down,"","返回",theme.ink,Color.TRANSPARENT,this::navigateUp),new LinearLayout.LayoutParams(dp(48),dp(48)));
    TextView title=text("连接",22,theme.ink);title.setTypeface(Typeface.create("sans-serif-medium",0));header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
    header.addView(button("添加连接",true,this::chooseAdd));root.addView(header);
    summary=text("正在读取连接…",13,theme.muted);summary.setPadding(dp(22),0,dp(22),dp(14));root.addView(summary);
    ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);list.setPadding(dp(18),0,dp(18),dp(24));scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    setContentView(root);
  }
  private TextView text(String value,int size,int color){TextView view=Ui.text(this,value,size,color);view.setLineSpacing(dp(3),1);return view;}
  private LinearLayout row(){LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);return row;}
  private TextView button(String label,boolean prominent,Runnable action){
    TextView view=text(label,14,prominent?theme.onAccent:theme.accent);view.setGravity(Gravity.CENTER);view.setMinimumHeight(dp(48));view.setPadding(dp(15),dp(9),dp(15),dp(9));
    view.setBackground(Ui.ripple(this,prominent?theme.accent:theme.field,14,prominent?theme.onAccent:theme.accent));view.setContentDescription(label);view.setFocusable(true);view.setOnClickListener(v->{Ui.click(v);action.run();});return view;
  }
  private void refresh(){if(runtime.ready)runtime.send(PiRuntime.json("type","connector_manage","action","list"));else summary.setText("正在启动本机运行时…");}
  private String request(String action,JSONObject args,String purpose,boolean login){
    String id=UUID.randomUUID().toString();JSONObject command=PiRuntime.json("type","connector_manage","action",action,"requestId",id);
    try{for(Iterator<String> keys=args.keys();keys.hasNext();){String key=keys.next();command.put(key,args.get(key));}}catch(Exception ignored){}
    pending.put(id,purpose);if(login)loginRequests.add(id);runtime.send(command);return id;
  }
  @Override public void event(JSONObject event){
    if(isFinishing())return;String type=event.optString("type");
    if(type.equals("ready")){refresh();return;}
    if(type.equals("connectors")){state=event;recipes=event.optJSONArray("recipes")==null?new JSONArray():event.optJSONArray("recipes");render();return;}
    if(!type.equals("connector_result"))return;
    String id=event.optString("requestId"),purpose=pending.remove(id);boolean login=loginRequests.remove(id);
    if(purpose==null)return;
    if(!event.optBoolean("ok")){
      if(purpose.equals("save")&&editor!=null&&id.equals(editorRequest)){editorError.setTextColor(theme.error);editorError.setText(event.optString("error"));editor.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);return;}
      summary.setText(event.optString("error","操作未完成"));new AlertDialog.Builder(this).setTitle("未完成").setMessage(event.optString("error")).setPositiveButton("返回",null).show();return;
    }
    if(purpose.startsWith("tools:")){showTools(purpose.substring(6),event);return;}
    if(purpose.equals("call")){showResult(event);return;}
    if(editor!=null&&purpose.equals("save")&&id.equals(editorRequest)){editor.dismiss();editor=null;}
    JSONObject server=event.optJSONObject("server");if(login&&server!=null&&!server.optString("authUrl").isEmpty())browse(server.optString("authUrl"));
    refresh();
  }
  private void render(){
    list.removeAllViews();JSONArray servers=state.optJSONArray("servers");if(servers==null)servers=new JSONArray();int connected=0;
    for(int i=0;i<servers.length();i++)if(servers.optJSONObject(i).optBoolean("connected"))connected++;
    summary.setText(state.optString("error").isEmpty()?connected+" 个已连接 · "+state.optInt("toolCount")+" 个外部工具":state.optString("error"));
    if(servers.length()==0){
      LinearLayout empty=card();TextView title=text("把常用工具接到 Agent",21,theme.ink);title.setTypeface(Typeface.create("sans-serif-medium",0));empty.addView(title);
      addText(empty,"粘贴 MCP 地址，或在手机启动本机工具。连接后，Agent 可以查询并调用它们。",14,theme.muted);
      addAction(empty,"添加第一个连接",true,this::chooseAdd);addAction(empty,"导入 MCP JSON",false,this::importConfig);
    }
    for(int i=0;i<servers.length();i++)serverCard(servers.optJSONObject(i));
    LinearLayout help=card();addText(help,"从哪里开始",16,theme.ink);
    for(int i=0;i<recipes.length();i++){
      JSONObject recipe=recipes.optJSONObject(i);addAction(help,recipe.optString("name"),false,()->edit(recipe,false));
    }
    addAction(help,"浏览官方 MCP Registry",false,()->browse("https://registry.modelcontextprotocol.io/"));
    addAction(help,"导入已有 JSON 配置",false,this::importConfig);
  }
  private LinearLayout card(){LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(16),dp(15),dp(16),dp(15));card.setBackground(Ui.card(this,theme.surface,20,theme.line));LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.bottomMargin=dp(12);list.addView(card,params);return card;}
  private void addText(LinearLayout parent,String value,int size,int color){TextView view=text(value,size,color);LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(7);parent.addView(view,params);}
  private void addAction(LinearLayout parent,String label,boolean prominent,Runnable action){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(10);parent.addView(button(label,prominent,action),params);}
  private String stateLabel(JSONObject server){switch(server.optString("state")){case "connected":return "已连接";case "connecting":return "正在连接…";case "auth_required":return "需要登录";case "error":return "连接未完成";case "disabled":return "已断开";default:return "未连接";}}
  private void serverCard(JSONObject server){
    LinearLayout card=card(),head=row();TextView name=text(server.optString("name"),18,theme.ink);name.setTypeface(Typeface.create("sans-serif-medium",0));head.addView(name,new LinearLayout.LayoutParams(0,-2,1));
    head.addView(new Ui.Action(this,R.drawable.ic_dots_three,"","管理连接 "+server.optString("name"),theme.muted,Color.TRANSPARENT,()->manage(server)),new LinearLayout.LayoutParams(dp(48),dp(48)));card.addView(head);
    addText(card,server.optString("transport").equals("stdio")?"本机 · "+server.optString("command"):server.optString("address"),12,theme.muted);
    addText(card,stateLabel(server)+(server.optBoolean("connected")?" · "+server.optInt("toolCount")+" 个工具":""),14,server.optBoolean("connected")?theme.success:server.optString("state").equals("error")?theme.error:theme.accent);
    if(!server.optString("error").isEmpty())addText(card,server.optString("error"),12,theme.muted);
    if(server.optBoolean("oauthNeedsClient"))addAction(card,"配置 OAuth 客户端",true,()->edit(server,true));
    String id=server.optString("id");
    if(server.optBoolean("connected"))addAction(card,"查看工具",false,()->request("tools",PiRuntime.json("id",id),"tools:"+id,false));
    else if(server.optString("state").equals("connecting"))addAction(card,"取消连接",false,()->request("disconnect",PiRuntime.json("id",id),"disconnect",false));
    else if(!server.optBoolean("oauthNeedsClient"))addAction(card,server.optString("authUrl").isEmpty()?"连接":"继续登录",true,()->{if(!server.optString("authUrl").isEmpty())browse(server.optString("authUrl"));else request("connect",PiRuntime.json("id",id),"connect",true);});
    if(!server.optString("authUrl").isEmpty()){addAction(card,"手动填写回调地址",false,this::manualCallback);addAction(card,"重新开始登录",false,()->request("connect",PiRuntime.json("id",id),"connect",true));}
  }
  private void manualCallback(){
    EditText input=new EditText(this);input.setSingleLine(true);input.setSaveEnabled(false);input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);input.setHint("粘贴浏览器登录后的完整回调地址");input.setContentDescription("MCP 登录回调地址");
    new AlertDialog.Builder(this).setTitle("完成 MCP 登录").setMessage("仅在浏览器没有自动返回时使用。请粘贴授权后的回调地址，不是最初的授权链接。").setView(input).setNegativeButton("取消",null).setPositiveButton("继续",(d,w)->request("oauth_callback",PiRuntime.json("url",input.getText().toString().trim()),"callback",false)).show();
  }
  private void manage(JSONObject server){
    String id=server.optString("id");String[] items={"编辑连接",server.optBoolean("enabled")?"断开连接":"重新连接","删除连接"};
    new AlertDialog.Builder(this).setTitle(server.optString("name")).setItems(items,(dialog,which)->{
      if(which==0)edit(server,true);
      else if(which==1)request(server.optBoolean("enabled")?"disconnect":"connect",PiRuntime.json("id",id),"connect",!server.optBoolean("enabled"));
      else new AlertDialog.Builder(this).setTitle("删除这个连接？").setMessage("会移除本机保存的连接与认证配置，不会删除远程服务的数据。").setNegativeButton("保留",null).setPositiveButton("删除",(d,w)->request("remove",PiRuntime.json("id",id),"remove",false)).show();
    }).show();
  }
  private void chooseAdd(){new AlertDialog.Builder(this).setTitle("添加连接").setItems(new String[]{"远程 MCP 地址","本机 MCP 命令","导入 JSON"},(dialog,which)->{if(which==2)importConfig();else edit(PiRuntime.json("transport",which==0?"http":"stdio","name","","auth",which==0?"oauth":"none"),false);}).show();}
  private EditText field(LinearLayout form,String label,String value,String hint,boolean secret,boolean multiline){
    addText(form,label,13,theme.muted);EditText input=new EditText(this);input.setTextSize(15);input.setTextColor(theme.ink);input.setHintTextColor(theme.muted);input.setText(value);input.setHint(hint);input.setSingleLine(!multiline);input.setMinLines(multiline?2:1);input.setMaxLines(multiline?5:1);
    input.setInputType(InputType.TYPE_CLASS_TEXT|(multiline?InputType.TYPE_TEXT_FLAG_MULTI_LINE:0)|(secret?InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));input.setSaveEnabled(!secret);if(secret)input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);input.setContentDescription(label);
    input.setPadding(dp(12),dp(10),dp(12),dp(10));input.setBackground(Ui.shape(this,theme.field,12,theme.line));form.addView(input,new LinearLayout.LayoutParams(-1,-2));return input;
  }
  private void edit(JSONObject data,boolean existing){
    boolean local=data.optString("transport").equals("stdio");LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(20),dp(8),dp(20),dp(18));
    if(!data.optString("description").isEmpty())addText(form,data.optString("description"),13,theme.muted);
    if(local)addAction(form,"安装 Node.js / npm",false,()->startActivity(new Intent(this,RuntimePackageManagerActivity.class).putExtra("search","nodejs")));
    EditText name=field(form,"连接名称",data.optString("name"),"例如：项目文件、GitHub",false,false);
    EditText address=field(form,local?"程序名称或路径":"MCP 地址",local?data.optString("command"):existing?"":data.optString("url"),local?"例如 npx、python 或程序路径":existing?"已保存 "+data.optString("address")+"；留空保留":"https://example.com/mcp",false,false);
    EditText arguments=local?field(form,"参数 · 每行一项",join(data.optJSONArray("args")),existing?"已有 "+data.optInt("argsCount")+" 项；留空保留，[] 清空":"例如 -y 和包名分别占一行",false,true):null;
    Spinner authentication=new Spinner(this);String[] authLabels={"浏览器登录（OAuth）","Bearer Token","无需认证","自定义 Headers"};String[] modes={"oauth","bearer","none","headers"};
    if(!local){addText(form,"认证方式",13,theme.muted);authentication.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,authLabels));int selected=0;for(int i=0;i<modes.length;i++)if(modes[i].equals(data.optString("auth")))selected=i;authentication.setSelection(selected);form.addView(authentication);}
    LinearLayout tokenGroup=new LinearLayout(this);tokenGroup.setOrientation(LinearLayout.VERTICAL);if(!local)form.addView(tokenGroup);
    EditText token=!local?field(tokenGroup,"Token", "",existing&&data.optBoolean("hasToken")?"已保存；留空保留":"只用于该服务，保存在手机私有目录",true,false):null;
    tokenGroup.setVisibility(!local&&authentication.getSelectedItemPosition()==1?View.VISIBLE:View.GONE);
    LinearLayout advanced=new LinearLayout(this);advanced.setOrientation(LinearLayout.VERTICAL);advanced.setVisibility(data.optBoolean("oauthNeedsClient")?View.VISIBLE:View.GONE);
    addAction(form,"高级参数",false,()->advanced.setVisibility(advanced.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));form.addView(advanced);
    EditText options=field(advanced,local?"环境变量 JSON":"Headers JSON","",existing?"留空保留，{} 清空":"例如 {\"X-API-Key\":\"…\"}",true,true);
    EditText cwd=local?field(advanced,"工作目录",data.optString("cwd"),"留空使用当前项目",false,false):null;
    EditText clientId=!local?field(advanced,"OAuth Client ID（仅预注册时填写）",data.optString("clientId"),"支持动态注册的服务无需填写",false,false):null;
    EditText clientSecret=!local?field(advanced,"OAuth Client Secret（服务要求时填写）","",data.optBoolean("hasClientSecret")?"已保存；留空保留":"仅保存在手机私有配置中",true,false):null;
    EditText issuer=!local?field(advanced,"对应授权服务地址",data.optString("issuer"),"使用预注册 Client ID 时必填",false,false):null;
    if(!local){String redirect=data.optString("redirectUrl","dev.zfold.assistant://connector-oauth");addText(advanced,"OAuth 应用需登记此回调地址：\n"+redirect,12,theme.muted);addAction(advanced,"复制 OAuth 回调地址",false,()->{((android.content.ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("OAuth callback",redirect));Toast.makeText(this,"回调地址已复制",Toast.LENGTH_SHORT).show();});}
    if(!data.optString("docs").isEmpty())addAction(advanced,"查看接入说明",false,()->browse(data.optString("docs")));
    if(!local)authentication.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> parent){}public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){tokenGroup.setVisibility(position==1?View.VISIBLE:View.GONE);if(position==3)advanced.setVisibility(View.VISIBLE);}});
    TextView error=text("",13,theme.error);editorError=error;editorRequest=null;form.addView(error);ScrollView scroll=new ScrollView(this);scroll.addView(form);
    editor=new AlertDialog.Builder(this).setTitle(existing?"编辑连接":local?"本机命令":"远程连接").setView(scroll).setNegativeButton("取消",null).setPositiveButton("保存并连接",null).create();
    editor.setOnShowListener(dialog->editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{
      try{
        if(!runtime.ready){error.setText("本机运行时正在启动，请稍后连接");return;}
        String displayName=name.getText().toString().trim(),location=address.getText().toString().trim();if(displayName.isEmpty()){name.setError("请填写名称");return;}
        if(location.isEmpty()&&(!existing||local)){address.setError(local?"请填写程序名称":"请填写 MCP 地址");return;}
        JSONObject config=PiRuntime.json("name",displayName,"transport",data.optString("transport",local?"stdio":"http"),"enabled",true,"keepSecrets",existing);
        if(existing)config.put("id",data.getString("id"));
        String json=options.getText().toString().trim();JSONObject extra=json.isEmpty()?new JSONObject():new JSONObject(json);
        if(local){String raw=arguments.getText().toString().trim();JSONArray args=new JSONArray();if(!raw.isEmpty()&&!raw.equals("[]"))for(String item:raw.split("\n"))args.put(item.trim());config.put("command",location).put("args",args).put("keepArgs",existing&&raw.isEmpty()).put("env",extra).put("keepEnvironment",existing&&json.isEmpty()).put("cwd",cwd.getText().toString().trim());}
        else{String mode=modes[authentication.getSelectedItemPosition()];config.put("url",location).put("keepAddress",existing&&location.isEmpty()).put("auth",mode).put("token",token.getText().toString().trim()).put("headers",mode.equals("headers")?extra:new JSONObject()).put("keepHeaders",existing&&mode.equals("headers")&&json.isEmpty()).put("clientId",clientId.getText().toString().trim()).put("clientSecret",clientSecret.getText().toString().trim()).put("issuer",issuer.getText().toString().trim());}
        error.setTextColor(theme.muted);error.setText("正在保存并连接…");editorRequest=request("save",PiRuntime.json("config",config),"save",true);editor.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
      }catch(Exception failure){error.setTextColor(theme.error);error.setText("请检查高级 JSON 格式："+failure.getMessage());}
    }));editor.show();
  }
  private String join(JSONArray values){StringBuilder text=new StringBuilder();if(values!=null)for(int i=0;i<values.length();i++){if(i>0)text.append('\n');text.append(values.optString(i));}return text.toString();}
  private void importConfig(){
    EditText input=new EditText(this);input.setContentDescription("MCP 配置 JSON");input.setHint("{\"mcpServers\":{…}}");input.setMinLines(6);input.setGravity(Gravity.TOP);input.setTextSize(13);input.setSaveEnabled(false);
    AlertDialog dialog=new AlertDialog.Builder(this).setTitle("导入 MCP 配置").setMessage("粘贴常见的 mcpServers / servers JSON。配置只保存到手机私有目录。").setView(input).setNegativeButton("取消",null).setPositiveButton("导入并连接",null).create();
    dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{new JSONObject(input.getText().toString());request("import",PiRuntime.json("json",input.getText().toString()),"import",false);dialog.dismiss();}catch(Exception error){input.setError("JSON 格式无效");}}));dialog.show();
  }
  private void showTools(String id,JSONObject page){
    JSONArray tools=page.optJSONArray("tools");if(tools==null)tools=new JSONArray();JSONArray entries=tools;
    ArrayList<String> labels=new ArrayList<>();for(int i=0;i<tools.length();i++){JSONObject tool=tools.optJSONObject(i);labels.add(tool.optString("name")+"\n"+tool.optString("description"));}
    boolean more=!page.isNull("nextOffset")&&page.has("nextOffset");if(more)labels.add("下一页");
    new AlertDialog.Builder(this).setTitle("工具 · 共 "+page.optInt("total")+" 个").setItems(labels.toArray(new String[0]),(dialog,which)->{if(which==entries.length())request("tools",PiRuntime.json("id",id,"offset",page.optInt("nextOffset")),"tools:"+id,false);else inspectTool(id,entries.optJSONObject(which));}).setNegativeButton("返回",null).show();
  }
  private void inspectTool(String id,JSONObject tool){
    LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),dp(8),dp(20),dp(12));
    addText(body,tool.optString("description"),14,theme.ink);addText(body,"输入结构",12,theme.muted);addText(body,String.valueOf(tool.opt("inputSchema")),12,theme.muted);
    EditText input=field(body,"调用参数 JSON","{}","按上面的输入结构填写",false,true);
    ScrollView scroll=new ScrollView(this);scroll.addView(body);
    AlertDialog dialog=new AlertDialog.Builder(this).setTitle(tool.optString("name")).setView(scroll).setNegativeButton("返回",null).setPositiveButton("执行这个工具",null).create();
    dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{request("call",PiRuntime.json("id",id,"tool",tool.optString("name"),"arguments",new JSONObject(input.getText().toString())),"call",false);dialog.dismiss();summary.setText("正在调用 "+tool.optString("name")+"…");}catch(Exception error){input.setError("参数需要是 JSON 对象");}}));dialog.show();
  }
  private void showResult(JSONObject result){String text=result.toString();if(text.length()>12000)text=text.substring(0,12000)+"\n…显示已截断";TextView value=this.text(text,13,theme.ink);value.setTextIsSelectable(true);value.setPadding(dp(18),dp(8),dp(18),dp(18));ScrollView scroll=new ScrollView(this);scroll.addView(value);new AlertDialog.Builder(this).setTitle(result.optBoolean("isError")?"工具返回错误":"工具已返回").setView(scroll).setPositiveButton("完成",null).show();}
  private void browse(String url){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}catch(Exception error){new AlertDialog.Builder(this).setTitle("无法打开浏览器").setMessage("可安装浏览器后重试。").setPositiveButton("返回",null).show();}}
  private int dp(float value){return Ui.dp(this,value);}
}
