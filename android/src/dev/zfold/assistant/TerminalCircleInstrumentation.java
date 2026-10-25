package dev.zfold.assistant;

import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/** Dedicated-package tests: durable composer state and exact bytes through real native PTYs. */
public final class TerminalCircleInstrumentation extends Instrumentation {
  private Bundle options;
  private TerminalActivity activity;
  private TerminalSessions sessions;
  private File fixture;
  private final ArrayList<TerminalSessions.Job> owned=new ArrayList<>();
  private final ArrayList<TerminalCircleDraft.Draft> captures=new ArrayList<>();

  @Override public void onCreate(Bundle args){PiRuntime.testMode=true;options=TerminalWorkspaceInstrumentation.launchOptions(args);start();}
  @Override public void onStart(){
    Bundle report=new Bundle();String failure="";
    SharedPreferences preferences=getTargetContext().getSharedPreferences("test-terminal-workspaces",Context.MODE_PRIVATE);
    String saved=preferences.getString("state",null);
    try{
      sessions=main(()->TerminalSessions.get(getTargetContext()));
      require(main(()->sessions.jobs.isEmpty()),"Dedicated test process required; refusing to change existing sessions");
      require(TerminalCircleDraft.load(getTargetContext())==null,"Preexisting test draft must be resolved first");
      File root=PiRuntime.agentRoot(getTargetContext());require(root.isDirectory()||root.mkdirs(),"Test root unavailable");
      fixture=Files.createTempDirectory(root.toPath(),"circle-pty-").toFile();
      TerminalCircleDraft.Draft empty=capture();launch();awaitComposer();
      require(main(()->sessions.jobs.isEmpty()),"Circle capture silently created a terminal");
      require(main(()->get(panel(),"target"))==null,"Empty capture acquired an implicit target");
      main(()->{editor().setText("问题保留\n第二行");return null;});
      recreate();awaitComposer();
      require(main(()->editor().getText().toString()).equals("问题保留\n第二行"),"Activity recreation lost edited question");
      require(TerminalCircleDraft.load(getTargetContext()).id.equals(empty.id),"Activity recreation changed draft identity");
      click(AlertDialog.BUTTON_NEGATIVE);
      require(main(()->dialog())==null&&main(()->panel().reopen.getVisibility())==View.VISIBLE,"Dismiss stranded pending draft");
      main(()->{panel().reopen.performClick();return null;});awaitComposer();
      require(main(()->editor().getText().toString()).equals("问题保留\n第二行"),"Reopening lost question");
      click(AlertDialog.BUTTON_POSITIVE);
      require(TerminalCircleDraft.load(getTargetContext())!=null&&main(()->sessions.jobs.isEmpty()),"No-target insertion consumed draft or spawned a session");
      click(AlertDialog.BUTTON_NEUTRAL);require(!empty.file.exists(),"Discard retained owned image");
      report.putBoolean("noSessionRecreationDismissReopen",true);

      TerminalCircleDraft.Draft missing=capture();require(missing.file.delete(),"Missing-file fixture not removed");
      main(()->{panel().refresh(true);return null;});awaitComposer();
      click(AlertDialog.BUTTON_NEUTRAL);
      require(TerminalCircleDraft.load(getTargetContext())==null,"Missing image stranded the pending draft");
      report.putBoolean("missingImageCanBeDiscarded",true);

      TerminalWorkspaces store=TerminalWorkspaces.get(getTargetContext());
      TerminalWorkspaces.Entry workspace=store.upsert("Circle PTY fixture",fixture,false);store.select(workspace.id);
      File firstBytes=new File(fixture,"first-input"),secondBytes=new File(fixture,"second-input");
      TerminalSessions.Job first=receiver(firstBytes),second=receiver(secondBytes);
      awaitReceiver(first,firstBytes);awaitReceiver(second,secondBytes);
      main(()->{sessions.select(first);return null;});
      TerminalCircleDraft.Draft pending=capture();
      Bitmap png=BitmapFactory.decodeFile(pending.file.getPath());require(png!=null&&png.getWidth()==24&&png.getHeight()==16,"Captured PNG is not decodable");png.recycle();
      main(()->{sessions.select(second);activity.onNewIntent(new Intent(activity,TerminalActivity.class));return null;});awaitComposer();
      require(main(()->get(panel(),"target"))==first,"Capture-time target changed with selected tab");
      main(()->{editor().setText("检查这张图\n保留输入");first.ctrl=true;first.session.write("EXISTING ");return null;});
      TerminalCircleDraft.Draft expected=TerminalCircleDraft.update(getTargetContext(),pending.id,"检查这张图\n保留输入",first.session.mHandle);
      String insertion=TerminalCircleDraft.insertion(getTargetContext(),expected,false);
      require(insertion.indexOf('\n')<0&&insertion.indexOf('\r')<0&&insertion.indexOf('\u001b')<0,"Insertion contains execution/control bytes");
      View insertButton=main(()->dialog().getButton(AlertDialog.BUTTON_POSITIVE));
      main(()->{insertButton.performClick();insertButton.performClick();return null;});
      awaitBytes(firstBytes,"EXISTING "+insertion);
      require(main(()->get(activity,"current"))==first&&!first.ctrl,"Insertion did not switch exact target and clear Ctrl");
      require(TerminalCircleDraft.load(getTargetContext())==null&&pending.file.isFile(),"Consume did not clear only metadata");
      main(()->{activity.onNewIntent(new Intent(activity,TerminalActivity.class));return null;});recreate();Thread.sleep(250);
      require(read(firstBytes).equals("EXISTING "+insertion)&&read(secondBytes).isEmpty(),"Resume/recreation replayed or retargeted insertion");
      report.putBoolean("captureTargetExactPtyBytesNoEnterOnceOnly",true);

      main(()->{sessions.select(first);return null;});TerminalCircleDraft.Draft closing=capture();
      main(()->{panel().refresh(true);return null;});awaitComposer();
      main(()->{first.close();return null;});
      click(AlertDialog.BUTTON_POSITIVE);
      require(TerminalCircleDraft.load(getTargetContext()).id.equals(closing.id)&&read(secondBytes).isEmpty(),"Closed target silently retargeted or consumed draft");
      main(()->{((TextView)get(panel(),"targetLabel")).performClick();AlertDialog choices=(AlertDialog)get(panel(),"targetDialog");choices.getListView().performItemClick(null,0,0);return null;});
      require(main(()->get(panel(),"target"))==second,"Explicit replacement target was not selected");
      String secondInsertion=TerminalCircleDraft.insertion(getTargetContext(),TerminalCircleDraft.load(getTargetContext()),false);
      click(AlertDialog.BUTTON_POSITIVE);awaitBytes(secondBytes,secondInsertion);
      report.putBoolean("closedTargetRequiresExplicitSelection",true);

      TerminalCircleDraft.Draft stale=capture();main(()->{panel().refresh(true);return null;});awaitComposer();
      TerminalCircleDraft.discard(getTargetContext(),stale.id);TerminalCircleDraft.Draft replacement=capture();
      click(AlertDialog.BUTTON_POSITIVE);
      require(TerminalCircleDraft.load(getTargetContext()).id.equals(replacement.id)&&read(secondBytes).equals(secondInsertion),"Stale composer consumed a replacement draft");
      main(()->{panel().refresh(true);return null;});awaitComposer();click(AlertDialog.BUTTON_NEUTRAL);
      report.putBoolean("changedDraftIdentityRejected",true);

      main(()->{set(activity,"pendingPickerHandle",second.session.mHandle);return null;});
      TerminalCircleDraft.Draft deferred=capture();main(()->{panel().refresh(true);return null;});
      require(main(()->dialog())==null&&TerminalCircleDraft.load(getTargetContext()).id.equals(deferred.id),"Circle composer conflicted with active SAF picker");
      main(()->{set(activity,"pendingPickerHandle",null);panel().refresh(false);return null;});awaitComposer();click(AlertDialog.BUTTON_NEUTRAL);
      report.putBoolean("attachmentPickerDefersComposer",true);
      TerminalCircleDraft.Draft shared=main(()->TerminalCircleDraft.capture(getTargetContext(),null,"共享文字\n第二行"));
      captures.add(shared);
      require(shared.file==null&&TerminalCircleDraft.load(getTargetContext()).question.equals("共享文字\n第二行"),"Text share was not preserved as a durable draft");
      main(()->{panel().refresh(true);return null;});awaitComposer();recreate();awaitComposer();
      require(main(()->editor().getText().toString()).equals("共享文字\n第二行"),"Text-only composer lost shared text");
      click(AlertDialog.BUTTON_POSITIVE);awaitBytes(secondBytes,secondInsertion+"共享文字 第二行");
      require(TerminalCircleDraft.load(getTargetContext())==null,"Text share was not explicitly consumed");
      report.putBoolean("sharedTextDraftSurvivesRecreationWithoutEnter",true);
      report.putString("processRecreation","Activity recreation exercised; Android process kill not exercised");
    }catch(Throwable error){failure=error.toString();report.putString("error",failure);}
    finally{
      try{main(()->{for(TerminalSessions.Job job:owned)job.close();if(activity!=null)activity.finish();return null;});}catch(Exception ignored){}
      for(TerminalCircleDraft.Draft draft:captures)try{TerminalCircleDraft.discard(getTargetContext(),draft.id);if(draft.file!=null){draft.file.delete();draft.file.getParentFile().delete();}}catch(Exception ignored){}
      if(fixture!=null){new File(fixture,"first-input").delete();new File(fixture,"second-input").delete();fixture.delete();}
      SharedPreferences.Editor edit=preferences.edit();if(saved==null)edit.remove("state");else edit.putString("state",saved);edit.commit();
    }
    finish(failure.isEmpty()?-1:0,report);
  }

