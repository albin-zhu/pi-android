package dev.zfold.assistant;
import android.app.AlertDialog;
import android.content.Context;
import android.view.Gravity;
import android.widget.*;
import java.io.File;
import java.util.List;
import java.util.function.Consumer;

/** Read-only in-app directory browser. Does not request SAF grants or copy files. */
final class DirectoryPicker {
  static void show(Context context,String initial,Consumer<String> selected){new DirectoryPicker(context,selected).open(initial);}
  final Context context;final Consumer<String> selected;final File root;
  AlertDialog dialog;TextView path,status;ListView list;Button up;File current;int generation;boolean closed;
  DirectoryPicker(Context c,Consumer<String> callback){context=c;selected=callback;root=PiRuntime.agentRoot(c);}
  void open(String initial){
    Ui.Theme theme=new Ui.Theme(context);
    LinearLayout body=new LinearLayout(context);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(Ui.dp(context,20),0,Ui.dp(context,20),0);
    path=Ui.text(context,"",13,theme.ink);path.setTextIsSelectable(true);body.addView(path,new LinearLayout.LayoutParams(-1,-2));
    up=new Button(context);up.setText("↑ 返回上级");up.setMinHeight(Ui.dp(context,48));up.setOnClickListener(v->{if(current!=null&&!current.equals(rootCanonical()))load(current.getParentFile());});body.addView(up);
    status=Ui.text(context,"正在读取目录…",13,theme.muted);status.setGravity(Gravity.CENTER_VERTICAL);status.setMinHeight(Ui.dp(context,48));body.addView(status);
    list=new ListView(context);list.setMinimumHeight(Ui.dp(context,96));
    int height=Math.min(Ui.dp(context,320),Math.max(Ui.dp(context,96),context.getResources().getDisplayMetrics().heightPixels/3));
    body.addView(list,new LinearLayout.LayoutParams(-1,height));
    dialog=new AlertDialog.Builder(context).setTitle("选择工作目录").setView(body).setNegativeButton("取消",null).setPositiveButton("选择此目录",null).create();
    dialog.setOnDismissListener(d->{closed=true;generation++;});dialog.show();
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{File valid=DirectoryChoices.validate(root,current);selected.accept(valid.getPath());dialog.dismiss();}catch(Exception e){status.setText("目录已不可用，请重新选择");dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);}});
    File start=new File(initial);try{start=DirectoryChoices.validate(root,start);}catch(Exception ignored){start=root;}load(start);
  }
  File rootCanonical(){try{return root.getCanonicalFile();}catch(Exception e){return root;}}
  void load(File target){
    final int request=++generation;list.setEnabled(false);up.setEnabled(false);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);status.setText("正在读取目录…");
    new Thread(()->{
      try{File canonical=DirectoryChoices.validate(root,target);List<File> children=DirectoryChoices.list(root,canonical);
        list.post(()->{if(closed||request!=generation)return;current=canonical;path.setText(current.getPath());up.setEnabled(!current.equals(rootCanonical()));
          String[] names=new String[children.size()];for(int i=0;i<names.length;i++)names[i]="▸ "+children.get(i).getName();
          list.setAdapter(new ArrayAdapter<String>(context,android.R.layout.simple_list_item_1,names));
          list.setOnItemClickListener((parent,view,index,id)->load(children.get(index)));list.setEnabled(true);status.setText(children.isEmpty()?"没有子文件夹，可以选择当前目录":"仅显示可用文件夹");dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
        });
      }catch(Exception error){list.post(()->{if(closed||request!=generation)return;status.setText("无法读取此目录");if(current!=null){up.setEnabled(!current.equals(rootCanonical()));list.setEnabled(true);}});}
    },"directory-browser").start();
  }
}
