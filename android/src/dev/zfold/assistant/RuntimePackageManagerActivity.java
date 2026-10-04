package dev.zfold.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;

public final class RuntimePackageManagerActivity extends Activity {
  private EditText search;
  private ListView list;
  private TextView status;
  private ProgressBar progress;
  private Button refresh,installedOnly;
  private boolean showInstalled;
  private String query="";
  private ArrayList<JSONObject> rows=new ArrayList<>();

  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);setContentView(R.layout.activity_runtime_packages);setTitle("Runtime 软件包");
    search=findViewById(R.id.packageSearch);list=findViewById(R.id.packageList);status=findViewById(R.id.packageStatus);
    progress=findViewById(R.id.packageProgress);refresh=findViewById(R.id.packageRefresh);installedOnly=findViewById(R.id.packageInstalled);
    search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void onTextChanged(CharSequence s,int a,int b,int c){}public void afterTextChanged(Editable value){query=value.toString().trim();runSearch(false);}});
    refresh.setOnClickListener(v->runSearch(true));
    installedOnly.setOnClickListener(v->{showInstalled=!showInstalled;installedOnly.setText(showInstalled?"显示全部":"已安装");runSearch(false);});
    list.setOnItemClickListener((parent,view,position,id)->{
      JSONObject item=position>=0&&position<rows.size()?rows.get(position):null;if(item==null)return;
      String name=item.optString("name");boolean installed=item.optBoolean("installed");
      boolean upgradable=installed&&item.has("installedVersion")&&!item.optString("version").equals(item.optString("installedVersion"));
      String message=(installed?"名称："+name+"\n已安装："+item.optString("installedVersion")+"\n仓库版本："+item.optString("version")+"\n\n":"安装 "+name+" "+item.optString("version")+" 及其依赖？\n\n")+item.optString("description");
      new AlertDialog.Builder(this).setTitle(name).setMessage(message).setNegativeButton("关闭",null)
        .setPositiveButton(installed?(upgradable?"升级":"删除"):"安装",(d,w)->{
          if(installed&&!upgradable)run("remove",name,"正在删除 "+name+"…");
          else run("install",name,"正在安装/升级 "+name+" 及依赖…");
        }).show();
    });
    if(getIntent().hasExtra("search"))search.setText(getIntent().getStringExtra("search"));
    runSearch(true);
  }

  private void runSearch(boolean force) {
    progress.setVisibility(View.VISIBLE);status.setText(force?"刷新仓库索引…":"搜索 "+query+"…");refresh.setEnabled(false);list.setEnabled(false);
    new Thread(()->{
      try {
        JSONObject result=force?PiPackageManager.status(this,true):PiPackageManager.search(this,query,query.length()==0);
        runOnUiThread(()->{progress.setVisibility(View.GONE);refresh.setEnabled(true);list.setEnabled(true);
          JSONArray packages=result.optJSONArray("packages");rows.clear();ArrayList<String> labels=new ArrayList<>();
          if(packages!=null)for(int i=0;i<packages.length();i++) {
            JSONObject item=packages.optJSONObject(i);if(item==null)continue;
            if(showInstalled&&!item.optBoolean("installed"))continue;
            rows.add(item);
            String title=item.optString("name")+"  "+item.optString("version");
            if(item.optBoolean("installed"))title+="  · 已安装";
            labels.add(title+"\n"+item.optString("description"));
          }
          status.setText("共 "+rows.size()+" 个包"+(query.isEmpty()?"":" · 搜索 “"+query+"”"));
          list.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,labels){public View getView(int position,View convert,ViewGroup parent){View v=super.getView(position,convert,parent);v.setMinimumHeight((int)(64*getResources().getDisplayMetrics().density));return v;}});
        });
      } catch(Exception e) {
        runOnUiThread(()->{progress.setVisibility(View.GONE);refresh.setEnabled(true);list.setEnabled(true);status.setText("失败："+e.getMessage());Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();});
      }
    },"pkg-search").start();
  }

  private void run(String action,String name,String message) {
    progress.setVisibility(View.VISIBLE);status.setText(message);refresh.setEnabled(false);list.setEnabled(false);
    new Thread(()->{
      try {
        JSONObject result=action.equals("remove")?PiPackageManager.remove(this,name):PiPackageManager.install(this,name);
        runOnUiThread(()->{progress.setVisibility(View.GONE);refresh.setEnabled(true);list.setEnabled(true);
          Toast.makeText(this,name+" 完成",Toast.LENGTH_SHORT).show();status.setText(name+" 完成");
          runSearch(false);});
      } catch(Exception e) {
        runOnUiThread(()->{progress.setVisibility(View.GONE);refresh.setEnabled(true);list.setEnabled(true);status.setText("失败："+e.getMessage());new AlertDialog.Builder(this).setTitle(name).setMessage(e.getMessage()).setPositiveButton("好的",null).show();});
      }
    },"pkg-"+action).start();
  }
}
