package dev.zfold.assistant;

import android.app.*;
import android.os.*;
import android.provider.Settings;
import java.io.*;
import java.util.regex.*;

/** Shared setup for isolated emulator acceptance, never personal device automation. */
final class EmulatorChecks {
  static void accessibility(Instrumentation test)throws Exception{
    if(!Build.HARDWARE.contains("ranchu")&&!Build.HARDWARE.contains("goldfish"))throw new IllegalStateException("Emulator only");
    UiAutomation automation=test.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
    automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
    try{
      String component="dev.zfold.assistant/dev.zfold.assistant.PhoneAccessibility";
      android.content.ContentResolver resolver=test.getTargetContext().getContentResolver();
      String old=Settings.Secure.getString(resolver,"enabled_accessibility_services");
      String other=old==null?"":old.replace(component,"").replaceAll("^:|:$","").replace("::",":");
      Settings.Secure.putString(resolver,"enabled_accessibility_services",other);Thread.sleep(500);
      Settings.Secure.putString(resolver,"enabled_accessibility_services",other.isEmpty()?component:other+":"+component);
    }finally{automation.dropShellPermissionIdentity();}
    for(int i=0;i<200&&PhoneAccessibility.instance==null;i++)Thread.sleep(100);
    if(PhoneAccessibility.instance==null)throw new IllegalStateException("Accessibility service did not bind");
  }
  static String shell(Instrumentation test,String command)throws Exception{
    ParcelFileDescriptor descriptor=test.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(command);
    try(InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(descriptor)){return new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
  }
  static void cancelPointer(Instrumentation test){long now=android.os.SystemClock.uptimeMillis();android.view.MotionEvent event=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_CANCEL,0,0,0);event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);test.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).injectInputEvent(event,true);event.recycle();}
  static int imeTop(Instrumentation test)throws Exception{
    // The accessibility window can span the display or be omitted entirely. InsetsSource is the visible IME region.
    Matcher match=Pattern.compile("type=ime frame=\\[\\d+,(\\d+)\\]\\[\\d+,\\d+\\][^\\n]*visible=true").matcher(shell(test,"dumpsys window displays"));
    return match.find()?Integer.parseInt(match.group(1)):-1;
  }
  static void screenshot(Instrumentation test,String name)throws Exception{
    try{test.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).waitForIdle(300,5000);}catch(java.util.concurrent.TimeoutException ignored){}
    Thread.sleep(400);
    android.graphics.Bitmap image=test.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot();
    if(image==null)throw new IllegalStateException("Screenshot unavailable");
    try(FileOutputStream output=new FileOutputStream(new File(test.getTargetContext().getCacheDir(),name))){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}finally{image.recycle();}
  }
  private static android.view.accessibility.AccessibilityNodeInfo find(android.view.accessibility.AccessibilityNodeInfo node,String text){
    if(node==null)return null;
    if(node.isVisibleToUser()&&(text.contentEquals(node.getText()==null?"":node.getText())||text.contentEquals(node.getContentDescription()==null?"":node.getContentDescription()))){android.view.accessibility.AccessibilityNodeInfo target=node;while(target!=null&&!target.isClickable())target=target.getParent();if(target!=null)return target;}
    for(int i=0;i<node.getChildCount();i++){android.view.accessibility.AccessibilityNodeInfo match=find(node.getChild(i),text);if(match!=null)return match;}return null;
  }
  private static boolean textVisible(android.view.accessibility.AccessibilityNodeInfo node,String text){if(node==null)return false;if(node.isVisibleToUser()&&(String.valueOf(node.getText()).contains(text)||String.valueOf(node.getContentDescription()).contains(text)))return true;for(int i=0;i<node.getChildCount();i++)if(textVisible(node.getChild(i),text))return true;return false;}
  static void waitText(Instrumentation test,String text)throws Exception{UiAutomation automation=test.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);for(int i=0;i<150;i++){if(textVisible(automation.getRootInActiveWindow(),text)){try{automation.waitForIdle(200,3000);}catch(java.util.concurrent.TimeoutException ignored){}return;}Thread.sleep(100);}throw new IllegalStateException("Visible page missing: "+text);}
  private static boolean keyboardHint(android.view.accessibility.AccessibilityNodeInfo node){if(node==null)return false;if(String.valueOf(node.getText()).replaceAll("\\s+"," ").contains("Keyboard font size updated"))return true;for(int i=0;i<node.getChildCount();i++)if(keyboardHint(node.getChild(i)))return true;return false;}
  static void dismissKeyboardHint(Instrumentation test){
    UiAutomation automation=test.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);android.accessibilityservice.AccessibilityServiceInfo info=automation.getServiceInfo();info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;automation.setServiceInfo(info);
    for(android.view.accessibility.AccessibilityWindowInfo window:automation.getWindows())if(window.getType()==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD){android.view.accessibility.AccessibilityNodeInfo root=window.getRoot();if(keyboardHint(root)){android.view.accessibility.AccessibilityNodeInfo ok=find(root,"OK");if(ok!=null)ok.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);}}
  }
  static void click(Instrumentation test,String text)throws Exception{
    UiAutomation automation=test.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);android.accessibilityservice.AccessibilityServiceInfo info=automation.getServiceInfo();info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;automation.setServiceInfo(info);
    for(int i=0;i<100;i++){android.view.accessibility.AccessibilityNodeInfo node=find(automation.getRootInActiveWindow(),text);if(node==null)for(android.view.accessibility.AccessibilityWindowInfo window:automation.getWindows()){node=find(window.getRoot(),text);if(node!=null)break;}if(node!=null&&node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)){Thread.sleep(200);return;}Thread.sleep(100);}throw new IllegalStateException("Missing action: "+text);
  }
}
