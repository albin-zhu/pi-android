package dev.zfold.assistant;
public final class UsageMathCheck {
  public static void main(String[] args){
    if(!"openai".equals(UsageMath.family("openai-codex","gpt-5.3-codex")))throw new AssertionError();
    if(!"xai".equals(UsageMath.family("custom","grok-4.7")))throw new AssertionError();
    if(!"kimi".equals(UsageMath.family("custom","kimi-k2.5")))throw new AssertionError();
    if(UsageMath.family("custom","claude-sonnet-5")!=null)throw new AssertionError();
    if(UsageMath.remaining(100,20)!=80||UsageMath.remaining(100,120)!=0)throw new AssertionError();
    System.out.println("usage family classification and local budget checks passed");
  }
}
