package dev.zfold.assistant;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.json.*;

/** Emulator only: synthetic provider events and a loopback MCP issuer; no real accounts. */
public final class OAuthInstrumentation extends Instrumentation implements PiRuntime.Listener {
  private MainActivity chat;private PiRuntime runtime;private ProviderAuthDialog panel;private ConnectorActivity connector;
  private final ArrayList<JSONObject> commands=new ArrayList<>();private final BlockingQueue<JSONObject> results=new LinkedBlockingQueue<>();private final Set<String> created=new HashSet<>();
  private volatile JSONObject connectors;private ActivityMonitor browserMonitor;private File config;private byte[] saved;private boolean captured;
  @Override public void onCreate(Bundle args){start();}
  public void event(JSONObject event){if(event.optString("type").equals("connector_result"))results.add(event);if(event.optString("type").equals("connectors"))connectors=event;}
  @Override public void onStart(){Bundle report=new Bundle();int code=Activity.RESULT_OK;
    try{
      if(!Build.HARDWARE.contains("ranchu")&&!Build.HARDWARE.contains("goldfish"))throw new IllegalStateException("Emulator only");
      PiRuntime.testMode=true;config=new File(CredentialStore.directory(getTargetContext()),"connectors.json");saved=config.isFile()?Files.readAllBytes(config.toPath()):null;captured=true;
      EmulatorChecks.shell(this,"am start --windowingMode 1 -n dev.zfold.assistant/.MainActivity");await(()->MainActivity.current()!=null&&MainActivity.current().runtime.ready,"Runtime did not start");chat=MainActivity.current();runtime=chat.runtime;runtime.listen(this);
      runOnMainSync(()->AuthSessionService.update(chat,PiRuntime.json("requestId","qa-auth-lifetime","state","started","active",true)));await(AuthSessionService::running,"OAuth foreground lifetime missing");
      runOnMainSync(()->AuthSessionService.update(chat,PiRuntime.json("requestId","qa-auth-lifetime","state","success","active",false)));await(()->!AuthSessionService.running(),"OAuth foreground lifetime not released");report.putBoolean("loginHasBoundedBackgroundLifetime",true);
      IntentFilter browser=new IntentFilter(Intent.ACTION_VIEW);browser.addDataScheme("https");browser.addDataScheme("http");browserMonitor=addMonitor(browser,new ActivityResult(Activity.RESULT_CANCELED,null),true);
      openPanel();deliver(providers(new JSONArray()));clickPanel("使用 OAuth 登录 OpenAI");String id=lastCommand().optString("requestId");
      JSONObject link=PiRuntime.json("url","https://auth.example/authorize?state=fixture","instructions","fixture");
      JSONObject pending=flow(id,"waiting").put("browser",link);deliver(pending);
      AlertDialog first=dialog();runOnMainSync(()->first.getButton(AlertDialog.BUTTON_POSITIVE).performClick());check(browserMonitor.getHits()==1&&first.isShowing(),"Browser launch lost the pending UI");
      JSONObject manual=flow(id,"prompt").put("browser",link).put("promptId",id+":prompt:fixture").put("prompt",PiRuntime.json("type","manual_code","message","callback","placeholder","http://localhost:1455/auth/callback"));deliver(manual);
      AlertDialog combined=dialog();check(combined.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString().equals("打开浏览器"),"Manual prompt replaced browser entry");EditText input=(EditText)find(combined.getWindow().getDecorView(),"登录信息输入");check(input!=null&&input.length()==0&&!input.isSaveEnabled(),"Authorization URL prefilled or secret state persisted");
      runOnMainSync(()->{input.setText("http://localhost:1455/auth/callback?code=fixture-code&state=fixture");combined.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();});
      check(lastCommand().optString("promptId").equals(id+":prompt:fixture")&&lastCommand().optString("requestId").equals(id),"Manual callback addressed the wrong prompt");report.putBoolean("browserAndManualPromptShareOneFlow",true);
      deliver(flow(id,"success").put("active",false));check(!combined.isShowing(),"Completed login dialog remained open");report.putBoolean("successDismissesActualDialog",true);
      runOnMainSync(panel::close);openPanel();deliver(providers(new JSONArray().put(manual)));clickPanel("继续登录 OpenAI");check(dialog().getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled(),"Reopened panel lost manual callback");
      AlertDialog resumed=dialog();runOnMainSync(()->resumed.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());await(()->commands.stream().anyMatch(c->c.optString("action").equals("cancel")&&c.optString("requestId").equals(id)),"Cancel did not reach runtime");report.putBoolean("resumeAndCancelUseOriginalRequest",true);
      deliver(providers(new JSONArray()));clickPanel("使用 OAuth 登录 OpenAI");String deviceId=lastCommand().optString("requestId");deliver(flow(deviceId,"waiting").put("device",PiRuntime.json("verificationUri","https://auth.example/device","userCode","ABCD-EFGH","expiresInSeconds",900)));
      AlertDialog device=dialog();runOnMainSync(()->device.getButton(AlertDialog.BUTTON_POSITIVE).performClick());check(device.isShowing()&&find(device.getWindow().getDecorView(),"登录设备码")!=null,"Opening browser hid device code");
      int cancellations=countCancels();runOnMainSync(panel::close);check(countCancels()==cancellations,"Closing panel cancelled process-owned login");report.putBoolean("deviceCodeSurvivesBrowserAndPanelClose",true);

      JSONObject missing=request("save",PiRuntime.json("config",PiRuntime.json("name","QA OAuth preregistration","url","http://127.0.0.1:18875/oauth","auth","oauth")));
      String connectorId=missing.getString("id");created.add(connectorId);JSONObject server=missing.getJSONObject("server");check(server.optBoolean("oauthNeedsClient")&&server.optString("error").contains("Client ID"),"Missing registration guidance");
      ActivityMonitor page=addMonitor(ConnectorActivity.class.getName(),null,false);runOnMainSync(()->ConnectorActivity.open(chat));connector=(ConnectorActivity)waitForMonitorWithTimeout(page,10000);removeMonitor(page);check(connector!=null,"Connector page missing");
      java.lang.reflect.Method edit=ConnectorActivity.class.getDeclaredMethod("edit",JSONObject.class,boolean.class);edit.setAccessible(true);runOnMainSync(()->{try{edit.invoke(connector,server,true);}catch(Exception error){throw new IllegalStateException(error);}});
      AlertDialog editor=connectorDialog();EditText client=(EditText)find(editor.getWindow().getDecorView(),"OAuth Client ID（仅预注册时填写）"),secret=(EditText)find(editor.getWindow().getDecorView(),"OAuth Client Secret（服务要求时填写）");
      check(client!=null&&secret!=null&&secret.length()==0&&!secret.isSaveEnabled()&&secret.getImportantForAutofill()==View.IMPORTANT_FOR_AUTOFILL_NO,"Secret input is missing or exposed");
      AlertDialog initialEditor=editor;runOnMainSync(()->{client.setText("fixture-client");secret.setText("fixture-client-secret");initialEditor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();});await(()->server(connectorId)!=null&&server(connectorId).optString("state").equals("auth_required"),"Pre-registered client did not start login");report.putBoolean("clientSecretFormAndNonDcrGuidance",true);
      JSONObject ready=server(connectorId);check(!ready.toString().contains("fixture-client-secret"),"Secret was exposed in UI snapshot");
      HttpURLConnection authorization=(HttpURLConnection)new URL(ready.getString("authUrl")).openConnection();authorization.setInstanceFollowRedirects(false);authorization.setConnectTimeout(5000);authorization.setReadTimeout(5000);String callback;
      try{check(authorization.getResponseCode()==302,"Fixture issuer did not redirect");callback=authorization.getHeaderField("Location");}finally{authorization.disconnect();}
      getTargetContext().startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(callback)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));await(()->server(connectorId)!=null&&server(connectorId).optBoolean("connected"),"Android OAuth callback did not finish");report.putBoolean("mcpAndroidCallbackExchangesPrivateClientSecret",true);
      JSONObject authenticated=server(connectorId);runOnMainSync(()->{try{edit.invoke(connector,authenticated,true);}catch(Exception error){throw new IllegalStateException(error);}});editor=connectorDialog();EditText savedField=(EditText)find(editor.getWindow().getDecorView(),"OAuth Client Secret（服务要求时填写）");check(savedField.length()==0,"Saved client secret was rendered");report.putBoolean("savedClientSecretNeverPrefills",true);
      AlertDialog finalEditor=editor;runOnMainSync(()->finalEditor.dismiss());EmulatorChecks.screenshot(this,"oauth-connector-fixed.png");
    }catch(Throwable error){report.putString("failure",error.toString());code=Activity.RESULT_CANCELED;}
    finally{
      if(browserMonitor!=null)removeMonitor(browserMonitor);
      if(chat!=null)runOnMainSync(()->AuthSessionService.update(chat,PiRuntime.json("requestId","qa-auth-lifetime","state","cancelled","active",false)));
      for(String id:created)try{request("remove",PiRuntime.json("id",id));}catch(Exception ignored){}
      if(runtime!=null)runtime.unlisten(this);runOnMainSync(()->{if(panel!=null)panel.close();if(connector!=null)connector.finishAndRemoveTask();if(chat!=null)chat.finish();});
      if(captured)try{if(saved==null)Files.deleteIfExists(config.toPath());else Files.write(config.toPath(),saved);}catch(Exception error){report.putString("cleanupFailure",error.toString());code=Activity.RESULT_CANCELED;}
    }
    finish(code,report);
  }
  private JSONObject providers(JSONArray flows){return PiRuntime.json("type","auth_state","state","providers","providers",new JSONArray().put(PiRuntime.json("id","openai-codex","name","OpenAI","oauthLogin",true)),"availableProviders",new JSONArray(),"flows",flows);}
  private JSONObject flow(String id,String state){return PiRuntime.json("type","auth_state","requestId",id,"provider","openai-codex","authType","oauth","state",state,"active",true);}
  private void openPanel(){runOnMainSync(()->{panel=new ProviderAuthDialog(chat.overflowButton,null,commands::add);panel.open();});}
  private void deliver(JSONObject event){runOnMainSync(()->panel.event(event));}
  private JSONObject lastCommand(){return commands.get(commands.size()-1);}
  private int countCancels(){return (int)commands.stream().filter(c->c.optString("action").equals("cancel")).count();}
  private void clickPanel(String label)throws Exception{java.lang.reflect.Field field=ProviderAuthDialog.class.getDeclaredField("frame");field.setAccessible(true);View root=(View)field.get(panel);runOnMainSync(()->{View action=find(root,label);check(action!=null,"Missing auth action: "+label);action.performClick();});}
  private AlertDialog dialog()throws Exception{java.lang.reflect.Field field=ProviderAuthDialog.class.getDeclaredField("activeDialog");field.setAccessible(true);AlertDialog dialog=(AlertDialog)field.get(panel);check(dialog!=null&&dialog.isShowing(),"Expected provider auth dialog");return dialog;}
  private AlertDialog connectorDialog()throws Exception{java.lang.reflect.Field field=ConnectorActivity.class.getDeclaredField("editor");field.setAccessible(true);return (AlertDialog)field.get(connector);}
  private JSONObject server(String id){JSONArray servers=connectors==null?null:connectors.optJSONArray("servers");if(servers!=null)for(int i=0;i<servers.length();i++){JSONObject server=servers.optJSONObject(i);if(id.equals(server.optString("id")))return server;}return null;}
  private JSONObject request(String action,JSONObject fields)throws Exception{String id=UUID.randomUUID().toString();fields.put("type","connector_manage").put("action",action).put("requestId",id);runOnMainSync(()->runtime.send(fields));for(int i=0;i<40;i++){JSONObject event=results.poll(1,TimeUnit.SECONDS);if(event!=null&&id.equals(event.optString("requestId"))){check(event.optBoolean("ok"),event.optString("error"));return event;}}throw new IllegalStateException("Connector request timed out");}
  private void await(BooleanSupplier ready,String failure)throws Exception{for(int i=0;i<200;i++){boolean[] ok={false};runOnMainSync(()->ok[0]=ready.getAsBoolean());if(ok[0])return;Thread.sleep(50);}throw new IllegalStateException(failure);}
  private static View find(View view,String label){if(label.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}return null;}
  private static void check(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
