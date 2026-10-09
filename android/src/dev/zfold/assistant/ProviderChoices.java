package dev.zfold.assistant;

/** Presentation and eligibility for the SDK-owned provider catalog. */
final class ProviderChoices {
  static boolean canAdd(String id, boolean oauthLogin, boolean apiKeyLogin) {
    return !"custom".equals(id) && (oauthLogin || apiKeyLogin);
  }
  static String displayName(String id, String fallback) {
    switch (id) {
      case "deepseek": return "DeepSeek（深度求索）";
      case "zai": return "智谱 / Z.AI";
      case "zai-coding-cn": return "智谱 Coding（国内）";
      case "moonshotai": return "Kimi / Moonshot（国际）";
      case "moonshotai-cn": return "Kimi / 月之暗面（国内）";
      case "kimi-coding": return "Kimi Coding";
      case "minimax": return "MiniMax（国际）";
      case "minimax-cn": return "MiniMax（国内）";
      case "qwen-token-plan": return "通义千问 Token Plan（国际）";
      case "qwen-token-plan-cn": return "通义千问 Token Plan（国内）";
      case "qwen-token-plan-individual": return "通义千问 Token Plan（个人）";
      case "xiaomi": return "小米 MiMo";
      case "xiaomi-token-plan-cn": return "小米 MiMo Token Plan（国内）";
      case "xiaomi-token-plan-ams": return "小米 MiMo Token Plan（欧洲）";
      case "xiaomi-token-plan-sgp": return "小米 MiMo Token Plan（新加坡）";
      case "ant-ling": return "蚂蚁 Ling";
      default: return fallback;
    }
  }
}
