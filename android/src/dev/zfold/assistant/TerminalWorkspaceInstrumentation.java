package dev.zfold.assistant;

import android.app.ActivityOptions;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.system.Os;
import android.view.View;
import android.view.ViewGroup;
import com.termux.view.TerminalView;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Dedicated test-package acceptance: real PTYs and the production workspace store, no GUI agent. */
public final class TerminalWorkspaceInstrumentation extends Instrumentation {
  private Bundle options;
  private boolean ubuntu;
  private TerminalActivity activity;
  private TerminalSessions sessions;
  private TerminalWorkspaces store;
  private final List<TerminalSessions.Job> owned=new ArrayList<>();
  private File fixture;

  @Override public void onCreate(Bundle args){
    PiRuntime.testMode=true;
    options=launchOptions(args);
    ubuntu=args!=null&&Boolean.parseBoolean(args.getString("ubuntu","false"));
    start();
  }

  // am instrument arguments are strings; also accept a directly supplied integer Bundle value.
  static Bundle launchOptions(Bundle args){
    if(args==null||!args.containsKey("display_id"))return null;
    int id=Integer.parseInt(String.valueOf(args.get("display_id")));
    require(id>=0,"display_id must be nonnegative");
    ActivityOptions options=ActivityOptions.makeBasic();options.setLaunchDisplayId(id);return options.toBundle();
  }

  @Override public void onStart(){
    Bundle report=new Bundle();String error="";
    SharedPreferences preferences=getTargetContext().getSharedPreferences("test-terminal-workspaces",Context.MODE_PRIVATE);
    String saved=preferences.getString("state",null);
    try{
      runOnMainSync(()->sessions=TerminalSessions.get(getTargetContext()));
      require(onMain(()->sessions.jobs.isEmpty()),"Dedicated test process required: refusing to close preexisting sessions");
      store=TerminalWorkspaces.get(getTargetContext());
      launch();assertEmpty();report.putBoolean("emptyLaunchNoAutostart",true);
      File root=PiRuntime.agentRoot(getTargetContext());require(root.isDirectory()||root.mkdirs(),"Test agent root unavailable");
      fixture=Files.createTempDirectory(root.toPath(),"terminal-workspace-").toFile();
      File aDir=new File(fixture,"项目 A ; echo INJECTED ; #"),bDir=new File(fixture,"项目 B $(echo INJECTED) ' space");
      require(aDir.mkdir()&&bDir.mkdir(),"Cannot create workspace fixtures");
      TerminalWorkspaces.Entry a=store.upsert("项目 A",aDir,false),b=store.upsert("项目 B",bDir,false);
      TerminalWorkspaces.Entry alias=store.upsert("项目 A",new File(aDir,"../"+aDir.getName()),false);
      require(a.id.equals(alias.id)&&a.path.equals(alias.path),"Canonical alias changed workspace identity");
      File link=new File(fixture,"inside-link");Os.symlink(aDir.getPath(),link.getPath());
      require(store.upsert("项目 A",link,false).id.equals(a.id),"Symlink alias changed canonical identity");
      File escape=new File(fixture,"escape-link");Os.symlink(getTargetContext().getCacheDir().getPath(),escape.getPath());
      expectIOException(()->store.upsert("escape",escape,false));
      expectIOException(()->store.upsert("parent",root.getParentFile(),false));
      store.select(b.id);
      TerminalWorkspaces restored=reloadStore();
      require(restored.selected().id.equals(b.id)&&restored.find(a.id).path.equals(a.path)&&restored.find(b.id).name.equals(b.name),"Actual metadata reload lost selection, identity or directory");
      report.putBoolean("metadataPersistenceAndCanonicalBoundary",true);

      TerminalSessions.Job first=create(a),second=create(b);int firstId=first.id,secondId=second.id;
      require(firstId!=secondId,"Two workspaces reused one PTY");
      pwd(first,a.path,"A");pwd(second,b.path,"B");
      require(!transcript(first).contains("\nINJECTED\n")&&!transcript(second).contains("\nINJECTED\n"),"Directory text executed as shell code");
      report.putBoolean("twoRealPtysLiteralDirectories",true);
      store.upsert("A changed default",aDir,true);
      require(!first.ubuntu&&first.workspace.id.equals(a.id)&&!first.workspace.ubuntu,"Default environment mutated existing Job");
      TerminalWorkspaces changed=reloadStore();require(changed.find(a.id).ubuntu,"Changed default did not persist");
      TerminalSessions.Job selectedBefore=onMain(()->sessions.selected());
      store.select(b.id);store.select(a.id);
      require(onMain(()->sessions.selected())==selectedBefore,"Workspace navigation changed selected terminal");
      require(first.id==firstId&&second.id==secondId&&first.session.isRunning()&&second.session.isRunning(),"Workspace selection changed a live PTY");
      attach(first);require(attached()==first,"Activity did not reattach selected PTY");
      attach(second);require(attached()==second,"Activity did not switch to selected PTY");
      pwd(first,a.path,"A_AGAIN");pwd(second,b.path,"B_AGAIN");
      report.putBoolean("defaultsAndNavigationDoNotMutateJobs",true);
      composingInputStaysWithItsPty(first,second,b.path);
      report.putBoolean("composingInputAndStaleImeIsolatedAcrossTabs",true);

      File deleted=new File(fixture,"deleted");require(deleted.mkdir(),"Cannot create deleted-directory fixture");
      TerminalWorkspaces.Entry missing=store.upsert("deleted",deleted,false);require(deleted.delete(),"Cannot delete fixture directory");
      expectCreateFailure(missing);require(onMain(()->sessions.jobs.size())==2&&first.session.isRunning()&&second.session.isRunning(),"Invalid directory damaged existing sessions");
      report.putBoolean("deletedDirectoryIsolatedFailure",true);
      TerminalSessions.Job third=create(b),fourth=create(b);
      require(onMain(()->sessions.liveCount())==4,"Four live sessions not reached");expectCreateFailure(b);
      require(onMain(()->sessions.jobs.size())==4&&first.id==firstId&&second.id==secondId,"Session cap changed existing jobs");
      report.putBoolean("fourSessionCap",true);
      close(third);close(fourth);close(first);
      require(second.id==secondId&&second.session.isRunning(),"Closing one terminal killed its neighbor");
      pwd(second,b.path,"SURVIVOR");report.putBoolean("closeIsolated",true);

      if(ubuntu){
        String unavailable=StartupPreferences.ubuntuUnavailable(getTargetContext());
        require(unavailable==null,"Ubuntu scenario requested but unavailable: "+unavailable);
        TerminalWorkspaces.Entry linux=store.upsert("Ubuntu",aDir,true);
        TerminalSessions.Job guest=create(linux);require(guest.ubuntu,"Ubuntu Job was silently downgraded");
        pwd(guest,store.ubuntuDirectory(linux),"UBUNTU");
        send(guest,"printf 'OS_%s\\n' BEGIN; cat /etc/os-release; printf 'OS_%s\\n' END\n");
        awaitText(guest,"OS_END");require(transcript(guest).contains("ID=ubuntu"),"Requested Ubuntu PTY is not Ubuntu");
        report.putBoolean("ubuntuRealPty",true);close(guest);
      }else report.putString("ubuntuScenario","not requested; not exercised");

      // Exercise actual shell-process exit, not an in-memory fake or a claim of Android process death.
      send(second,"exit\n");awaitClosed(second);relaunch();assertEmpty();
      require(reloadStore().find(a.id)!=null,"Navigation metadata disappeared with process exit");
      report.putBoolean("shellExitNoAutostart",true);
      report.putString("androidProcessLoss","not exercised: killing instrumentation also kills its observer");
      TerminalSessions.Job last=create(b);attach(last);close(last);assertEmpty();
      require(onMain(()->!activity.isFinishing()),"Closing the last terminal finished its activity");
      report.putBoolean("lastCloseKeepsEmptyActivity",true);
    }catch(Throwable failure){error=failure.toString();report.putString("error",error);}
    finally{
      runOnMainSync(()->{for(TerminalSessions.Job job:owned)job.close();if(activity!=null)activity.finish();});
      if(fixture!=null)removeFixture(fixture);
      SharedPreferences.Editor editor=preferences.edit();if(saved==null)editor.remove("state");else editor.putString("state",saved);
      if(!editor.commit()){error="Failed to restore test workspace metadata";report.putString("cleanupError",error);}
    }
    finish(error.isEmpty()?-1:0,report);
  }

