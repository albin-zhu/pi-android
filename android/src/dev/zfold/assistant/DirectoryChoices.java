package dev.zfold.assistant;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
/** Canonical boundary checks shared by listing and final selection. No Android dependency. */
final class DirectoryChoices {
  static File validate(File root,File directory)throws IOException{
    File base=root.getCanonicalFile(),target=directory.getCanonicalFile();
    if(!target.equals(base)&&!target.getPath().startsWith(base.getPath()+File.separator))throw new IOException("不能选择 /storage/pi 之外的目录");
    if(!target.isDirectory()||!target.canRead()||!target.canWrite()||!target.canExecute())throw new IOException("目录不存在或无法读写");
    return target;
  }
  static File create(File root,File parent,String name)throws IOException{
    name=name==null?"":name.trim();
    if(name.isEmpty()||name.equals(".")||name.equals("..")||name.contains("/")||name.contains("\\")||name.chars().anyMatch(n->Character.isISOControl(n))||name.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>255)throw new IOException("请输入有效文件夹名，不含斜杠或控制字符");
    File directory=validate(root,parent),child=new File(directory,name);
    try{java.nio.file.Files.createDirectory(child.toPath());}
    catch(java.nio.file.FileAlreadyExistsException e){throw new IOException("此名称已存在，请换一个名称");}
    catch(IOException e){throw new IOException("创建失败："+e.getMessage(),e);}
    return validate(root,child);
  }
  static List<File> list(File root,File directory)throws IOException{
    File target=validate(root,directory);File[] files=target.listFiles();if(files==null)throw new IOException("无法读取此目录");
    List<File> result=new ArrayList<>();
    for(File child:files)if(child.isDirectory())try{validate(root,child);result.add(child);}catch(IOException ignored){}
    result.sort(Comparator.comparing(File::getName,String.CASE_INSENSITIVE_ORDER));return result;
  }
}
