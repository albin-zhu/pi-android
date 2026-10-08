package dev.zfold.assistant;
import java.util.Locale;
final class UsageMath {
  static String family(String provider,String model){
    String p=provider.toLowerCase(Locale.ROOT),m=model.toLowerCase(Locale.ROOT);
    if(p.equals("xai")||m.startsWith("grok-"))return "xai";
    if(p.contains("moonshot")||p.equals("kimi")||m.contains("kimi"))return "kimi";
    if(p.startsWith("openai")||m.startsWith("gpt-")||m.matches("o[134]-.*"))return "openai";
    return null;
  }
  static long remaining(long budget,long used){return Math.max(0,budget-used);}
}
