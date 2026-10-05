package dev.zfold.assistant;

import java.util.*;
import java.util.function.Consumer;

/** Bounded, one-use PCM handoff. Audio is delivered only after the producer released its mic. */
final class WakeHandoff {
  static final long TTL=10_000;private static final int LIMIT=16000*2*5;
  static final class Audio {final long id;final ArrayList<byte[]> pcm;final boolean lost;
    Audio(long id,ArrayList<byte[]> pcm,boolean lost){this.id=id;this.pcm=pcm;this.lost=lost;}}
  private long nextId,id,at;private int bytes;private boolean finished,claimed,overflow;
  private ArrayList<byte[]> pcm;private Consumer<Audio> receiver;
  static ArrayList<byte[]> keywordPrefix(Collection<byte[]> history,long totalSamples,float firstTokenSeconds){
    ArrayList<byte[]> all=new ArrayList<>(history);long desired=16000*2;
    if(Float.isFinite(firstTokenSeconds)&&firstTokenSeconds>=0&&firstTokenSeconds*16000<=totalSamples)desired=Math.max(512,totalSamples-Math.max(0,(long)(firstTokenSeconds*16000)-4000));
    int keep=Math.min(all.size(),(int)Math.min(128,(desired+511)/512));return new ArrayList<>(all.subList(all.size()-keep,all.size()));
  }
  synchronized long begin(Collection<byte[]> history,long now){cancel();id=++nextId;at=now;pcm=new ArrayList<>();for(byte[] chunk:history)append(id,chunk);return id;}
  synchronized void append(long token,byte[] chunk){if(token!=id||pcm==null||finished||overflow)return;bytes+=chunk.length;if(bytes>LIMIT){overflow=true;pcm.clear();return;}pcm.add(chunk);}
  synchronized boolean pending(long token,long now){return token>0&&token==id&&pcm!=null&&!claimed&&now-at<=TTL;}
  synchronized boolean claim(long token,long now,Consumer<Audio> consumer){if(!pending(token,now))return false;claimed=true;receiver=consumer;deliver();return true;}
  synchronized void finish(long token){if(token!=id||pcm==null)return;finished=true;deliver();}
  private void deliver(){if(!finished||receiver==null)return;Consumer<Audio> next=receiver;Audio audio=new Audio(id,new ArrayList<>(pcm),overflow);cancel();next.accept(audio);}
  synchronized boolean full(long token){return token==id&&overflow;}
  synchronized void cancel(){id=0;at=0;bytes=0;finished=false;claimed=false;overflow=false;pcm=null;receiver=null;}
  synchronized void cancel(long token){if(token==id)cancel();}
}
