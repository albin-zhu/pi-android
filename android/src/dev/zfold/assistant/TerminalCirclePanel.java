package dev.zfold.assistant;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;

/** A durable draft is only consumed by an explicit, validated PTY insertion. */
final class TerminalCirclePanel {
  interface Insert { void run(TerminalCircleDraft.Draft draft,TerminalSessions.Job target)throws IOException; }
  private final TerminalActivity activity;
  private final TerminalSessions sessions;
  private final BooleanSupplier available;
  private final Insert insert;
  private final Runnable create;
  final TextView reopen;
  private AlertDialog dialog,targetDialog;
  private EditText question;
  private TextView targetLabel,error;
  private TerminalCircleDraft.Draft draft;
  private TerminalSessions.Job target;
  private Bitmap preview;
  private String dismissedId;
  private String restoreId,restoreQuestion,restoreTarget;
  private boolean closing;

  TerminalCirclePanel(TerminalActivity activity,TerminalSessions sessions,BooleanSupplier available,Insert insert,Runnable create,Bundle state){
    this.activity=activity;this.sessions=sessions;this.available=available;this.insert=insert;this.create=create;
    if(state!=null){dismissedId=state.getString("terminal_circle_dismissed");restoreId=state.getString("terminal_circle_edit_id");restoreQuestion=state.getString("terminal_circle_edit_question");restoreTarget=state.getString("terminal_circle_edit_target");}
    reopen=Ui.text(activity,"终端草稿 · 点击继续",14,0xffe5e9ed);
    reopen.setPadding(dp(16),dp(12),dp(16),dp(12));reopen.setBackground(Ui.ripple(activity,0xff294437,0,0xffe5e9ed));
    reopen.setFocusable(true);reopen.setVisibility(View.GONE);reopen.setOnClickListener(v->refresh(true));
  }

  void refresh(boolean requested){
    if(requested)dismissedId=null;
    try{
      TerminalCircleDraft.Draft pending=TerminalCircleDraft.load(activity);
      reopen.setVisibility(pending==null?View.GONE:View.VISIBLE);
      if(dialog!=null&&(pending==null||!pending.id.equals(draft.id)))close(false);
      if(pending==null)return;
      reopen.setText("终端草稿 · 点击继续");
      if(dialog!=null){updateTargetLabel();return;}
      if(!available.getAsBoolean()||pending.id.equals(dismissedId))return;
      draft=pending;target=sessions.jobs.get(pending.id.equals(restoreId)?restoreTarget:pending.targetHandle);
      // Keep the capture-time identity, including a now-closed target. Never choose a neighbor.
      show();
      if(pending.id.equals(restoreId))question.setText(restoreQuestion);
    }catch(IOException failure){
      reopen.setVisibility(View.VISIBLE);reopen.setText("终端草稿无法读取 · 点击重试");
      if(requested)notice(failure.getMessage());
    }
  }

