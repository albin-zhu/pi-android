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
  final View anchor;final PiRuntime runtime;final Ui.Theme theme;int drawerWidth;
  final FrameLayout frame;final View scrim;final LinearLayout drawer,list;final TextView footer;Ui.Action create,filterAction;PopupWindow popup;
  final Host host;
  boolean waiting;SwipeNavigationLayout nativeHost;private View[] expandedChrome;private boolean compactDrawer;
  private JSONArray records=new JSONArray();private int filter;private String pending;private android.app.AlertDialog dialog;
  private static final String[] FILTERS={"会话","已归档","最近删除"};

  static void show(View anchor,PiRuntime runtime){new SessionPicker(anchor,runtime).open();}

  static void show(View anchor,PiRuntime runtime,Host host){new SessionPicker(anchor,runtime,host).open();}

  SessionPicker(View anchor,PiRuntime runtime){
    this(anchor,runtime,null);
  }

  SessionPicker(View anchor,PiRuntime runtime,Host host){
    this.anchor=anchor;this.runtime=runtime;Context context=anchor.getContext();theme=new Ui.Theme(context);
    this.host=host;
    boolean small=context.getResources().getConfiguration().screenWidthDp<360||context.getResources().getConfiguration().screenHeightDp<400;compactDrawer=small;
    drawerWidth=Math.min(Ui.dp(context,310),context.getResources().getDisplayMetrics().widthPixels-Ui.dp(context,small?0:44));
    frame=new FrameLayout(context);frame.setFocusableInTouchMode(true);frame.setOnKeyListener((v,key,event)->{if(key==KeyEvent.KEYCODE_BACK&&event.getAction()==KeyEvent.ACTION_UP){dismiss();return true;}return key==KeyEvent.KEYCODE_BACK;});
    scrim=new View(context);scrim.setBackgroundColor(0x74000000);scrim.setContentDescription("关闭会话侧边栏");scrim.setClickable(true);frame.addView(scrim,new FrameLayout.LayoutParams(-1,-1));
    drawer=new LinearLayout(context);drawer.setOrientation(LinearLayout.VERTICAL);drawer.setBackgroundColor(theme.surface);drawer.setElevation(Ui.dp(context,16));drawer.setClickable(true);drawer.setContentDescription("会话侧边栏");
    FrameLayout.LayoutParams drawerParams=new FrameLayout.LayoutParams(drawerWidth,-1,Gravity.START);
    if(!(context instanceof MainActivity)){int statusId=context.getResources().getIdentifier("status_bar_height","dimen","android");drawerParams.topMargin=statusId>0?context.getResources().getDimensionPixelSize(statusId):Ui.dp(context,24);}
    frame.addView(drawer,drawerParams);
    int inset=Ui.dp(context,small?4:16);drawer.setPadding(Ui.dp(context,14),inset,Ui.dp(context,14),inset);
    frame.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());drawer.setPadding(Ui.dp(context,14)+bars.left,Ui.dp(context,compactDrawer?4:16)+bars.top,Ui.dp(context,14),Ui.dp(context,compactDrawer?4:16)+bars.bottom);return insets;});

    LinearLayout brand=new LinearLayout(context);brand.setGravity(Gravity.CENTER_VERTICAL);brand.addView(Ui.agentMark(context,runtime.agentAvatar,Ui.dp(context,29)),new LinearLayout.LayoutParams(Ui.dp(context,29),Ui.dp(context,29)));
    TextView name=Ui.text(context,runtime.agentName,16,theme.ink);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);name.setTypeface(Typeface.create("sans-serif-medium",0));LinearLayout.LayoutParams nameParams=new LinearLayout.LayoutParams(0,-2,1);nameParams.leftMargin=Ui.dp(context,9);brand.addView(name,nameParams);
    Ui.Action close=new Ui.Action(context,R.drawable.ic_x,"","关闭会话侧边栏",theme.muted,Color.TRANSPARENT,()->dismiss());brand.addView(close,new LinearLayout.LayoutParams(Ui.dp(context,48),Ui.dp(context,48)));drawer.addView(brand);
    LinearLayout controls=new LinearLayout(context);controls.setGravity(Gravity.CENTER_VERTICAL);drawer.addView(controls,new LinearLayout.LayoutParams(-1,-2));
    create=new Ui.Action(context,R.drawable.ic_chat_circle_text,"新对话","新建会话",theme.accent,theme.accentSoft,()->{if(waiting||runtime.busy)return;waiting=true;create.setEnabled(false);runtime.send(PiRuntime.json("type","new_session"));});controls.addView(create,new LinearLayout.LayoutParams(0,-2,1));
    filterAction=new Ui.Action(context,R.drawable.ic_sidebar,"会话 ▾","会话列表筛选",theme.muted,Color.TRANSPARENT,this::chooseFilter);filterAction.label.setTextSize(12);filterAction.label.setMaxWidth(Ui.dp(context,60));controls.addView(filterAction,new LinearLayout.LayoutParams(-2,-2));
    Ui.Action toolsAction=new Ui.Action(context,R.drawable.ic_gear_six,"工作台","终端、连接、Skills 与手机能力",theme.ink,theme.surface,()->{dismiss();WorkbenchActivity.open(context);});drawer.addView(toolsAction,new LinearLayout.LayoutParams(-1,-2));LinearLayout.LayoutParams toolsParams=(LinearLayout.LayoutParams)toolsAction.getLayoutParams();toolsParams.topMargin=Ui.dp(context,7);toolsAction.setLayoutParams(toolsParams);
    LinearLayout actions=new LinearLayout(context);actions.setOrientation(LinearLayout.VERTICAL);LinearLayout.LayoutParams actionsParams=new LinearLayout.LayoutParams(-1,-2);actionsParams.topMargin=Ui.dp(context,7);drawer.addView(actions,actionsParams);
    View divider=new View(context);divider.setBackgroundColor(theme.line);LinearLayout.LayoutParams dividerParams=new LinearLayout.LayoutParams(-1,Ui.dp(context,1));dividerParams.setMargins(Ui.dp(context,8),Ui.dp(context,16),Ui.dp(context,8),Ui.dp(context,7));drawer.addView(divider,dividerParams);
    ScrollView scroll=new ScrollView(context);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);list=new LinearLayout(context);list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);drawer.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    footer=Ui.text(context,"会话仅保存在这台手机",11,theme.muted);footer.setPadding(Ui.dp(context,9),Ui.dp(context,13),0,0);drawer.addView(footer);
    expandedChrome=new View[]{toolsAction,actions,divider,footer};
    if(small){toolsAction.setVisibility(View.GONE);actions.setVisibility(View.GONE);divider.setVisibility(View.GONE);footer.setVisibility(View.GONE);}
    popup=new PopupWindow(frame,-1,-1,true);popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));popup.setOutsideTouchable(true);popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);popup.setOnDismissListener(this::detached);
    scrim.setOnClickListener(v->dismiss());
  }

  void open(){
    Context context=anchor.getContext();
    try{
      ((InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(anchor.getWindowToken(),0);
      if(context instanceof MainActivity){nativeHost=((MainActivity)context).swipeNavigation;nativeHost.show(this);}
      else popup.showAtLocation(anchor,Gravity.TOP|Gravity.START,0,0);
      frame.requestFocus();
      if(nativeHost==null&&Ui.motion()){drawer.setTranslationX(-drawerWidth);scrim.setAlpha(0);drawer.animate().translationX(0).setDuration(190).start();scrim.animate().alpha(1).setDuration(190).start();}
      runtime.listen(this);waiting=true;create.setEnabled(false);message("正在读取会话…");runtime.send(PiRuntime.json("type","sessions"));
    }catch(RuntimeException error){
      runtime.unlisten(this);
      if(context instanceof MainActivity)Toast.makeText(context,"会话侧边栏暂时无法打开",Toast.LENGTH_SHORT).show();
      else context.startActivity(new Intent(context,MainActivity.class).putExtra("open_sessions",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }
  }

  void dismiss(){if(nativeHost!=null)nativeHost.close();else popup.dismiss();}
  void detached(){runtime.unlisten(this);nativeHost=null;pending=null;waiting=false;if(dialog!=null)dialog.dismiss();}
  void resize(int width,int height){
    if(width<=0)return;boolean small=width<Ui.dp(anchor.getContext(),360)||height<Ui.dp(anchor.getContext(),400);
    if(compactDrawer!=small){compactDrawer=small;for(View view:expandedChrome)view.setVisibility(small?View.GONE:View.VISIBLE);frame.requestApplyInsets();}
    drawerWidth=Math.min(Ui.dp(anchor.getContext(),310),width-Ui.dp(anchor.getContext(),small?0:44));
    FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)drawer.getLayoutParams();if(params.width!=drawerWidth){params.width=drawerWidth;drawer.setLayoutParams(params);}
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
    if(nativeHost!=null?nativeHost.currentPicker()!=this:!popup.isShowing())return;
    switch(event.optString("type")){
      case "sessions":render(event.optJSONArray("items"));break;
      case "session_changed":if(!"session_management".equals(event.optString("reason")))dismiss();break;
      case "session_managed":if(event.optString("requestId").equals(pending)){pending=null;render(records);toast("会话已更新");}break;
      case "session_error":if(pending==null||pending.equals(event.optString("requestId"))){pending=null;waiting=false;create.setEnabled(!runtime.busy);toast(event.optString("message"));if(records.length()==0)message(event.optString("message"));}break;
      case "turn_start":case "turn_end":create.setEnabled(!waiting&&!runtime.busy);break;
      case "error":if(waiting){waiting=false;create.setEnabled(true);message(Ui.errorMessage(event.optString("message")));}break;
    }
  }

  void render(JSONArray items){
    records=items==null?new JSONArray():items;waiting=pending!=null;create.setEnabled(!waiting&&!runtime.busy);footer.setText(runtime.busy?"任务执行中，完成后可管理会话":filter==2?"可恢复，彻底删除后无法找回":"会话仅保存在这台手机");list.removeAllViews();
    filterAction.setText(FILTERS[filter]+" ▾");filterAction.setContentDescription("会话列表筛选，当前"+FILTERS[filter]);
    Context context=anchor.getContext();
    for(int i=0;i<records.length();i++){
      JSONObject item=records.optJSONObject(i);if(item==null||category(item)!=filter)continue;
      String id=item.optString("id"),name=item.optString("title","新对话");boolean current=item.optBoolean("current");
      LinearLayout row=new LinearLayout(context);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(Ui.dp(context,12),Ui.dp(context,8),0,Ui.dp(context,8));row.setMinimumHeight(Ui.dp(context,66));
      row.setBackground(Ui.ripple(context,current?theme.accentSoft:Color.TRANSPARENT,10,theme.ink));
      LinearLayout labels=new LinearLayout(context);labels.setOrientation(LinearLayout.VERTICAL);row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
      TextView title=Ui.text(context,name,14,current?theme.accent:theme.ink);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setTypeface(Typeface.create("sans-serif-medium",0));labels.addView(title);
      String time=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(item.optLong("modified",System.currentTimeMillis())));
      TextView detail=Ui.text(context,(filter==0?(current?"当前 · ":""):FILTERS[filter]+" · ")+time,11,theme.muted);detail.setSingleLine(true);detail.setEllipsize(android.text.TextUtils.TruncateAt.END);LinearLayout.LayoutParams detailParams=new LinearLayout.LayoutParams(-1,-2);detailParams.topMargin=Ui.dp(context,4);labels.addView(detail,detailParams);
      row.setContentDescription(name+(filter!=0?"，管理会话":current?"，当前会话":"，打开会话"));row.setClickable(true);row.setFocusable(true);
      row.setOnClickListener(v->{if(waiting||runtime.busy)return;if(filter!=0){manage(item);return;}if(current){dismiss();return;}waiting=true;runtime.send(PiRuntime.json("type","open_session","id",id));});
      row.setOnLongClickListener(v->{manage(item);return true;});
      row.addView(new Ui.Action(context,R.drawable.ic_dots_three,"","管理会话："+name,theme.muted,Color.TRANSPARENT,()->manage(item)),new LinearLayout.LayoutParams(Ui.dp(context,48),Ui.dp(context,48)));
      LinearLayout.LayoutParams rowParams=new LinearLayout.LayoutParams(-1,-2);rowParams.bottomMargin=Ui.dp(context,3);list.addView(row,rowParams);
    }
    if(list.getChildCount()==0)message(filter==0?"还没有保存的对话":filter==1?"没有已归档的会话":"最近删除为空");
  }
  private static int category(JSONObject item){return item.optBoolean("deleted")?2:item.optBoolean("archived")?1:0;}
  private void toast(String message){Toast.makeText(anchor.getContext(),message,Toast.LENGTH_SHORT).show();}
  private boolean activityHost(){if(anchor.getContext() instanceof android.app.Activity)return true;dismiss();anchor.getContext().startActivity(new Intent(anchor.getContext(),MainActivity.class).putExtra("open_sessions",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));return false;}
  private void show(android.app.AlertDialog next){if(dialog!=null)dialog.dismiss();dialog=next;next.setOnDismissListener(d->{if(dialog==next)dialog=null;});next.show();}
  private void chooseFilter(){
    if(waiting||!activityHost())return;int[] counts=new int[3];for(int i=0;i<records.length();i++){JSONObject item=records.optJSONObject(i);if(item!=null)counts[category(item)]++;}
    String[] choices=new String[3];for(int i=0;i<3;i++)choices[i]=FILTERS[i]+"（"+counts[i]+"）";
    show(new android.app.AlertDialog.Builder(anchor.getContext()).setTitle("会话列表").setSingleChoiceItems(choices,filter,(d,which)->{filter=which;d.dismiss();render(records);}).setNegativeButton("取消",null).create());
  }
  private boolean canManage(JSONObject item){
    if(waiting)return false;
    if(runtime.busy){toast("任务执行中，完成或停止后才能管理会话");return false;}
    VoiceTurnController voice=MainActivity.activeVoice();if(item.optString("id").equals(runtime.sessionId)&&voice!=null&&voice.sessionActive()){toast("请先结束当前语音通话再管理这个会话");return false;}
    return activityHost();
  }
  private void manage(JSONObject item){
    if(!canManage(item))return;
    String[] actions=item.optBoolean("deleted")?new String[]{"恢复到会话列表","彻底删除"}:new String[]{"重命名",item.optBoolean("archived")?"恢复到会话列表":"归档","删除"};
    String title=item.optString("title","新对话");if(title.codePointCount(0,title.length())>24)title=title.substring(0,title.offsetByCodePoints(0,24))+"…";
    show(new android.app.AlertDialog.Builder(anchor.getContext()).setTitle(title).setItems(actions,(d,which)->{
      if(item.optBoolean("deleted")){if(which==0)send(item,"restore",null);else confirmDelete(item,true);}
      else if(which==0)rename(item);else if(which==1)send(item,item.optBoolean("archived")?"restore":"archive",null);else confirmDelete(item,false);
    }).setNegativeButton("取消",null).create());
  }
  private void rename(JSONObject item){
    Context context=anchor.getContext();EditText name=new EditText(context);name.setSingleLine(true);name.setText(item.optString("title"));name.setSelectAllOnFocus(true);name.setContentDescription("会话名称");
    LinearLayout body=new LinearLayout(context);body.setPadding(Ui.dp(context,20),0,Ui.dp(context,20),0);body.addView(name,new LinearLayout.LayoutParams(-1,-2));
    android.app.AlertDialog edit=new android.app.AlertDialog.Builder(context).setTitle("重命名会话").setView(body).setPositiveButton("保存",null).setNegativeButton("取消",null).create();show(edit);
    edit.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String value=name.getText().toString().trim();if(value.isEmpty()||value.codePointCount(0,value.length())>80){name.setError("请输入 1–80 个字符");return;}if(canManage(item)){edit.dismiss();send(item,"rename",value);}});
    name.requestFocus();edit.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
  }
  private void confirmDelete(JSONObject item,boolean permanent){
    show(new android.app.AlertDialog.Builder(anchor.getContext()).setTitle(permanent?"彻底删除这个会话？":"删除这个会话？")
      .setMessage("“"+item.optString("title","新对话")+"”\n\n"+(permanent?"本地聊天记录将永久删除，无法恢复。":"会话将移到最近删除，之后可以恢复。")+"工作区文件不会被删除。")
      .setNegativeButton("取消",null).setPositiveButton(permanent?"彻底删除":"删除",(d,w)->send(item,permanent?"purge":"delete",null)).create());
  }
  private void send(JSONObject item,String action,String name){
    if(!canManage(item))return;pending=java.util.UUID.randomUUID().toString();waiting=true;create.setEnabled(false);
    JSONObject command=PiRuntime.json("type","session_manage","id",item.optString("id"),"action",action,"requestId",pending,"confirm",action.equals("delete")||action.equals("purge"));
    try{if(name!=null)command.put("name",name);}catch(JSONException ignored){}runtime.send(command);
    String request=pending;anchor.postDelayed(()->{if(request.equals(pending)){pending=null;waiting=false;create.setEnabled(!runtime.busy);toast("操作结果尚未确认，请重新打开列表查看");}},10000);
  }
}
