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
  static String compact(long value){
    if(value<1000)return Long.toString(value);
    double divisor=value>=999500000000L?1e12:value>=999500000L?1e9:value>=999500L?1e6:1e3;
    String unit=divisor==1e12?"T":divisor==1e9?"B":divisor==1e6?"M":"K";
    double number=value/divisor;String text=String.format(Locale.ROOT,number>=100?"%.0f":"%.1f",number);
    if(text.endsWith(".0"))text=text.substring(0,text.length()-2);return text+unit;
  }
  static long remaining(long budget,long used){return Math.max(0,budget-used);}
}
