package dev.zfold.assistant;

import android.content.Context;
import org.json.*;
import java.util.*;
import java.util.regex.Pattern;
import com.k2fsa.sherpa.onnx.*;

/** One shipped configuration for the detector, UI, notification and initial ASR prefix. */
final class WakeConfig {
  private static WakeConfig cached;
  final String word,tokens;private final Pattern prefix;
  private WakeConfig(JSONObject value)throws Exception{
    word=value.getString("word");tokens=value.getString("tokens");JSONArray aliases=value.getJSONArray("asrPrefixes");
    ArrayList<String> parts=new ArrayList<>();for(int i=0;i<aliases.length();i++){String alias=aliases.getString(i);parts.add(Pattern.quote(alias).replace(" ","\\E\\s+\\Q")+(alias.matches("[a-zA-Z ]+")?"(?![a-zA-Z])":""));}
    prefix=Pattern.compile("(?i)^\\s*(?:(?:"+String.join("|",parts)+")[\\s，,。：:？?！!]*)+");
  }
  static synchronized WakeConfig get(Context context){if(cached==null)try(java.io.InputStream in=context.getAssets().open("wake.json")){cached=new WakeConfig(new JSONObject(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception error){throw new IllegalStateException("唤醒配置不可用",error);}return cached;}
  String stripPrefix(String text){return prefix.matcher(text==null?"":text.trim()).replaceFirst("").trim();}
  KeywordSpotter createSpotter(Context context){String p="models/kws/";
    return new KeywordSpotter(context.getAssets(),new KeywordSpotterConfig(new FeatureConfig(),new OnlineModelConfig(
      new OnlineTransducerModelConfig(p+"encoder.int8.onnx",p+"decoder.int8.onnx",p+"joiner.int8.onnx",new QnnConfig()),
      new OnlineParaformerModelConfig(),new OnlineZipformer2CtcModelConfig(),new OnlineNeMoCtcModelConfig(),new OnlineToneCtcModelConfig(),
      p+"tokens.txt",1,false,"cpu","zipformer2","",""),4,p+"keywords.txt",1.5f,0.25f,2));
  }
}
