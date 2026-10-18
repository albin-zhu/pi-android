package dev.zfold.assistant;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** Decoded names, never provider IDs or a second round of URL decoding. */
final class PreviewMarkdownPaths {
  private PreviewMarkdownPaths(){}
  static String segment(String name)throws IOException {
    if(name==null||name.isEmpty()||name.equals(".")||name.equals("..")||name.indexOf('/')>=0||name.indexOf('\\')>=0||name.indexOf(':')>=0)
      throw new IOException("无效的本地资源名称");
    for(int i=0;i<name.length();i++)if(Character.isISOControl(name.charAt(i)))throw new IOException("无效的本地资源名称");
    return name;
  }
  static String relative(List<String> segments)throws IOException {
    if(segments.size()<2||!segments.get(0).equals("files"))throw new IOException("资源不在授权目录内");
    StringBuilder result=new StringBuilder();
    for(int i=1;i<segments.size();i++){if(i>1)result.append('/');result.append(segment(segments.get(i)));}
    return result.toString();
  }
  static String fileRelative(File root,File file)throws IOException {
    String base=root.getCanonicalPath()+File.separator,target=file.getCanonicalPath();
    if(!target.startsWith(base))throw new IOException("文件不在授权目录内");
    String relative=target.substring(base.length());
    for(String part:relative.split("/",-1))segment(part);
    return relative;
  }
  static boolean image(String mime){
    return "image/png".equals(mime)||"image/jpeg".equals(mime)||"image/gif".equals(mime)||"image/webp".equals(mime)
      ||"image/svg+xml".equals(mime)||"image/bmp".equals(mime)||"image/avif".equals(mime)||"image/x-icon".equals(mime);
  }
}
