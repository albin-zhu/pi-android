package dev.zfold.assistant;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.UUID;

/** OAuth callback is checked against private PKCE state by the runtime. */
public final class ConnectorOAuthActivity extends Activity implements PiRuntime.Listener {
  private PiRuntime runtime;private String callback,id;private boolean sent;
  @Override protected void onCreate(Bundle state){
    super.onCreate(state);TextView label=Ui.text(this,"正在完成连接…",18,new Ui.Theme(this).ink);label.setGravity(android.view.Gravity.CENTER);setContentView(label);
    callback=getIntent().getDataString();if(callback==null||callback.length()>8192){finish();return;}
    id=UUID.randomUUID().toString();runtime=PiRuntime.get(this);runtime.listen(this);if(runtime.ready)send();
  }
  private void send(){if(sent)return;sent=true;runtime.send(PiRuntime.json("type","connector_manage","action","oauth_callback","requestId",id,"url",callback));}
  public void event(JSONObject event){if(event.optString("type").equals("ready"))send();else if(event.optString("type").equals("connector_result")&&id.equals(event.optString("requestId"))){
    if(!event.optBoolean("ok"))android.widget.Toast.makeText(this,event.optString("error"),android.widget.Toast.LENGTH_LONG).show();ConnectorActivity.returnFromOAuth(this);finish();
  }}
  @Override protected void onDestroy(){if(runtime!=null)runtime.unlisten(this);super.onDestroy();}
}
