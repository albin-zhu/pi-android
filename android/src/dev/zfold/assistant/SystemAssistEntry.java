package dev.zfold.assistant;

import android.os.*;

/** One short-lived framework screenshot, offered only to an empty visible draft. */
final class SystemAssistEntry {
  private static final Handler main=new Handler(Looper.getMainLooper());private static long generation,started;private static String image;private static boolean consumed;
  static long currentId(){return generation;}
  static long begin(){generation++;started=SystemClock.elapsedRealtime();image=null;consumed=false;long id=generation;main.postDelayed(()->{if(id==generation)cancel();},10000);return generation;}
  static void offer(long id,String value){if(id!=generation||consumed||SystemClock.elapsedRealtime()-started>10000)return;image=value;MainActivity chat=MainActivity.current();if(chat!=null)attach(chat);}
  static void attach(MainActivity chat){
    if(consumed||image==null||!chat.hasWindowFocus()||chat.isFinishing())return;
    if(chat.pendingImage==null&&chat.localFiles.isEmpty()&&chat.input.getText().toString().trim().isEmpty()){
      chat.pendingImage=image;chat.pendingImageLabel="系统助手获取的屏幕";chat.updateDraftAttachment();
    }
    cancel();
  }
  static void cancel(){image=null;consumed=true;}
}
