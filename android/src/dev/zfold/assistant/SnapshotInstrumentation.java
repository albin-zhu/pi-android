package dev.zfold.assistant;
import android.app.*;
import android.content.Intent;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.widget.CheckBox;
import android.widget.EditText;
import org.json.*;

/** Read-only guard checks plus edits confined to our internal acceptance page. */
public final class SnapshotInstrumentation extends Instrumentation {
  Activity fixture;String error,receiptCode,receiptReason;boolean noiseAccepted,unrelatedChangeAccepted,changedTargetRejected;
  JSONObject screen,receipt;String target;
  @Override public void onCreate(Bundle args){start();}
  void read()throws Exception{
    PhoneAccessibility.instance.execute("screen_read",new JSONObject(),(value,failure)->{screen=value;error=failure;});
    target=null;JSONArray nodes=screen.getJSONArray("nodes");for(int i=0;i<nodes.length();i++){JSONObject n=nodes.getJSONObject(i);if(n.optString("resourceId").endsWith(":id/jev_fixture_input"))target=n.getString("nodeId");}
    if(target==null)throw new Exception("Fixture input missing");
  }
  void input(String value)throws Exception{PhoneAccessibility.instance.execute("ui_act",PiRuntime.json("generation",screen.getLong("generation"),"packageName","dev.zfold.assistant","action","input","nodeId",target,"text",value),(result,failure)->{receipt=result;error=failure;receiptCode=result==null?"error":result.optString("code");receiptReason=result==null?"error":result.optString("reason");});}
  @Override public void onStart(){
    try{
      PiRuntime.testMode=true;
      for(int i=0;i<100&&PhoneAccessibility.instance==null;i++)Thread.sleep(100);
      if(PhoneAccessibility.instance==null)throw new Exception("Accessibility not connected");
      fixture=startActivitySync(new Intent(getTargetContext(),PhoneTaskFixtureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
      runOnMainSync(()->{try{
        read();AccessibilityEvent event=AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);event.setPackageName("test.unrelated.window");PhoneAccessibility.instance.onAccessibilityEvent(event);event.recycle();
        input("snapshot guard test");noiseAccepted=receipt!=null&&receipt.optBoolean("performed");
      }catch(Exception e){error=e.toString();}});
      waitForIdleSync();Thread.sleep(200);
      runOnMainSync(()->{try{((CheckBox)fixture.findViewById(R.id.jev_fixture_toggle)).setText("页面已经更新");}catch(Exception e){error=e.toString();}});
      waitForIdleSync();Thread.sleep(200);
      runOnMainSync(()->{try{input("unrelated change accepted");unrelatedChangeAccepted=receipt!=null&&receipt.optBoolean("performed");}catch(Exception e){error=e.toString();}});
      waitForIdleSync();Thread.sleep(200);
      runOnMainSync(()->{try{read();EditText input=fixture.findViewById(R.id.jev_fixture_input);input.setHint("目标已变化");input.setContentDescription("目标已变化");}catch(Exception e){error=e.toString();}});
      waitForIdleSync();Thread.sleep(200);
      runOnMainSync(()->{try{input("must not be entered");changedTargetRejected=receipt!=null&&"stale_snapshot".equals(receipt.optString("code"))&&((EditText)fixture.findViewById(R.id.jev_fixture_input)).getText().toString().equals("unrelated change accepted");}catch(Exception e){error=e.toString();}});
    }catch(Exception e){error=e.toString();}
    runOnMainSync(()->{if(fixture!=null)fixture.finish();});
    Bundle result=new Bundle();result.putString("error",error);result.putString("receiptCode",receiptCode);result.putString("receiptReason",receiptReason);result.putBoolean("noiseAccepted",noiseAccepted);result.putBoolean("unrelatedChangeAccepted",unrelatedChangeAccepted);result.putBoolean("changedTargetRejected",changedTargetRejected);finish(error==null&&noiseAccepted&&unrelatedChangeAccepted&&changedTargetRejected?-1:0,result);
  }
}
