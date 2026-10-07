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
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;

/** Tiny overlay next to the current app: ask about circled pixels or system-selected text/image. */
final class SelectionAskOverlay {
  private static LinearLayout card;
  private static WindowManager manager;

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

    LinearLayout actions=new LinearLayout(app);actions.setGravity(Gravity.END);
    actions.setPadding(0,Ui.dp(app,4),0,0);
    Ui.Action send=new Ui.Action(app,R.drawable.ic_arrow_up,"发送","发送提问",0xffedf4ff,0xff2563eb,()->submit(app,input.getText().toString(),image,selectedText));
    actions.addView(send,new LinearLayout.LayoutParams(Ui.dp(app,88),Ui.dp(app,40)));
    body.addView(actions);
    input.setOnEditorActionListener((v,actionId,event)->{if(actionId==EditorInfo.IME_ACTION_SEND){submit(app,input.getText().toString(),image,selectedText);return true;}return false;});

    int type=service!=null?WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY:WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    WindowManager.LayoutParams params=new WindowManager.LayoutParams(Ui.dp(app,280),-2,type,WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
    params.gravity=Gravity.BOTTOM|Gravity.END;
    params.x=Ui.dp(app,16);params.y=Ui.dp(app,88);
    params.setFitInsetsTypes(WindowInsets.Type.ime()|WindowInsets.Type.navigationBars()|WindowInsets.Type.displayCutout());
    try{wm.addView(body,params);manager=wm;card=body;Ui.arrive(body);input.requestFocus();}
    catch(RuntimeException error){Toast.makeText(app,"未能在当前屏幕打开提问窗",Toast.LENGTH_LONG).show();}
  }

  private static void submit(Context app,String raw,String image,String selectedText){
    String text=raw==null?"":raw.trim();
    if(text.isEmpty())text=image!=null?"请看圈选区域，说明里面是什么，并给出有用建议。":"请解释这段选中的文字，并给出简要建议。";
    if(selectedText!=null&&!selectedText.isBlank())text=text+"\n\n选中内容：\n"+selectedText;
    PiRuntime runtime=PiRuntime.get(app);
    if(!runtime.ready||!runtime.configured){Toast.makeText(app,"请先在橙橙里连接模型",Toast.LENGTH_LONG).show();return;}
    if(runtime.busy){Toast.makeText(app,"助手正忙，稍后再问",Toast.LENGTH_SHORT).show();return;}
    JSONObject command=PiRuntime.json("type","prompt","id",java.util.UUID.randomUUID().toString(),"text",text);
    try{if(image!=null)command.put("image",image);}catch(Exception ignored){}
    runtime.send(command);hide();CircleOverlay.ensureBubble(app);
  }

  static void hide(){
    if(card==null)return;
    try{manager.removeView(card);}catch(Exception ignored){}
    card=null;manager=null;
  }
}
