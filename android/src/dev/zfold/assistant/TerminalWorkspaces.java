package dev.zfold.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Terminal navigation metadata only; never changes a live terminal or the GUI workspace. */
final class TerminalWorkspaces {
  private static final int MAX_RECENT=20;
  private static final String STATE="state";
  private static TerminalWorkspaces instance;

  static final class Entry {
    final String id,name,path;
    final boolean ubuntu;
    final long lastUsed;
    private Entry(String id,String name,String path,boolean ubuntu,long lastUsed){
      this.id=id;this.name=name;this.path=path;this.ubuntu=ubuntu;this.lastUsed=lastUsed;
    }
  }

  private final Context context;
  private final SharedPreferences preferences;
  private List<Entry> entries=new ArrayList<>();
  private String selectedId="";
  private IOException loadError;

  static synchronized TerminalWorkspaces get(Context context){
    if(instance==null)instance=new TerminalWorkspaces(context.getApplicationContext());
    return instance;
  }

  private TerminalWorkspaces(Context context){
    this.context=context;
    preferences=context.getSharedPreferences(PiRuntime.testMode?"test-terminal-workspaces":"terminal-workspaces",Context.MODE_PRIVATE);
    try{
      String saved=preferences.getString(STATE,null);
      if(saved==null)return;
      JSONObject state=new JSONObject(saved);
      if(state.getInt("version")!=1)throw new IOException("不支持的终端工作区记录版本");
      JSONArray values=state.getJSONArray("entries");
      List<Entry> restored=new ArrayList<>();
      Set<String> ids=new HashSet<>(),paths=new HashSet<>();
      for(int i=0;i<values.length();i++){
        JSONObject value=values.getJSONObject(i);
        Entry entry=new Entry(value.getString("id"),value.getString("name"),value.getString("path"),value.getBoolean("ubuntu"),value.getLong("lastUsed"));
        // Loading is metadata-only: missing directories and revoked grants remain visible.
        if(entry.id.isEmpty()||entry.name.isEmpty()||!new File(entry.path).isAbsolute()||entry.path.indexOf('\0')>=0||entry.lastUsed<0||!ids.add(entry.id)||!paths.add(entry.path))throw new IOException("终端工作区记录无效");
        restored.add(entry);
      }
      String selected=state.getString("selected");
      if(restored.isEmpty()?!selected.isEmpty():!ids.contains(selected))throw new IOException("保存的终端工作区选择已损坏");
      restored.sort(Comparator.comparingLong((Entry entry)->entry.lastUsed).reversed());
      entries=restored;selectedId=selected;
    }catch(IOException|JSONException|RuntimeException error){
      loadError=new IOException("无法读取终端工作区记录；未切换到其他目录",error);
    }
  }

  synchronized Entry selected()throws IOException{
    requireLoaded();
    Entry saved=find(selectedId);
    if(saved!=null)return saved;
    // Only a genuinely empty store uses the existing Android/default environment preference.
    return upsert(null,RuntimeEnvironment.workingDirectory(context,null),StartupPreferences.ubuntu(context));
  }

  synchronized List<Entry> list(){return new ArrayList<>(entries);}

  synchronized Entry find(String id){
    for(Entry entry:entries)if(entry.id.equals(id))return entry;
    return null;
  }

  synchronized Entry upsert(String name,File directory,boolean ubuntu)throws IOException{
    requireLoaded();
    if(directory==null)throw new IOException("请选择工作区目录");
    File target=validate(directory);
    String path=target.getPath(),label=name==null?"":name.trim();
    if(label.isEmpty())label=target.getName().isEmpty()?path:target.getName();
    Entry existing=null;
    for(Entry entry:entries)if(entry.path.equals(path)){existing=entry;break;}
    // A path retains its identity even if it aged out of the bounded recent list.
    String id=existing==null?UUID.nameUUIDFromBytes(path.getBytes(StandardCharsets.UTF_8)).toString():existing.id;
    Entry entry=new Entry(id,label,path,ubuntu,nextUsed());
    saveSelection(entry);
    return entry;
  }

  synchronized void select(String id)throws IOException{
    requireLoaded();
    Entry entry=find(id);
    if(entry==null)throw new IOException("找不到此终端工作区");
    // Navigation must still work for stale metadata; launching/browsing validates separately.
    saveSelection(new Entry(entry.id,entry.name,entry.path,entry.ubuntu,nextUsed()));
  }

  File directory(Entry entry)throws IOException{
    if(entry==null)throw new IOException("未选择终端工作区");
    File target=validate(new File(entry.path));
    if(!target.getPath().equals(entry.path))throw new IOException("工作区目录映射已改变，请重新选择："+entry.path);
    return target;
  }

  String ubuntuDirectory(Entry entry)throws IOException{
    try{
      File root=PiRuntime.agentRoot(context);
      root=DirectoryChoices.validate(root,root);
      File target=directory(entry);
      // Recheck against the same canonical root used for the mapping.
      target=DirectoryChoices.validate(root,target);
      if(!target.getPath().equals(entry.path))throw new IOException("工作区目录映射已改变，请重新选择："+entry.path);
      String relative=root.toPath().relativize(target.toPath()).toString();
      return relative.isEmpty()?"/workspace":"/workspace/"+relative;
    }catch(SecurityException error){throw new IOException("工作区数据根访问权限已失效",error);}
  }

  private File validate(File directory)throws IOException{
    try{
      File root=PiRuntime.agentRoot(context);
      root=DirectoryChoices.validate(root,root);
      return DirectoryChoices.validate(root,directory);
    }catch(IOException|SecurityException error){
      throw new IOException("工作区目录不可用："+directory.getPath()+"（"+error.getMessage()+"）",error);
    }
  }

  private void requireLoaded()throws IOException{if(loadError!=null)throw loadError;}

  private long nextUsed(){
    long now=System.currentTimeMillis();
    if(!entries.isEmpty()){
      long latest=entries.get(0).lastUsed;
      now=Math.max(now,latest==Long.MAX_VALUE?latest:latest+1);
    }
    return now;
  }

  private void saveSelection(Entry selected)throws IOException{
    List<Entry> updated=new ArrayList<>();updated.add(selected);
    for(Entry entry:entries)if(!entry.id.equals(selected.id)&&updated.size()<MAX_RECENT)updated.add(entry);
    try{
      JSONArray values=new JSONArray();
      for(Entry entry:updated)values.put(new JSONObject().put("id",entry.id).put("name",entry.name).put("path",entry.path).put("ubuntu",entry.ubuntu).put("lastUsed",entry.lastUsed));
      String state=new JSONObject().put("version",1).put("selected",selected.id).put("entries",values).toString();
      if(!preferences.edit().putString(STATE,state).commit())throw new IOException("无法保存终端工作区选择");
    }catch(JSONException|RuntimeException error){throw new IOException("无法保存终端工作区记录",error);}
    entries=updated;selectedId=selected.id;
  }
}
