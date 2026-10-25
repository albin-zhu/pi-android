package dev.zfold.assistant;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.*;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Provider-grouped model switcher shared by the main composer and assistant panel. */
final class ModelPicker {
  static void show(View anchor,PiRuntime runtime){
    Context context=anchor.getContext();Ui.Theme theme=new Ui.Theme(context);
    FrameLayout frame=new FrameLayout(context);frame.setFocusableInTouchMode(true);
    final PopupWindow[] handle=new PopupWindow[1];
    frame.setOnKeyListener((v,key,event)->{if(key==KeyEvent.KEYCODE_BACK&&event.getAction()==KeyEvent.ACTION_UP){handle[0].dismiss();return true;}return key==KeyEvent.KEYCODE_BACK;});
    LinearLayout sheet=new LinearLayout(context);sheet.setOrientation(LinearLayout.VERTICAL);sheet.setBackground(Ui.shape(context,theme.surface,18,theme.line));sheet.setElevation(Ui.dp(context,16));sheet.setClickable(true);
    int width=Math.min(Ui.dp(context,340),context.getResources().getDisplayMetrics().widthPixels-Ui.dp(context,28));
    frame.addView(sheet,new FrameLayout.LayoutParams(width,-2));
    LinearLayout head=new LinearLayout(context);head.setGravity(Gravity.CENTER_VERTICAL);head.setPadding(Ui.dp(context,14),Ui.dp(context,11),Ui.dp(context,8),Ui.dp(context,7));
    TextView title=Ui.text(context,"模型与供应商",14,theme.ink);title.setTypeface(Typeface.create("sans-serif-medium",0));head.addView(title,new LinearLayout.LayoutParams(0,-2,1));
    head.addView(new Ui.Action(context,R.drawable.ic_x,"","关闭模型切换",theme.muted,Color.TRANSPARENT,()->handle[0].dismiss()),new LinearLayout.LayoutParams(Ui.dp(context,48),Ui.dp(context,48)));
    sheet.addView(head);
    LinearLayout list=new LinearLayout(context);list.setOrientation(LinearLayout.VERTICAL);
    JSONArray providers=runtime.modelProviders;int rows=1;
    if(providers!=null&&providers.length()>0){
      String currentProvider=runtime.currentProvider.isEmpty()?"custom":runtime.currentProvider,currentModel=runtime.currentModel;
      for(int p=0;p<providers.length();p++){
        JSONObject provider=providers.optJSONObject(p);if(provider==null)continue;
        String providerId=provider.optString("id"),providerName=provider.optString("name",providerId);
        TextView heading=Ui.text(context,providerName+(provider.optBoolean("configured")?"":" · 未配置"),11,theme.muted);
        heading.setTypeface(Typeface.create("sans-serif-medium",0));heading.setPadding(Ui.dp(context,14),0,Ui.dp(context,10),0);heading.setMinimumHeight(Ui.dp(context,34));
        list.addView(heading);rows++;
        JSONArray models=provider.optJSONArray("models");
        for(int m=0;models!=null&&m<models.length();m++){
          JSONObject model=models.optJSONObject(m);if(model==null)continue;
          String modelId=model.optString("id");boolean selected=providerId.equals(currentProvider)&&modelId.equals(currentModel);
          LinearLayout row=new LinearLayout(context);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(Ui.dp(context,14),0,Ui.dp(context,10),0);row.setMinimumHeight(Ui.dp(context,48));
          row.setBackground(Ui.ripple(context,selected?theme.accentSoft:Color.TRANSPARENT,0,theme.ink));
          TextView label=Ui.text(context,modelId,13,selected?theme.accent:theme.ink);label.setSingleLine(true);label.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);row.addView(label,new LinearLayout.LayoutParams(0,-2,1));
          row.setContentDescription((selected?"当前模型 ":"切换到 ")+providerId+"/"+modelId);row.setClickable(true);row.setFocusable(true);
          row.setOnClickListener(v->{
            if(runtime.busy){Toast.makeText(context,"请先停止当前任务，再切换模型",Toast.LENGTH_SHORT).show();return;}
            if(runtime.activeConfig==null){Toast.makeText(context,"模型还没有连接，不能切换",Toast.LENGTH_SHORT).show();return;}
            try{
              JSONObject next=new JSONObject(runtime.activeConfig.toString());
              File file=new File(context.getNoBackupFilesDir(),"model.json");
              if(file.isFile())next=new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
              next.put("provider",providerId);next.put("model",modelId);
              if(!providerId.equals("custom")){next.remove("baseUrl");next.remove("apiKey");next.remove("api");}
              try(FileOutputStream out=new FileOutputStream(file)){out.write(next.toString().getBytes(StandardCharsets.UTF_8));}
              handle[0].dismiss();runtime.send(PiRuntime.json("type","configure","config",next));
            }catch(Exception error){Toast.makeText(context,"切换模型失败",Toast.LENGTH_SHORT).show();}
          });
          list.addView(row,new LinearLayout.LayoutParams(-1,-2));rows++;
        }
      }
    }else{
      String current=runtime.currentModel.isEmpty()?"未连接模型":runtime.currentModel;
      TextView empty=Ui.text(context,current,13,theme.muted);empty.setPadding(Ui.dp(context,14),0,Ui.dp(context,10),0);empty.setMinimumHeight(Ui.dp(context,48));list.addView(empty);
    }
    LinearLayout manage=new LinearLayout(context);manage.setGravity(Gravity.CENTER_VERTICAL);manage.setPadding(Ui.dp(context,14),0,Ui.dp(context,10),0);manage.setMinimumHeight(Ui.dp(context,48));
    manage.setBackground(Ui.ripple(context,Color.TRANSPARENT,0,theme.ink));manage.setClickable(true);manage.setFocusable(true);
    manage.addView(Ui.text(context,"供应商与模型设置…",13,theme.muted),new LinearLayout.LayoutParams(0,-2,1));
    manage.setOnClickListener(v->{handle[0].dismiss();context.startActivity(new Intent(context,SettingsActivity.class).putExtra("open_models",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));});
    list.addView(manage);
    ScrollView scroll=new ScrollView(context);scroll.setVerticalScrollBarEnabled(false);scroll.addView(list);
    int maxHeight=Ui.dp(context,Math.min(420,Math.max(190,rows*40+46)));
    sheet.addView(scroll,new LinearLayout.LayoutParams(-1,maxHeight));
    handle[0]=new PopupWindow(frame,-2,-2,true);handle[0].setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));handle[0].setOutsideTouchable(true);
    handle[0].showAsDropDown(anchor,0,-Math.min(maxHeight,Ui.dp(context,330))-anchor.getHeight()-Ui.dp(context,6));
  }
}
