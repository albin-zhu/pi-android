package dev.zfold.assistant;

/** Small process-local bridge between the voice controller and the floating status orb. */
final class VoiceStatus {
  enum State {IDLE,LISTENING,THINKING,SPEAKING,TASK,ERROR}
  interface Listener {void onChanged(State state,String message,State previous);}

  private static final Object lock=new Object();
  private static State state=State.IDLE;
  private static String message="";
  private static boolean task;
  private static Listener listener;

  static void voice(State next,String text){
    synchronized(lock){
      State previous=displayState();
      state=next==null?State.IDLE:next;
      message=text==null?"":text;
      notify(previous);
    }
  }

  static void message(String text){
    synchronized(lock){
      State previous=displayState();
      message=text==null?"":text;
      if(state==State.IDLE)state=State.LISTENING;
      notify(previous);
    }
  }

  static void task(boolean active){
    synchronized(lock){
      State previous=displayState();
      task=active;
      if(active){state=State.TASK;message="任务执行中";}
      else if(state==State.TASK){state=State.IDLE;message="";}
      notify(previous);
    }
  }

  static State current(){
    synchronized(lock){return displayState();}
  }

  static String currentMessage(){
    synchronized(lock){return message;}
  }

  static void listen(Listener value){
    synchronized(lock){listener=value;if(value!=null)value.onChanged(displayState(),message,State.IDLE);}
  }

  static void stopListening(Listener value){
    synchronized(lock){if(listener==value)listener=null;}
  }

  private static State displayState(){return task?State.TASK:state;}
  private static void notify(State previous){
    Listener target=listener;
    State visible=displayState();
    if(target!=null&&visible!=previous)target.onChanged(visible,message,previous);
  }
  private VoiceStatus(){}
}
