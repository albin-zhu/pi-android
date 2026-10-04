package dev.zfold.assistant;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.app.AlertDialog;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local .pi/skills manager backed by the embedded Pi runtime. */
final class SkillManager implements PiRuntime.Listener {
  private final View anchor;private final PiRuntime runtime;private final Ui.Theme theme;
  private FrameLayout frame;private LinearLayout list;private TextView footer;private PopupWindow popup;

  static void show(View anchor,PiRuntime runtime){new SkillManager(anchor,runtime).open();}

  private SkillManager(View anchor,PiRuntime runtime){this.anchor=anchor;this.runtime=runtime;theme=new Ui.Theme(anchor.getContext());}

  private void open(){
    Context context=anchor.getContext();
    frame=new FrameLayout(context);frame.setFocusableInTouchMode(true);
    frame.setOnKeyListener((v,key,event)->{if(key==KeyEvent.KEYCODE_BACK&&event.getAction()==KeyEvent.ACTION_UP){close();return true;}return key==KeyEvent.KEYCODE_BACK;});
    View scrim=new View(context);scrim.setBackgroundColor(0x74000000);scrim.setContentDescription("关闭 Skill 管理");scrim.setClickable(true);scrim.setOnClickListener(v->close());frame.addView(scrim,new FrameLayout.LayoutParams(-1,-1));
    LinearLayout drawer=new LinearLayout(context);drawer.setOrientation(LinearLayout.VERTICAL);drawer.setBackgroundColor(theme.surface);drawer.setElevation(Ui.dp(context,18));drawer.setClickable(true);
    int width=Math.min(Ui.dp(context,480),context.getResources().getDisplayMetrics().widthPixels-Ui.dp(context,36));
    frame.addView(drawer,new FrameLayout.LayoutParams(width,-1,Gravity.END));
    int statusId=context.getResources().getIdentifier("status_bar_height","dimen","android");
    int top=statusId>0?context.getResources().getDimensionPixelSize(statusId):Ui.dp(context,24);
    frame.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());drawer.setPadding(Ui.dp(context,16)+bars.left,bars.top+Ui.dp(context,16),Ui.dp(context,16)+bars.right,bars.bottom+Ui.dp(context,16));return insets;});
    drawer.setPadding(Ui.dp(context,16),top+Ui.dp(context,16),Ui.dp(context,16),Ui.dp(context,16));
    LinearLayout brand=new LinearLayout(context);brand.setGravity(Gravity.CENTER_VERTICAL);
    TextView title=Ui.text(context,"本地 Skills",18,theme.ink);title.setTypeface(Typeface.create("sans-serif-medium",0));title.setLetterSpacing(-.01f);brand.addView(title,new LinearLayout.LayoutParams(0,-2,1));
    brand.addView(new Ui.Action(context,R.drawable.ic_arrow_clockwise,"","刷新本地 Skills",theme.muted,Color.TRANSPARENT,this::refresh),new LinearLayout.LayoutParams(Ui.dp(context,48),Ui.dp(context,48)));
    brand.addView(new Ui.Action(context,R.drawable.ic_x,"","关闭 Skill 管理",theme.muted,Color.TRANSPARENT,this::close),new LinearLayout.LayoutParams(Ui.dp(context,48),Ui.dp(context,48)));
    drawer.addView(brand);
    TextView help=Ui.text(context,"项目 Skill 存放在项目 .pi/skills，全局 Skill 对所有项目生效。实际路径见下方目录信息。",12,theme.muted);help.setLineSpacing(Ui.dp(context,3),1f);LinearLayout.LayoutParams helpParams=new LinearLayout.LayoutParams(-1,-2);helpParams.setMargins(0,Ui.dp(context,10),0,0);drawer.addView(help,helpParams);
    LinearLayout buttons=new LinearLayout(context);buttons.setOrientation(LinearLayout.HORIZONTAL);
    buttons.addView(new Ui.Action(context,R.drawable.ic_chat_circle_text,"新建项目 Skill","写入 workspace/.pi/skills",theme.accent,theme.accentSoft,()->edit(null,"project")),new LinearLayout.LayoutParams(0,-2,1));
    LinearLayout.LayoutParams firstParams=(LinearLayout.LayoutParams)buttons.getChildAt(0).getLayoutParams();firstParams.rightMargin=Ui.dp(context,7);buttons.getChildAt(0).setLayoutParams(firstParams);
    buttons.addView(new Ui.Action(context,R.drawable.ic_arrows_out_simple,"新建全局","写入 pi/skills",theme.ink,theme.field,()->edit(null,"global")),new LinearLayout.LayoutParams(0,-2,1));
    drawer.addView(buttons,new LinearLayout.LayoutParams(-1,-2));LinearLayout.LayoutParams buttonParams=(LinearLayout.LayoutParams)buttons.getLayoutParams();buttonParams.topMargin=Ui.dp(context,14);buttons.setLayoutParams(buttonParams);
    TextView heading=Ui.text(context,"已安装",12,theme.muted);heading.setLetterSpacing(.05f);LinearLayout.LayoutParams headingParams=new LinearLayout.LayoutParams(-1,-2);headingParams.setMargins(0,Ui.dp(context,18),0,Ui.dp(context,8));drawer.addView(heading,headingParams);
    ScrollView scroll=new ScrollView(context);scroll.setVerticalScrollBarEnabled(false);list=new LinearLayout(context);list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);drawer.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    footer=Ui.text(context,"正在读取 .pi/skills…",11,theme.muted);footer.setPadding(0,Ui.dp(context,12),0,0);drawer.addView(footer);
    popup=new PopupWindow(frame,-1,-1,true);popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));popup.setOutsideTouchable(true);popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);popup.setOnDismissListener(()->runtime.unlisten(this));
    try{
      ((android.view.inputmethod.InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(anchor.getWindowToken(),0);
      popup.showAtLocation(anchor,Gravity.TOP|Gravity.END,0,0);frame.requestFocus();runtime.listen(this);refresh();
    }catch(RuntimeException error){
      runtime.unlisten(this);Toast.makeText(context,"Skill 管理无法打开",Toast.LENGTH_SHORT).show();
    }
  }

  private void refresh(){runtime.send(PiRuntime.json("type","skills_manage","action","list"));}
  private void close(){if(popup!=null)popup.dismiss();}

  @Override public void event(JSONObject event){
    if(popup==null||!popup.isShowing())return;
    String type=event.optString("type");
    if(type.equals("skills"))render(event);
    else if(type.equals("error")){
      String message=Ui.errorMessage(event.optString("message"));
      footer.setText("操作未完成："+message);
      Toast.makeText(anchor.getContext(),message,Toast.LENGTH_LONG).show();
    }
  }

  private void render(JSONObject event){
    JSONArray items=event.optJSONArray("skills");list.removeAllViews();
    if(items==null||items.length()==0){
      TextView empty=Ui.text(anchor.getContext(),"还没有本地 Skill。点击“新建 Skill”创建第一个。",13,theme.muted);empty.setPadding(0,Ui.dp(anchor.getContext(),16),0,0);list.addView(empty);
    }
    Context context=anchor.getContext();
    for(int i=0;items!=null&&i<items.length();i++){
      JSONObject item=items.optJSONObject(i);if(item==null)continue;
      LinearLayout card=new LinearLayout(context);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(Ui.dp(context,13),Ui.dp(context,11),Ui.dp(context,9),Ui.dp(context,11));
      card.setBackground(Ui.card(context,theme.surface,16,theme.line));card.setElevation(Ui.dp(context,2));
      LinearLayout head=new LinearLayout(context);head.setGravity(Gravity.CENTER_VERTICAL);
      TextView name=Ui.text(context,item.optString("name","skill"),14,theme.ink);name.setTypeface(Typeface.create("sans-serif-medium",0));head.addView(name,new LinearLayout.LayoutParams(0,-2,1));
      TextView scope=Ui.text(context,"project".equals(item.optString("scope"))?"工作区":"全局",11,theme.accent);head.addView(scope);
      card.addView(head);
      TextView desc=Ui.text(context,item.optString("description","没有描述"),12,theme.muted);desc.setMaxLines(3);desc.setEllipsize(TextUtils.TruncateAt.END);desc.setLineSpacing(Ui.dp(context,3),1f);LinearLayout.LayoutParams descParams=new LinearLayout.LayoutParams(-1,-2);descParams.topMargin=Ui.dp(context,6);card.addView(desc,descParams);
      LinearLayout actions=new LinearLayout(context);actions.setGravity(Gravity.END);actions.setOrientation(LinearLayout.HORIZONTAL);
      actions.addView(new Ui.Action(context,R.drawable.ic_copy,"编辑","编辑本地 Skill",theme.ink,Color.TRANSPARENT,()->edit(item,item.optString("scope","project"))),new LinearLayout.LayoutParams(-2,Ui.dp(context,48)));
      actions.addView(new Ui.Action(context,R.drawable.ic_x,"","删除 "+item.optString("name","skill"),theme.error,Color.TRANSPARENT,()->confirmDelete(item)),new LinearLayout.LayoutParams(Ui.dp(context,48),Ui.dp(context,48)));
      card.addView(actions,new LinearLayout.LayoutParams(-1,-2));
      list.addView(card,new LinearLayout.LayoutParams(-1,-2));LinearLayout.LayoutParams params=(LinearLayout.LayoutParams)card.getLayoutParams();params.topMargin=i==0?0:Ui.dp(context,8);card.setLayoutParams(params);
    }
    JSONArray diagnostics=event.optJSONArray("diagnostics");
    JSONObject roots=event.optJSONObject("roots");
    if(roots!=null)footer.setText("工作区 "+roots.optString("project")+"\n全局 "+roots.optString("global")+"\n诊断 "+(diagnostics==null?0:diagnostics.length())+" 项");
  }

  private void edit(JSONObject item,String scope){
    Context context=anchor.getContext();LinearLayout body=new LinearLayout(context);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(Ui.dp(context,24),Ui.dp(context,10),Ui.dp(context,24),0);
    EditText name=field(body,"名称（小写字母、数字、连字符）",item==null?"":item.optString("name"));
    EditText description=field(body,"描述：它做什么，什么时候用",item==null?"":item.optString("description"));
    TextView contentLabel=Ui.text(context,"SKILL.md 内容",12,theme.muted);contentLabel.setPadding(0,Ui.dp(context,16),0,Ui.dp(context,4));body.addView(contentLabel);
    EditText content=new EditText(context);content.setMinLines(8);content.setMaxLines(20);content.setGravity(Gravity.TOP);content.setTextSize(13);content.setTextColor(theme.ink);
    content.setBackground(Ui.shape(context,theme.field,12,theme.line));content.setPadding(Ui.dp(context,12),Ui.dp(context,12),Ui.dp(context,12),Ui.dp(context,12));body.addView(content,new LinearLayout.LayoutParams(-1,Ui.dp(context,260)));
    PiRuntime.Listener reader=new PiRuntime.Listener(){
      @Override public void event(JSONObject event){
        if(event.optString("type").equals("skill")){
          runtime.unlisten(this);description.setText(event.optString("description",""));content.setText(event.optString("content",""));
        }
      }
    };
    String selectedScope=item==null?scope:item.optString("scope","project");
    if(item!=null)runtime.send(PiRuntime.json("type","skills_manage","action","read","scope",selectedScope,"name",item.optString("name")));
    if(item!=null)runtime.listen(reader);
    AlertDialog sheet=new AlertDialog.Builder(context).setTitle(item==null?"新建 "+("project".equals(selectedScope)?"工作区":"全局")+" Skill":"编辑 "+item.optString("name")).setView(body)
      .setNegativeButton("取消",null)
      .setPositiveButton("保存",(clicked,which)->{
        runtime.send(PiRuntime.json("type","skills_manage","action","write","scope",selectedScope,"name",name.getText().toString().trim(),"description",description.getText().toString().trim(),"content",content.getText().toString()));
      }).create();
    sheet.setOnDismissListener(ignored->runtime.unlisten(reader));
    sheet.show();
  }

  private void confirmDelete(JSONObject item){
    new AlertDialog.Builder(anchor.getContext()).setTitle("删除 Skill").setMessage("删除 "+item.optString("name")+" 及其目录？此操作不可恢复。")
      .setNegativeButton("取消",null)
      .setPositiveButton("删除",(dialog,which)->runtime.send(PiRuntime.json("type","skills_manage","action","delete","scope",item.optString("scope","project"),"name",item.optString("name")))).show();
  }

  private EditText field(LinearLayout body,String label,String value){
    TextView view=Ui.text(anchor.getContext(),label,12,theme.muted);view.setPadding(0,Ui.dp(anchor.getContext(),14),0,Ui.dp(anchor.getContext(),4));body.addView(view);
    EditText input=new EditText(anchor.getContext());input.setSingleLine(true);input.setTextSize(14);input.setTextColor(theme.ink);
    input.setBackground(Ui.shape(anchor.getContext(),theme.field,12,theme.line));input.setPadding(Ui.dp(anchor.getContext(),12),Ui.dp(anchor.getContext(),12),Ui.dp(anchor.getContext(),12),Ui.dp(anchor.getContext(),12));input.setText(value);
    body.addView(input,new LinearLayout.LayoutParams(-1,-2));return input;
  }
}
