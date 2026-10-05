package dev.zfold.assistant;

import android.app.*;
import android.content.*;
import android.media.*;
import android.os.*;
import android.view.*;
import android.widget.TextView;
import java.util.function.BooleanSupplier;

/** Real Android output routing and lifecycle, with synthetic PCM only. Never records a microphone. */
public final class AudioRouteInstrumentation extends Instrumentation {
  @Override public void onCreate(Bundle args){start();}
  @Override public void onStart(){
    Bundle report=new Bundle();int code=Activity.RESULT_OK;Object token=new Object();AudioTrack track=null;VoiceReadyCue cue=null;VoiceCallActivity view=null;boolean entered=false;
    try{
      if(!Build.HARDWARE.contains("ranchu")&&!Build.HARDWARE.contains("goldfish"))throw new IllegalStateException("Emulator only");
      if(Build.VERSION.SDK_INT<31)throw new IllegalStateException("Requires Android 12+ routing API");
      PiRuntime.testMode=true;Context context=getTargetContext();AudioManager manager=context.getSystemService(AudioManager.class);int mode=manager.getMode();
      int ear=AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,speaker=AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,usb=AudioDeviceInfo.TYPE_USB_HEADSET,bt=AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
      check(AudioModeOwner.automaticId(new int[]{ear,speaker},new int[]{1,2},1)==2,"Default is not hands-free");
      check(AudioModeOwner.automaticId(new int[]{ear,speaker,usb,bt},new int[]{1,2,3,4},4)==4,"Current Bluetooth headset lost");
      check(AudioModeOwner.automaticId(new int[]{ear,speaker,usb},new int[]{1,2,3},2)==3,"Headset not preferred");
      check(AudioModeOwner.automaticId(new int[]{ear,speaker},new int[]{1,2},3)==2,"Unplug did not fall back");
      check(AudioModeOwner.automaticId(new int[]{},new int[]{},3)==0,"Empty device set failed");report.putBoolean("handsFreeAndHeadsetPolicy",true);
      runOnMainSync(()->AudioModeOwner.enterSession(context,token));entered=true;
      track=new AudioTrack.Builder().setAudioAttributes(AudioModeOwner.speechAttributes(true)).setAudioFormat(new AudioFormat.Builder().setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(4800).build();
      track.write(new short[2400],0,2400);track.setLoopPoints(0,2400,-1);track.play();
      await(()->manager.getCommunicationDevice()!=null&&manager.getCommunicationDevice().getType()==speaker,"Default speaker not applied");
      int speakerVolume=manager.getStreamVolume(AudioManager.STREAM_VOICE_CALL),mediaVolume=manager.getStreamVolume(AudioManager.STREAM_MUSIC);
      check("扬声器".equals(AudioModeOwner.outputLabel()),"UI does not reflect actual output");report.putBoolean("actualDefaultSpeaker",true);
      AudioDeviceInfo receiver=null;for(AudioDeviceInfo device:manager.getAvailableCommunicationDevices())if(device.getType()==ear)receiver=device;
      report.putBoolean("receiverAvailable",receiver!=null);
      if(receiver==null)receiver=manager.getCommunicationDevice();int receiverId=receiver.getId();String manualLabel=AudioModeOwner.label(receiver);check(AudioModeOwner.select(receiverId),"Manual output request rejected");
      await(()->manager.getCommunicationDevice()!=null&&manager.getCommunicationDevice().getId()==receiverId,"Receiver not applied");int receiverVolume=manager.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
      runOnMainSync(()->{AudioModeOwner.enterSession(context,token);AudioModeOwner.exitSession(context,new Object());});
      check(AudioModeOwner.active()&&AudioModeOwner.selected()==receiverId&&manualLabel.equals(AudioModeOwner.outputLabel()),"Route reset by duplicate start or stale owner");
      check(!AudioModeOwner.select(Integer.MAX_VALUE)&&AudioModeOwner.selected()==receiverId,"Unavailable device erased choice");report.putBoolean("manualAvailableOutputAndOwnership",true);
      check(AudioModeOwner.select(0),"Automatic request rejected");await(()->manager.getCommunicationDevice()!=null&&manager.getCommunicationDevice().getType()==speaker,"Automatic did not restore speaker");
      check(manager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)==speakerVolume&&manager.getStreamVolume(AudioManager.STREAM_MUSIC)==mediaVolume,"Changed speaker/media volume");
      check(AudioModeOwner.select(receiverId),"Second receiver request rejected");await(()->manager.getCommunicationDevice()!=null&&manager.getCommunicationDevice().getId()==receiverId,"Second receiver route failed");
      check(manager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)==receiverVolume,"Changed receiver volume");AudioModeOwner.select(0);
      report.putBoolean("routeVolumesPreserved",true);
      int played=VoiceReadyCue.completedPlays;cue=new VoiceReadyCue();cue.arm(80001);cue.viewReady(true);cue.microphoneReady();cue.backendReady();await(()->VoiceReadyCue.completedPlays==played+1,"Ready cue did not drain");report.putBoolean("readyCuePlaysOnCallOutput",true);
      check(AudioModeOwner.speechAttributes(true).getVolumeControlStream()==AudioManager.STREAM_VOICE_CALL,"Call speech uses wrong stream");
      check(AudioModeOwner.speechAttributes(false).getUsage()==AudioAttributes.USAGE_MEDIA,"Preview uses call stream");report.putBoolean("callAndPreviewStreams",true);
      // The animated orb keeps rendering. Wait for the actual view, not a globally idle UI queue.
      runOnMainSync(()->context.startActivity(new Intent(context,VoiceCallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
      await(VoiceCallActivity::isForeground,"Voice UI did not open");java.lang.reflect.Field active=VoiceCallActivity.class.getDeclaredField("active");active.setAccessible(true);
      view=(VoiceCallActivity)active.get(null);VoiceCallActivity activity=view;
      check(view.getVolumeControlStream()==AudioManager.STREAM_VOICE_CALL,"Volume keys target wrong stream");
      java.lang.reflect.Field field=VoiceCallActivity.class.getDeclaredField("audioOutput");field.setAccessible(true);TextView output=(TextView)field.get(view);
      await(()->output.getText().toString().contains("扬声器"),"Route not shown in voice UI");
      runOnMainSync(()->activity.getWindow().setLayout(Ui.dp(activity,239),Ui.dp(activity,282)));
      await(()->activity.getWindow().getDecorView().getWidth()==Ui.dp(activity,239)&&output.isShown()&&output.getWidth()>0&&output.getHeight()>=Ui.dp(activity,44),"Compact output layout did not complete");
      android.graphics.Rect bounds=new android.graphics.Rect();output.getGlobalVisibleRect(bounds);check(bounds.height()==output.getHeight(),"Compact output control clipped");
      android.graphics.Bitmap[] rendered={null};runOnMainSync(()->{View root=activity.getWindow().getDecorView();rendered[0]=android.graphics.Bitmap.createBitmap(root.getWidth(),root.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);root.draw(new android.graphics.Canvas(rendered[0]));});android.graphics.Bitmap screenshot=rendered[0];
      if(screenshot!=null)try(java.io.FileOutputStream file=new java.io.FileOutputStream(new java.io.File(context.getCacheDir(),"audio-route-compact.png"))){screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,file);screenshot.recycle();}
      runOnMainSync(()->output.performClick());java.lang.reflect.Field dialogField=VoiceCallActivity.class.getDeclaredField("audioDialog");dialogField.setAccessible(true);AlertDialog dialog=(AlertDialog)dialogField.get(activity);
      check(dialog!=null&&dialog.isShowing(),"Output button did not open picker");
      runOnMainSync(()->{android.widget.ListView list=dialog.getListView();for(int i=0;i<list.getAdapter().getCount();i++)if(manualLabel.equals(list.getAdapter().getItem(i).toString())){list.performItemClick(list.getChildAt(i-list.getFirstVisiblePosition()),i,list.getAdapter().getItemId(i));return;}throw new IllegalStateException("Available route missing from picker");});
      await(()->!dialog.isShowing()&&AudioModeOwner.selected()==receiverId&&manager.getCommunicationDevice()!=null&&manager.getCommunicationDevice().getId()==receiverId,"Picker did not select output");
      report.putBoolean("compactUiAndPicker",true);
      runOnMainSync(activity::finish);Thread.sleep(150);check(AudioModeOwner.active()&&AudioModeOwner.selected()==receiverId,"Closing UI ended audio ownership");report.putBoolean("viewLifecyclePreservesRoute",true);
      runOnMainSync(()->AudioModeOwner.exitSession(context,token));entered=false;
      await(()->manager.getMode()==mode,"Audio mode not restored");check(!AudioModeOwner.active()&&AudioModeOwner.selected()==0,"Call route not released");report.putBoolean("sessionCleanup",true);
      VoiceTurnController controller=VoiceCoordinator.get(context).controller;
      java.lang.reflect.Method pause=VoiceTurnController.class.getDeclaredMethod("pause",String.class);pause.setAccessible(true);
      boolean[] released={false};runOnMainSync(()->{AudioModeOwner.enterSession(context,controller);try{pause.invoke(controller,"Synthetic recognition failure");released[0]=!AudioModeOwner.active();}catch(Exception error){throw new IllegalStateException(error);}finally{if(AudioModeOwner.active())AudioModeOwner.exitSession(context,controller);}});check(released[0],"Failure retained route ownership");
      await(()->manager.getMode()==mode,"Failure path retained audio mode");check(!AudioModeOwner.active(),"Failure retained route");report.putBoolean("recognitionFailureReleasesOutput",true);
    }catch(Throwable error){report.putString("failure",error.toString());code=Activity.RESULT_CANCELED;}
    finally{if(cue!=null)cue.cancel();if(track!=null){track.stop();track.release();}if(entered)runOnMainSync(()->AudioModeOwner.exitSession(getTargetContext(),token));if(view!=null){VoiceCallActivity activity=view;runOnMainSync(activity::finish);}}
    finish(code,report);
  }
  private static void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
  private static void await(BooleanSupplier condition,String message)throws Exception{for(int i=0;i<150;i++){if(condition.getAsBoolean())return;Thread.sleep(50);}throw new IllegalStateException(message);}
}
