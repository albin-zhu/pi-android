package dev.zfold.assistant;

import android.accessibilityservice.InputMethod;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.SurroundingText;
import org.json.JSONObject;
import java.util.concurrent.Executors;

/** Android 13+ accessibility editing: uses the user's current keyboard unchanged. */
final class FocusedInput {
  private final PhoneAccessibility service;
  private final Handler main=new Handler(Looper.getMainLooper());
  private final java.util.concurrent.ExecutorService worker=Executors.newSingleThreadExecutor();
  FocusedInput(PhoneAccessibility service){this.service=service;}
  private boolean password(EditorInfo info){
    int type=info.inputType&InputType.TYPE_MASK_CLASS,variation=info.inputType&InputType.TYPE_MASK_VARIATION;
    return (type==InputType.TYPE_CLASS_TEXT&&(variation==InputType.TYPE_TEXT_VARIATION_PASSWORD||variation==InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD||variation==InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))||(type==InputType.TYPE_CLASS_NUMBER&&variation==InputType.TYPE_NUMBER_VARIATION_PASSWORD);
  }
  void replace(JSONObject args,AndroidTools.Result callback){
    if(Build.VERSION.SDK_INT<33){callback.done(null,"焦点输入需要 Android 13 或更新版本");return;}
    String pkg=args.optString("packageName"),text=args.optString("text");
    if(pkg.isEmpty()||text.length()>4000){callback.done(null,"焦点输入需要应用包名，文字不能超过 4000 字");return;}
    awaitEditor(pkg,text,System.currentTimeMillis()+2000,NativeToolExecution.currentCall(),callback);
  }
  private void awaitEditor(String pkg,String text,long deadline,NativeToolExecution call,AndroidTools.Result callback){
    if(call!=null&&call.cancelled()){callback.done(null,"操作已停止");return;}
    InputMethod method=service.getInputMethod();EditorInfo editor=method==null?null:method.getCurrentInputEditorInfo();
    InputMethod.AccessibilityInputConnection connection=method==null?null:method.getCurrentInputConnection();
    if(editor==null||connection==null||!method.getCurrentInputStarted()||!pkg.equals(editor.packageName)||!pkg.equals(service.foregroundPackage())){
      if(System.currentTimeMillis()<deadline){main.postDelayed(()->awaitEditor(pkg,text,deadline,call,callback),100);return;}
      callback.done(null,"没有找到该应用的活动输入框，请先点击输入框；不要切换其他应用代替输入");return;
    }
    if(password(editor)){callback.done(null,"密码字段不支持自动输入");return;}
    long epoch=service.inputEpoch;
    worker.execute(()->{
      SurroundingText before=connection.getSurroundingText(4096,4096,0);
      main.post(()->{
        if(!current(method,editor,pkg,epoch,call)){callback.done(null,"输入框已变化或操作已停止，未输入文字");return;}
        boolean readable=before!=null&&before.getOffset()==0&&before.getText().length()<4096;
        if(readable&&text.contentEquals(before.getText())){callback.done(PiRuntime.json("performed",false,"verified",true,"alreadyMatched",true,"packageName",pkg),null);return;}
        // Some editors accept normal IME input but decline surrounding-text reads.
        // The tool explicitly requests replacement; use the editor's own Select All,
        // then report visual verification required rather than pretending read-back.
        if(readable)connection.setSelection(0,before.getText().length());else connection.performContextMenuAction(android.R.id.selectAll);
        connection.commitText(text,1,null);
        main.postDelayed(()->verify(method,connection,editor,pkg,text,epoch,call,callback),120);
      });
    });
  }
  private boolean current(InputMethod method,EditorInfo editor,String pkg,long epoch,NativeToolExecution call){
    EditorInfo now=method.getCurrentInputEditorInfo();
    return (call==null||!call.cancelled())&&PhoneAccessibility.instance==service&&service.inputEpoch==epoch&&method.getCurrentInputStarted()&&now!=null&&now.fieldId==editor.fieldId&&pkg.equals(now.packageName)&&pkg.equals(service.foregroundPackage());
  }
  private void verify(InputMethod method,InputMethod.AccessibilityInputConnection connection,EditorInfo editor,String pkg,String text,long epoch,NativeToolExecution call,AndroidTools.Result callback){
    worker.execute(()->{
      SurroundingText after=connection.getSurroundingText(4096,4096,0);
      main.post(()->{
        boolean verified=current(method,editor,pkg,epoch,call)&&after!=null&&after.getOffset()==0&&text.contentEquals(after.getText());
        callback.done(PiRuntime.json("performed",true,"verified",verified,"requiresVisualVerification",!verified,"packageName",pkg,"reason",verified?"text_read_back":"input_submitted_check_screenshot","characterCount",text.length()),null);
      });
    });
  }
  void close(){worker.shutdownNow();}
}
