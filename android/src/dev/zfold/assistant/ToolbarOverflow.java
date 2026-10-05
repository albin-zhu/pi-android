package dev.zfold.assistant;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.*;
import android.widget.*;
import java.util.List;

/** Compact overflow menu for composer actions that do not fit in the current width. */
final class ToolbarOverflow {
  static void show(View anchor,List<Ui.Action> actions){
    Context context=anchor.getContext();Ui.Theme theme=new Ui.Theme(context);
    if(context instanceof android.app.Activity){
      String[] labels=new String[actions.size()];for(int i=0;i<actions.size();i++)labels[i]=actions.get(i).getContentDescription().toString();
      new android.app.AlertDialog.Builder(context).setTitle("更多工具").setItems(labels,(dialog,index)->anchor.post(actions.get(index)::performClick)).setNegativeButton("返回",null).show();return;
    }
    FrameLayout frame=new FrameLayout(context);frame.setFocusableInTouchMode(true);
    final PopupWindow[] handle=new PopupWindow[1];
    frame.setOnKeyListener((v,key,event)->{if(key==KeyEvent.KEYCODE_BACK&&event.getAction()==KeyEvent.ACTION_UP){handle[0].dismiss();return true;}return key==KeyEvent.KEYCODE_BACK;});
    LinearLayout sheet=new LinearLayout(context);sheet.setOrientation(LinearLayout.VERTICAL);sheet.setBackground(Ui.shape(context,theme.surface,16,theme.line));sheet.setElevation(Ui.dp(context,14));sheet.setClickable(true);
    int width=Math.min(Ui.dp(context,260),context.getResources().getDisplayMetrics().widthPixels-Ui.dp(context,28));
    TextView title=Ui.text(context,"更多工具",12,theme.muted);title.setLetterSpacing(.05f);title.setPadding(Ui.dp(context,14),Ui.dp(context,12),Ui.dp(context,14),Ui.dp(context,5));sheet.addView(title);
    for(Ui.Action action:actions){
      LinearLayout row=new LinearLayout(context);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(Ui.dp(context,12),0,Ui.dp(context,10),0);row.setMinimumHeight(Ui.dp(context,46));
      row.setBackground(Ui.ripple(context,Color.TRANSPARENT,0,theme.ink));
      ImageView icon=new ImageView(context);icon.setImageDrawable(Ui.icon(context,resourceFor(action),theme.muted));row.addView(icon,new LinearLayout.LayoutParams(Ui.dp(context,20),Ui.dp(context,20)));
      TextView label=Ui.text(context,action.getContentDescription().toString(),13,theme.ink);label.setTypeface(Typeface.create("sans-serif-medium",0));label.setSingleLine(true);label.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
      LinearLayout.LayoutParams labelParams=new LinearLayout.LayoutParams(0,-2,1);labelParams.leftMargin=Ui.dp(context,10);row.addView(label,labelParams);
      row.setContentDescription(action.getContentDescription());row.setClickable(true);row.setFocusable(true);
      row.setOnClickListener(v->{handle[0].dismiss();anchor.postDelayed(action::performClick,70);});
      sheet.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }
    ScrollView scroll=new ScrollView(context);scroll.setVerticalScrollBarEnabled(false);scroll.addView(sheet);
    frame.addView(scroll,new FrameLayout.LayoutParams(width,-2));
    handle[0]=new PopupWindow(frame,-2,-2,true);handle[0].setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));handle[0].setOutsideTouchable(true);
    frame.measure(View.MeasureSpec.UNSPECIFIED,View.MeasureSpec.UNSPECIFIED);
    handle[0].showAsDropDown(anchor,0,-frame.getMeasuredHeight()-anchor.getHeight()-Ui.dp(context,6));
  }
  private static int resourceFor(Ui.Action action){
    String description=action.getContentDescription().toString();
    if(description.contains("会话"))return R.drawable.ic_sidebar;
    if(description.contains("圈选"))return R.drawable.ic_corners_out;
    if(description.contains("文件"))return R.drawable.ic_file;
    if(description.contains("共享"))return R.drawable.ic_screen_share;
    return R.drawable.ic_dots_three;
  }
}
