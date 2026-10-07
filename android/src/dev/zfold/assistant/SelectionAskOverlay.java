package dev.zfold.assistant;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;

/** Tiny overlay next to the current app: ask about circled pixels or system-selected text/image. */
final class SelectionAskOverlay {
  private static LinearLayout card;
  private static WindowManager manager;
  private static TextView status;
  private static TextView reply;
  private static final StringBuilder stream=new StringBuilder();
  private static PiRuntime boundRuntime;
  private static PiRuntime.Listener listener;
  private static String requestId;
  private static boolean pending;

  static void showImage(Context context,String image){show(context,image,null);}
  static void showText(Context context,String text){show(context,null,text);}

  static void show(Context base,String image,String selectedText){
    hide();
    Context app=base.getApplicationContext();
    PhoneAccessibility service=PhoneAccessibility.instance;
    if(service==null&&!Settings.canDrawOverlays(app)){
      Toast.makeText(app,"需要悬浮窗或读屏服务才能在当前屏幕提问",Toast.LENGTH_LONG).show();
      return;
    }
    Context host=service!=null?service:app;
    WindowManager wm=(WindowManager)host.getSystemService(Context.WINDOW_SERVICE);
    LinearLayout body=new LinearLayout(new android.view.ContextThemeWrapper(host,R.style.AppTheme));
    body.setOrientation(LinearLayout.VERTICAL);
    body.setBackground(Ui.shape(app,0xf2141820,18,Ui.dp(app,1),0xff2a3544));
    body.setPadding(Ui.dp(app,10),Ui.dp(app,10),Ui.dp(app,10),Ui.dp(app,8));
    body.setElevation(Ui.dp(app,12));

    LinearLayout head=new LinearLayout(app);head.setOrientation(LinearLayout.HORIZONTAL);head.setGravity(Gravity.CENTER_VERTICAL);
    head.addView(Ui.agentMark(app,PiRuntime.get(app).agentAvatar,22),new LinearLayout.LayoutParams(Ui.dp(app,22),Ui.dp(app,22)));
    TextView title=Ui.text(app,"问橙橙",13,0xffedf4ff);title.setPadding(Ui.dp(app,8),0,0,0);
    head.addView(title,new LinearLayout.LayoutParams(0,-2,1f));
    head.addView(new Ui.Action(app,R.drawable.ic_x,"","关闭",0xffe8eff8,Color.TRANSPARENT,SelectionAskOverlay::hide),new LinearLayout.LayoutParams(Ui.dp(app,40),Ui.dp(app,40)));
    body.addView(head);

    if(selectedText!=null&&!selectedText.isBlank()){
      TextView snippet=Ui.text(app,selectedText.length()>180?selectedText.substring(0,180)+"…":selectedText,12,0xffc5d4e4);
      snippet.setPadding(0,Ui.dp(app,4),0,Ui.dp(app,6));
      body.addView(snippet);
    }
    if(image!=null){
      byte[] raw=Base64.decode(image,Base64.DEFAULT);
      Bitmap preview=BitmapFactory.decodeByteArray(raw,0,raw.length);
      if(preview!=null){
        ImageView thumb=new ImageView(app);thumb.setImageBitmap(preview);thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackground(Ui.shape(app,0xff0b1016,10,0,0));
        LinearLayout.LayoutParams imageParams=new LinearLayout.LayoutParams(-1,Ui.dp(app,72));imageParams.bottomMargin=Ui.dp(app,6);
        body.addView(thumb,imageParams);
      }
    }

    EditText input=new EditText(app);
    input.setHint(image!=null?"想问圈选内容什么？":"想问这段文字什么？");
    input.setTextSize(14);input.setTextColor(0xffedf4ff);input.setHintTextColor(0xff7d8ea3);
    input.setBackground(Ui.shape(app,0xff0b1016,12,Ui.dp(app,1),0xff2a3544));
    input.setPadding(Ui.dp(app,10),Ui.dp(app,8),Ui.dp(app,10),Ui.dp(app,8));
    input.setMinHeight(Ui.dp(app,40));
    input.setImeOptions(EditorInfo.IME_ACTION_SEND);input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    body.addView(input,new LinearLayout.LayoutParams(-1,-2));
    status=Ui.text(app,"",11,0xffa8bed4);status.setPadding(0,Ui.dp(app,4),0,0);status.setVisibility(android.view.View.GONE);body.addView(status);
    ScrollView scroller=new ScrollView(app);scroller.setFillViewport(false);
    reply=Ui.text(app,"",13,0xffedf4ff);reply.setPadding(0,Ui.dp(app,4),0,0);
    scroller.addView(reply,new android.widget.FrameLayout.LayoutParams(-1,-2));
    LinearLayout.LayoutParams scrollParams=new LinearLayout.LayoutParams(-1,Ui.dp(app,180));scrollParams.topMargin=Ui.dp(app,4);scroller.setVisibility(android.view.View.GONE);body.addView(scroller,scrollParams);

    LinearLayout actions=new LinearLayout(app);actions.setGravity(Gravity.CENTER_VERTICAL);
    actions.setPadding(0,Ui.dp(app,4),0,0);
    boolean[] fresh={false};
    TextView neu=Ui.text(app,"新会话",12,0xff8aa0b5);
    neu.setPadding(Ui.dp(app,4),Ui.dp(app,8),Ui.dp(app,8),Ui.dp(app,8));
    neu.setContentDescription("发送到新会话");
    neu.setOnClickListener(v->{fresh[0]=!fresh[0];neu.setText(fresh[0]?"新会话 · 开":"新会话");neu.setTextColor(fresh[0]?0xff93c5fd:0xff8aa0b5);});
    actions.addView(neu,new LinearLayout.LayoutParams(0,-2,1f));
    Ui.Action send=new Ui.Action(app,R.drawable.ic_arrow_up,"发送","发送提问",0xffedf4ff,0xff2563eb,()->submit(app,input.getText().toString(),image,selectedText,fresh[0]));
    actions.addView(send,new LinearLayout.LayoutParams(Ui.dp(app,88),Ui.dp(app,40)));
    body.addView(actions);
    input.setOnEditorActionListener((v,actionId,event)->{if(actionId==EditorInfo.IME_ACTION_SEND){submit(app,input.getText().toString(),image,selectedText,fresh[0]);return true;}return false;});

    int type=service!=null?WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY:WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    WindowManager.LayoutParams params=new WindowManager.LayoutParams(Ui.dp(app,280),-2,type,WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
    params.gravity=Gravity.BOTTOM|Gravity.END;
    params.x=Ui.dp(app,16);params.y=Ui.dp(app,88);
    params.setFitInsetsTypes(WindowInsets.Type.ime()|WindowInsets.Type.navigationBars()|WindowInsets.Type.displayCutout());
    try{wm.addView(body,params);manager=wm;card=body;Ui.arrive(body);input.requestFocus();}
    catch(RuntimeException error){Toast.makeText(app,"未能在当前屏幕打开提问窗",Toast.LENGTH_LONG).show();}
  }

  private static void submit(Context app,String raw,String image,String selectedText,boolean newSession){
    if(pending)return;
    String text=raw==null?"":raw.trim();
    if(text.isEmpty())text=image!=null?"请看圈选区域，说明里面是什么，并给出有用建议。":"请解释这段选中的文字，并给出简要建议。";
    if(selectedText!=null&&!selectedText.isBlank())text=text+"\n\n选中内容：\n"+selectedText;
    PiRuntime runtime=PiRuntime.get(app);
    if(!runtime.ready||!runtime.configured){Toast.makeText(app,"请先在橙橙里连接模型",Toast.LENGTH_LONG).show();return;}
    if(runtime.busy&&!newSession){Toast.makeText(app,"当前会话忙碌，可打开「新会话」再问",Toast.LENGTH_SHORT).show();return;}
    JSONObject command=PiRuntime.json("type","prompt","id",java.util.UUID.randomUUID().toString(),"text",text);
    try{if(image!=null)command.put("image",image);if(newSession)command.put("newSession",true);}catch(Exception ignored){}
    requestId=command.optString("id");pending=true;
    if(card!=null){android.view.View focus=card.findFocus();if(focus!=null)((android.view.inputmethod.InputMethodManager)app.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focus.getWindowToken(),0);}
    stream.setLength(0);if(status!=null){status.setVisibility(android.view.View.VISIBLE);status.setText("正在思考…");}
    if(reply!=null){reply.setText("");if(reply.getParent() instanceof android.view.View)((android.view.View)reply.getParent()).setVisibility(android.view.View.VISIBLE);}
    bind(runtime);
    runtime.send(command);
  }

  private static void bind(PiRuntime runtime){
    if(listener!=null)return;boundRuntime=runtime;
    listener=event->{
      if(card==null||status==null||reply==null)return;
      if(requestId==null||!requestId.equals(event.optString("turnId")))return;
      String type=event.optString("type");
      if(type.equals("turn_start"))status.setText("正在思考…");
      else if(type.equals("delta")){stream.append(event.optString("text"));reply.setText(stream.toString());status.setText("");}
      else if(type.equals("turn_end")){pending=false;status.setText(event.optBoolean("aborted")?"已停止":"回复完成");}
      else if(type.equals("error")){pending=false;status.setText(event.optString("message"));}
    };runtime.listen(listener);
  }

  static void hide(){
    if(boundRuntime!=null&&listener!=null)boundRuntime.unlisten(listener);
    boundRuntime=null;listener=null;requestId=null;pending=false;
    if(card==null)return;
    try{manager.removeView(card);}catch(Exception ignored){}
    card=null;manager=null;status=null;reply=null;
  }
}
