package dev.zfold.assistant;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Arrays;

/** Android-independent document policy. A supported suffix is not proof of readable text. */
final class PreviewFiles {
  static final int MAX_DOCUMENT_BYTES=2*1024*1024;
  enum Kind {MARKDOWN, HTML, SVG, TEXT}

  private PreviewFiles(){}

  static Kind kind(File file){
    String extension=extension(file);
    if(extension.equals("md")||extension.equals("markdown")||extension.equals("mdown")||extension.equals("mkd"))return Kind.MARKDOWN;
    if(extension.equals("html")||extension.equals("htm"))return Kind.HTML;
    if(extension.equals("svg"))return Kind.SVG;
    return Kind.TEXT;
  }

  static boolean supports(File file,String mime){
    if(kind(file)!=Kind.TEXT)return true;
    String extension=extension(file);
    switch(extension){
      case "txt":case "text":case "log":case "csv":case "tsv":case "json":case "jsonl":case "ndjson":
      case "yaml":case "yml":case "xml":case "gtm":case "toml":case "ini":case "conf":case "cfg":case "properties":
      case "js":case "mjs":case "cjs":case "jsx":case "ts":case "tsx":case "css":case "scss":case "less":
      case "py":case "sh":case "bash":case "zsh":case "fish":case "java":case "kt":case "kts":
      case "c":case "h":case "cc":case "cpp":case "cxx":case "hpp":case "cs":case "go":case "rs":
      case "rb":case "php":case "swift":case "lua":case "pl":case "r":case "sql":case "vue":case "svelte":
      case "gradle":case "cmake":case "make":case "diff":case "patch":case "rst":case "tex":case "env":return true;
      default:break;
    }
    String name=file.getName().toLowerCase(Locale.ROOT);
    if(name.equals("readme")||name.equals("license")||name.equals("copying")||name.equals("makefile")||name.equals("dockerfile")||name.equals(".gitignore"))return true;
    String type=mime==null?"":mime.split(";",2)[0].trim().toLowerCase(Locale.ROOT);
    return type.startsWith("text/")||type.equals("application/json")||type.endsWith("+json")||type.equals("application/xml")||type.endsWith("+xml")
      ||type.equals("application/javascript")||type.equals("application/x-javascript")||type.equals("application/yaml")||type.equals("application/x-yaml")||type.equals("image/svg+xml");
  }

  static boolean markdownMime(String mime){
    String type=normalizedMime(mime);
    return type.equals("text/markdown")||type.equals("text/x-markdown")||type.equals("application/markdown")||type.equals("application/x-markdown");
  }

  static boolean markdownRequestMime(String mime){
    return markdownMime(mime)||normalizedMime(mime).equals("text/plain");
  }

  static boolean supportsMarkdownDocument(String name,String mime){
    if(!markdownRequestMime(mime))return false;
    if(markdownMime(mime))return true;
    // A display name is metadata, never a local path. Bound it before parsing.
    return name!=null&&name.length()<=1024&&name.indexOf('/')<0&&name.indexOf('\\')<0
      &&kind(new File(name))==Kind.MARKDOWN;
  }

  private static String normalizedMime(String mime){
    if(mime==null||mime.length()>128)return "";
    int semicolon=mime.indexOf(';');
    return (semicolon<0?mime:mime.substring(0,semicolon)).trim().toLowerCase(Locale.ROOT);
  }

  static String readText(File file)throws IOException {
    if(!file.isFile())throw new FileNotFoundException("文件不存在或已移除");
    byte[] data;
    try(FileInputStream input=new FileInputStream(file)){
      long size=input.getChannel().size();
      if(size>MAX_DOCUMENT_BYTES)throw new IOException("文件超过 2 MiB 预览上限；请使用外部应用打开");
      data=new byte[(int)size];int offset=0;
      while(offset<data.length){
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("预览已取消");
        int count=input.read(data,offset,data.length-offset);
        if(count<0)throw new IOException("文件在读取时发生变化，请刷新");
        offset+=count;
      }
      if(input.read()!=-1)throw new IOException("文件在读取时发生变化，请刷新");
    }
    return decode(data,data.length);
  }

