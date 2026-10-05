package dev.zfold.assistant;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/** Provider choices are independent; importing a key never enables cloud recording. */
final class SpeechConfig {
  static final String[] VOICE_NAMES={"Vivi 2.0","小何 2.0","云舟 2.0"};
  static final String[] VOICES={"zh_female_vv_uranus_bigtts","zh_female_xiaohe_uranus_bigtts","zh_male_m191_uranus_bigtts"};
  final JSONObject document;final boolean cloudAsr,cloudTts;final String speaker;
  private SpeechConfig(JSONObject value){document=value;JSONObject asr=value.optJSONObject("asr"),tts=value.optJSONObject("tts");cloudAsr=asr!=null&&"volc".equals(asr.optString("provider"));cloudTts=tts!=null&&"volc".equals(tts.optString("provider"));speaker=tts==null?VOICES[0]:tts.optString("speaker",VOICES[0]);}
  static File file(Context context){return new File(CredentialStore.directory(context),"speech.json");}
  static SpeechConfig load(Context context){try{File file=file(context);if(file.length()>65536||Files.isSymbolicLink(file.toPath()))throw new IOException();return new SpeechConfig(new JSONObject(Files.readString(file.toPath())));}catch(Exception ignored){return new SpeechConfig(new JSONObject());}}
  boolean hasKey(){return !document.optString("apiKey").trim().isEmpty();}
  static void save(Context context,boolean asr,boolean tts,int voice,String replacement)throws Exception{
    SpeechConfig current=load(context);JSONObject value=current.document;
    String key=replacement.trim();if(key.isEmpty())key=value.optString("apiKey").trim();
    if(key.length()>4096||key.matches(".*\\s.*"))throw new IOException("API Key 格式不正确");
    if((asr||tts)&&key.isEmpty())throw new IOException("先填写豆包语音 API Key");
    if(voice<0||voice>=VOICES.length)throw new IOException("请选择音色");
    value.put("schemaVersion",1).put("provider","volc").put("apiKey",key);
    JSONObject recognition=value.optJSONObject("asr");if(recognition==null)recognition=new JSONObject();
    recognition.put("provider",asr?"volc":"system").put("resourceId","volc.seedasr.sauc.duration").put("sampleRate",16000);
    if(!recognition.has("hotwords"))recognition.put("hotwords",new JSONArray(new String[]{"橙橙","Pi","API","ADB"}));
    JSONObject synthesis=value.optJSONObject("tts");if(synthesis==null)synthesis=new JSONObject();
    synthesis.put("provider",tts?"volc":"system").put("resourceId","seed-tts-2.0").put("sampleRate",24000).put("speaker",VOICES[voice]);
    value.put("asr",recognition).put("tts",synthesis);
    File file=file(context),dir=file.getParentFile();if(Files.isSymbolicLink(dir.toPath())||Files.isSymbolicLink(file.toPath()))throw new IOException("语音配置路径无效");
    if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法保存语音配置");
    dir.setReadable(false,false);dir.setWritable(false,false);dir.setExecutable(false,false);dir.setReadable(true,true);dir.setWritable(true,true);dir.setExecutable(true,true);
    File temp=File.createTempFile("speech-",".tmp",dir);
    try{temp.setReadable(false,false);temp.setWritable(false,false);temp.setReadable(true,true);temp.setWritable(true,true);
      try(FileOutputStream out=new FileOutputStream(temp)){out.write(value.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
      Files.move(temp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }finally{temp.delete();}
  }
  static String reason(String code){switch(code){case "authentication":return "鉴权失败";case "rate_limited":return "额度或并发受限";case "configuration_or_request":return "配置不可用";case "timeout":return "连接超时";default:return "连接或服务异常";}}
}
