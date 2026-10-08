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
  static List<File> list(File root,File directory)throws IOException{
    File target=validate(root,directory);File[] files=target.listFiles();if(files==null)throw new IOException("无法读取此目录");
    List<File> result=new ArrayList<>();
    for(File child:files)if(child.isDirectory())try{validate(root,child);result.add(child);}catch(IOException ignored){}
    result.sort(Comparator.comparing(File::getName,String.CASE_INSENSITIVE_ORDER));return result;
  }
}
