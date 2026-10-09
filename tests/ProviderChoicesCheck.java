package dev.zfold.assistant;

public final class ProviderChoicesCheck {
  public static void main(String[] args) {
    for (String id : new String[]{"deepseek", "zai", "zai-coding-cn", "moonshotai-cn", "minimax-cn", "qwen-token-plan-cn"}) {
      if (!ProviderChoices.canAdd(id, false, true)) throw new AssertionError(id + " API Key provider is missing");
    }
    if (!ProviderChoices.canAdd("openai-codex", true, false)) throw new AssertionError("OAuth provider missing");
    if (ProviderChoices.canAdd("custom", true, true)) throw new AssertionError("Custom is configured separately");
    if (ProviderChoices.canAdd("unsupported", false, false)) throw new AssertionError("No usable login");
    if (!ProviderChoices.displayName("zai-coding-cn", "Z.AI Coding CN").contains("智谱")) throw new AssertionError("Missing Chinese Zhipu label");
    if (!ProviderChoices.displayName("deepseek", "DeepSeek").equals("DeepSeek（深度求索）")) throw new AssertionError("Missing DeepSeek label");
    if (!ProviderChoices.displayName("future-sdk-provider", "Future Provider").equals("Future Provider")) throw new AssertionError("Future SDK providers must retain their name");
    System.out.println("Provider choices verified: API Key, OAuth, Chinese names and SDK fallback");
  }
}
