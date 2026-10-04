package dev.zfold.assistant;
import android.app.Activity;
import android.os.Bundle;
import android.widget.*;

/** Local-only accessibility acceptance page; available only in instrumentation mode. */
public final class PhoneTaskFixtureActivity extends Activity {
  @Override public void onCreate(Bundle state){
    super.onCreate(state);if(!PiRuntime.testMode){finish();return;}
    LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(40,120,40,40);root.setFocusableInTouchMode(true);
    TextView title=new TextView(this);title.setText("Zfold 自动化测试页");title.setTextSize(24);root.addView(title);
    CheckBox toggle=new CheckBox(this);toggle.setId(R.id.jev_fixture_toggle);toggle.setText("演示开关");toggle.setChecked(false);root.addView(toggle);
    EditText input=new EditText(this){
      @Override public android.view.inputmethod.InputConnection onCreateInputConnection(android.view.inputmethod.EditorInfo info){
        android.view.inputmethod.InputConnection connection=super.onCreateInputConnection(info);
        if(connection==null||!getIntent().getBooleanExtra("opaque_input",false))return connection;
        return new android.view.inputmethod.InputConnectionWrapper(connection,false){
          @Override public android.view.inputmethod.SurroundingText getSurroundingText(int before,int after,int flags){return null;}
        };
      }
    };input.setId(R.id.jev_fixture_input);input.setHint("备注");input.setContentDescription("备注");input.setSingleLine(true);root.addView(input);
    TextView info=new TextView(this);info.setText("仅修改本测试页，不会发送消息或更改系统设置。");root.addView(info);
    setContentView(root);root.requestFocus();
  }
}
