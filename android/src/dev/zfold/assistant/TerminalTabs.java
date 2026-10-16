package dev.zfold.assistant;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Session selection only: no shell creation on selection and no process termination on detach. */
final class TerminalTabs extends LinearLayout {
  private static final int INK=0xffe5e9ed,MUTED=0xff95a1ad,SELECTED=0xff294437;
  private final TerminalSessions sessions;
  private final Consumer<TerminalSessions.Job> select,menu;
  private final HorizontalScrollView scroll;
  private final LinearLayout strip;
  private final LinkedHashMap<String,Tab> tabs=new LinkedHashMap<>();

  TerminalTabs(Context context,TerminalSessions sessions,Consumer<TerminalSessions.Job> select,Consumer<TerminalSessions.Job> menu,Runnable create,Runnable options){
    super(context);this.sessions=sessions;this.select=select;this.menu=menu;
    setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);setBackgroundColor(0xff17212c);
    scroll=new HorizontalScrollView(context);scroll.setHorizontalScrollBarEnabled(false);
    strip=new LinearLayout(context);strip.setGravity(Gravity.CENTER_VERTICAL);scroll.addView(strip);
    addView(scroll,new LayoutParams(0,Ui.dp(context,48),1));
    TextView add=button("＋","新建终端");add.setOnClickListener(v->{Ui.click(v);create.run();});
    addView(add,new LayoutParams(Ui.dp(context,48),Ui.dp(context,48)));
    addView(new Ui.Action(context,R.drawable.ic_dots_three,"","终端选项",INK,Color.TRANSPARENT,options),
      new LayoutParams(Ui.dp(context,48),Ui.dp(context,48)));
  }

  private TextView button(String label,String description){
    Context c=getContext();TextView view=Ui.text(c,label,13,INK);
    view.setGravity(Gravity.CENTER);view.setSingleLine(true);view.setEllipsize(TextUtils.TruncateAt.END);
    view.setFocusable(true);view.setContentDescription(description);view.setMinimumWidth(Ui.dp(c,72));
    view.setPadding(Ui.dp(c,12),0,Ui.dp(c,12),0);view.setMaxWidth(Ui.dp(c,220));
    view.setBackground(Ui.ripple(c,Color.TRANSPARENT,8,INK));return view;
  }

  void sync(TerminalSessions.Job current){
    Iterator<Map.Entry<String,Tab>> iterator=tabs.entrySet().iterator();
    while(iterator.hasNext()){
      Map.Entry<String,Tab> entry=iterator.next();
      if(!sessions.jobs.containsKey(entry.getKey())){strip.removeView(entry.getValue().view);iterator.remove();}
    }
    for(TerminalSessions.Job job:sessions.jobs.values()){
      Tab tab=tabs.get(job.session.mHandle);
      if(tab==null){
        TextView view=button(job.name,job.name);view.setOnClickListener(v->{Ui.click(v);select.accept(job);});
        view.setOnLongClickListener(v->{menu.accept(job);return true;});
        tab=new Tab(view);tabs.put(job.session.mHandle,tab);strip.addView(view,new LayoutParams(-2,Ui.dp(getContext(),48)));
      }
      update(job,current);
    }
    Tab active=current==null?null:tabs.get(current.session.mHandle);
    if(active!=null)scroll.post(()->{
      int left=active.view.getLeft(),right=active.view.getRight();
      if(left<scroll.getScrollX())scroll.smoothScrollTo(left,0);
      else if(right>scroll.getScrollX()+scroll.getWidth())scroll.smoothScrollTo(right-scroll.getWidth(),0);
    });
  }

  void update(TerminalSessions.Job job,TerminalSessions.Job current){
    Tab tab=tabs.get(job.session.mHandle);if(tab==null)return;
    String title=job.session.getTitle();
    if(!tab.initialized||!Objects.equals(tab.title,title)||!Objects.equals(tab.name,job.name)){
      tab.initialized=true;tab.title=title;tab.name=job.name;
      String label=job.name+(title==null||title.isBlank()?"":" · "+title);
      tab.view.setText(label);tab.view.setContentDescription("切换到 "+label);
    }
    boolean selected=job==current;
    if(tab.view.isSelected()!=selected){
      tab.view.setSelected(selected);tab.view.setTextColor(selected?INK:MUTED);
      tab.view.setBackground(Ui.ripple(getContext(),selected?SELECTED:Color.TRANSPARENT,8,INK));
    }
  }

  private static final class Tab {
    final TextView view;String title,name;boolean initialized;
    Tab(TextView view){this.view=view;view.setTextColor(MUTED);}
  }
}
