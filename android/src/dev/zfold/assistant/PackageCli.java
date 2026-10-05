package dev.zfold.assistant;

import android.content.Context;
import java.util.*;
import org.json.*;

/** Terminal syntax backed by exactly the same package database and lock as the UI/tool. */
final class PackageCli {
  interface Output {void write(boolean error,String text);}
  static final String HELP="橙橙本机软件包管理\n用法：pkg install [-y] <包名...>\n"
    +"      pkg search <关键词>\n      pkg list / list-installed\n      pkg list-all\n"
    +"      pkg update\n      pkg remove <包名...>\n"
    +"与工作台共用软件包和安装记录。update 刷新索引；install 同时用于升级指定包。\n";
  static int run(Context context,List<String> arguments,Output output)throws Exception {
    List<String> args=new ArrayList<>(arguments);
    if(args.isEmpty()||args.get(0).equals("help")||args.get(0).equals("--help")||args.get(0).equals("-h")){
      output.write(false,HELP);return 0;
    }
    String action=args.remove(0);
    if(action.equals("uninstall"))action="remove";
    boolean mutation=action.equals("install")||action.equals("remove");
    if(mutation){
      args.removeIf(value->value.equals("-y")||value.equals("--yes"));
      if(args.isEmpty())return usage(output,"需要至少一个包名");
      for(String name:args)if(!name.matches("[a-z0-9][a-z0-9+._-]{0,63}"))return usage(output,"无效包名或选项："+name);
      if(context.getApplicationInfo().targetSdkVersion!=28)return usage(output,"安装与执行软件包需要 compat28 通道");
      for(String name:new LinkedHashSet<>(args)){
        NativeToolExecution.checkpoint();
        if(action.equals("install")){
          JSONObject result=PiPackageManager.install(context,name,message->output.write(true,message+"\n"));
          output.write(false,name+" "+result.optString("version")+(result.optBoolean("alreadyInstalled")?" 已安装":" 安装完成")+"\n");
        }else{
          JSONObject result=PiPackageManager.remove(context,name);
          output.write(false,name+(result.optBoolean("removed")?" 已删除":" 未安装")+"\n");
        }
      }
      return 0;
    }
    if(action.equals("search")){
      if(args.isEmpty())return usage(output,"search 需要关键词");
      JSONArray rows=PiPackageManager.search(context,String.join(" ",args),false).getJSONArray("packages");
      for(int i=0;i<rows.length();i++){
        JSONObject row=rows.getJSONObject(i);
        output.write(false,row.getString("name")+" "+row.optString("version")+(row.optBoolean("installed")?" [已安装]":"")+"\n  "+row.optString("description")+"\n");
      }
      if(rows.length()==0)output.write(false,"没有匹配的软件包\n");
      return 0;
    }
    if(!args.isEmpty())return usage(output,"不支持的参数："+String.join(" ",args));
    if(action.equals("list")||action.equals("list-installed")){
      synchronized(PiPackageManager.class){
        JSONObject state=PiPackageManager.installed(context);
        List<String> names=new ArrayList<>();state.keys().forEachRemaining(names::add);Collections.sort(names);
        for(String name:names)output.write(false,name+" "+state.getJSONObject(name).optString("version")+"\n");
        if(names.isEmpty())output.write(false,"尚未安装软件包\n");
      }
      return 0;
    }
    if(action.equals("update")||action.equals("list-all")){
      output.write(true,"正在读取软件包目录…\n");
      JSONArray rows=PiPackageManager.status(context,action.equals("update")).getJSONArray("packages");
      if(action.equals("update"))output.write(false,"索引已更新，共 "+rows.length()+" 个软件包\n");
      else for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);output.write(false,row.getString("name")+" "+row.optString("version")+"\n");}
      return 0;
    }
    return usage(output,"不支持的命令："+action);
  }
  private static int usage(Output output,String message){output.write(true,"pkg: "+message+"\n"+HELP);return 2;}
}
