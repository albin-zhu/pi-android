package dev.zfold.assistant;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/** Compact composer palette for local slash commands. */
final class SlashSuggest {
  interface Target {boolean run(String name,String argument);}

  private SlashSuggest(){}

  static android.view.View attach(EditText input,Ui.Theme theme,Target target){
    Context context=input.getContext();
    LinearLayout list=new LinearLayout(context);list.setOrientation(LinearLayout.VERTICAL);
    ScrollView wrapper=new ScrollView(context);wrapper.setVerticalScrollBarEnabled(false);wrapper.setVisibility(LinearLayout.GONE);
    wrapper.addView(list,new ScrollView.LayoutParams(-1,-2));
    input.addTextChangedListener(new TextWatcher(){
      public void beforeTextChanged(CharSequence s,int start,int count,int after){}
      public void onTextChanged(CharSequence s,int start,int before,int count){update(wrapper,list,input,theme,target);}
      public void afterTextChanged(Editable s){}
    });
    return wrapper;
  }

  private static void update(ScrollView wrapper,LinearLayout list,EditText input,Ui.Theme theme,Target target){
    Context context=input.getContext();List<SlashCommands.Command> items=SlashCommands.filter(input.getText().toString());
    list.removeAllViews();
    if(items.isEmpty()){wrapper.setVisibility(LinearLayout.GONE);return;}
    for(SlashCommands.Command item:items){
      LinearLayout row=new LinearLayout(context);row.setOrientation(LinearLayout.VERTICAL);
      row.setPadding(Ui.dp(context,12),Ui.dp(context,8),Ui.dp(context,12),Ui.dp(context,8));row.setMinimumHeight(Ui.dp(context,48));
      row.setBackground(Ui.ripple(context,theme.surface,12,theme.ink));
      TextView title=Ui.text(context,"/"+item.name+(item.argument.isEmpty()?"":" "+item.argument),13,item.argument.isEmpty()?theme.accent:theme.ink);
      title.setTypeface(Typeface.create("sans-serif-medium",0));row.addView(title);
      TextView detail=Ui.text(context,item.title+" · "+item.detail,11,theme.muted);detail.setSingleLine(true);
      detail.setEllipsize(android.text.TextUtils.TruncateAt.END);LinearLayout.LayoutParams detailParams=new LinearLayout.LayoutParams(-1,-2);detailParams.topMargin=Ui.dp(context,2);row.addView(detail,detailParams);
      row.setContentDescription("执行命令 "+item.invocation());
      row.setClickable(true);row.setFocusable(true);
      row.setOnClickListener(v->{
        String[] parsed=new String[2];boolean exact=SlashCommands.parse(input.getText().toString(),parsed)&&item.name.equals(parsed[0]);
        String argument=exact?parsed[1]:"";
        if(item.wantsArgument&&argument.isEmpty()){
          input.setText("/"+item.name+" ");input.setSelection(input.getText().length());return;
        }
        if(target.run(item.name,argument)){input.setText("");wrapper.setVisibility(LinearLayout.GONE);}

      });
      list.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }
    wrapper.setVisibility(LinearLayout.VISIBLE);
    wrapper.getLayoutParams().height=Ui.dp(context,Math.min(196,items.size()*60));
  }

  private static SlashCommands.Command command(String name){
    for(SlashCommands.Command item:SlashCommands.catalog())if(item.name.equals(name))return item;
    return null;
  }
}
