package com.termux.terminal;

import android.os.Handler;
import android.os.Looper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;

/** Terminal-view adapter for Zfold's PTY broker. Does not own another native runtime. */
public final class TerminalSession extends TerminalOutput {
  public interface Transport {
    void start(TerminalSession session,int columns,int rows);
    void resize(int columns,int rows);
    void write(byte[] bytes);
    void close();
  }
  public final String mHandle=UUID.randomUUID().toString();
  public String mSessionName;
  private final Transport transport;
  private TerminalSessionClient client;
  private TerminalEmulator emulator;
  private final Handler main=new Handler(Looper.getMainLooper());
  private final ArrayBlockingQueue<byte[]> input=new ArrayBlockingQueue<>(32);
  private volatile boolean running=true;
  private int exitStatus;

  public TerminalSession(Transport transport,TerminalSessionClient client){this.transport=transport;this.client=client;}
  public void updateTerminalSessionClient(TerminalSessionClient value){client=value;if(emulator!=null)emulator.updateTerminalSessionClient(value);}
  public void updateSize(int columns,int rows,int width,int height){
    if(emulator==null){emulator=new TerminalEmulator(this,columns,rows,width,height,4000,client);transport.start(this,columns,rows);}
    else{emulator.resize(columns,rows,width,height);if(running)transport.resize(columns,rows);}
  }
  /** Called by the reader; bounded backpressure keeps floods off the main thread. */
  public void acceptOutput(byte[] bytes)throws InterruptedException{
    for(int offset=0;offset<bytes.length;offset+=4096){
      input.put(Arrays.copyOfRange(bytes,offset,Math.min(bytes.length,offset+4096)));
      main.post(()->{byte[] chunk=input.poll();if(chunk!=null&&emulator!=null){emulator.append(chunk,chunk.length);client.onTextChanged(this);}});
    }
  }
  public void finish(int code){main.post(()->{if(!running)return;running=false;exitStatus=code;client.onSessionFinished(this);});}
  @Override public void write(byte[] data,int offset,int count){if(running)transport.write(Arrays.copyOfRange(data,offset,offset+count));}
  public void writeCodePoint(boolean escape,int codePoint){
    if(!Character.isValidCodePoint(codePoint)||(codePoint>=0xd800&&codePoint<=0xdfff))return;
    write((escape?"\u001b":"")+new String(Character.toChars(codePoint)));
  }
  public TerminalEmulator getEmulator(){return emulator;}
  public String getTitle(){return emulator==null?null:emulator.getTitle();}
  public boolean isRunning(){return running;}
  public int getExitStatus(){return exitStatus;}
  public void finishIfRunning(){if(running)transport.close();}
  public void reset(){if(emulator!=null){emulator.reset();client.onTextChanged(this);}}
  @Override public void titleChanged(String oldTitle,String newTitle){client.onTitleChanged(this);}
  @Override public void onCopyTextToClipboard(String text){client.onCopyTextToClipboard(this,text);}
  @Override public void onPasteTextFromClipboard(){client.onPasteTextFromClipboard(this);}
  @Override public void onBell(){client.onBell(this);}
  @Override public void onColorsChanged(){client.onColorsChanged(this);}
}