  private TerminalCircleDraft.Draft capture()throws Exception{
    return main(()->{Bitmap image=Bitmap.createBitmap(24,16,Bitmap.Config.ARGB_8888);image.eraseColor(0xff37a86b);try{TerminalCircleDraft.Draft draft=TerminalCircleDraft.capture(getTargetContext(),image);captures.add(draft);return draft;}finally{image.recycle();}});
  }
  private TerminalSessions.Job receiver(File bytes)throws Exception{
    String command="stty -echo -icanon min 1 time 0; printf '\\033[?2004lCIRCLE_READY\\n'; exec cat > "+TerminalAttachmentFiles.quote(bytes.getPath());
    return main(()->{TerminalSessions.Job created=sessions.createCommand(new String[]{"/system/bin/sh","-c",command},"圈选字节接收",false);owned.add(created);created.session.updateSize(100,30,10,20);return created;});
  }
  private void awaitReceiver(TerminalSessions.Job job,File bytes)throws Exception{
    for(int i=0;i<100;i++){if(bytes.isFile()&&job.id>0&&main(()->job.session.getEmulator().getScreen().getTranscriptText().contains("CIRCLE_READY")))return;Thread.sleep(50);}
    throw new IllegalStateException("Real PTY receiver failed: "+job.status);
  }
  private void launch(){activity=(TerminalActivity)startActivitySync(new Intent(getTargetContext(),TerminalActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),options);}
  private void recreate()throws Exception{
    ActivityMonitor monitor=addMonitor(TerminalActivity.class.getName(),null,false);
    try{main(()->{activity.recreate();return null;});TerminalActivity next=(TerminalActivity)waitForMonitorWithTimeout(monitor,10000);require(next!=null&&next!=activity,"Recreation did not replace Activity");activity=next;waitForIdleSync();}
    finally{removeMonitor(monitor);}
  }
  private TerminalCirclePanel panel()throws Exception{return (TerminalCirclePanel)get(activity,"circlePanel");}
  private AlertDialog dialog()throws Exception{return (AlertDialog)get(panel(),"dialog");}
  private EditText editor()throws Exception{return (EditText)get(panel(),"question");}
  private void awaitComposer()throws Exception{for(int i=0;i<100;i++){if(main(()->dialog()!=null&&dialog().isShowing()))return;Thread.sleep(50);}throw new IllegalStateException("Circle composer missing");}
  private void click(int button)throws Exception{main(()->{require(dialog()!=null,"Composer missing");dialog().getButton(button).performClick();return null;});}
  private void awaitBytes(File file,String expected)throws Exception{for(int i=0;i<100;i++){if(read(file).equals(expected))return;Thread.sleep(50);}throw new IllegalStateException("PTY bytes mismatch: "+read(file));}
  private static String read(File file)throws Exception{return file.isFile()?new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8):"";}
  private <T>T main(Callable<T> work)throws Exception{
    AtomicReference<T> value=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();
    runOnMainSync(()->{try{value.set(work.call());}catch(Throwable problem){error.set(problem);}});
    if(error.get()!=null)throw new IllegalStateException(error.get());return value.get();
  }
  private static Object get(Object object,String name)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
  private static void set(Object object,String name,Object value)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);}
  private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