  /** Reads at most the limit plus one probe byte; size metadata never controls allocation. */
  static String readText(InputStream input)throws IOException {
    if(input==null)throw new FileNotFoundException("文件提供方未返回可读内容");
    byte[] data=new byte[8192];int length=0;
    while(true){
      if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("预览已取消");
      if(length==MAX_DOCUMENT_BYTES){
        if(input.read()!=-1)throw new IOException("文件超过 2 MiB 预览上限；请使用外部应用打开");
        break;
      }
      if(length==data.length)data=Arrays.copyOf(data,Math.min(MAX_DOCUMENT_BYTES,data.length*2));
      int count=input.read(data,length,data.length-length);
      if(count<0)break;
      if(count==0){
        int next=input.read();if(next<0)break;data[length++]=(byte)next;
      }else length+=count;
    }
    if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("预览已取消");
    return decode(data,length);
  }

  private static String decode(byte[] data,int length)throws IOException {
    int offset=0;Charset encoding=StandardCharsets.UTF_8;
    if(length>=4&&((data[0]==0&&data[1]==0&&(data[2]&255)==254&&(data[3]&255)==255)||((data[0]&255)==255&&(data[1]&255)==254&&data[2]==0&&data[3]==0)))
      throw new IOException("不支持 UTF-32；请转换为 UTF-8 或带 BOM 的 UTF-16");
    if(length>=3&&(data[0]&255)==239&&(data[1]&255)==187&&(data[2]&255)==191)offset=3;
    else if(length>=2&&(data[0]&255)==255&&(data[1]&255)==254){encoding=StandardCharsets.UTF_16LE;offset=2;}
    else if(length>=2&&(data[0]&255)==254&&(data[1]&255)==255){encoding=StandardCharsets.UTF_16BE;offset=2;}
    String text;
    try{text=encoding.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data,offset,length-offset)).toString();}
    catch(CharacterCodingException error){throw new IOException("不是有效的 UTF-8 / 带 BOM 的 UTF-16 文本；请使用外部应用打开",error);}
    for(int i=0;i<text.length();i++){
      char c=text.charAt(i);
      if((c<32&&c!='\t'&&c!='\n'&&c!='\r')||(c>=127&&c<=159))throw new IOException("文件包含二进制或控制字符，无法作为文本预览");
    }
    if(text.startsWith("%PDF-"))throw new IOException("PDF 不是可读文本预览格式；请使用外部应用打开");
    return text;
  }

  /** Input has already been URL-decoded once. Never decode again here. */
  static File resource(File root,String decodedRelativePath)throws IOException {
    if(decodedRelativePath==null||decodedRelativePath.isEmpty()||decodedRelativePath.startsWith("/")||decodedRelativePath.indexOf('\\')>=0||decodedRelativePath.indexOf('\0')>=0)
      throw new IOException("无效本地资源路径");
    for(String segment:decodedRelativePath.split("/",-1))
      if(segment.equals("..")||segment.equals(".")||segment.indexOf(':')>=0)throw new IOException("不允许访问文档目录以外的资源");
    File base=root.getCanonicalFile();
    if(!base.isDirectory())throw new FileNotFoundException("文档目录不存在或已移除");
    File file=new File(base,decodedRelativePath).getCanonicalFile();
    if(!file.getPath().startsWith(base.getPath()+File.separator)||!file.isFile())throw new FileNotFoundException("本地资源不存在或不在文档目录内");
    return file;
  }

  private static String extension(File file){
    String name=file.getName();int dot=name.lastIndexOf('.');
    return dot<0?"":name.substring(dot+1).toLowerCase(Locale.ROOT);
  }
}
