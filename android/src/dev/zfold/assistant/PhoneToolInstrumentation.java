package dev.zfold.assistant;

import android.app.*;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.*;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.json.*;

/** Real same-UID socket and production consent checks; no model, call, SMS or accepted mutation. */
public final class PhoneToolInstrumentation extends Instrumentation {
  private boolean screen;
  private String socketName;
  private UiAutomation automation;
  @Override public void onCreate(Bundle arguments){screen=arguments!=null&&"true".equals(arguments.getString("screen"));PiRuntime.testMode=true;start();}
  @Override public void onStart(){
    Bundle report=new Bundle();String failure="";
    try{
      automation=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
      socketName=PhoneToolServer.ensure(getTargetContext());
      PhoneToolPolicy.setAlwaysAllow(getTargetContext(),false);
      try(Client client=new Client()){
        check(client.ready.getInt("protocol")==1&&client.ready.getString("workspaceRoot").equals(PiRuntime.agentRoot(getTargetContext()).getCanonicalPath()),"Handshake roots/protocol");
        JSONArray tools=client.ready.getJSONArray("tools");check(tools.length()==18&&!tools.toString().contains("runtime_exec"),"Phone-only allowlist");
        JSONObject device=client.call("device","device_info",new JSONObject()).getJSONObject("result");
        check(Build.MODEL.equals(device.getString("model"))&&getTargetContext().getPackageName().equals(device.getString("packageName")),"device_info must describe this real Android device");
        check(client.call("capabilities","phone_capabilities",new JSONObject()).getJSONObject("result").has("telephony"),"Real phone capabilities");
        check(client.call("runtime","runtime_exec",PiRuntime.json("argv",new JSONArray().put("/system/bin/true"))).has("error"),"Runtime tool escaped allowlist");
        check(client.call("unknown","not_a_phone_tool",new JSONObject()).has("error"),"Unknown tool accepted");
        client.send(PiRuntime.json("type","tool_request","id","bad-args","name","device_info","args","invalid"));check(client.result("bad-args").has("error"),"Malformed args accepted");
        report.putBoolean("realDeviceCapabilitiesAndRestrictedDispatch",true);report.putString("deviceModel",device.getString("model"));

        getTargetContext().startActivity(new android.content.Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        waitPackage("com.android.settings");
        client.request("private-read","screen_read",new JSONObject());waitButton("拒绝");click("拒绝");
        check(client.result("private-read").optString("error").contains("拒绝"),"Sensitive read bypassed Android consent");
        waitPackage("com.android.settings"); // Denying consent must not steal target focus.
        client.request("cancelled","ui_back",new JSONObject());waitButton("仅允许这一次");
        try(Client other=new Client()){
          check(other.call("cancelled","device_info",new JSONObject()).has("error"),"Concurrent external controller was not bounded");
          CountDownLatch done=new CountDownLatch(1);String[] error={null};
          PhoneToolDispatcher.embedded(getTargetContext(),PiRuntime.json("id","embedded-busy","name","device_info","args",new JSONObject()),()->false,(value,problem)->{error[0]=problem;done.countDown();},null);
          check(done.await(5,TimeUnit.SECONDS)&&error[0]!=null,"Embedded route bypassed common ownership");
          client.send(PiRuntime.json("type","tool_cancel","id","cancelled","reason","instrumentation"));
          check(client.result("cancelled").has("error"),"Cancellation did not finish pending consent");
          waitNoButton();check(other.call("after-cancel","device_info",new JSONObject()).has("result"),"Cancelled request retained external slot");
        }
        report.putBoolean("sensitiveReadDeniedMutationCancelledAndSingleController",true);
      }
      Client disconnected=new Client();
      try{disconnected.request("disconnect","ui_back",new JSONObject());waitButton("仅允许这一次");}finally{disconnected.close();}
      waitNoButton();
      try(Client next=new Client()){check(next.call("after-disconnect","device_info",new JSONObject()).has("result"),"Disconnected approval still owns phone");}
      report.putBoolean("disconnectCancelsUnapprovedMutation",true);

      CountDownLatch embeddedDone=new CountDownLatch(1);
      PhoneToolDispatcher.embedded(getTargetContext(),PiRuntime.json("id","embedded-read","name","screen_read","args",new JSONObject()),()->false,(value,error)->embeddedDone.countDown(),null);
      check(embeddedDone.await(8,TimeUnit.SECONDS),"Embedded real phone dispatch did not finish");
      try(Client blocked=new Client()){check(blocked.call("embedded-owner","device_info",new JSONObject()).has("error"),"External route ignored embedded phone turn");}
      runOnMainSync(PhoneToolDispatcher::embeddedEnd);
      try(Client released=new Client()){check(released.call("released","device_info",new JSONObject()).has("result"),"Embedded turn end retained ownership");}
      report.putBoolean("embeddedTurnOwnershipAndRelease",true);

      rejectedFrame(0);rejectedFrame(PhoneToolServer.MAX_FRAME+1);
      try(Client partial=new Client()){
        partial.out.writeByte(0);partial.out.flush();
        check(partial.in.read()==-1,"Partial frame deadline did not close socket");
      }
      report.putBoolean("zeroOversizeAndPartialFrameRejected",true);
      try(Client client=new Client()){
        PhoneToolPolicy.setAlwaysAllow(getTargetContext(),true);
        JSONObject status=client.call("trusted-status","phone_sms",PiRuntime.json("action","status","operationId","missing-"+java.util.UUID.randomUUID())).getJSONObject("result");
        check(status.optString("status").equals("not_found")&&!status.optBoolean("performed"),"Always-allow did not execute actual read-only SMS status");
        check(!button("仅允许这一次",false),"Always-allow still prompted");
        check(client.call("trusted-forbidden","runtime_exec",new JSONObject()).has("error"),"Always-allow bypassed tool allowlist");
        PhoneToolPolicy.setAlwaysAllow(getTargetContext(),false);
        client.request("revoked","phone_sms",PiRuntime.json("action","status","operationId","missing"));
        waitButton("拒绝");click("拒绝");
        check(client.result("revoked").optString("error").contains("拒绝"),"Policy change did not revoke bypass on existing connection");
      }
      report.putBoolean("alwaysAllowActualReadAndLiveRevocation",true);
      if(screen){
        check(PhoneAccessibility.instance!=null,"Enable the app accessibility service before screen=true (instrumentation never changes permission settings)");
        try(Client client=new Client()){
          client.request("approved-read","screen_read",new JSONObject());waitButton("仅允许这一次");click("仅允许这一次");
          JSONObject result=client.result("approved-read");check(result.has("result")&&result.getJSONObject("result").has("nodes"),"Approved real screen_read failed: "+result);
          check(client.call("after-read","device_info",new JSONObject()).has("result"),"Completed sensitive read retained slot");
        }
        report.putBoolean("realScreenReadThroughVisibleAndroidConsent",true);
      }
      report.putString("scope","No provider requests; no approved mutations, calls, SMS, contacts or camera access");
    }catch(Throwable error){failure=error.toString();report.putString("error",failure);}
    finally{PhoneToolPolicy.setAlwaysAllow(getTargetContext(),false);runOnMainSync(()->{PhoneToolDispatcher.stopExternal();PhoneToolDispatcher.embeddedEnd();PhoneHud.forceHide();});}
    finish(failure.isEmpty()?Activity.RESULT_OK:Activity.RESULT_CANCELED,report);
  }
  private void rejectedFrame(int size)throws Exception{try(Client client=new Client()){client.out.writeInt(size);client.out.flush();check(client.in.read()==-1,"Invalid frame length accepted: "+size);}}
  private void waitPackage(String expected)throws Exception{
    for(int attempt=0;attempt<60;attempt++){
      AccessibilityNodeInfo root=automation.getRootInActiveWindow();
      if(root!=null)try{if(expected.contentEquals(root.getPackageName()==null?"":root.getPackageName()))return;}finally{root.recycle();}
      Thread.sleep(100);
    }
    throw new IllegalStateException("Consent did not preserve foreground package: "+expected);
  }
  private void waitButton(String label)throws Exception{
    for(int attempt=0;attempt<60;attempt++){if(button(label,false))return;Thread.sleep(100);}throw new IllegalStateException("Production Android approval button unavailable: "+label);
  }
  private void click(String label)throws Exception{check(button(label,true),"Android approval button click failed: "+label);Thread.sleep(150);}
  private boolean button(String label,boolean click){
    AccessibilityNodeInfo root=automation.getRootInActiveWindow();if(root==null)return false;
    try{
      for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(label)){
        try{if(node.isVisibleToUser()&&label.contentEquals(node.getText()==null?"":node.getText())&&node.isClickable())return !click||node.performAction(AccessibilityNodeInfo.ACTION_CLICK);}finally{node.recycle();}
      }
      return false;
    }finally{root.recycle();}
  }
  private void waitNoButton()throws Exception{for(int attempt=0;attempt<60;attempt++){if(!button("仅允许这一次",false))return;Thread.sleep(100);}throw new IllegalStateException("Cancelled/disconnected approval remained visible");}
  private final class Client implements AutoCloseable {
    final LocalSocket socket=new LocalSocket();final DataInputStream in;final DataOutputStream out;final JSONObject ready;
    Client()throws Exception{socket.connect(new LocalSocketAddress(socketName,LocalSocketAddress.Namespace.ABSTRACT));socket.setSoTimeout(10000);in=new DataInputStream(socket.getInputStream());out=new DataOutputStream(socket.getOutputStream());ready=frame();check(ready.optString("type").equals("bridge_ready"),"Missing ready frame");}
    void send(JSONObject message)throws IOException{byte[] bytes=message.toString().getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);out.flush();}
    void request(String id,String name,JSONObject args)throws IOException{send(PiRuntime.json("type","tool_request","id",id,"name",name,"args",args,"turnId","instrumentation"));}
    JSONObject call(String id,String name,JSONObject args)throws Exception{request(id,name,args);return result(id);}
    JSONObject result(String id)throws Exception{
      for(int count=0;count<20;count++){JSONObject value=frame();check(id.equals(value.optString("id")),"Outbound request ID changed");if(value.optString("type").equals("tool_result"))return value;check(value.optString("type").equals("tool_heartbeat"),"Unexpected frame: "+value);}
      throw new IOException("Tool result did not arrive");
    }
    JSONObject frame()throws Exception{int length=in.readInt();check(length>0&&length<=PhoneToolServer.MAX_FRAME,"Invalid server frame");byte[] bytes=new byte[length];in.readFully(bytes);return new JSONObject(new String(bytes,StandardCharsets.UTF_8));}
    public void close()throws IOException{socket.close();}
  }
  private static void check(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
