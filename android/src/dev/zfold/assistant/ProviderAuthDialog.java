package dev.zfold.assistant;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;

/** Provider login sheet for OAuth and provider-owned API-key flows. */
final class ProviderAuthDialog implements PiRuntime.Listener {
  private final View anchor;private final PiRuntime runtime;private final Ui.Theme theme;private final java.util.function.Consumer<JSONObject> commands;
  private FrameLayout frame;private LinearLayout list;private TextView footer;private PopupWindow popup;
  private final Map<String,JSONObject> flows=new LinkedHashMap<>();
  private AlertDialog activeDialog;private EditText manualInput;private String selectedRequest,selectedProvider,dialogFlow,renderedStamp;private String outcomeStatus="";
  private final View.OnAttachStateChangeListener lifetime=new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View view){}public void onViewDetachedFromWindow(View view){close();}};

  private ProviderAuthDialog(View anchor,PiRuntime runtime){this(anchor,runtime,runtime::send);}
  ProviderAuthDialog(View anchor,PiRuntime runtime,java.util.function.Consumer<JSONObject> commands){this.anchor=anchor;this.runtime=runtime;this.commands=commands;theme=new Ui.Theme(anchor.getContext());}

  static void show(View anchor,PiRuntime runtime){ProviderAuthDialog dialog=new ProviderAuthDialog(anchor,runtime);dialog.open();}

  void open(){
    Context context=anchor.getContext();
    if(!(context instanceof android.app.Activity)){context.startActivity(new Intent(context,SettingsActivity.class).putExtra("open_provider_auth",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));return;}
    frame=new FrameLayout(context);frame.setFocusableInTouchMode(true);
    frame.setOnKeyListener((v,key,event)->{if(event.getAction()==KeyEvent.ACTION_UP&&key==KeyEvent.KEYCODE_BACK){close();return true;}return false;});
    View scrim=new View(context);scrim.setBackgroundColor(0x74000000);scrim.setContentDescription("关闭供应商登录");scrim.setClickable(true);scrim.setOnClickListener(v->close());
    frame.addView(scrim,new FrameLayout.LayoutParams(-1,-1));
    LinearLayout drawer=new LinearLayout(context);drawer.setOrientation(LinearLayout.VERTICAL);drawer.setBackgroundColor(theme.surface);drawer.setElevation(Ui.dp(context,18));drawer.setClickable(true);
    int width=Math.min(Ui.dp(context,460),context.getResources().getDisplayMetrics().widthPixels-Ui.dp(context,32));
    frame.addView(drawer,new FrameLayout.LayoutParams(width,-1,Gravity.END));
    frame.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars());drawer.setPadding(Ui.dp(context,16)+bars.left,bars.top+Ui.dp(context,16),Ui.dp(context,16)+bars.right,bars.bottom+Ui.dp(context,16));return insets;});
    LinearLayout brand=new LinearLayout(context);brand.setGravity(Gravity.CENTER_VERTICAL);
    TextView title=Ui.text(context,"供应商登录",19,theme.ink);title.setTypeface(Typeface.create("sans-serif-medium",0));brand.addView(title,new LinearLayout.LayoutParams(0,-2,1));
    brand.addView(new Ui.Action(context,R.drawable.ic_arrow_clockwise,"","刷新供应商登录状态",theme.muted,Color.TRANSPARENT,this::refresh),new LinearLayout.LayoutParams(Ui.dp(context,48),Ui.dp(context,48)));
    brand.addView(new Ui.Action(context,R.drawable.ic_x,"","关闭供应商登录",theme.muted,Color.TRANSPARENT,this::close),new LinearLayout.LayoutParams(Ui.dp(context,48),Ui.dp(context,48)));
    drawer.addView(brand);
    TextView help=Ui.text(context,"在系统浏览器完成授权，返回后自动接续。关闭此面板后，可从“继续登录”恢复；凭据仅保存在手机私有配置中。",12,theme.muted);
    help.setLineSpacing(Ui.dp(context,3),1f);LinearLayout.LayoutParams helpParams=new LinearLayout.LayoutParams(-1,-2);helpParams.setMargins(0,Ui.dp(context,9),0,0);drawer.addView(help,helpParams);
    ScrollView scroll=new ScrollView(context);scroll.setVerticalScrollBarEnabled(false);list=new LinearLayout(context);list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);drawer.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    footer=Ui.text(context,"正在读取供应商…",11,theme.muted);footer.setPadding(0,Ui.dp(context,13),0,0);drawer.addView(footer);
    popup=new PopupWindow(frame,-1,-1,true);popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));popup.setOutsideTouchable(true);popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);popup.setOnDismissListener(()->{anchor.removeOnAttachStateChangeListener(lifetime);if(runtime!=null)runtime.unlisten(this);dismissDialog();});
    try{
      ((android.view.inputmethod.InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(anchor.getWindowToken(),0);
      popup.showAtLocation(anchor,Gravity.TOP|Gravity.END,0,0);anchor.addOnAttachStateChangeListener(lifetime);frame.requestFocus();if(runtime!=null)runtime.listen(this);refresh();
    }catch(RuntimeException error){if(runtime!=null)runtime.unlisten(this);Toast.makeText(context,"供应商登录无法打开",Toast.LENGTH_SHORT).show();}
  }

  private void refresh(){commands.accept(PiRuntime.json("type","auth_manage","action","list"));}
  void close(){dismissDialog();if(popup!=null)popup.dismiss();}

  @Override public void event(JSONObject event){
    if(popup==null||!popup.isShowing())return;
    String type=event.optString("type");
    if(type.equals("auth_state"))handleAuth(event);
  }

  private void handleAuth(JSONObject event){
    String state=event.optString("state");
    if(state.equals("providers")){flows.clear();JSONArray saved=event.optJSONArray("flows");if(saved!=null)for(int i=0;i<saved.length();i++){JSONObject flow=saved.optJSONObject(i);if(flow!=null)flows.put(flow.optString("requestId"),flow);}renderProviders(event.optJSONArray("providers"),event.optJSONArray("availableProviders"));return;}
    String id=event.optString("requestId");
    if(state.equals("command_error")){outcomeStatus=event.optString("error");footer.setText(outcomeStatus);Toast.makeText(anchor.getContext(),outcomeStatus,Toast.LENGTH_LONG).show();return;}
    flows.put(id,event);
    if(event.optString("provider").equals(selectedProvider)&&!flows.containsKey(selectedRequest))selectedRequest=id;
    if(!id.equals(selectedRequest))return;
    if(!event.optBoolean("active")){dismissDialog();outcomeStatus=state.equals("success")?"登录成功":event.optString("error","登录已结束");footer.setText(outcomeStatus);if(state.equals("success"))Toast.makeText(anchor.getContext(),"登录成功",Toast.LENGTH_SHORT).show();refresh();return;}
    if(state.equals("cancelling")){dismissDialog();footer.setText("正在取消登录…");return;}
    footer.setText(event.optString("message","登录进行中…"));showFlow(event);
  }

  private void renderProviders(JSONArray providers,JSONArray available){
    Context context=anchor.getContext();list.removeAllViews();int visible=0;
    if(providers!=null)for(int i=0;i<providers.length();i++){
      JSONObject provider=providers.optJSONObject(i);if(provider==null||!provider.optBoolean("oauthLogin")&&!provider.optBoolean("apiKeyLogin"))continue;
      visible++;
      LinearLayout card=new LinearLayout(context);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(Ui.dp(context,13),Ui.dp(context,11),Ui.dp(context,10),Ui.dp(context,10));
      card.setBackground(Ui.card(context,theme.surface,16,theme.line));
      LinearLayout head=new LinearLayout(context);head.setGravity(Gravity.CENTER_VERTICAL);
      TextView name=Ui.text(context,provider.optString("name",provider.optString("id")),14,theme.ink);name.setTypeface(Typeface.create("sans-serif-medium",0));
      head.addView(name,new LinearLayout.LayoutParams(0,-2,1));
      TextView status=Ui.text(context,provider.optBoolean("configured")?"已登录":"未登录",11,provider.optBoolean("configured")?theme.success:theme.muted);head.addView(status);
      card.addView(head);
      String detail=provider.optBoolean("oauthLogin")?provider.optString("oauthName","OAuth"):"";
      if(provider.optBoolean("apiKeyLogin"))detail+=(detail.isEmpty()?"":" · ")+provider.optString("apiKeyName","API Key");
      TextView description=Ui.text(context,detail,12,theme.muted);LinearLayout.LayoutParams descParams=new LinearLayout.LayoutParams(-1,-2);descParams.setMargins(0,Ui.dp(context,5),0,0);card.addView(description,descParams);
      LinearLayout actions=new LinearLayout(context);actions.setGravity(Gravity.END);
      JSONObject running=activeFlow(provider.optString("id"));
      if(running!=null){actions.addView(new Ui.Action(context,R.drawable.ic_arrow_square_out,"继续登录","继续登录 "+provider.optString("name"),theme.accent,theme.accentSoft,()->{selectedRequest=running.optString("requestId");selectedProvider=running.optString("provider");renderedStamp=null;showFlow(running);}),new LinearLayout.LayoutParams(-2,-2));actions.addView(new Ui.Action(context,R.drawable.ic_x,"取消","取消登录 "+provider.optString("name"),theme.muted,Color.TRANSPARENT,()->cancel(running.optString("requestId"))),new LinearLayout.LayoutParams(-2,-2));}
      else{
        if(provider.optBoolean("oauthLogin"))actions.addView(new Ui.Action(context,R.drawable.ic_arrow_square_out,"OAuth 登录","使用 OAuth 登录 "+provider.optString("name"),theme.accent,theme.accentSoft,()->login(provider.optString("id"),"oauth")),new LinearLayout.LayoutParams(-2,-2));
        if(provider.optBoolean("apiKeyLogin"))actions.addView(new Ui.Action(context,R.drawable.ic_gear_six,"Key 登录","使用供应商提供的 API Key 登录",theme.ink,theme.field,()->login(provider.optString("id"),"api_key")),new LinearLayout.LayoutParams(-2,-2));
      }
      if(!provider.optString("id").equals("custom"))actions.addView(new Ui.Action(context,R.drawable.ic_x,"","移除 "+provider.optString("name"),theme.error,Color.TRANSPARENT,()->remove(provider.optString("id"))),new LinearLayout.LayoutParams(Ui.dp(context,48),Ui.dp(context,48)));
      card.addView(actions,new LinearLayout.LayoutParams(-1,-2));
      list.addView(card,new LinearLayout.LayoutParams(-1,-2));LinearLayout.LayoutParams params=(LinearLayout.LayoutParams)card.getLayoutParams();params.topMargin=list.getChildCount()==1?0:Ui.dp(context,8);card.setLayoutParams(params);
    }
    if(visible==0){
      TextView empty=Ui.text(context,"当前 Pi 版本没有可交互登录的供应商。",13,theme.muted);empty.setPadding(0,Ui.dp(context,18),0,0);list.addView(empty);
    }
    LinearLayout addCard=new LinearLayout(context);addCard.setGravity(Gravity.CENTER_VERTICAL);addCard.setPadding(Ui.dp(context,13),Ui.dp(context,11),Ui.dp(context,10),Ui.dp(context,10));
    addCard.setBackground(Ui.card(context,theme.accentSoft,16,0));addCard.setClickable(true);addCard.setFocusable(true);
    TextView addLabel=Ui.text(context,"添加供应商",14,theme.accent);addLabel.setTypeface(Typeface.create("sans-serif-medium",0));addCard.addView(addLabel,new LinearLayout.LayoutParams(0,-2,1));
    addCard.addView(new Ui.Action(context,R.drawable.ic_arrows_out_simple,"","选择要添加的 OAuth 供应商",theme.accent,Color.TRANSPARENT,()->showAdd(available)),new LinearLayout.LayoutParams(Ui.dp(context,48),Ui.dp(context,48)));
    addCard.setContentDescription("添加供应商");addCard.setOnClickListener(v->showAdd(available));
    list.addView(addCard,new LinearLayout.LayoutParams(-1,-2));LinearLayout.LayoutParams addParams=(LinearLayout.LayoutParams)addCard.getLayoutParams();addParams.topMargin=Ui.dp(context,10);addCard.setLayoutParams(addParams);
    footer.setText(outcomeStatus.isEmpty()?visible+" 个已添加供应商":outcomeStatus);
  }

  private void showAdd(JSONArray available){
    if(available==null)return;Context context=anchor.getContext();
    java.util.ArrayList<JSONObject> items=new java.util.ArrayList<>();
    for(int i=0;i<available.length();i++){JSONObject provider=available.optJSONObject(i);if(provider!=null&&provider.optBoolean("oauthLogin")&&!provider.optString("id").equals("custom"))items.add(provider);}
    if(items.isEmpty()){Toast.makeText(context,"当前 Pi 没有可添加的 OAuth 供应商",Toast.LENGTH_LONG).show();return;}
    String[] labels=new String[items.size()];
    for(int i=0;i<items.size();i++){JSONObject provider=items.get(i);labels[i]=provider.optString("name",provider.optString("id"))+(provider.optBoolean("configured")?" · 已登录":"");}
    new AlertDialog.Builder(context).setTitle("添加 OAuth 供应商").setItems(labels,(dialog,index)->{
      JSONObject provider=items.get(index);commands.accept(PiRuntime.json("type","auth_manage","action","add","provider",provider.optString("id")));
    }).setNegativeButton("取消",null).show();
  }

  private void remove(String provider){new AlertDialog.Builder(anchor.getContext()).setTitle("移除供应商").setMessage("从已添加列表移除 "+provider+"？不会删除已保存的登录凭据。").setNegativeButton("取消",null).setPositiveButton("移除",(dialog,which)->{JSONObject flow=activeFlow(provider);if(flow!=null)cancel(flow.optString("requestId"));commands.accept(PiRuntime.json("type","auth_manage","action","remove","provider",provider));}).show();}

  private JSONObject activeFlow(String provider){for(JSONObject flow:flows.values())if(flow.optBoolean("active")&&provider.equals(flow.optString("provider")))return flow;return null;}
  private void login(String provider,String authType){
    selectedRequest="auth-"+java.util.UUID.randomUUID();selectedProvider=provider;renderedStamp=null;outcomeStatus="";
    commands.accept(PiRuntime.json("type","auth_manage","action","login","requestId",selectedRequest,"provider",provider,"authType",authType));footer.setText("正在启动登录…");
  }
  private void showFlow(JSONObject flow){
    String id=flow.optString("requestId");JSONObject prompt=flow.optJSONObject("prompt"),browser=flow.optJSONObject("browser"),device=flow.optJSONObject("device");
    String stamp=id+"|"+flow.optString("promptId")+"|"+(browser==null?"":browser.optString("url"))+"|"+(device==null?"":device.optString("userCode"));
    if(stamp.equals(renderedStamp)&&activeDialog!=null&&activeDialog.isShowing())return;
    dismissDialog();renderedStamp=stamp;dialogFlow=id;Context context=anchor.getContext();
    if(prompt!=null&&prompt.optString("type").equals("select")){
      JSONArray options=prompt.optJSONArray("options");if(options==null)return;String[] labels=new String[options.length()];
      for(int i=0;i<labels.length;i++){JSONObject option=options.optJSONObject(i);String key=option.optString("id");labels[i]=key.equals("browser")?"浏览器登录":key.equals("device_code")?"设备码登录":option.optString("label");}
      activeDialog=new AlertDialog.Builder(context).setTitle("登录方式").setItems(labels,(d,index)->answer(flow,options.optJSONObject(index).optString("id"))).setNegativeButton("取消登录",(d,w)->cancel(id)).create();display(id);return;
    }
    if(browser!=null){
      LinearLayout body=body();TextView help=Ui.text(context,"在浏览器完成授权后返回，应用会自动接续。只有无法自动完成时，才需要粘贴回调地址。",13,theme.ink);body.addView(help);
      final EditText input=prompt==null?null:input(body,"授权后的回调 URL 或授权码",true);manualInput=input;
      activeDialog=new AlertDialog.Builder(context).setTitle("浏览器登录").setView(body).setPositiveButton("打开浏览器",null).setNegativeButton("取消登录",(d,w)->cancel(id)).setNeutralButton("提交回调",null).create();display(id);
      activeDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->open(browser.optString("url")));
      activeDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(input!=null);
      activeDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{if(input==null)return;if(input.getText().toString().trim().isEmpty()){input.setError("请粘贴登录后的回调地址，不是授权链接");return;}answer(flow,input.getText().toString());input.setText("");});return;
    }
    if(device!=null){
      LinearLayout body=body();TextView help=Ui.text(context,"打开登录页面并输入下方设备码，完成后回到这里。",13,theme.ink);body.addView(help);
      TextView code=Ui.text(context,device.optString("userCode"),24,theme.accent);code.setTextIsSelectable(true);code.setContentDescription("登录设备码");code.setPadding(0,Ui.dp(context,14),0,Ui.dp(context,14));body.addView(code);
      activeDialog=new AlertDialog.Builder(context).setTitle("设备码登录").setView(body).setPositiveButton("打开页面",null).setNeutralButton("复制代码",null).setNegativeButton("取消登录",(d,w)->cancel(id)).create();display(id);
      activeDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->open(device.optString("verificationUri")));
      activeDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{((android.content.ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("Login code",device.optString("userCode")));Toast.makeText(context,"代码已复制",Toast.LENGTH_SHORT).show();});return;
    }
    if(prompt!=null){
      LinearLayout body=body();body.addView(Ui.text(context,prompt.optString("message","请输入"),13,theme.ink));EditText input=input(body,prompt.optString("placeholder"),true);manualInput=input;
      activeDialog=new AlertDialog.Builder(context).setTitle("供应商登录").setView(body).setPositiveButton("继续",null).setNegativeButton("取消登录",(d,w)->cancel(id)).create();display(id);
      activeDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{if(input.getText().toString().trim().isEmpty()){input.setError("请输入登录信息");return;}answer(flow,input.getText().toString());input.setText("");});
    }
  }
  private LinearLayout body(){LinearLayout body=new LinearLayout(anchor.getContext());body.setOrientation(LinearLayout.VERTICAL);body.setPadding(Ui.dp(anchor.getContext(),24),Ui.dp(anchor.getContext(),10),Ui.dp(anchor.getContext(),24),0);return body;}
  private EditText input(LinearLayout body,String hint,boolean secret){Context context=anchor.getContext();EditText input=new EditText(context);input.setSingleLine(true);input.setSaveEnabled(false);input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);input.setTextSize(14);input.setHint(hint);input.setContentDescription("登录信息输入");input.setInputType(InputType.TYPE_CLASS_TEXT|(secret?InputType.TYPE_TEXT_VARIATION_PASSWORD:0));body.addView(input,new LinearLayout.LayoutParams(-1,-2));return input;}
  private void display(String id){activeDialog.setOnCancelListener(d->cancel(id));activeDialog.show();activeDialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);}
  private void cancel(String id){commands.accept(PiRuntime.json("type","auth_manage","action","cancel","requestId",id));dismissDialog();footer.setText("正在取消登录…");refresh();}
  private void answer(JSONObject flow,String value){commands.accept(PiRuntime.json("type","auth_manage","action","prompt","requestId",flow.optString("requestId"),"promptId",flow.optString("promptId"),"value",value));footer.setText("正在完成登录…");}
  private void open(String value){try{Uri url=Uri.parse(value);if(!"https".equals(url.getScheme())&&!"http".equals(url.getScheme()))throw new IllegalArgumentException();anchor.getContext().startActivity(new Intent(Intent.ACTION_VIEW,url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}catch(Exception error){footer.setText("无法打开浏览器，请检查默认浏览器设置");}}
  private void dismissDialog(){if(activeDialog!=null&&activeDialog.isShowing())activeDialog.dismiss();activeDialog=null;manualInput=null;dialogFlow=null;}
}
