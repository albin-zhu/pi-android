package dev.zfold.assistant;
import android.app.*;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.*;
public final class UsageActivity extends Activity {
  @Override public void onCreate(Bundle state){super.onCreate(state);render();}
  @Override protected void onResume(){super.onResume();render();}
  void render(){
    Ui.Theme theme=new Ui.Theme(this);ScrollView scroll=new ScrollView(this);LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(Ui.dp(this,24),Ui.dp(this,24),Ui.dp(this,24),Ui.dp(this,24));body.setBackgroundColor(theme.page);scroll.addView(body);setContentView(scroll);
    body.addView(new Ui.Action(this,R.drawable.ic_x,"关闭","关闭用量详情",theme.muted,android.graphics.Color.TRANSPARENT,this::finish));
    body.addView(Ui.text(this,"模型用量与本地预算",22,theme.ink));
    TextView note=Ui.text(this,"仅统计安装此版本后，橙橙收到的模型用量回执。按模型家族归类，含网关调用；不代表 OpenAI、xAI 或 Kimi 的实际账户余额。\n\n预算按本机自然月重置，以 tokens 为单位。订阅额度和 API 账户余额尚未接入。",13,theme.muted);note.setPadding(0,Ui.dp(this,14),0,Ui.dp(this,14));body.addView(note);
    for(int i=0;i<UsageStore.FAMILIES.length;i++){
      final String family=UsageStore.FAMILIES[i],name=UsageStore.NAMES[i];
      TextView title=Ui.text(this,name,18,theme.ink);title.setPadding(0,Ui.dp(this,18),0,Ui.dp(this,8));body.addView(title);
      body.addView(Ui.text(this,UsageStore.line(this,family),16,theme.ink));
      long today=UsageStore.prefs(this).getLong(UsageStore.day()+"."+family+".tokens",0),unknown=UsageStore.prefs(this).getLong(UsageStore.month()+"."+family+".unknown",0);
      body.addView(Ui.text(this,"今日已用 "+today+" · 本月已用 "+UsageStore.used(this,family)+" tokens"+(unknown>0?"\n另有 "+unknown+" 条回复无用量回执，未计入 tokens":""),12,theme.muted));
      body.addView(new Ui.Action(this,R.drawable.ic_gear_six,"设置本月 Token 预算","设置"+name+"本地预算",theme.accent,theme.accentSoft,()->editBudget(family,name)));
    }
    body.addView(new Ui.Action(this,R.drawable.ic_arrow_clockwise,"刷新本地统计","读取本机记录，不查询官方账户",theme.muted,android.graphics.Color.TRANSPARENT,()->{UsageWidget.updateAll(this);render();}));
  }
  void editBudget(String family,String name){
    EditText input=new EditText(this);input.setSingleLine(true);input.setInputType(InputType.TYPE_CLASS_NUMBER);long budget=UsageStore.budget(this,family);if(budget>0)input.setText(Long.toString(budget));input.setHint("Token 数；0 表示不设预算");input.setContentDescription(name+"本地每月Token预算");
    LinearLayout body=new LinearLayout(this);body.setPadding(Ui.dp(this,20),0,Ui.dp(this,20),0);body.addView(input,new LinearLayout.LayoutParams(-1,-2));
    AlertDialog dialog=new AlertDialog.Builder(this).setTitle(name+" · 本地每月预算").setView(body).setNegativeButton("取消",null).setPositiveButton("保存",null).create();dialog.show();
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{long value=Long.parseLong(input.getText().toString().trim());if(value<0||value>1000000000000L)throw new IllegalArgumentException();
      if(!UsageStore.prefs(this).edit().putLong("budget."+family,value).commit()){input.setError("保存失败，请重试");return;}dialog.dismiss();UsageWidget.updateAll(this);render();
    }catch(Exception e){input.setError("请输入 0–1000000000000 的整数");}});
  }
}
