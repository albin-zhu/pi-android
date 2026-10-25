package dev.zfold.assistant;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** Filesystem policy shared by the SAF importer and standalone boundary checks. */
final class TerminalAttachmentFiles {
  private TerminalAttachmentFiles(){}

  static boolean within(File file,File root){return file.equals(root)||file.getPath().startsWith(root.getPath()+File.separator);}

  static File primaryDocument(File storage,String id)throws IOException {
    if(id==null||!id.startsWith("primary:"))return null;
    String relative=id.substring("primary:".length());
    if(relative.isEmpty()||relative.startsWith("/"))return null;
    for(String part:relative.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))return null;
    File root=storage.getCanonicalFile(),file=new File(root,relative).getCanonicalFile();
    return within(file,root)&&file.isFile()&&file.canRead()?file:null;
  }

  static String path(File file,File workspace,File storage,boolean ubuntu)throws IOException {
    File actual=file.getCanonicalFile(),root=workspace.getCanonicalFile();
    if(!actual.isFile()||!actual.canRead())throw new IOException("所选文件无法直接读取");
    String result;
    if(ubuntu&&within(actual,root))result="/workspace"+actual.getPath().substring(root.getPath().length());
    else {
      File shared;
      try{shared=storage.getCanonicalFile();}catch(IOException unavailable){
        if(ubuntu)throw unavailable;
        quote(actual.getPath());return actual.getPath();
      }
      if(within(actual,shared)){
        if(ubuntu&&(!shared.isDirectory()||!shared.canRead()||!shared.canExecute()))throw new IOException("共享存储尚不可挂载，请导入文件");
        result="/sdcard"+actual.getPath().substring(shared.getPath().length());
      }else if(!ubuntu)result=actual.getPath();
      else throw new IOException("文件不在 Ubuntu 的共享路径内");
    }
    quote(result); // A terminal path may not contain control keys, even inside shell quotes.
    return result;
  }

  static String quote(String path)throws IOException {
    if(path==null||!path.startsWith("/"))throw new IOException("文件路径必须是绝对路径");
    for(int i=0;i<path.length();i++){
      char c=path.charAt(i);
      if(Character.isISOControl(c)||c=='\u2028'||c=='\u2029')throw new IOException("文件路径包含终端控制字符，请导入后使用");
    }
    return "'"+path.replace("'","'\\''")+"'";
  }

  static String circleText(String path,String question)throws IOException {
    String plain=draftText(question).trim();
    return quote(path)+" "+(plain.isEmpty()?"请查看这张图片":plain);
  }
  static String draftText(String question)throws IOException {
    String value=question==null?"":question;
    if(value.length()>8000)throw new IOException("草稿过长，请限制在 8000 字以内");
    StringBuilder text=new StringBuilder(value.length());
    for(int at=0;at<value.length();){
      int cp=value.codePointAt(at);at+=Character.charCount(cp);
      if(cp=='\t'||cp=='\r'||cp=='\n'||cp==0x85||cp==0x2028||cp==0x2029)text.append(' ');
      else {
        if(Character.isISOControl(cp)||Character.getType(cp)==Character.FORMAT||cp>=0xd800&&cp<=0xdfff)throw new IOException("问题包含终端控制字符");
        text.appendCodePoint(cp);
      }
    }
    return text.toString();
  }

  static File ownedCircle(File workspace,File file,String id)throws IOException {
    if(id==null||!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IOException("圈选草稿标识无效");
    File root=workspace.getCanonicalFile(),imports=new File(root,"workspace/attachments");
    File actual=file.getCanonicalFile(),batch=actual.getParentFile();
    if(!imports.equals(imports.getCanonicalFile())||!actual.equals(file.getAbsoluteFile())
        ||!actual.getName().equals("circle-"+id+".png")||batch==null||!batch.getName().startsWith("picked-")
        ||!imports.equals(batch.getParentFile()))throw new IOException("圈选图片不在原有附件目录内");
    return actual;
  }

  static String safeName(String name){
    if(name==null||name.isEmpty())return "file";
    // Preserve a short extension for image/file tools while bounding the UTF-8 filename size.
    int dot=name.lastIndexOf('.');String suffix="";
    if(dot>0&&name.length()-dot<=17){
      boolean simple=true;
      for(int i=dot+1;i<name.length();i++)if(!Character.isLetterOrDigit(name.charAt(i))){simple=false;break;}
      if(simple){suffix=name.substring(dot);name=name.substring(0,dot);}
    }
    int remaining=180-suffix.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    StringBuilder out=new StringBuilder();
    for(int at=0;at<name.length();){
      int cp=name.codePointAt(at);at+=Character.charCount(cp);
      if(cp=='/'||cp=='\\'||Character.isISOControl(cp)||cp==0x2028||cp==0x2029||cp>=0xd800&&cp<=0xdfff)cp='_';
      int bytes=cp<=0x7f?1:cp<=0x7ff?2:cp<=0xffff?3:4;
      if(bytes>remaining)break;
      out.appendCodePoint(cp);remaining-=bytes;
    }
    String base=out.toString().trim();
    if(base.isEmpty()||base.equals(".")||base.equals(".."))base="file";
    return base+suffix;
  }

  static File newBatch(File workspace)throws IOException {
    File root=workspace.getCanonicalFile();
    File imports=new File(root,"workspace/attachments").getCanonicalFile();
    if(!within(imports,root))throw new IOException("附件目录不在共享工作区内");
    Files.createDirectories(imports.toPath());
    if(!within(imports.getCanonicalFile(),root))throw new IOException("附件目录已改变");
    return Files.createTempDirectory(imports.toPath(),"picked-").toFile();
  }

  static File copy(InputStream source,File directory,String name,int ordinal,Runnable cancellation)throws IOException {
    File target=new File(directory,ordinal+"-"+safeName(name));
    boolean created=false;
    try{
      cancellation.run();
      try(OutputStream output=Files.newOutputStream(target.toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
        created=true;byte[] buffer=new byte[32*1024];
        while(true){
          cancellation.run();int count=source.read(buffer);
          if(count<0)break;
          if(count==0){int one=source.read();if(one<0)break;output.write(one);}
          else output.write(buffer,0,count);
        }
        cancellation.run();
      }
      return target;
    }catch(IOException|RuntimeException error){
      if(created)try{Files.deleteIfExists(target.toPath());}catch(IOException cleanup){error.addSuppressed(cleanup);}
      throw error;
    }
  }

  static void rollback(File batch,List<File> created)throws IOException {
    IOException failure=null;
    for(File file:created)try{Files.deleteIfExists(file.toPath());}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
    if(batch!=null)try{Files.deleteIfExists(batch.toPath());}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}
    if(failure!=null)throw failure;
  }
}
