package dev.zfold.assistant;

import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.*;
import java.text.DateFormat;
import java.util.Date;

/** Left session drawer shared by the full chat and the assistant window. */
final class SessionPicker implements PiRuntime.Listener {
  interface Host {void openModelSettings();void openCapabilities();void openSpeech();void openToolCenter();void openProviderAuth();}
  final View anchor;final PiRuntime runtime;final Ui.Theme theme;final int drawerWidth;
  final FrameLayout frame;final View scrim;final LinearLayout drawer,list;final TextView footer;Ui.Action create;PopupWindow popup;
  final Host host;
  boolean waiting;

  static void show(View anchor,PiRuntime runtime){new SessionPicker(anchor,runtime).open();}

  static void show(View anchor,PiRuntime runtime,Host host){new SessionPicker(anchor,runtime,host).open();}

  SessionPicker(View anchor,PiRuntime runtime){
    this(anchor,runtime,null);
  }

  SessionPicker(View anchor,PiRuntime runtime,Host host){
    this.anchor=anchor;this.runtime=runtime;Context context=anchor.getContext();theme=new Ui.Theme(context);
    this.host=host;
    drawerWidth=Math.min(Ui.dp(context,310),context.getResources().getDisplayMetrics().widthPixels-Ui.dp(context,44));
    frame=new FrameLayout(context);frame.setFocusableInTouchMode(true);frame.setOnKeyListener((v,key,event)->{if(key==KeyEvent.KEYCODE_BACK&&event.getAction()==KeyEvent.ACTION_UP){popup.dismiss();return true;}return key==KeyEvent.KEYCODE_BACK;});
    scrim=new View(context);scrim.setBackgroundColor(0x74000000);scrim.setContentDescription("关闭会话侧边栏");scrim.setClickable(true);frame.addView(scrim,new FrameLayout.LayoutParams(-1,-1));
    drawer=new LinearLayout(context);drawer.setOrientation(LinearLayout.VERTICAL);drawer.setBackgroundColor(theme.surface);drawer.setElevation(Ui.dp(context,16));drawer.setClickable(true);drawer.setContentDescription("会话侧边栏");
    FrameLayout.LayoutParams drawerParams=new FrameLayout.LayoutParams(drawerWidth,-1,Gravity.START);
    if(!(context instanceof MainActivity)){int statusId=context.getResources().getIdentifier("status_bar_height","dimen","android");drawerParams.topMargin=statusId>0?context.getResources().getDimensionPixelSize(statusId):Ui.dp(context,24);}
    frame.addView(drawer,drawerParams);
    drawer.setPadding(Ui.dp(context,14),Ui.dp(context,18),Ui.dp(context,14),Ui.dp(context,16));
    frame.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());drawer.setPadding(Ui.dp(context,14)+bars.left,Ui.dp(context,16)+bars.top,Ui.dp(context,14),Ui.dp(context,16)+bars.bottom);return insets;});

    LinearLayout brand=new LinearLayout(context);brand.setGravity(Gravity.CENTER_VERTICAL);brand.addView(Ui.agentMark(context,runtime.agentAvatar,Ui.dp(context,29)),new LinearLayout.LayoutParams(Ui.dp(context,29),Ui.dp(context,29)));
    TextView name=Ui.text(context,runtime.agentName,16,theme.ink);name.setTypeface(Typeface.create("sans-serif-medium",0));LinearLayout.LayoutParams nameParams=new LinearLayout.LayoutParams(0,-2,1);nameParams.leftMargin=Ui.dp(context,9);brand.addView(name,nameParams);
    Ui.Action close=new Ui.Action(context,R.drawable.ic_x,"","关闭会话侧边栏",theme.muted,Color.TRANSPARENT,()->popup.dismiss());brand.addView(close,new LinearLayout.LayoutParams(Ui.dp(context,48),Ui.dp(context,48)));drawer.addView(brand);
    TextView heading=Ui.text(context,"会话",12,theme.muted);heading.setLetterSpacing(.05f);LinearLayout.LayoutParams headingParams=new LinearLayout.LayoutParams(-1,-2);headingParams.setMargins(Ui.dp(context,9),Ui.dp(context,24),0,Ui.dp(context,9));drawer.addView(heading,headingParams);
    create=new Ui.Action(context,R.drawable.ic_chat_circle_text,"新对话","新建会话",theme.accent,theme.accentSoft,()->{if(waiting||runtime.busy)return;waiting=true;create.setEnabled(false);runtime.send(PiRuntime.json("type","new_session"));});drawer.addView(create,new LinearLayout.LayoutParams(-1,-2));
    Ui.Action toolsAction=new Ui.Action(context,R.drawable.ic_gear_six,"工作台","终端、连接、Skills 与手机能力",theme.ink,theme.surface,()->{popup.dismiss();WorkbenchActivity.open(context);});drawer.addView(toolsAction,new LinearLayout.LayoutParams(-1,-2));LinearLayout.LayoutParams toolsParams=(LinearLayout.LayoutParams)toolsAction.getLayoutParams();toolsParams.topMargin=Ui.dp(context,7);toolsAction.setLayoutParams(toolsParams);
    LinearLayout actions=new LinearLayout(context);actions.setOrientation(LinearLayout.VERTICAL);LinearLayout.LayoutParams actionsParams=new LinearLayout.LayoutParams(-1,-2);actionsParams.topMargin=Ui.dp(context,7);drawer.addView(actions,actionsParams);
    View divider=new View(context);divider.setBackgroundColor(theme.line);LinearLayout.LayoutParams dividerParams=new LinearLayout.LayoutParams(-1,Ui.dp(context,1));dividerParams.setMargins(Ui.dp(context,8),Ui.dp(context,16),Ui.dp(context,8),Ui.dp(context,7));drawer.addView(divider,dividerParams);
    ScrollView scroll=new ScrollView(context);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);list=new LinearLayout(context);list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);drawer.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    footer=Ui.text(context,"会话仅保存在这台手机",11,theme.muted);footer.setPadding(Ui.dp(context,9),Ui.dp(context,13),0,0);drawer.addView(footer);
    popup=new PopupWindow(frame,-1,-1,true);popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));popup.setOutsideTouchable(true);popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);popup.setOnDismissListener(()->runtime.unlisten(this));
    scrim.setOnClickListener(v->popup.dismiss());
  }

  void open(){
    Context context=anchor.getContext();
    try{
      ((InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(anchor.getWindowToken(),0);
      popup.showAtLocation(anchor,Gravity.TOP|Gravity.START,0,0);
      frame.requestFocus();
      if(Ui.motion()){drawer.setTranslationX(-drawerWidth);scrim.setAlpha(0);drawer.animate().translationX(0).setDuration(190).start();scrim.animate().alpha(1).setDuration(190).start();}
      runtime.listen(this);waiting=true;create.setEnabled(false);message("正在读取会话…");runtime.send(PiRuntime.json("type","sessions"));
    }catch(RuntimeException error){
      runtime.unlisten(this);
      if(context instanceof MainActivity)Toast.makeText(context,"会话侧边栏暂时无法打开",Toast.LENGTH_SHORT).show();
      else context.startActivity(new Intent(context,MainActivity.class).putExtra("open_sessions",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }
  }

  void message(String value){list.removeAllViews();TextView label=Ui.text(anchor.getContext(),value,13,theme.muted);label.setPadding(Ui.dp(anchor.getContext(),9),Ui.dp(anchor.getContext(),15),0,0);list.addView(label);}

  private LinearLayout option(Context context,int icon,String title,String detail,Runnable action){
    LinearLayout row=new LinearLayout(context);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(Ui.dp(context,10),Ui.dp(context,7),Ui.dp(context,8),Ui.dp(context,7));row.setMinimumHeight(Ui.dp(context,52));
    row.setBackground(Ui.ripple(context,Color.TRANSPARENT,12,theme.ink));
    ImageView image=new ImageView(context);image.setImageDrawable(Ui.icon(context,icon,theme.muted));row.addView(image,new LinearLayout.LayoutParams(Ui.dp(context,21),Ui.dp(context,21)));
    LinearLayout labels=new LinearLayout(context);labels.setOrientation(LinearLayout.VERTICAL);LinearLayout.LayoutParams labelParams=new LinearLayout.LayoutParams(0,-2,1);labelParams.leftMargin=Ui.dp(context,11);row.addView(labels,labelParams);
    TextView titleView=Ui.text(context,title,14,theme.ink);titleView.setTypeface(Typeface.create("sans-serif-medium",0));labels.addView(titleView);
    TextView detailView=Ui.text(context,detail,11,theme.muted);LinearLayout.LayoutParams detailParams=new LinearLayout.LayoutParams(-1,-2);detailParams.topMargin=Ui.dp(context,3);labels.addView(detailView,detailParams);
    row.setContentDescription(title+"，"+detail);row.setClickable(true);row.setFocusable(true);row.setOnClickListener(v->action.run());
    return row;
  }

  @Override public void event(JSONObject event){
    if(!popup.isShowing())return;
    switch(event.optString("type")){
      case "sessions":render(event.optJSONArray("items"));break;
      case "session_changed":popup.dismiss();break;
      case "error":if(waiting){waiting=false;create.setEnabled(true);message(Ui.errorMessage(event.optString("message")));}break;
    }
  }

  void render(JSONArray items){
    waiting=false;create.setEnabled(!runtime.busy);footer.setText(runtime.busy?"正在处理，完成后可切换会话":"会话仅保存在这台手机");list.removeAllViews();
    if(items==null||items.length()==0){message("还没有保存的对话");return;}
    Context context=anchor.getContext();
    for(int i=0;i<items.length();i++){
      JSONObject item=items.optJSONObject(i);if(item==null)continue;
      String id=item.optString("id"),name=item.optString("title","新对话");boolean current=item.optBoolean("current");
      LinearLayout row=new LinearLayout(context);row.setOrientation(LinearLayout.VERTICAL);row.setPadding(Ui.dp(context,12),Ui.dp(context,10),Ui.dp(context,12),Ui.dp(context,10));row.setMinimumHeight(Ui.dp(context,66));
      row.setBackground(Ui.ripple(context,current?theme.accentSoft:Color.TRANSPARENT,10,theme.ink));
      TextView title=Ui.text(context,name,14,current?theme.accent:theme.ink);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setTypeface(Typeface.create("sans-serif-medium",0));row.addView(title);
      String time=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(item.optLong("modified",System.currentTimeMillis())));
      TextView detail=Ui.text(context,current?"当前 · "+time:time,11,theme.muted);LinearLayout.LayoutParams detailParams=new LinearLayout.LayoutParams(-1,-2);detailParams.topMargin=Ui.dp(context,4);row.addView(detail,detailParams);
      row.setContentDescription(name+(current?"，当前会话":"，打开会话"));row.setClickable(true);row.setFocusable(true);
      row.setOnClickListener(v->{if(waiting||runtime.busy)return;if(current){popup.dismiss();return;}waiting=true;runtime.send(PiRuntime.json("type","open_session","id",id));});
      LinearLayout.LayoutParams rowParams=new LinearLayout.LayoutParams(-1,-2);rowParams.bottomMargin=Ui.dp(context,3);list.addView(row,rowParams);
    }
  }
}
