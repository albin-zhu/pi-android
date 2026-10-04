package dev.zfold.assistant;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.speech.*;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import java.util.*;
import java.util.function.Consumer;

/** Selects a downloaded Mandarin TTS voice instead of a network/default placeholder. */
final class PhoneSpeech {
  static final class AsrSupport {
    final boolean service,installed,downloadable;final String language,error;
    AsrSupport(boolean service,boolean installed,boolean downloadable,String language,String error){this.service=service;this.installed=installed;this.downloadable=downloadable;this.language=language;this.error=error;}
  }
  static Intent intent(String language,boolean offline){Intent request=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE,language).putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,true);if(offline)request.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true);return request;}
  private static String mandarin(List<String> languages){for(String value:languages){String lower=value.toLowerCase(Locale.ROOT);if(lower.equals("cmn-hans-cn")||lower.equals("zh-cn")||lower.equals("zh-hans-cn"))return value;}return null;}
  static void inspectMandarin(Context context,Consumer<AsrSupport> callback){
    inspectMandarin(context,callback,0);
  }
  private static void inspectMandarin(Context context,Consumer<AsrSupport> callback,int attempt){
    if(Build.VERSION.SDK_INT<31){callback.accept(new AsrSupport(false,false,false,null,null));return;}
    if(!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)){callback.accept(new AsrSupport(false,false,false,null,null));return;}
    if(Build.VERSION.SDK_INT<33){callback.accept(new AsrSupport(true,false,false,null,"系统版本无法检查本机语言包"));return;}
    SpeechRecognizer recognizer;
    try{recognizer=SpeechRecognizer.createOnDeviceSpeechRecognizer(context);}catch(RuntimeException error){callback.accept(new AsrSupport(false,false,false,null,error.getMessage()));return;}
    Handler main=new Handler(Looper.getMainLooper());boolean[] finished={false};
    Runnable timeout=()->{if(finished[0])return;finished[0]=true;recognizer.destroy();callback.accept(new AsrSupport(true,false,false,null,"识别服务检测超时"));};
    main.postDelayed(timeout,7000);
    try{recognizer.checkRecognitionSupport(intent("zh-CN",true),context.getMainExecutor(),new RecognitionSupportCallback(){
      void complete(AsrSupport support){
        if(finished[0])return;finished[0]=true;main.removeCallbacks(timeout);recognizer.destroy();
        if(attempt<1&&!support.installed&&!support.downloadable&&support.error==null){main.postDelayed(()->inspectMandarin(context,callback,attempt+1),600);return;}
        callback.accept(support);
      }
      public void onSupportResult(RecognitionSupport support){String installed=mandarin(support.getInstalledOnDeviceLanguages()),downloadable=mandarin(support.getSupportedOnDeviceLanguages());complete(new AsrSupport(true,installed!=null,downloadable!=null,installed!=null?installed:downloadable,null));}
      public void onError(int code){complete(new AsrSupport(true,false,false,null,"识别服务检测失败（"+code+"）"));}
    });}catch(RuntimeException error){if(!finished[0]){finished[0]=true;main.removeCallbacks(timeout);recognizer.destroy();callback.accept(new AsrSupport(true,false,false,null,error.getMessage()));}}
  }
  static void downloadMandarin(Context context,Consumer<String> callback){
    if(Build.VERSION.SDK_INT<33){callback.accept("系统版本不支持应用内下载识别包");return;}
    if(!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)){callback.accept("本机识别服务不可用");return;}
    SpeechRecognizer recognizer=SpeechRecognizer.createOnDeviceSpeechRecognizer(context);boolean[] finished={false};
    Runnable close=()->{if(!finished[0]){finished[0]=true;recognizer.destroy();}};
    if(Build.VERSION.SDK_INT<34){try{recognizer.triggerModelDownload(intent("cmn-Hans-CN",true));callback.accept("已向系统请求下载中文识别包");}catch(RuntimeException error){callback.accept("无法启动下载："+error.getMessage());}finally{close.run();}return;}
    try{recognizer.triggerModelDownload(intent("cmn-Hans-CN",true),context.getMainExecutor(),new ModelDownloadListener(){
      public void onProgress(int value){callback.accept("中文识别包下载中 · "+value+"%");}
      public void onSuccess(){close.run();callback.accept("本机中文识别包已就绪");}
      public void onScheduled(){close.run();callback.accept("本机中文识别包已安排下载，完成后可再检查状态");}
      public void onError(int code){close.run();callback.accept("中文识别包下载未完成（"+code+"）");}
    });}catch(RuntimeException error){close.run();callback.accept("无法启动下载："+error.getMessage());}
  }
  static SpeechRecognizer createRecognizer(Context context,boolean onDevice){return onDevice&&Build.VERSION.SDK_INT>=31?SpeechRecognizer.createOnDeviceSpeechRecognizer(context):SpeechRecognizer.createSpeechRecognizer(context);}
  static List<String> completeSpeechSentences(StringBuilder buffer){
    List<String> sentences=new ArrayList<>();
    String original=buffer.toString();
    if((original.length()-original.replace("```","").length())/3%2==1){buffer.setLength(0);buffer.append(original);return sentences;}
    String text=speakable(buffer.toString());
    java.util.regex.Matcher match=java.util.regex.Pattern.compile("[\\s\\S]*?[。！？；;\\n]").matcher(text);
    int start=0;
    while(match.region(start,text.length()).find()){
      String sentence=text.substring(match.start(),match.end()).trim();
      if(!sentence.isEmpty())sentences.add(sentence);
      start=match.end();
    }
    buffer.setLength(0);buffer.append(text.substring(start));
    return sentences;
  }
  static String remainingSpeechSentence(StringBuilder buffer){
    List<String> sentences=completeSpeechSentences(buffer);if(!sentences.isEmpty())return sentences.get(0);
    String sentence=buffer.toString().trim();buffer.setLength(0);return sentence;
  }
  static String speakable(String value){
    if(value==null)return "";
    String text=value
      .replaceAll("(?s)```.*?(?:```|$)"," 代码已略过。 ")
      .replaceAll("(?s)`([^`]*)`","$1")
      .replaceAll("\\{\\s*\"[^\"]*\"\\s*:[\\s\\S]{0,300}?\\}","参数已略。")
      .replaceAll("(?s)\\{[\\s\\S]{40,400}?\\}","参数已略。")
      .replaceAll("(调用|使用|执行)(了)?工具[：:，,]?\\s*[\\s\\S]{0,80}?(?=[。！？\\n]|$)","$1工具。")
      .replaceAll("(正在|准备)(调用|使用|执行)\\s*[\\s\\S]{0,60}?(?=[。！？\\n]|$)","$1工具。")
      .replaceAll("(?m)^#{1,6}\\s*","").replaceAll("\\*\\*|__|==|~~","")
      .replaceAll("(?m)^\\s*[-*+]\\s+","").replaceAll("\\[([^\\]]+)]\\([^)]+\\)","$1")
      .replaceAll("https?://\\S+","链接").replaceAll("\\|","，");
    return text.replaceAll("\\s+"," ").trim();
  }
  static String availableOfflineChineseVoice(TextToSpeech engine){Voice voice=bestVoice(engine);return voice==null?null:voice.getName();}
  static String selectOfflineChineseVoice(TextToSpeech engine){
    Voice best=bestVoice(engine);return best!=null&&engine.setVoice(best)==TextToSpeech.SUCCESS?best.getName():null;
  }
  private static Voice bestVoice(TextToSpeech engine){
    Set<Voice> voices=engine.getVoices();if(voices==null)return null;
    Voice best=null;
    for(Voice voice:voices){
      Locale locale=voice.getLocale();if(locale==null||!"zh".equals(locale.getLanguage())||voice.isNetworkConnectionRequired()||(voice.getFeatures()!=null&&voice.getFeatures().contains("notInstalled")))continue;
      if(best==null||score(voice)>score(best)||(score(voice)==score(best)&&voice.getName().compareTo(best.getName())<0))best=voice;
    }
    return best;
  }
  private static int score(Voice voice){return ("CN".equals(voice.getLocale().getCountry())?1000:0)+voice.getQuality()-voice.getLatency()/10;}
  private PhoneSpeech(){}
}