  private void show(){
    Ui.Theme theme=new Ui.Theme(activity);
    LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),dp(12),dp(20),dp(16));body.setBackgroundColor(theme.page);
    TextView warning=Ui.text(activity,"请确认目标正在等待智能体输入，而不是 Shell 命令。只插入文字与图片路径，不清空已有输入，不回车、不启动 omp。",13,theme.muted);body.addView(warning);
    if(draft.file!=null){
      BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(draft.file.getPath(),bounds);
      BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
      while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>1024)options.inSampleSize*=2;
      preview=BitmapFactory.decodeFile(draft.file.getPath(),options);
      ImageView image=new ImageView(activity);image.setContentDescription("已保存的草稿图片");image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setImageBitmap(preview);body.addView(image,new LinearLayout.LayoutParams(-1,dp(180)));
      TextView path=Ui.text(activity,draft.file.getName(),12,theme.muted);body.addView(path);
    }
    targetLabel=Ui.text(activity,"",14,theme.ink);targetLabel.setPadding(0,dp(16),0,dp(16));targetLabel.setMinimumHeight(dp(48));targetLabel.setFocusable(true);targetLabel.setBackground(Ui.ripple(activity,theme.surface,8,theme.ink));targetLabel.setOnClickListener(v->chooseTarget());body.addView(targetLabel);updateTargetLabel();
    question=new EditText(activity);question.setTextColor(theme.ink);question.setHintTextColor(theme.muted);question.setHint("输入问题（可留空）");question.setContentDescription("圈选问题");question.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);question.setMinLines(2);question.setMaxLines(6);question.setText(draft.question);body.addView(question,new LinearLayout.LayoutParams(-1,-2));
    question.setFilters(new android.text.InputFilter[]{(source,start,end,dest,dstart,dend)->{
      if(dest.length()-(dend-dstart)+(end-start)<=8000)return null;
      question.setError("问题最多 8000 个字符；本次输入未添加，请缩短后重试。");
      return dest.subSequence(dstart,dend);
    }});
    error=Ui.text(activity,draft.file!=null&&preview==null?"图片预览不可用；插入前会重新检查文件。":"",13,theme.error);body.addView(error);
    Button newTerminal=new Button(activity);newTerminal.setText("新建终端（选择目录和环境）");newTerminal.setOnClickListener(v->{if(!save())return;dismissedId=draft.id;close(false);create.run();});body.addView(newTerminal);
    ScrollView scroll=new ScrollView(activity);scroll.addView(body);
    dialog=new AlertDialog.Builder(activity).setTitle("草稿到终端").setView(scroll).setPositiveButton("切换并插入",null).setNegativeButton("稍后",null).setNeutralButton("丢弃草稿",null).create();
    AlertDialog shown=dialog;
    dialog.setOnDismissListener(ignored->{if(!closing&&dialog==shown){save();dismissedId=draft.id;release();}});
    dialog.show();dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{if(dialog==shown)insert();});
    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->{if(dialog==shown&&save()){dismissedId=draft.id;close(false);}});
    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{
      if(dialog!=shown)return;
      try{if(!TerminalCircleDraft.discard(activity,draft.id))throw new IOException("草稿已变化，请重新打开。");close(false);refresh(false);}
      catch(IOException failure){error.setText(failure.getMessage());}
    });
  }

  private boolean live(TerminalSessions.Job job){return job!=null&&sessions.find(job.session)==job&&!job.closing&&job.session.isRunning()&&job.session.getEmulator()!=null;}
  private void updateTargetLabel(){
    if(targetLabel==null)return;
    targetLabel.setText(live(target)?"目标："+target.name+" · "+target.workspace.name+" · "+(target.ubuntu?"Ubuntu":"Android")+"（点击更换）":"未选择可用目标（点击选择）；不会自动新建或改投其他终端");
  }
  private void chooseTarget(){
    ArrayList<TerminalSessions.Job> choices=new ArrayList<>();ArrayList<String> labels=new ArrayList<>();
    for(TerminalSessions.Job job:sessions.jobs.values())if(live(job)){choices.add(job);labels.add(job.name+" · "+job.workspace.name+" · "+(job.ubuntu?"Ubuntu":"Android"));}
    if(choices.isEmpty()){error.setText("没有就绪的终端。请明确新建终端，之后回到圈选草稿选择目标。");return;}
    targetDialog=new AlertDialog.Builder(activity).setTitle("选择接收圈选的终端").setItems(labels.toArray(new String[0]),(d,index)->{
      TerminalSessions.Job selected=choices.get(index);
      if(!live(selected)){error.setText("所选终端已关闭，请重新选择。");return;}
      target=selected;updateTargetLabel();save();
    }).setNegativeButton("取消",null).create();targetDialog.show();
  }
  private void insert(){
    if(dialog==null)return;
    if(!available.getAsBoolean()){error.setText("请先完成本地文件导入，再插入圈选。");return;}
    if(!live(target)){updateTargetLabel();error.setText("原目标已关闭或尚未就绪，请明确选择一个终端。");return;}
    if(!save())return;
    try{insert.run(draft,target);close(false);refresh(false);}
    catch(IOException failure){error.setText(failure.getMessage());}
  }
  private boolean save(){
    if(question==null||draft==null)return true;
    restoreId=draft.id;restoreQuestion=question.getText().toString();restoreTarget=target==null?draft.targetHandle:target.session.mHandle;
    try{draft=TerminalCircleDraft.update(activity,draft.id,restoreQuestion,restoreTarget);return true;}
    catch(IOException failure){if(error!=null)error.setText(failure.getMessage());else notice(failure.getMessage());return false;}
  }
  void saveState(Bundle state){
    save();state.putString("terminal_circle_dismissed",dismissedId);
    // Also retain a rejected edit during recreation; failed validation must not erase typed text.
    state.putString("terminal_circle_edit_id",restoreId);state.putString("terminal_circle_edit_question",restoreQuestion);state.putString("terminal_circle_edit_target",restoreTarget);
  }
  void pause(){close(true);}
  private void close(boolean persist){
    if(persist)save();
    closing=true;if(targetDialog!=null){targetDialog.dismiss();targetDialog=null;}if(dialog!=null)dialog.dismiss();release();closing=false;
  }
  private void release(){dialog=null;question=null;targetLabel=null;error=null;if(preview!=null){preview.recycle();preview=null;}}
  private void notice(String message){Toast.makeText(activity,message,Toast.LENGTH_LONG).show();}
  private int dp(int value){return Ui.dp(activity,value);}
}
