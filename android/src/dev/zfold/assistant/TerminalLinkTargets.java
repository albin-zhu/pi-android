package dev.zfold.assistant;

import java.io.File;
import java.io.IOException;
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

  /** Common local-open boundary, also used after terminal links have been decoded. */
  static File localFile(String target,File sharedRoot)throws IOException,URISyntaxException {
    File root=sharedRoot.getCanonicalFile();
    String value=androidTarget(target,root.getPath());
    for(String alias:new String[]{"/storage/pi","/sdcard/pi","/storage/emulated/0/pi"}){
      if(value.equals(alias)||value.startsWith(alias+"/")){
        value=root.getPath()+value.substring(alias.length());
        break;
      }
    }
    File file=new File(value);
    if(!file.isAbsolute())throw new IOException("文件链接必须使用绝对路径");
    file=file.getCanonicalFile();
    if(!file.equals(root)&&!file.getPath().startsWith(root.getPath()+File.separator))throw new IOException("只能打开共享目录内的文件：Ubuntu /workspace 对应 Android "+root.getPath()+"；/root、/tmp 等私有目录不共享");
    return file;
  }
}
