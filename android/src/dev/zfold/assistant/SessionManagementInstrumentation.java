package dev.zfold.assistant;

import android.app.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Real drawer dialogs -> Node -> Pi JSONL, isolated emulator data; no model or microphone requests. */
public final class SessionManagementInstrumentation extends Instrumentation implements PiRuntime.Listener {
  private MainActivity chat;private PiRuntime runtime;private volatile JSONArray items=new JSONArray();private volatile int lists;private volatile String error;
  private final Map<File,byte[]> originals=new LinkedHashMap<>();private Set<String> oldSessions;private File sessions;private SessionPicker picker;
  public void event(JSONObject event){if(event.optString("type").equals("sessions")){items=event.optJSONArray("items");lists++;}if(event.optString("type").equals("session_error")||event.optString("type").equals("error"))error=event.optString("message");}
  @Override public void onCreate(Bundle args){start();}
  @Override public void onStart(){Bundle report=new Bundle();int code=Activity.RESULT_OK;boolean isolated=false;
    try{
      if(!Build.HARDWARE.contains("ranchu")&&!Build.HARDWARE.contains("goldfish"))throw new IllegalStateException("Emulator only");
      PiRuntime.testMode=true;isolated=true;File root=PiRuntime.agentRoot(getTargetContext());sessions=new File(root,"sessions");oldSessions=names(sessions);
      save(new File(root,"active-session.json"));save(new File(sessions,".session-state.json"));save(new File(getTargetContext().getNoBackupFilesDir(),"model.json"));
      for(String name:new String[]{"models.json","models-store.json","auth.json"})save(new File(CredentialStore.directory(getTargetContext()),name));
      EmulatorChecks.shell(this,"am start --windowingMode 1 -n dev.zfold.assistant/.MainActivity");await(()->MainActivity.current()!=null&&MainActivity.current().runtime.ready,"Runtime did not start");chat=MainActivity.current();runtime=chat.runtime;runtime.listen(this);
      if(!runtime.configured){runOnMainSync(()->runtime.send(PiRuntime.json("type","configure","config",PiRuntime.json("provider","custom","baseUrl","http://127.0.0.1:9/v1","model","session-ui-fixture","api","openai-completions","apiKey","fixture-only"))));await(()->runtime.configured,"Fixture configuration failed");}
      String previous=runtime.sessionId;runOnMainSync(()->runtime.send(PiRuntime.json("type","new_session")));await(()->!runtime.sessionId.equals(previous),"New conversation missing");String id=runtime.sessionId;
      runOnMainSync(chat::openSidebar);await(()->chat.swipeNavigation.currentPicker()!=null&&lookup(id)!=null,"Session drawer missing");picker=chat.swipeNavigation.currentPicker();
      click("管理会话：新对话");choose("重命名");AlertDialog rename=dialog();runOnMainSync(()->((EditText)find(rename.getWindow().getDecorView(),"会话名称")).setText("会话管理验收"));positive();await(()->lookup(id)!=null&&lookup(id).optString("title").equals("会话管理验收"),"Rename was not persisted");
      check(new String(Files.readAllBytes(sessionFile(id).toPath()),java.nio.charset.StandardCharsets.UTF_8).contains("会话管理验收"),"Rename only changed UI");report.putBoolean("renameDialogPersistsEmptyPiSession",true);
      click("管理会话：会话管理验收");choose("归档");await(()->lookup(id).optBoolean("archived")&&!runtime.sessionId.equals(id),"Archive did not switch current conversation");report.putBoolean("archiveCurrentStartsFreshConversation",true);
      filter(1);click("管理会话：会话管理验收");choose("恢复到会话列表");await(()->!lookup(id).optBoolean("archived"),"Archive restore failed");report.putBoolean("archiveFilterAndRestore",true);
      filter(0);click("管理会话：会话管理验收");choose("删除");negative();check(!lookup(id).optBoolean("deleted"),"Cancel deleted the session");
      click("管理会话：会话管理验收");choose("删除");positive();await(()->lookup(id).optBoolean("deleted"),"Trash flag missing");check(sessionFile(id)!=null,"Trash removed recoverable history");report.putBoolean("deleteConfirmationAndRecoverableHistory",true);
      filter(2);click("管理会话：会话管理验收");choose("恢复到会话列表");await(()->!lookup(id).optBoolean("deleted"),"Trash restore failed");report.putBoolean("trashFilterAndRestore",true);
      filter(0);click("管理会话：会话管理验收");choose("删除");positive();await(()->lookup(id).optBoolean("deleted"),"Second delete failed");filter(2);
      click("管理会话：会话管理验收");choose("彻底删除");negative();check(sessionFile(id)!=null,"Purge cancellation deleted file");
      click("管理会话：会话管理验收");choose("彻底删除");positive();await(()->lookup(id)==null,"Purged session is still listed");check(sessionFile(id)==null,"Purge left the JSONL file");report.putBoolean("purgeConfirmationRemovesOnlySelectedJsonl",true);
      filter(0);EmulatorChecks.screenshot(this,"session-management-drawer.png");report.putBoolean("drawerRemainsUsableAfterManagement",true);
      check(error==null,"Runtime reported: "+error);
    }catch(Throwable failure){report.putString("failure",failure.toString());code=Activity.RESULT_CANCELED;}
    finally{
      if(runtime!=null)runtime.unlisten(this);
      if(chat!=null)runOnMainSync(()->{if(picker!=null)picker.dismiss();chat.finish();});
      if(isolated)try{
        if(oldSessions!=null)for(File file:Optional.ofNullable(sessions.listFiles()).orElse(new File[0]))if(file.getName().endsWith(".jsonl")&&!oldSessions.contains(file.getName()))Files.deleteIfExists(file.toPath());
        for(Map.Entry<File,byte[]> entry:originals.entrySet()){if(entry.getValue()==null)Files.deleteIfExists(entry.getKey().toPath());else{entry.getKey().getParentFile().mkdirs();Files.write(entry.getKey().toPath(),entry.getValue());}}
      }catch(Exception failure){report.putString("cleanupFailure",failure.toString());code=Activity.RESULT_CANCELED;}
    }
    finish(code,report);
  }
  private void save(File file)throws Exception{originals.put(file,file.isFile()?Files.readAllBytes(file.toPath()):null);}
  private Set<String> names(File directory){String[] names=directory.list();return new HashSet<>(Arrays.asList(names==null?new String[0]:names));}
  private File sessionFile(String id){File[] files=sessions.listFiles();if(files!=null)for(File file:files)if(file.getName().endsWith("_"+id+".jsonl"))return file;return null;}
  private JSONObject lookup(String id){JSONArray snapshot=items;if(snapshot!=null)for(int i=0;i<snapshot.length();i++){JSONObject item=snapshot.optJSONObject(i);if(item!=null&&id.equals(item.optString("id")))return item;}return null;}
  private AlertDialog dialog()throws Exception{java.lang.reflect.Field field=SessionPicker.class.getDeclaredField("dialog");field.setAccessible(true);AlertDialog shown=(AlertDialog)field.get(picker);check(shown!=null&&shown.isShowing(),"Expected management dialog");return shown;}
  private void click(String label)throws Exception{await(()->!picker.waiting&&find(picker.list,label)!=null,"Missing row action: "+label);runOnMainSync(()->find(picker.list,label).performClick());}
  private void choose(String label)throws Exception{AlertDialog shown=dialog();runOnMainSync(()->{ListView list=shown.getListView();for(int i=0;i<list.getAdapter().getCount();i++)if(label.equals(list.getAdapter().getItem(i).toString())){list.performItemClick(null,i,list.getAdapter().getItemId(i));return;}throw new IllegalStateException("Missing menu item: "+label);});}
  private void positive()throws Exception{AlertDialog shown=dialog();runOnMainSync(()->shown.getButton(AlertDialog.BUTTON_POSITIVE).performClick());}
  private void negative()throws Exception{AlertDialog shown=dialog();runOnMainSync(()->shown.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());}
  private void filter(int index)throws Exception{await(()->!picker.waiting,"Pending operation did not finish");runOnMainSync(()->picker.filterAction.performClick());AlertDialog shown=dialog();runOnMainSync(()->shown.getListView().performItemClick(null,index,index));}
  private static View find(View view,String label){if(label.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View result=find(((ViewGroup)view).getChildAt(i),label);if(result!=null)return result;}return null;}
  private void await(BooleanSupplier condition,String message)throws Exception{for(int i=0;i<200;i++){boolean[] ready={false};runOnMainSync(()->ready[0]=condition.getAsBoolean());if(ready[0])return;Thread.sleep(50);}throw new IllegalStateException(message+(error==null?"":" / "+error));}
  private static void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
}
