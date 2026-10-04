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
  private final View anchor;private final PiRuntime runtime;private final Ui.Theme theme;
  private FrameLayout frame;private LinearLayout list;private TextView footer;private PopupWindow popup;
  private final Map<String,JSONObject> prompts=new LinkedHashMap<>();
  private AlertDialog activeDialog;

  private ProviderAuthDialog(View anchor,PiRuntime runtime){this.anchor=anchor;this.runtime=runtime;theme=new Ui.Theme(anchor.getContext());}

  static void show(View anchor,PiRuntime runtime){ProviderAuthDialog dialog=new ProviderAuthDialog(anchor,runtime);dialog.open();}

  private void open(){
    Context context=anchor.getContext();
    frame=new FrameLayout(context);frame.setFocusableInTouchMode(true);
    frame.setOnKeyListener((v,key,event)->event.getAction()==KeyEvent.ACTION_UP&&key==KeyEvent.KEYCODE_BACK);
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
    TextView help=Ui.text(context,"OAuth 会打开浏览器或显示设备码；完成后凭据保存在应用私有 auth.json，不会写入 git。",12,theme.muted);
    help.setLineSpacing(Ui.dp(context,3),1f);LinearLayout.LayoutParams helpParams=new LinearLayout.LayoutParams(-1,-2);helpParams.setMargins(0,Ui.dp(context,9),0,0);drawer.addView(help,helpParams);
    ScrollView scroll=new ScrollView(context);scroll.setVerticalScrollBarEnabled(false);list=new LinearLayout(context);list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);drawer.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    footer=Ui.text(context,"正在读取供应商…",11,theme.muted);footer.setPadding(0,Ui.dp(context,13),0,0);drawer.addView(footer);
    popup=new PopupWindow(frame,-1,-1,true);popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));popup.setOutsideTouchable(true);popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);popup.setOnDismissListener(()->runtime.unlisten(this));
    try{
      ((android.view.inputmethod.InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(anchor.getWindowToken(),0);
      popup.showAtLocation(anchor,Gravity.TOP|Gravity.END,0,0);frame.requestFocus();runtime.listen(this);refresh();
    }catch(RuntimeException error){runtime.unlisten(this);Toast.makeText(context,"供应商登录无法打开",Toast.LENGTH_SHORT).show();}
  }

  private void refresh(){runtime.send(PiRuntime.json("type","auth_manage","action","list"));}
  private void close(){if(popup!=null)popup.dismiss();}

  @Override public void event(JSONObject event){
    if(popup==null||!popup.isShowing())return;
    String type=event.optString("type");
    if(type.equals("auth_state"))handleAuth(event);
    else if(type.equals("error")&&!event.optString("turnId").isEmpty())footer.setText(Ui.errorMessage(event.optString("message")));
  }

  private void handleAuth(JSONObject event){
    String state=event.optString("state");
    if(state.equals("providers")){renderProviders(event.optJSONArray("providers"),event.optJSONArray("availableProviders"));return;}
    if(state.equals("prompt")){prompts.put(event.optString("promptId"),event);showPrompt(event);return;}
    if(state.equals("event")){
      JSONObject payload=event.optJSONObject("event");if(payload==null)return;
      String kind=payload.optString("type");
      if(kind.equals("device_code"))showDeviceCode(event.optString("requestId"),payload);
      else if(kind.equals("auth_url"))openAuthUrl(event.optString("requestId"),payload);
      else if(kind.equals("progress"))footer.setText(payload.optString("message","登录中…"));
      return;
    }
    if(state.equals("success")){
      activeDialog=null;footer.setText("登录成功");refresh();
      Toast.makeText(anchor.getContext(),"登录成功",Toast.LENGTH_SHORT).show();
    }else if(state.equals("error")||state.equals("cancelled")){
      activeDialog=null;footer.setText(state.equals("cancelled")?"登录已取消":"登录失败："+event.optString("error"));
    }
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
      if(provider.optBoolean("oauthLogin"))actions.addView(new Ui.Action(context,R.drawable.ic_arrow_square_out,"OAuth 登录","使用 OAuth 登录 "+provider.optString("name"),theme.accent,theme.accentSoft,()->login(provider.optString("id"),"oauth")),new LinearLayout.LayoutParams(-2,-2));
      if(provider.optBoolean("apiKeyLogin"))actions.addView(new Ui.Action(context,R.drawable.ic_gear_six,"Key 登录","使用供应商提供的 API Key 登录",theme.ink,theme.field,()->login(provider.optString("id"),"api_key")),new LinearLayout.LayoutParams(-2,-2));
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
    footer.setText(visible+" 个已添加供应商");
  }

  private void showAdd(JSONArray available){
    if(available==null)return;Context context=anchor.getContext();
    java.util.ArrayList<JSONObject> items=new java.util.ArrayList<>();
    for(int i=0;i<available.length();i++){JSONObject provider=available.optJSONObject(i);if(provider!=null&&provider.optBoolean("oauthLogin")&&!provider.optString("id").equals("custom"))items.add(provider);}
    if(items.isEmpty()){Toast.makeText(context,"当前 Pi 没有可添加的 OAuth 供应商",Toast.LENGTH_LONG).show();return;}
    String[] labels=new String[items.size()];
    for(int i=0;i<items.size();i++){JSONObject provider=items.get(i);labels[i]=provider.optString("name",provider.optString("id"))+(provider.optBoolean("configured")?" · 已登录":"");}
    new AlertDialog.Builder(context).setTitle("添加 OAuth 供应商").setItems(labels,(dialog,index)->{
      JSONObject provider=items.get(index);runtime.send(PiRuntime.json("type","auth_manage","action","add","provider",provider.optString("id")));
    }).setNegativeButton("取消",null).show();
  }

  private void remove(String provider){new AlertDialog.Builder(anchor.getContext()).setTitle("移除供应商").setMessage("从已添加列表移除 "+provider+"？不会删除已保存的登录凭据。").setNegativeButton("取消",null).setPositiveButton("移除",(dialog,which)->runtime.send(PiRuntime.json("type","auth_manage","action","remove","provider",provider))).show();}

  private void login(String provider,String authType){
    String requestId="auth-"+System.currentTimeMillis();
    runtime.send(PiRuntime.json("type","auth_manage","action","login","requestId",requestId,"provider",provider,"authType",authType));
    footer.setText("正在启动登录…");
  }

  private void showPrompt(JSONObject event){
    Context context=anchor.getContext();JSONObject prompt=event.optJSONObject("prompt");if(prompt==null)return;
    String kind=prompt.optString("type"),message=prompt.optString("message","请输入");
    dismissDialog();
    if(kind.equals("select")){
      JSONArray options=prompt.optJSONArray("options");if(options==null||options.length()==0)return;
      String[] labels=new String[options.length()];
      for(int i=0;i<options.length();i++)labels[i]=options.optJSONObject(i)==null?"":options.optJSONObject(i).optString("label");
      activeDialog=new AlertDialog.Builder(context).setTitle("登录方式").setItems(labels,(dialog,index)->{
        JSONObject option=options.optJSONObject(index);answer(event.optString("promptId"),option==null?"":option.optString("id"));
      }).setNegativeButton("取消",null).create();activeDialog.show();return;
    }
    LinearLayout body=new LinearLayout(context);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(Ui.dp(context,24),Ui.dp(context,10),Ui.dp(context,24),0);
    TextView label=Ui.text(context,message,13,theme.ink);label.setLineSpacing(Ui.dp(context,3),1f);body.addView(label);
    EditText input=new EditText(context);input.setSingleLine(true);input.setTextSize(14);input.setTextColor(theme.ink);
    input.setHint(prompt.optString("placeholder"));input.setInputType(kind.equals("secret")?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT);
    input.setBackground(Ui.shape(context,theme.field,12,theme.line));input.setPadding(Ui.dp(context,12),Ui.dp(context,12),Ui.dp(context,12),Ui.dp(context,12));
    LinearLayout.LayoutParams inputParams=new LinearLayout.LayoutParams(-1,-2);inputParams.setMargins(0,Ui.dp(context,14),0,0);body.addView(input,inputParams);
    activeDialog=new AlertDialog.Builder(context).setTitle("供应商登录").setView(body).setPositiveButton("继续",(dialog,which)->answer(event.optString("promptId"),input.getText().toString()))
      .setNegativeButton("取消",null).create();activeDialog.show();
  }

  private void showDeviceCode(String requestId,JSONObject payload){
    dismissDialog();Context context=anchor.getContext();
    String message="访问 "+payload.optString("verificationUri")+"\n输入代码："+payload.optString("userCode");
    activeDialog=new AlertDialog.Builder(context).setTitle("设备码登录").setMessage(message)
      .setPositiveButton("打开页面",(dialog,which)->open(payload.optString("verificationUri")))
      .setNeutralButton("复制代码",(dialog,which)->{((android.content.ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("Login code",payload.optString("userCode")));Toast.makeText(context,"代码已复制",Toast.LENGTH_SHORT).show();})
      .setNegativeButton("后台等待",null).create();activeDialog.show();
  }

  private void openAuthUrl(String requestId,JSONObject payload){
    String url=payload.optString("url");if(url.isEmpty())return;dismissDialog();Context context=anchor.getContext();
    activeDialog=new AlertDialog.Builder(context).setTitle("浏览器登录").setMessage(payload.optString("instructions","打开授权页面完成登录。"))
      .setPositiveButton("打开",(dialog,which)->open(url))
      .setNeutralButton("手动输入",(dialog,which)->requestManual(requestId,payload.optString("url")))
      .setNegativeButton("取消",null).create();activeDialog.show();
  }

  private void requestManual(String promptId,String placeholder){
    Context context=anchor.getContext();LinearLayout body=new LinearLayout(context);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(Ui.dp(context,24),Ui.dp(context,10),Ui.dp(context,24),0);
    TextView label=Ui.text(context,"粘贴授权码、回调 URL 或验证码。",13,theme.ink);body.addView(label);
    EditText input=new EditText(context);input.setSingleLine(true);input.setText(placeholder);input.setTextSize(14);
    input.setBackground(Ui.shape(context,theme.field,12,theme.line));input.setPadding(Ui.dp(context,12),Ui.dp(context,12),Ui.dp(context,12),Ui.dp(context,12));
    LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.setMargins(0,Ui.dp(context,14),0,0);body.addView(input,params);
    activeDialog=new AlertDialog.Builder(context).setTitle("手动输入").setView(body).setPositiveButton("继续",(dialog,which)->answer(promptId,input.getText().toString()))
      .setNegativeButton("取消",null).create();activeDialog.show();
  }

  private void answer(String promptId,String value){runtime.send(PiRuntime.json("type","auth_manage","action","prompt","promptId",promptId,"value",value));footer.setText("正在完成登录…");}
  private void open(String url){if(url.isEmpty())return;anchor.getContext().startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
  private void dismissDialog(){if(activeDialog!=null&&activeDialog.isShowing())activeDialog.dismiss();activeDialog=null;}
}
