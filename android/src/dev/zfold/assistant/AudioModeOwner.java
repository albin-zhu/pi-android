package dev.zfold.assistant;

import android.content.Context;
import android.media.*;
import android.os.*;
import java.util.*;

/** Process-owned call audio. A view recreation or ASR turn must not reset the route. */
final class AudioModeOwner {
  private static final Handler main=new Handler(Looper.getMainLooper());
  private static final Set<Runnable> listeners=new HashSet<>();
  private static AudioManager audio;
  private static Object owner;
  private static int selectedId;
  private static boolean previousSpeaker,requestedRoute;
  private static AudioDeviceCallback devices;
  private static AudioManager.OnCommunicationDeviceChangedListener routeListener;

  static AudioAttributes speechAttributes(boolean call){
    return new AudioAttributes.Builder().setUsage(call?AudioAttributes.USAGE_VOICE_COMMUNICATION:AudioAttributes.USAGE_MEDIA)
      .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
  }
  static synchronized void enterSession(Context context,Object token){
    if(owner!=null)return;
    audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);if(audio==null)return;
    int previousMode=audio.getMode();previousSpeaker=audio.isSpeakerphoneOn();
    // Do not take audio away from a cellular call.
    if(previousMode==AudioManager.MODE_IN_CALL){audio=null;return;}
    owner=token;selectedId=0;requestedRoute=false;
    audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
    devices=new AudioDeviceCallback(){
      @Override public void onAudioDevicesAdded(AudioDeviceInfo[] added){refreshDevices(token);}
      @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed){refreshDevices(token);}
    };
    audio.registerAudioDeviceCallback(devices,main);
    if(Build.VERSION.SDK_INT>=31){
      routeListener=device->{synchronized(AudioModeOwner.class){if(owner==token)notifyChanged();}};
      audio.addOnCommunicationDeviceChangedListener(main::post,routeListener);
    }
    applyRoute();notifyChanged();
  }
  static synchronized void exitSession(Context context,Object token){
    if(owner!=token||audio==null)return;
    owner=null;audio.unregisterAudioDeviceCallback(devices);devices=null;
    if(Build.VERSION.SDK_INT>=31){
      if(routeListener!=null)audio.removeOnCommunicationDeviceChangedListener(routeListener);
      routeListener=null;if(requestedRoute)audio.clearCommunicationDevice();
    }else if(requestedRoute)audio.setSpeakerphoneOn(previousSpeaker);
    // MODE_NORMAL removes this process's mode claim, even if a higher-priority
    // phone call currently owns the effective mode. Never copy another app's claim.
    audio.setMode(AudioManager.MODE_NORMAL);
    selectedId=0;requestedRoute=false;audio=null;notifyChanged();
  }
  static synchronized void listen(Runnable listener){listeners.add(listener);listener.run();}
  static synchronized void unlisten(Runnable listener){listeners.remove(listener);}
  private static void notifyChanged(){for(Runnable listener:listeners)main.post(listener);}
  static synchronized boolean active(){return owner!=null;}
  static synchronized int selected(){return selectedId;}
  static synchronized String outputLabel(){
    if(audio==null)return "自动 · 耳机优先";
    if(Build.VERSION.SDK_INT>=31){AudioDeviceInfo current=audio.getCommunicationDevice();return current==null?"等待声音输出":label(current);}
    if(audio.isBluetoothScoOn())return "蓝牙耳机";
    if(audio.isSpeakerphoneOn())return "扬声器";
    for(AudioDeviceInfo device:audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS))if(external(device.getType()))return label(device);
    return "听筒";
  }
  static String label(AudioDeviceInfo device){
    switch(device.getType()){
      case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:return "扬声器";
      case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:return "听筒";
      case AudioDeviceInfo.TYPE_WIRED_HEADSET:case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:return "有线耳机";
      case AudioDeviceInfo.TYPE_USB_HEADSET:case AudioDeviceInfo.TYPE_USB_DEVICE:return "USB 耳机";
      default:CharSequence name=device.getProductName();return name==null||name.length()==0?"耳机":name.toString();
    }
  }
  static synchronized List<AudioDeviceInfo> choices(){
    return audio!=null&&Build.VERSION.SDK_INT>=31?new ArrayList<>(audio.getAvailableCommunicationDevices()):Collections.emptyList();
  }
  /** 0 = automatic, positive = available device ID; legacy -1/-2 = speaker/receiver. */
  static synchronized boolean select(int id){
    if(audio==null)return false;
    int old=selectedId;selectedId=id;
    if(!applyRoute()){selectedId=old;notifyChanged();return false;}
    notifyChanged();return true;
  }
  private static synchronized void refreshDevices(Object token){
    if(owner!=token)return;
    if(Build.VERSION.SDK_INT>=31&&selectedId>0&&find(choices(),selectedId)==null)selectedId=0;
    applyRoute();notifyChanged();
  }
  private static boolean applyRoute(){
    try{return applyAvailableRoute();}
    catch(RuntimeException error){android.util.Log.w("ZfoldSpeech","Audio route unavailable: "+error.getClass().getSimpleName());return false;}
  }
  private static boolean applyAvailableRoute(){
    if(audio.getMode()==AudioManager.MODE_IN_CALL)return false;
    if(Build.VERSION.SDK_INT>=31){
      List<AudioDeviceInfo> available=audio.getAvailableCommunicationDevices();
      AudioDeviceInfo target=selectedId==0?automatic(available,audio.getCommunicationDevice()):find(available,selectedId);
      if(target==null)return false;
      // A request is not evidence of the actual route; the UI reads getCommunicationDevice.
      boolean accepted=audio.setCommunicationDevice(target);requestedRoute|=accepted;return accepted;
    }
    // API 30: keep a system-established Bluetooth route; wired/USB headsets route naturally.
    boolean speaker=selectedId==-1;
    if(selectedId==0){speaker=!audio.isBluetoothScoOn();for(AudioDeviceInfo device:audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS))if(external(device.getType()))speaker=false;}
    if(selectedId>0)return false;
    audio.setSpeakerphoneOn(speaker);requestedRoute=true;return true;
  }
  static AudioDeviceInfo automatic(List<AudioDeviceInfo> available,AudioDeviceInfo current){
    int[] types=new int[available.size()],ids=new int[available.size()];for(int i=0;i<types.length;i++){types[i]=available.get(i).getType();ids[i]=available.get(i).getId();}
    return find(available,automaticId(types,ids,current==null?0:current.getId()));
  }
  /** Keeps a currently selected external sink, otherwise headphones, then hands-free. */
  static int automaticId(int[] types,int[] ids,int current){
    for(int i=0;i<ids.length;i++)if(ids[i]==current&&external(types[i]))return current;
    for(int i=0;i<ids.length;i++)if(external(types[i]))return ids[i];
    for(int i=0;i<ids.length;i++)if(types[i]==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)return ids[i];
    return ids.length==0?0:ids[0];
  }
  private static AudioDeviceInfo find(List<AudioDeviceInfo> available,int id){for(AudioDeviceInfo device:available)if(device.getId()==id)return device;return null;}
  private static boolean external(int type){
    return type==AudioDeviceInfo.TYPE_WIRED_HEADSET||type==AudioDeviceInfo.TYPE_WIRED_HEADPHONES||type==AudioDeviceInfo.TYPE_USB_HEADSET
      ||type==AudioDeviceInfo.TYPE_USB_DEVICE||type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO||type==AudioDeviceInfo.TYPE_BLE_HEADSET||type==AudioDeviceInfo.TYPE_HEARING_AID;
  }
}
