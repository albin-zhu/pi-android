package dev.zfold.assistant;

import android.app.*;
import android.content.Intent;
import android.net.Uri;
import android.os.*;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Emulator acceptance: real form, MCP transport, tool call and OAuth callback. */
public final class ConnectorInstrumentation extends Instrumentation implements PiRuntime.Listener {
  private PiRuntime runtime;private ConnectorActivity activity;
  private final BlockingQueue<JSONObject> events=new LinkedBlockingQueue<>();
  private final Set<String> created=new HashSet<>();private volatile JSONObject state;
  @Override public void onCreate(Bundle args){PiRuntime.testMode=true;start();}
  @Override public void event(JSONObject event){if(event.optString("type").equals("connectors"))state=event;if(event.optString("type").equals("connector_result"))events.add(event);}
  @Override public void onStart(){
    Bundle report=new Bundle();String error="";
    try{
      runOnMainSync(()->{runtime=PiRuntime.get(getTargetContext());runtime.listen(this);});
      for(int i=0;i<300&&!runtime.ready;i++)Thread.sleep(100);require(runtime.ready,"Runtime not ready");require(runtime.isTestRuntime(),"Test runtime isolation missing");
      runOnMainSync(()->runtime.send(PiRuntime.json("type","connector_manage","action","list")));
      for(int i=0;i<100&&state==null;i++)Thread.sleep(100);
      if(state!=null){JSONArray old=state.optJSONArray("servers");for(int i=0;i<old.length();i++)if(old.optJSONObject(i).optString("name").startsWith("QA "))request("remove",PiRuntime.json("id",old.getJSONObject(i).getString("id")));}
      activity=(ConnectorActivity)startActivitySync(new Intent(getTargetContext(),ConnectorActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      click("添加连接");click("远程 MCP 地址");fill("连接名称","QA Remote");fill("MCP 地址","http://127.0.0.1:18874/bearer");
      click("浏览器登录（OAuth）");click("Bearer Token");fill("Token","wrong-token");click("保存并连接");
      JSONObject failed=waitServer("QA Remote","error");String id=failed.getString("id");created.add(id);report.putBoolean("authErrorVisible",true);
      click("管理连接 QA Remote");click("编辑连接");fill("Token","fixture-bearer-secret");click("保存并连接");
      JSONObject connected=waitServer("QA Remote","connected");require(connected.optInt("toolCount")==2,"Tools missing");require(!connected.toString().contains("fixture-bearer-secret"),"Token exposed to UI state");report.putBoolean("addEditConnect",true);
      waitNode("查看工具",true);screenshot("connector-connected.png");click("查看工具");clickContaining("echo\n");fill("调用参数 JSON","{\"value\":\"ANDROID_MCP_OK\"}");click("执行这个工具");
      waitNode("MCP_RESULT:ANDROID_MCP_OK",false);report.putBoolean("manualToolCall",true);screenshot("connector-tool-result.png");click("完成");
      click("管理连接 QA Remote");click("断开连接");waitServer("QA Remote","disabled");click("连接");waitServer("QA Remote","connected");report.putBoolean("disconnectReconnect",true);
      click("管理连接 QA Remote");click("删除连接");click("删除");waitAbsent(id);created.remove(id);report.putBoolean("remove",true);

      click("添加连接");click("导入 JSON");fill("MCP 配置 JSON","{\"mcpServers\":{\"QA Imported\":{\"url\":\"http://127.0.0.1:18874/mcp\",\"auth\":\"none\"}}}");click("导入并连接");
      JSONObject imported=waitServer("QA Imported","connected");created.add(imported.getString("id"));report.putBoolean("import",true);

      String program="require('node:readline').createInterface({input:process.stdin}).on('line',line=>{const q=JSON.parse(line);if(q.id===undefined)return;let result=q.method==='initialize'?{protocolVersion:q.params.protocolVersion,serverInfo:{name:'Android stdio fixture',version:'1'},capabilities:{tools:{}}}:q.method==='tools/list'?{tools:[{name:'echo',inputSchema:{type:'object',properties:{value:{type:'string'}}}}]}:{content:[{type:'text',text:'ANDROID_STDIO:'+q.params.arguments.value}]};process.stdout.write(JSON.stringify({jsonrpc:'2.0',id:q.id,result})+'\\n');});";
      JSONObject local=request("save",PiRuntime.json("config",PiRuntime.json("name","QA Local","transport","stdio","command","node","args",new JSONArray().put("-e").put(program))));
      String localId=local.getString("id");created.add(localId);require(local.getJSONObject("server").optBoolean("connected"),"Android stdio failed: "+local);
      JSONObject output=request("call",PiRuntime.json("id",localId,"tool","echo","arguments",PiRuntime.json("value","LOCAL_OK")));require(output.toString().contains("ANDROID_STDIO:LOCAL_OK"),"Local MCP output missing");report.putBoolean("androidStdio",true);

      JSONObject oauth=request("save",PiRuntime.json("config",PiRuntime.json("name","QA OAuth","url","http://127.0.0.1:18874/oauth","auth","oauth")));
      JSONObject auth=oauth.getJSONObject("server");created.add(auth.getString("id"));require(auth.optString("state").equals("auth_required"),"OAuth login state missing");
      // Follow the controlled issuer's redirect without external accounts; deliver the real Android VIEW callback.
      HttpURLConnection authorization=(HttpURLConnection)new URL(auth.getString("authUrl")).openConnection();authorization.setInstanceFollowRedirects(false);authorization.setConnectTimeout(10000);authorization.setReadTimeout(10000);
      String callback;try{require(authorization.getResponseCode()==302,"Issuer did not redirect");callback=authorization.getHeaderField("Location");}finally{authorization.disconnect();}
      getTargetContext().startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(callback)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      waitServer("QA OAuth","connected");waitNode("QA OAuth",true);report.putBoolean("oauthCallbackAndReturn",true);screenshot("connector-oauth.png");
    }catch(Throwable failure){error=failure.toString();report.putString("error",error);}
    finally{
      for(String id:created)try{request("remove",PiRuntime.json("id",id));}catch(Exception ignored){}
      runOnMainSync(()->{if(runtime!=null)runtime.unlisten(this);if(activity!=null)activity.finishAndRemoveTask();});
    }
    finish(error.isEmpty()?-1:0,report);
  }
  private JSONObject request(String action,JSONObject fields)throws Exception{
    String id=UUID.randomUUID().toString();fields.put("type","connector_manage").put("action",action).put("requestId",id);runOnMainSync(()->runtime.send(fields));
    long until=System.currentTimeMillis()+30000;while(System.currentTimeMillis()<until){JSONObject result=events.poll(1,TimeUnit.SECONDS);if(result!=null&&result.optString("requestId").equals(id)){require(result.optBoolean("ok"),result.optString("error"));return result;}}throw new IllegalStateException("No result for "+action);
  }
  private JSONObject waitServer(String name,String expected)throws Exception{
    long until=System.currentTimeMillis()+30000;JSONObject found=null;
    while(System.currentTimeMillis()<until){JSONObject snapshot=state;JSONArray servers=snapshot==null?null:snapshot.optJSONArray("servers");if(servers!=null)for(int i=0;i<servers.length();i++){JSONObject server=servers.getJSONObject(i);if(server.optString("name").equals(name)){found=server;if(server.optString("state").equals(expected)){Thread.sleep(250);return server;}}}Thread.sleep(100);}
    throw new IllegalStateException("Server "+name+" did not reach "+expected+": "+found);
  }
  private void waitAbsent(String id)throws Exception{for(int n=0;n<100;n++){boolean found=false;JSONArray servers=state.optJSONArray("servers");for(int i=0;i<servers.length();i++)if(servers.optJSONObject(i).optString("id").equals(id))found=true;if(!found)return;Thread.sleep(100);}throw new IllegalStateException("Removed server still present");}
  private AccessibilityNodeInfo find(AccessibilityNodeInfo root,String label,boolean exact){if(root==null)return null;String text=String.valueOf(root.getText()),description=String.valueOf(root.getContentDescription());if(root.isVisibleToUser()&&(exact?(label.equals(text)||label.equals(description)):(text.contains(label)||description.contains(label))))return root;for(int i=0;i<root.getChildCount();i++){AccessibilityNodeInfo match=find(root.getChild(i),label,exact);if(match!=null)return match;}return null;}
  private AccessibilityNodeInfo waitNode(String label,boolean exact)throws Exception{for(int i=0;i<100;i++){AccessibilityNodeInfo node=find(getUiAutomation().getRootInActiveWindow(),label,exact);if(node!=null)return node;Thread.sleep(100);}throw new IllegalStateException("UI element missing: "+label);}
  private void click(String label)throws Exception{clickLabel(label,true);}
  private void clickContaining(String label)throws Exception{clickLabel(label,false);}
  private void clickLabel(String label,boolean exact)throws Exception{android.util.Log.i("ConnectorQA","Click "+label);for(int i=0;i<100;i++){AccessibilityNodeInfo node=findClickable(getUiAutomation().getRootInActiveWindow(),label,exact);if(node!=null&&node.performAction(AccessibilityNodeInfo.ACTION_CLICK)){Thread.sleep(220);return;}Thread.sleep(100);}throw new IllegalStateException("UI click failed: "+label);}
  private AccessibilityNodeInfo findClickable(AccessibilityNodeInfo root,String label,boolean exact){if(root==null)return null;String text=String.valueOf(root.getText()),description=String.valueOf(root.getContentDescription());if(root.isVisibleToUser()&&(exact?(label.equals(text)||label.equals(description)):(text.contains(label)||description.contains(label)))){AccessibilityNodeInfo node=root;while(node!=null&&!node.isClickable())node=node.getParent();if(node!=null)return node;}for(int i=0;i<root.getChildCount();i++){AccessibilityNodeInfo node=findClickable(root.getChild(i),label,exact);if(node!=null)return node;}return null;}
  private void clickNode(AccessibilityNodeInfo node)throws Exception{while(node!=null&&!node.isClickable())node=node.getParent();require(node!=null&&node.performAction(AccessibilityNodeInfo.ACTION_CLICK),"UI click failed");Thread.sleep(180);}
  private void fill(String label,String text)throws Exception{AccessibilityNodeInfo node=waitNode(label,true);if(!node.isEditable()){AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();node=findEditable(root,label);}require(node!=null,"Editable field missing: "+label);Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);require(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args),"Text input failed: "+label);}
  private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo root,String label){if(root==null)return null;if(root.isEditable()&&label.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;for(int i=0;i<root.getChildCount();i++){AccessibilityNodeInfo found=findEditable(root.getChild(i),label);if(found!=null)return found;}return null;}
  private void screenshot(String name)throws Exception{android.graphics.Bitmap image=getUiAutomation().takeScreenshot();require(image!=null,"Screenshot failed");try(FileOutputStream output=new FileOutputStream(new File(getTargetContext().getCacheDir(),name))){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}finally{image.recycle();}}
  private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