  private TerminalWorkspaces reloadStore()throws Exception{
    Constructor<TerminalWorkspaces> constructor=TerminalWorkspaces.class.getDeclaredConstructor(Context.class);
    constructor.setAccessible(true);return constructor.newInstance(getTargetContext().getApplicationContext());
  }
  private TerminalSessions.Job create(TerminalWorkspaces.Entry entry)throws Exception{
    TerminalSessions.Job job=onMain(()->{TerminalSessions.Job created=sessions.create(entry);owned.add(created);created.session.updateSize(240,40,8,16);return created;});
    for(int i=0;i<300;i++){if(job.id>0&&job.session.isRunning())return job;if(job.closing)throw new IllegalStateException("PTY startup failed: "+job.status);Thread.sleep(100);}
    throw new IllegalStateException("PTY startup timed out: "+job.status);
  }
  private void expectCreateFailure(TerminalWorkspaces.Entry entry){
    onMain(()->{try{TerminalSessions.Job unexpected=sessions.create(entry);owned.add(unexpected);throw new AssertionError("Invalid/capped creation succeeded");}catch(IllegalStateException expected){return true;}});
  }
  private void launch(){activity=(TerminalActivity)startActivitySync(new Intent(getTargetContext(),TerminalActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),options);}
  private void relaunch(){runOnMainSync(()->{if(activity!=null)activity.finish();});waitForIdleSync();launch();}
  private void attach(TerminalSessions.Job job){runOnMainSync(()->sessions.select(job));relaunch();}
  private TerminalSessions.Job attached(){return onMain(()->{TerminalView view=find(activity.getWindow().getDecorView());return view==null||view.getCurrentSession()==null?null:sessions.find(view.getCurrentSession());});}
  private void composingInputStaysWithItsPty(TerminalSessions.Job first,TerminalSessions.Job second,String secondPath)throws Exception{
    selectTab(first);
    android.view.inputmethod.InputConnection old=onMain(()->find(activity.getWindow().getDecorView()).onCreateInputConnection(new android.view.inputmethod.EditorInfo()));
    runOnMainSync(()->old.setComposingText("printf 'DRAFT_%s\\n' ORIGINAL",1));
    selectTab(second);
    runOnMainSync(()->{
      old.commitText("printf 'LEAK_%s\\n' WRONG\n",1);
      old.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_ENTER));
    });
    pwd(second,secondPath,"NO_LEAK");
    require(!transcript(second).contains("LEAK_WRONG")&&!transcript(second).contains("DRAFT_ORIGINAL"),"Old IME connection wrote into the newly selected PTY");
    selectTab(first);send(first,"\n");awaitText(first,"\nDRAFT_ORIGINAL");
    selectTab(second);
  }
  private void selectTab(TerminalSessions.Job job){
    runOnMainSync(()->{View tab=findTab(activity.getWindow().getDecorView()," · "+job.name+" · ");require(tab!=null,"Terminal tab missing");tab.performClick();});
    waitForIdleSync();require(attached()==job,"Tab selection did not attach the expected PTY");
  }
  private static View findTab(View view,String identity){
    CharSequence description=view.getContentDescription();
    if(description!=null&&description.toString().startsWith("切换到 ")&&description.toString().contains(identity))return view;
    if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=findTab(((ViewGroup)view).getChildAt(i),identity);if(found!=null)return found;}
    return null;
  }
  private void assertEmpty()throws Exception{
    Thread.sleep(500);waitForIdleSync();require(onMain(()->sessions.jobs.isEmpty()&&sessions.selected()==null),"Normal launch automatically created a terminal");
    require(attached()==null,"Empty activity still attached a terminal");
    require(onMain(()->hasDescription(activity.getWindow().getDecorView(),"新建终端")),"Empty terminal has no new-terminal action");
  }
  private void pwd(TerminalSessions.Job job,String expected,String tag)throws Exception{
    String begin="PWD_"+tag+"_BEGIN",end="PWD_"+tag+"_END";
    send(job,"printf 'PWD_%s_BEGIN\\n' "+tag+"; pwd -P; printf 'PWD_%s_END\\n' "+tag+"\n");
    awaitText(job,"\n"+end);String output=transcript(job);int start=output.lastIndexOf("\n"+begin+"\n");
    require(start>=0,"Missing actual pwd output start: "+tag);
    int finish=output.indexOf("\n"+end,start+begin.length()+2);
    require(finish>=0&&output.substring(start+begin.length()+2,finish).trim().equals(expected),"Wrong actual pwd for "+tag+": "+output);
  }
  private void send(TerminalSessions.Job job,String text){runOnMainSync(()->job.session.write(text));}
  private String transcript(TerminalSessions.Job job){return onMain(()->job.session.getEmulator()==null?"":job.session.getEmulator().getScreen().getTranscriptText().replace("\r",""));}
  private void awaitText(TerminalSessions.Job job,String text)throws Exception{for(int i=0;i<300;i++){if(transcript(job).contains(text))return;Thread.sleep(100);}throw new IllegalStateException("Missing PTY output "+text+": "+transcript(job));}
  private void close(TerminalSessions.Job job)throws Exception{runOnMainSync(job::close);awaitClosed(job);}
  private void awaitClosed(TerminalSessions.Job job)throws Exception{for(int i=0;i<150;i++){if(onMain(()->!job.session.isRunning()&&sessions.find(job.session)==null))return;Thread.sleep(100);}throw new IllegalStateException("PTY did not close");}
  private interface Value<T>{T get();}
  private <T>T onMain(Value<T> action){AtomicReference<T> result=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();runOnMainSync(()->{try{result.set(action.get());}catch(Throwable error){failure.set(error);}});if(failure.get()!=null)throw new IllegalStateException("Main-thread acceptance failure",failure.get());return result.get();}
  private interface IoAction{void run()throws IOException;}
  private static void expectIOException(IoAction action)throws IOException{try{action.run();}catch(IOException expected){return;}throw new IllegalStateException("Out-of-root directory accepted");}
  private static TerminalView find(View view){if(view instanceof TerminalView)return (TerminalView)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){TerminalView found=find(((ViewGroup)view).getChildAt(i));if(found!=null)return found;}return null;}
  private static boolean hasDescription(View view,String description){if(description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return true;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(hasDescription(((ViewGroup)view).getChildAt(i),description))return true;return false;}
  private static void removeFixture(File file){if(!Files.isSymbolicLink(file.toPath())){File[] children=file.listFiles();if(children!=null)for(File child:children)removeFixture(child);}file.delete();}
  private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
