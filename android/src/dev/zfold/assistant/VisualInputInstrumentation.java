package dev.zfold.assistant;
import android.app.*;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.EditText;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Screenshot coordinates + accessibility IME, restricted to our fixture page. */
public final class VisualInputInstrumentation extends Instrumentation {
  Activity fixture;String error,keyboard;JSONObject frame,result;boolean staleRejected,fieldVerified,opaque;
  final CountDownLatch captured=new CountDownLatch(1),typed=new CountDownLatch(1);
  @Override public void onCreate(Bundle args){opaque=args!=null&&Boolean.parseBoolean(args.getString("opaque","false"));start();}
  void await(CountDownLatch latch)throws Exception{if(!latch.await(30,TimeUnit.SECONDS))throw new Exception("Timed out");if(error!=null)throw new Exception(error);}
  @Override public void onStart(){
    try{
      PiRuntime.testMode=true;keyboard=Settings.Secure.getString(getTargetContext().getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD);
      for(int i=0;i<100&&PhoneAccessibility.instance==null;i++)Thread.sleep(100);
      if(PhoneAccessibility.instance==null)throw new Exception("Accessibility not connected");
      fixture=startActivitySync(new Intent(getTargetContext(),PhoneTaskFixtureActivity.class).putExtra("opaque_input",opaque).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
      runOnMainSync(()->{try{PhoneAccessibility.instance.execute("screen_capture",new JSONObject(),(value,failure)->{error=failure;frame=value==null?null:value.optJSONObject("frame");captured.countDown();});}catch(Exception e){error=e.toString();captured.countDown();}});await(captured);
      runOnMainSync(()->{try{
        EditText input=fixture.findViewById(R.id.jev_fixture_input);int[] position=new int[2];input.getLocationOnScreen(position);
        double x=(position[0]+input.getWidth()/2.0)*frame.getInt("imageWidth")/frame.getInt("screenWidth"),y=(position[1]+input.getHeight()/2.0)*frame.getInt("imageHeight")/frame.getInt("screenHeight");
        PhoneAccessibility.instance.execute("ui_input",PiRuntime.json("screenshotId",frame.getString("screenshotId"),"x",x,"y",y,"packageName","dev.zfold.assistant","text","你好，视觉输入"),(value,failure)->{result=value;error=failure;typed.countDown();});
      }catch(Exception e){error=e.toString();typed.countDown();}});await(typed);
      runOnMainSync(()->{
        fieldVerified=((EditText)fixture.findViewById(R.id.jev_fixture_input)).getText().toString().equals("你好，视觉输入");
        try{PhoneAccessibility.instance.execute("ui_tap",PiRuntime.json("screenshotId",frame.optString("screenshotId"),"x",1,"y",1),(value,failure)->{});}catch(Exception expected){staleRejected=true;}
      });
    }catch(Exception e){error=e.getMessage();}
    boolean keyboardPreserved=java.util.Objects.equals(keyboard,Settings.Secure.getString(getTargetContext().getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD));
    Bundle out=new Bundle();out.putString("error",error);out.putBoolean("opaqueEditor",opaque);out.putString("inputResult",result==null?null:result.toString());out.putBoolean("fieldVerified",fieldVerified);out.putBoolean("staleFrameRejected",staleRejected);out.putBoolean("keyboardPreserved",keyboardPreserved);
    boolean reportedHonestly=result!=null&&(opaque?!result.optBoolean("verified")&&result.optBoolean("requiresVisualVerification"):result.optBoolean("verified"));
    runOnMainSync(()->{if(fixture!=null)fixture.finish();});finish(error==null&&fieldVerified&&reportedHonestly&&staleRejected&&keyboardPreserved?-1:0,out);
  }
}
