package dev.zfold.assistant;

import java.net.URI;
import java.net.URISyntaxException;

/** Translate the Ubuntu bind mount, never a guest-private path, into an Android target. */
final class TerminalLinkTargets {
  static String androidTarget(String target,String workspaceRoot)throws URISyntaxException {
    String value=target;
    if(target.regionMatches(true,0,"file:",0,5)){
      URI uri=new URI(target);
      String host=uri.getAuthority();
      if(host!=null&&!host.isEmpty()&&!host.equalsIgnoreCase("localhost"))throw new IllegalArgumentException("不能打开其他主机的文件链接");
      if(uri.getRawQuery()!=null||uri.getRawFragment()!=null)throw new IllegalArgumentException("文件链接不能包含查询参数或片段");
      value=uri.getPath();
      if(value==null||!value.startsWith("/"))throw new IllegalArgumentException("文件链接必须使用绝对路径");
    }
    if(!value.startsWith("/")&&!new URI(value).isAbsolute())throw new IllegalArgumentException("文件链接必须使用绝对路径");
    if(value.equals("/workspace")||value.startsWith("/workspace/"))return workspaceRoot+value.substring("/workspace".length());
    return value;
  }
}
