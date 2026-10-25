package dev.zfold.assistant;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import org.json.*;

/** Task-bound form. Dismissing the view preserves the unanswered request and draft. */
public final class UserQuestionActivity extends NavigableActivity {
  private String requestId;private UserQuestions.Request rendered;private LinearLayout body;private TextView status;private Ui.Theme theme;
  private final ArrayList<EditText> inputs=new ArrayList<>();private final Runnable changed=this::refresh;
  static void show(Context context,String id){
    Intent intent=new Intent(context,UserQuestionActivity.class).putExtra("question_request_id",id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
    if(!(context instanceof Activity))intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);ActivityOptions options=ActivityOptions.makeBasic();
    if(context instanceof Activity&&((Activity)context).isInMultiWindowMode())options.setLaunchBounds(((Activity)context).getWindowManager().getCurrentWindowMetrics().getBounds());
    try{context.startActivity(intent,options.toBundle());}catch(RuntimeException error){Toast.makeText(context,"有问题等待回答，点通知继续",Toast.LENGTH_LONG).show();}
  }
  @Override protected void onCreate(Bundle saved){
    super.onCreate(saved);requestId=getIntent().getStringExtra("question_request_id");theme=new Ui.Theme(this);getWindow().setDecorFitsSystemWindows(false);getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    getWindow().setStatusBarColor(theme.page);getWindow().setNavigationBarColor(theme.page);
    getWindow().getDecorView().setSystemUiVisibility(theme.dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(theme.page);
    root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()),ime=insets.getInsets(WindowInsets.Type.ime());root.setPadding(bars.left,bars.top,bars.right,Math.max(bars.bottom,ime.bottom));return insets;});
    TextView title=Ui.text(this,"助手需要你回答",18,theme.ink);title.setPadding(dp(16),dp(12),dp(16),dp(12));root.addView(title);
    ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(16),0,dp(16),dp(12));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    status=Ui.text(this,"稍后回答会保留当前选择；跳过会明确告知助手。",12,theme.muted);status.setPadding(dp(16),dp(6),dp(16),dp(6));root.addView(status);
    LinearLayout controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER);controls.setPadding(dp(8),0,dp(8),dp(8));
    controls.addView(action("稍后","稍后回答，保留草稿",false,this::finish),new LinearLayout.LayoutParams(0,-2,1));
    controls.addView(action("跳过","跳过本次提问",false,()->submit(true)),new LinearLayout.LayoutParams(0,-2,1));
    controls.addView(action("提交","提交全部回答",true,()->submit(false)),new LinearLayout.LayoutParams(0,-2,1));root.addView(controls);setContentView(root);
  }
  private View action(String label,String description,boolean primary,Runnable run){Ui.Action button=new Ui.Action(this,R.drawable.ic_check,label,description,primary?theme.onAccent:theme.muted,primary?theme.accent:0,run);button.image.setVisibility(View.GONE);((LinearLayout.LayoutParams)button.label.getLayoutParams()).leftMargin=0;button.setPadding(dp(4),dp(6),dp(4),dp(6));return button;}
  private int dp(int value){return Ui.dp(this,value);}
  @Override protected void onStart(){super.onStart();UserQuestions.listen(changed);}
  @Override protected void onResume(){super.onResume();VoiceMiniOverlay.hide();}
  @Override protected void onStop(){UserQuestions.unlisten(changed);super.onStop();}
  @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);requestId=intent.getStringExtra("question_request_id");rendered=null;refresh();}
  private void refresh(){
    UserQuestions.Request request=UserQuestions.current();if(request==null||!request.id.equals(requestId)){finish();return;}
    if(rendered==request){for(int i=0;i<inputs.size();i++){String draft=request.drafts.get(i).text;if(!inputs.get(i).getText().toString().equals(draft)){inputs.get(i).setText(draft);inputs.get(i).setSelection(draft.length());}}return;}
    rendered=request;body.removeAllViews();inputs.clear();
    for(int i=0;i<request.questions.length();i++){
      final int index=i;JSONObject question=request.questions.optJSONObject(i);UserQuestions.Draft draft=request.drafts.get(i);
      LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(12),dp(12),dp(12),dp(12));card.setBackground(Ui.shape(this,theme.surface,16,0));LinearLayout.LayoutParams cardParams=new LinearLayout.LayoutParams(-1,-2);cardParams.bottomMargin=dp(12);body.addView(card,cardParams);
      String heading=(i+1)+" / "+request.questions.length()+(question.optString("header").isEmpty()?"":" · "+question.optString("header"));card.addView(Ui.text(this,heading,12,theme.muted));
      TextView prompt=Ui.text(this,question.optString("question"),16,theme.ink);prompt.setPadding(0,dp(6),0,dp(10));card.addView(prompt);
      JSONArray options=question.optJSONArray("options");boolean multi=question.optBoolean("multiSelect");RadioGroup radios=multi?null:new RadioGroup(this);if(radios!=null)card.addView(radios,new LinearLayout.LayoutParams(-1,-2));
      if(options!=null)for(int j=0;j<options.length();j++){
        final int optionIndex=j;JSONObject option=options.optJSONObject(j);String label=option.optString("label"),description=option.optString("description");
        CompoundButton choice=multi?new CheckBox(this):new RadioButton(this);choice.setId(View.generateViewId());choice.setText(label+(description.isEmpty()?"":"\n"+description));choice.setTextSize(14);choice.setTextColor(theme.ink);choice.setMinHeight(dp(48));choice.setContentDescription("问题 "+(index+1)+"："+label);choice.setChecked(draft.selected.contains(j));
        choice.setOnCheckedChangeListener((button,checked)->{if(checked){if(!multi)draft.selected.clear();draft.selected.add(optionIndex);}else draft.selected.remove(optionIndex);});
        choice.setOnClickListener(v->request.focus=index);
        if(multi)card.addView(choice,new LinearLayout.LayoutParams(-1,-2));else radios.addView(choice,new RadioGroup.LayoutParams(-1,-2));
      }
      EditText text=new EditText(this);text.setTextSize(15);text.setTextColor(theme.ink);text.setHint(options==null||options.length()==0?"填写回答":"补充或自行填写");text.setContentDescription("问题 "+(i+1)+" 的文字回答");text.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);text.setMinLines(2);text.setMaxLines(5);text.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4000)});text.setSaveEnabled(false);text.setText(draft.text);inputs.add(text);card.addView(text,new LinearLayout.LayoutParams(-1,-2));
      text.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);text.setOnFocusChangeListener((v,focused)->{if(focused)request.focus=index;});text.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence value,int start,int count,int after){}public void onTextChanged(CharSequence value,int start,int before,int count){draft.text=value.toString();}public void afterTextChanged(Editable value){}});
    }
    body.setFocusableInTouchMode(true);body.requestFocus();
  }
  private void submit(boolean declined){try{UserQuestions.submit(this,requestId,declined);finish();}catch(Exception error){status.setTextColor(theme.error);status.setText(error.getMessage());}}
  @Override public void onBackPressed(){navigateUp();}
}
