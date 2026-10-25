package dev.zfold.assistant;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Emulator-only native form -> Node -> mock model continuation, with real cancellation. */
public final class UserQuestionInstrumentation extends Instrumentation {
  private SettingsActivity chat;private UserQuestionActivity view;private PiRuntime runtime;private File sessions;private Set<String> oldFiles;private String freeform;private boolean isolated,settingsSaved;
  private final Map<File,byte[]> backup=new LinkedHashMap<>();
  @Override public void onCreate(Bundle args){start();}
  @Override public void onStart(){Bundle report=new Bundle();int code=Activity.RESULT_OK;
    try{
      if(!Build.HARDWARE.contains("ranchu")&&!Build.HARDWARE.contains("goldfish"))throw new IllegalStateException("Emulator only");PiRuntime.testMode=true;isolated=true;
      File root=PiRuntime.agentRoot(getTargetContext());sessions=new File(root,"sessions");oldFiles=new HashSet<>(Arrays.asList(Optional.ofNullable(sessions.list()).orElse(new String[0])));save(new File(root,"active-session.json"));save(new File(getTargetContext().getApplicationInfo().dataDir,"shared_prefs/test-system-window.xml"));
      for(String name:new String[]{"models.json","models-store.json","auth.json"})save(new File(CredentialStore.directory(getTargetContext()),name));
      freeform=EmulatorChecks.shell(this,"settings get global enable_freeform_support").trim();settingsSaved=true;
      chat=(SettingsActivity)startActivitySync(new Intent(getTargetContext(),SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));runtime=PiRuntime.get(chat);await(()->runtime.ready,"Runtime missing");
      runOnMainSync(()->runtime.send(PiRuntime.json("type","configure","config",PiRuntime.json("provider","custom","baseUrl","http://127.0.0.1:18876/v1","model","question-fixture","api","openai-completions","apiKey","fixture-only"))));await(()->runtime.configured,"Fixture config missing");
      String previous=runtime.sessionId;runOnMainSync(()->runtime.send(PiRuntime.json("type","new_session")));await(()->!runtime.sessionId.equals(previous),"Fresh fixture session missing");
      startQuestion("ASK_FORM");UserQuestions.Request pending=UserQuestions.current();String firstId=pending.id;int task=chat.getTaskId();
      check(pending.drafts.stream().allMatch(draft->draft.selected.isEmpty()&&draft.text.isEmpty()),"An answer was preselected");click("提交全部回答");check(UserQuestions.current()==pending&&runtime.busy,"Empty submit completed the tool");report.putBoolean("noDefaultAnswerAndRequiredValidation",true);
      click("问题 1：Android");click("问题 2：语音");click("问题 2：相机");runOnMainSync(()->((EditText)find(view.getWindow().getDecorView(),"问题 3 的文字回答")).setText("真机优先"));
      click("稍后回答，保留草稿");await(chat::hasWindowFocus,"Settings did not return");check(UserQuestions.current()==pending&&runtime.busy,"Later lost task");
      openQuestion();check(((CompoundButton)find(view.getWindow().getDecorView(),"问题 1：Android")).isChecked()&&pending.drafts.get(1).selected.size()==2&&((EditText)find(view.getWindow().getDecorView(),"问题 3 的文字回答")).getText().toString().equals("真机优先"),"Reopening lost answers");report.putBoolean("singleMultiTextAndDraftResume",true);
      EmulatorChecks.screenshot(this,"user-question-full.png");
      runOnMainSync(view::finish);await(chat::hasWindowFocus,"Question did not close");EmulatorChecks.shell(this,"settings put global enable_freeform_support 1");runOnMainSync(()->SystemWindowNavigation.enterSmallWindow(chat,ignored->{}));await(()->SystemWindowNavigation.isPopup(chat),"Settings did not enter popup");int width=Ui.dp(chat,239),height=Ui.dp(chat,282);EmulatorChecks.shell(this,"am task resize "+task+" 200 450 "+(200+width)+" "+(450+height));openQuestion();
      check(view.getTaskId()==task&&view.isInMultiWindowMode(),"Question escaped the existing system window");await(()->find(view.getWindow().getDecorView(),"提交全部回答").isShown(),"Submit missing in small window");EmulatorChecks.screenshot(this,"user-question-small.png");report.putBoolean("sameTaskAndSmallWindow",true);
      click("提交全部回答");await(()->!runtime.busy&&UserQuestions.current()==null,"Answer did not resume model");check(runtime.lastResponse.contains("QUESTION_CONFIRMED"),"Model did not receive answers");report.putBoolean("nativeAnswerResumesSameModelTurn",true);
      // A fresh request must not accept a receipt from the previous form.
      await(chat::hasWindowFocus,"Chat missing after reply");startQuestion("SIMPLE CANCEL");String current=UserQuestions.current().id;boolean[] rejected={false};runOnMainSync(()->{try{UserQuestions.submit(chat,firstId,false);}catch(Exception expected){rejected[0]=true;}});check(rejected[0]&&current.equals(UserQuestions.current().id),"Stale answer changed another question");
      runOnMainSync(()->runtime.send(PiRuntime.json("type","stop")));await(()->UserQuestions.current()==null&&!runtime.busy,"Stop did not cancel pending question");report.putBoolean("stopAndStaleReceiptSafety",true);
      await(chat::hasWindowFocus,"Chat missing after stop");startQuestion("SIMPLE SKIP");click("跳过本次提问");await(()->!runtime.busy,"Skip did not return to model");check(runtime.lastResponse.contains("QUESTION_DECLINED"),"Skip was mistaken for a positive answer");report.putBoolean("skipIsExplicitlyDeclined",true);
    }catch(Throwable error){report.putString("failure",error.toString());code=Activity.RESULT_CANCELED;}
    finally{
      if(runtime!=null&&runtime.busy)try{runOnMainSync(()->runtime.send(PiRuntime.json("type","stop")));await(()->!runtime.busy,"Fixture task still active");}catch(Exception error){report.putString("stopFailure",error.toString());code=Activity.RESULT_CANCELED;}
      runOnMainSync(()->{if(view!=null)view.finish();if(chat!=null)chat.finish();});
      if(settingsSaved)try{EmulatorChecks.shell(this,"settings "+("null".equals(freeform)?"delete global enable_freeform_support":"put global enable_freeform_support "+freeform));}catch(Exception error){report.putString("restoreFailure",error.toString());code=Activity.RESULT_CANCELED;}
      if(isolated&&(runtime==null||!runtime.busy))try{if(oldFiles!=null)for(File file:Optional.ofNullable(sessions.listFiles()).orElse(new File[0]))if(file.getName().endsWith(".jsonl")&&!oldFiles.contains(file.getName()))Files.deleteIfExists(file.toPath());for(Map.Entry<File,byte[]> item:backup.entrySet()){if(item.getValue()==null)Files.deleteIfExists(item.getKey().toPath());else Files.write(item.getKey().toPath(),item.getValue());}}catch(Exception error){report.putString("cleanupFailure",error.toString());code=Activity.RESULT_CANCELED;}
    }
    finish(code,report);
  }
  private void save(File file)throws Exception{backup.put(file,file.isFile()?Files.readAllBytes(file.toPath()):null);}
  private void startQuestion(String text)throws Exception{runOnMainSync(()->runtime.send(PiRuntime.json("type","prompt","id",UUID.randomUUID().toString(),"sessionId",runtime.sessionId,"text",text)));await(()->UserQuestions.current()!=null,"Question not ready");openQuestion();}
  private void openQuestion()throws Exception{ActivityMonitor monitor=addMonitor(UserQuestionActivity.class.getName(),null,false);runOnMainSync(()->UserQuestions.show(chat));view=(UserQuestionActivity)waitForMonitorWithTimeout(monitor,10000);removeMonitor(monitor);check(view!=null,"Question did not reopen");await(view::hasWindowFocus,"Question did not focus");}
  private void click(String label){runOnMainSync(()->{View action=find(view.getWindow().getDecorView(),label);check(action!=null,"Missing question action: "+label);action.performClick();});}
  private void await(BooleanSupplier ready,String message)throws Exception{for(int i=0;i<250;i++){boolean[] yes={false};runOnMainSync(()->yes[0]=ready.getAsBoolean());if(yes[0])return;Thread.sleep(50);}throw new IllegalStateException(message);}
  private static View find(View view,String label){if(label.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View result=find(((ViewGroup)view).getChildAt(i),label);if(result!=null)return result;}return null;}
  private static void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
}
