package dev.zfold.assistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Local slash commands are UI controls; only skill/pkg prompts are sent to the model. */
final class SlashCommands {
  static final class Command {
    final String name,argument,title,detail,category;
    final boolean wantsArgument;
    Command(String name,String argument,String title,String detail,String category,boolean wantsArgument){
      this.name=name;this.argument=argument;this.title=title;this.detail=detail;this.category=category;this.wantsArgument=wantsArgument;
    }
    String invocation(){return argument.isEmpty()?"/"+name:"/"+name+" "+argument;}
  }

  interface Target {void run(String name,String argument);}

  private SlashCommands(){}

  static List<Command> catalog(){
    List<Command> items=new ArrayList<>();
    items.add(new Command("tools","","工具中心","查看 Skills、Agent 工具、MCP 和命令","界面",false));
    items.add(new Command("skills","","Skills","查看和管理工作区/全局 Skill","能力",false));
    items.add(new Command("mcp","","连接","添加、连接和管理 MCP 外部工具","能力",false));
    items.add(new Command("packages","","软件包","搜索和安装 Runtime 软件包","Runtime",false));
    items.add(new Command("model","","模型设置","切换或配置模型服务","设置",false));
    items.add(new Command("agent","","Agent 身份","修改名字、头像和系统提示词","设置",false));
    items.add(new Command("phone","","手机能力","无障碍、悬浮窗、语音和 Jev","设置",false));
    items.add(new Command("voice","","语音对话","开始或停止实时对话","执行",false));
    items.add(new Command("share","","共享屏幕","开关共享屏幕聊天","执行",false));
    items.add(new Command("new","","新对话","创建一个干净会话","会话",false));
    items.add(new Command("stop","","停止","停止当前任务或语音","执行",false));
    items.add(new Command("skill","name","调用 Skill","例如 /skill skill-creator 做一个 xxx Skill","能力",true));
    items.add(new Command("pkg","package","安装软件包","例如 /pkg jq；由 Agent 调用 pkg 工具","Runtime",true));
    items.add(new Command("runtime","","运行时体检","让 Agent 检查沙箱和已装运行时","Runtime",false));
    return items;
  }

  static List<Command> filter(String raw){
    String text=raw==null?"":raw.trim();
    if(!text.startsWith("/"))return new ArrayList<>();
    String[] parts=text.split("\\s+",2);
    String typed=(parts[0].substring(1)+" "+(parts.length>1?parts[1]:"")).trim().toLowerCase(Locale.ROOT);
    List<Command> out=new ArrayList<>();
    for(Command item:catalog()){
      String haystack=(item.name+" "+item.title+" "+item.detail+" "+item.category).toLowerCase(Locale.ROOT);
      if(typed.isEmpty()||haystack.contains(typed))out.add(item);
      if(out.size()>=8)break;
    }
    return out;
  }

  static boolean parse(String raw,String[] output){
    String text=raw==null?"":raw.trim();
    if(!text.startsWith("/"))return false;
    String[] parts=text.substring(1).split("\\s+",2);
    String name=parts[0].toLowerCase(Locale.ROOT),argument=parts.length>1?parts[1].trim():"";
    for(Command item:catalog())if(item.name.equals(name)){output[0]=name;output[1]=argument;return true;}
    return false;
  }

  static String skillPrompt(String argument){
    String request=argument==null?"":argument.trim();
    return request.isEmpty()
      ?"请先读取可用的本地 Skills，并告诉我可以调用哪一个。"
      :"请根据本地 Skills 判断是否应使用 \""+request+"\"；如果名称匹配某个 Skill，先读取它再按说明执行。";
  }

  static String packagePrompt(String argument){
    String name=argument==null?"":argument.trim().split("\\s+")[0];
    if(name.isEmpty())return "请打开 Runtime 软件包管理器，搜索并推荐适合当前任务的 CLI 包。";
    return "请使用 pkg 工具搜索 \""+name+"\"；确认存在后征求我同意，再安装并在必要时验证该 CLI 可执行。";
  }

  static String runtimePrompt(){
    return "请调用 sandbox_status，总结当前 Node/npm、Runtime Pack、软件包和二进制执行能力；不要猜测缺失的能力。";
  }
}
