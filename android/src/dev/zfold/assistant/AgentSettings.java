package dev.zfold.assistant;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

/** Edit the local agent identity and system prompt backed by /storage/pi/.pi/agent. */
final class AgentSettings implements PiRuntime.Listener {
  private final View anchor;private final PiRuntime runtime;private final Ui.Theme theme;
  private EditText name,prompt;private ImageView avatarPreview;private String avatar;
  private PopupWindow popup;private TextView status;

  static void show(View anchor,PiRuntime runtime){new AgentSettings(anchor,runtime).open();}

  private AgentSettings(View anchor,PiRuntime runtime){this.anchor=anchor;this.runtime=runtime;theme=new Ui.Theme(anchor.getContext());}

  private void open(){
    Context context=anchor.getContext();
    FrameLayout frame=new FrameLayout(context);frame.setFocusableInTouchMode(true);
    final PopupWindow[] handle=new PopupWindow[1];
    frame.setOnKeyListener((v,key,event)->{if(key==KeyEvent.KEYCODE_BACK&&event.getAction()==KeyEvent.ACTION_UP){handle[0].dismiss();return true;}return key==KeyEvent.KEYCODE_BACK;});
    LinearLayout sheet=new LinearLayout(context);sheet.setOrientation(LinearLayout.VERTICAL);sheet.setPadding(Ui.dp(context,16),Ui.dp(context,16),Ui.dp(context,16),Ui.dp(context,12));sheet.setBackground(Ui.shape(context,theme.surface,20,theme.line));sheet.setClickable(true);
    int width=Math.min(Ui.dp(context,420),context.getResources().getDisplayMetrics().widthPixels-Ui.dp(context,28));
    LinearLayout head=new LinearLayout(context);head.setGravity(Gravity.CENTER_VERTICAL);
    TextView title=Ui.text(context,"Agent 身份",17,theme.ink);title.setTypeface(Typeface.create("sans-serif-medium",0));head.addView(title,new LinearLayout.LayoutParams(0,-2,1));
    head.addView(new Ui.Action(context,R.drawable.ic_x,"","关闭 Agent 身份设置",theme.muted,Color.TRANSPARENT,()->handle[0].dismiss()),new LinearLayout.LayoutParams(Ui.dp(context,48),Ui.dp(context,48)));
    sheet.addView(head);
    TextView help=Ui.text(context,"名字会用于聊天标题、自我介绍和系统提示词。system.md 保存完整提示词，保存后立即重载。",12,theme.muted);help.setLineSpacing(Ui.dp(context,3),1f);LinearLayout.LayoutParams helpParams=new LinearLayout.LayoutParams(-1,-2);helpParams.topMargin=Ui.dp(context,9);sheet.addView(help,helpParams);
    LinearLayout avatarRow=new LinearLayout(context);avatarRow.setGravity(Gravity.CENTER_VERTICAL);LinearLayout.LayoutParams avatarRowParams=new LinearLayout.LayoutParams(-1,-2);avatarRowParams.topMargin=Ui.dp(context,14);sheet.addView(avatarRow,avatarRowParams);
    avatarPreview=new ImageView(context);avatarPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);avatarPreview.setBackground(Ui.shape(context,theme.field,31,theme.line));avatarPreview.setClipToOutline(true);
    setPreview(runtime.agentAvatar);avatarRow.addView(avatarPreview,new LinearLayout.LayoutParams(Ui.dp(context,62),Ui.dp(context,62)));
    LinearLayout avatarActions=new LinearLayout(context);avatarActions.setOrientation(LinearLayout.VERTICAL);LinearLayout.LayoutParams avatarActionParams=new LinearLayout.LayoutParams(0,-2,1);avatarActionParams.leftMargin=Ui.dp(context,12);avatarRow.addView(avatarActions,avatarActionParams);
    avatarActions.addView(new Ui.Action(context,R.drawable.ic_image,"选择头像","从本机选择 PNG 或 JPEG 图片",theme.accent,theme.accentSoft,this::pickAvatar),new LinearLayout.LayoutParams(-1,-2));
    avatarActions.addView(new Ui.Action(context,R.drawable.ic_x,"移除头像","恢复默认 Agent 图标",theme.muted,Color.TRANSPARENT,()->{avatar=null;setPreview(null);}),new LinearLayout.LayoutParams(-1,-2));
    name=input(sheet,"Agent 名字",runtime.agentName,false);
    TextView promptLabel=Ui.text(context,"系统提示词 / system.md",12,theme.muted);promptLabel.setPadding(0,Ui.dp(context,16),0,Ui.dp(context,4));sheet.addView(promptLabel);
    prompt=new EditText(context);prompt.setMinLines(10);prompt.setMaxLines(18);prompt.setGravity(Gravity.TOP);prompt.setTextSize(13);prompt.setTextColor(theme.ink);prompt.setText(runtime.agentPrompt);prompt.setBackground(Ui.shape(context,theme.field,14,theme.line));prompt.setPadding(Ui.dp(context,12),Ui.dp(context,12),Ui.dp(context,12),Ui.dp(context,12));sheet.addView(prompt,new LinearLayout.LayoutParams(-1,Ui.dp(context,260)));
    status=Ui.text(context,"",12,theme.muted);status.setPadding(0,Ui.dp(context,10),0,0);sheet.addView(status);
    LinearLayout buttons=new LinearLayout(context);buttons.setOrientation(LinearLayout.HORIZONTAL);LinearLayout.LayoutParams buttonsParams=new LinearLayout.LayoutParams(-1,-2);buttonsParams.topMargin=Ui.dp(context,10);sheet.addView(buttons,buttonsParams);
    buttons.addView(new Ui.Action(context,R.drawable.ic_arrow_clockwise,"恢复默认","恢复默认名字与提示词模板，不移除头像",theme.ink,theme.field,()->{name.setText("Zfold");prompt.setText(runtime.agentDefaultPrompt.isEmpty()?"{{AGENT_NAME}}":runtime.agentDefaultPrompt);}),new LinearLayout.LayoutParams(0,-2,1));
    buttons.addView(new Ui.Action(context,R.drawable.ic_check,"保存并重载","写入 system.md 并热重载",theme.onAccent,theme.accent,this::save),new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams saveParams=(LinearLayout.LayoutParams)buttons.getChildAt(1).getLayoutParams();saveParams.leftMargin=Ui.dp(context,8);
    frame.addView(sheet,new FrameLayout.LayoutParams(width,-2));
    handle[0]=new PopupWindow(frame,-2,-2,true);handle[0].setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));handle[0].setOutsideTouchable(true);
    handle[0].setOnDismissListener(()->runtime.unlisten(this));
    runtime.listen(this);
    try{handle[0].showAtLocation(anchor,Gravity.CENTER,0,0);}catch(RuntimeException error){runtime.unlisten(this);Toast.makeText(context,"Agent 设置无法打开",Toast.LENGTH_SHORT).show();return;}
    runtime.send(PiRuntime.json("type","agent_identity","action","get"));
  }

  private void save(){
    String nextName=name.getText().toString().trim(),nextPrompt=prompt.getText().toString().trim();
    if(nextName.isEmpty()){name.setError("填写 Agent 名字");return;}
    status.setText("正在保存 system.md…");
    JSONObject command=PiRuntime.json("type","agent_identity","action","save","name",nextName,"prompt",nextPrompt);
    try{if(avatar!=null)command.put("avatar",avatar);if(avatar==null&&avatarPreview.getTag()==Boolean.TRUE)command.put("removeAvatar",true);}catch(Exception ignored){}
    runtime.send(command);
  }

  private void pickAvatar(){
    FilePickActivity.request(anchor.getContext(),uris->{
      if(uris.isEmpty())return;
      AvatarEditorActivity.edit(anchor.getContext(),uris.get(0),encoded->{
        avatar=encoded;avatarPreview.post(()->{setPreview(encoded);avatarPreview.setTag(Boolean.FALSE);});
      });
    });
  }

  private void setPreview(String encoded){
    Bitmap bitmap=Ui.thumbnail(encoded,128);
    if(bitmap==null){avatarPreview.setImageDrawable(Ui.icon(anchor.getContext(),R.drawable.ic_chat_circle_text,theme.muted));avatarPreview.setTag(Boolean.TRUE);}
    else{avatarPreview.setImageBitmap(bitmap);avatarPreview.setTag(Boolean.FALSE);}
  }


  private EditText input(LinearLayout body,String label,String value,boolean secret){
    TextView view=Ui.text(anchor.getContext(),label,12,theme.muted);view.setPadding(0,Ui.dp(anchor.getContext(),14),0,Ui.dp(anchor.getContext(),4));body.addView(view);
    EditText edit=new EditText(anchor.getContext());edit.setSingleLine(true);edit.setTextSize(14);edit.setTextColor(theme.ink);edit.setText(value);edit.setInputType(secret?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT);edit.setBackground(Ui.shape(anchor.getContext(),theme.field,12,theme.line));edit.setPadding(Ui.dp(anchor.getContext(),12),Ui.dp(anchor.getContext(),12),Ui.dp(anchor.getContext(),12),Ui.dp(anchor.getContext(),12));body.addView(edit,new LinearLayout.LayoutParams(-1,-2));return edit;
  }

  @Override public void event(org.json.JSONObject event){
    if(popup==null||!popup.isShowing())return;
    String type=event.optString("type");
    if(type.equals("agent_identity")){
      runtime.agentName=event.optString("name","Zfold");runtime.agentAvatar=event.isNull("avatar")?null:event.optString("avatar",null);runtime.agentPrompt=event.optString("prompt");
      name.setText(runtime.agentName);prompt.setText(runtime.agentPrompt);setPreview(runtime.agentAvatar);status.setText("已同步 system.md");
    }else if(type.equals("agent_error")){status.setText(Ui.errorMessage(event.optString("message")));Toast.makeText(anchor.getContext(),Ui.errorMessage(event.optString("message")),Toast.LENGTH_LONG).show();}
  }
}
