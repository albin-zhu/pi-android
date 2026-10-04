package dev.zfold.assistant;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioDeviceInfo;
import android.os.Build;

/** One communication audio mode for KWS, ASR and barge-in on the main audio path. */
final class AudioModeOwner {
  private static final int EXPIRY_MS=15_000;
  private static String owner;
  private static long at;

  static synchronized Mode enter(Context context,String requestedBy){
    AudioManager manager=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
    if(manager==null)return null;
    long now=android.os.SystemClock.elapsedRealtime();
    if(owner!=null&&!owner.equals(requestedBy)&&now-at<EXPIRY_MS)return null;
    if(manager.getMode()!=AudioManager.MODE_IN_COMMUNICATION)manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
    if(manager.getMode()!=AudioManager.MODE_IN_COMMUNICATION)return null;
    owner=requestedBy;at=now;
    return new Mode(manager);
  }

  static synchronized void enterSession(Context context){
    AudioManager manager=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
    if(manager==null)return;
    manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
    if(Build.VERSION.SDK_INT>=31){
      for(AudioDeviceInfo device:manager.getAvailableCommunicationDevices()){
        if(device.getType()==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER){manager.setCommunicationDevice(device);break;}
      }
    }
    manager.setSpeakerphoneOn(true);
    int max=manager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
    int current=manager.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
    if(current<max/2)manager.setStreamVolume(AudioManager.STREAM_VOICE_CALL,max,0);
    owner="session";at=android.os.SystemClock.elapsedRealtime();
  }

  static synchronized void exitSession(Context context){
    AudioManager manager=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
    if(manager==null)return;
    if(Build.VERSION.SDK_INT>=31)manager.clearCommunicationDevice();
    if(!manager.isSpeakerphoneOn())manager.setSpeakerphoneOn(true);
    manager.setSpeakerphoneOn(false);
    manager.setMode(AudioManager.MODE_NORMAL);
    owner=null;at=0;
  }

  static final class Mode {
    private final AudioManager manager;
    private Mode(AudioManager manager){this.manager=manager;}
    void close(){
      synchronized(AudioModeOwner.class){
        manager.setMode(AudioManager.MODE_NORMAL);
        owner=null;at=0;
      }
    }
  }
}
