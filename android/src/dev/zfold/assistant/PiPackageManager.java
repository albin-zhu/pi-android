package dev.zfold.assistant;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;

/** Minimal user-facing package manager backed by the official Termux aarch64 repository. */
public final class PiPackageManager {
  public static final String REPO="https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/";
  public static final String REPO_ROOT="https://packages.termux.dev/apt/termux-main/";
  public static final long INDEX_TTL=10*60_000L, MAX_DEB=256_000_000L, MAX_INDEX=8_000_000L;
  private static final Object LOCK=new Object();

  public interface Progress {void update(String message);}
  private static final ThreadLocal<Progress> progress=new ThreadLocal<>();
  private static void report(String message){Progress observer=progress.get();if(observer!=null)observer.update(message);}
  public static JSONObject install(Context context,String name,Progress observer)throws Exception{progress.set(observer);try{report("正在读取软件包目录…");return install(context,name);}finally{progress.remove();}}
  private PiPackageManager(){}

  public static File root(Context context){return new File(context.getFilesDir(),"runtime/pkg");}

  private static File usr(Context context){return new File(root(context),"usr");}
  private static File indexFile(Context context){return new File(root(context),"Packages");}
  private static File stateFile(Context context){return new File(root(context),"installed.json");}
  private static File cacheDir(Context context){return new File(root(context),"cache");}

  public static synchronized JSONObject status(Context context,boolean refresh)throws Exception {
    NativeToolExecution.checkpoint();
    JSONObject state=installed(context);
    JSONArray available=refresh?packageArray(refreshIndex(context)):packageArray(readIndex(context));
    return PiRuntime.json("root",root(context).getPath(),"state",state,"packages",available);
  }

  public static synchronized JSONObject search(Context context,String query,boolean refresh)throws Exception {
    NativeToolExecution.checkpoint();
    if(refresh)refreshIndex(context);
    String q=query==null?"":query.trim().toLowerCase(Locale.ROOT);
    JSONArray all=packageArray(readIndex(context)),out=new JSONArray();
    JSONObject state=installed(context);
    if(all!=null)for(int i=0;i<all.length()&&out.length()<100;i++) {
      JSONObject item=all.optJSONObject(i);if(item==null)continue;
      String name=item.optString("name"),desc=item.optString("description");
      if(q.isEmpty()||name.toLowerCase(Locale.ROOT).contains(q)||desc.toLowerCase(Locale.ROOT).contains(q)) {
        JSONObject installed=state.optJSONObject(name);
        JSONObject row=PiRuntime.json("name",name,"version",item.optString("version"),"description",desc,
          "size",item.optLong("size"),"installed",installed!=null);
        if(installed!=null)row.put("installedVersion",installed.optString("version"));
        out.put(row);
      }
    }
    return PiRuntime.json("query",q,"indexed",all==null?-1:all.length(),"packages",out);
  }

  public static synchronized JSONObject install(Context context,String name)throws Exception {
    NativeToolExecution.checkpoint();
    if(name==null||!name.matches("[a-z0-9][a-z0-9+._-]{0,63}"))throw new Exception("包名非法");
    JSONObject index=readIndex(context),target=index.optJSONObject(name);
    if(target==null)throw new Exception("仓库中找不到 "+name);
    JSONObject state=installed(context),installed=state.optJSONObject(name);
    if(installed!=null&&target.optString("version").equals(installed.optString("version"))){
      if(name.equals("bash"))prepareBash(context);
      return PiRuntime.json("name",name,"version",installed.optString("version"),"alreadyInstalled",true,"state",state);
    }
    List<String> order=new ArrayList<>();resolve(context,index,name,order,new HashSet<>());
    String[] basePackages={"coreutils","bash","libandroid-support"};
    for(String base:basePackages) {
      JSONObject item=index.optJSONObject(base);
      if(item!=null&&state.optJSONObject(base)==null&&!order.contains(base))resolve(context,index,base,order,new HashSet<>());
    }
    for(String item:order)if(!item.equals(name)){NativeToolExecution.checkpoint();installOne(context,index,item);}
    NativeToolExecution.checkpoint();
    JSONObject result=installOne(context,index,name);
    return PiRuntime.json("name",name,"version",result.optString("version"),"installed",result,"state",installed(context));
  }

  public static synchronized JSONObject remove(Context context,String name)throws Exception {
    NativeToolExecution.checkpoint();
    JSONObject state=installed(context),record=state.optJSONObject(name);
    if(record==null)return PiRuntime.json("name",name,"alreadyInstalled",false,"state",state);
    for(java.util.Iterator<String> it=state.keys();it.hasNext();) {
      String other=it.next();
      if(other.equals(name))continue;
      JSONArray deps=state.optJSONObject(other)==null?null:state.optJSONObject(other).optJSONArray("depends");
      if(deps!=null)for(int i=0;i<deps.length();i++)if(name.equals(deps.optString(i)))throw new Exception(other+" 仍依赖 "+name);
    }
    removeOwnedFiles(context,state,name);
    deleteEmptyUsrDirs(context);
    state.remove(name);writeState(context,state);
    return PiRuntime.json("name",name,"removed",true,"state",state);
  }

  private static JSONObject installOne(Context context,JSONObject index,String name)throws Exception {
    NativeToolExecution.checkpoint();
    JSONObject item=index.optJSONObject(name);if(item==null)throw new Exception("仓库中找不到 "+name);
    report("下载 "+name+"…");File deb=download(context,item);
    report("安装 "+name+"…");
    File stage=Files.createTempDirectory(cacheDir(context).toPath(),"extract-").toFile();
    try {
      JSONObject state=installed(context);
      File data=splitDeb(deb,stage);
      File bootstrap=ensureBootstrap(context);
      ProcessBuilder builder=new ProcessBuilder("/system/bin/tar","--restrict","-xJf",data.getAbsolutePath(),"-C",stage.getAbsolutePath()).redirectErrorStream(false);
      builder.environment().put("PATH",bootstrap.getAbsolutePath()+":/system/bin:/system/xbin:/vendor/bin");
      builder.environment().put("LD_LIBRARY_PATH",bootstrap.getAbsolutePath());
      Process process=builder.start();StringBuilder err=new StringBuilder();
      Thread reader=new Thread(()->read(process.getErrorStream(),err));reader.start();
      if(!process.waitFor(300,java.util.concurrent.TimeUnit.SECONDS)) {process.destroyForcibly();throw new Exception("包解压超时");}
      reader.join(1000);if(process.exitValue()!=0)throw new Exception("包解压失败："+err);
      File source=new File(stage,"data/data/com.termux/files/usr");
      if(!source.isDirectory())throw new Exception(name+" 包缺少 usr 目录");
      File usr=usr(context);usr.mkdirs();
      // Once replacement starts, finish this package's state before cancelling.
      NativeToolExecution.checkpoint();
      removeOwnedFiles(context,state,name);
      copyTree(source,usr);
      rewritePrefix(context);
      if(name.equals("bash"))prepareBash(context);
      JSONArray files=new JSONArray();
      try(var paths=Files.walk(source.toPath())) {
        for(Path path:(Iterable<Path>)paths::iterator) {
          Path rel=source.toPath().relativize(path);
          if(rel.getNameCount()==0)continue;
          if(Files.isRegularFile(path,java.nio.file.LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(path))files.put("usr/"+rel.toString());
        }
      }
      File control=new File(stage,"control.tar.xz");
      if(control.isFile()) {
        File controlDir=new File(stage,"control");controlDir.mkdirs();
        ProcessBuilder controlExtract=new ProcessBuilder("/system/bin/tar","--restrict","-xJf",control.getAbsolutePath(),"-C",controlDir.getAbsolutePath()).redirectErrorStream(false);
        controlExtract.environment().put("PATH",bootstrap.getAbsolutePath()+":/system/bin:/system/xbin:/vendor/bin");
        controlExtract.environment().put("LD_LIBRARY_PATH",bootstrap.getAbsolutePath());
        Process controlProcess=controlExtract.start();StringBuilder controlErr=new StringBuilder();
        Thread controlReader=new Thread(()->read(controlProcess.getErrorStream(),controlErr));controlReader.start();
        if(!controlProcess.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)) {controlProcess.destroyForcibly();throw new Exception("维护脚本解压超时");}
        controlReader.join(1000);if(controlProcess.exitValue()!=0)throw new Exception("维护脚本解压失败："+controlErr);
        JSONArray entries=alternatives(context,new File(controlDir,"./postinst"));
        for(int i=0;i<entries.length();i++)files.put(entries.optString(i));
      }
      JSONObject record=PiRuntime.json("name",name,"version",item.optString("version"),"description",item.optString("description"),
        "depends",item.optJSONArray("depends"),"files",files,"size",item.optLong("size"),"installedAt",System.currentTimeMillis());
      state.put(name,record);writeState(context,state);
      return record;
    } finally {deleteQuiet(stage);}
  }

  private static void resolve(Context context,JSONObject index,String name,List<String> order,Set<String> visiting)throws Exception {
    if(visiting.contains(name))throw new Exception("依赖循环："+name);
    if(order.contains(name))return;
    JSONObject item=index.optJSONObject(name);if(item==null)return;
    visiting.add(name);
    JSONArray deps=item.optJSONArray("depends");
    if(deps!=null)for(int i=0;i<deps.length();i++) {
      String first=deps.optString(i).split("\\|")[0].trim();
      int cut=Math.max(first.indexOf('('),first.indexOf('['));
      if(cut>=0)first=first.substring(0,cut).trim();
      if(first.isEmpty())continue;
      JSONObject installed=installed(context).optJSONObject(first);
      if(installed==null&&index.has(first)) {
        resolve(context,index,first,order,visiting);
        if(!order.contains(first))order.add(first);
      }
    }
    visiting.remove(name);
    if(!order.contains(name)&&index.has(name))order.add(name);
  }

  private static JSONObject refreshIndex(Context context)throws Exception {
    root(context).mkdirs();
    File output=indexFile(context),partial=new File(root(context),"Packages.new");
    URL url=new URL(REPO+"Packages");
    HttpURLConnection connection=(HttpURLConnection)url.openConnection();
    connection.setConnectTimeout(10_000);connection.setReadTimeout(30_000);connection.setInstanceFollowRedirects(true);
    try {
      connection.connect();
      if(connection.getResponseCode()/100!=2)throw new Exception("仓库索引 HTTP "+connection.getResponseCode());
      try(InputStream input=connection.getInputStream();OutputStream out=new FileOutputStream(partial)) {transfer(input,out,MAX_INDEX);}
      connection.disconnect();
    }finally{connection.disconnect();}
    parsePackages(Files.readString(partial.toPath(),StandardCharsets.UTF_8));
    if(output.exists())output.delete();Files.move(partial.toPath(),output.toPath());
    return readIndex(context);
  }

  private static JSONObject readIndex(Context context)throws Exception {
    File file=indexFile(context);
    if(!file.isFile()||System.currentTimeMillis()-file.lastModified()>INDEX_TTL)return refreshIndex(context);
    return parsePackages(Files.readString(file.toPath(),StandardCharsets.UTF_8));
  }

  private static JSONObject parsePackages(String text)throws Exception {
    JSONObject index=new JSONObject();
    text=text.replace("\r\n\r\n","\n\n");
    for(String block:text.split("\n\n")) {
      String name="",version="",filename="",sha="",desc="";long size=0;JSONArray deps=new JSONArray();
      for(String line:block.split("\r?\n")) {
        int at=line.indexOf(": ");if(at<1)continue;
        String key=line.substring(0,at),value=line.substring(at+2).trim();
        switch(key) {
          case "Package":name=value;break;
          case "Version":version=value;break;
          case "Filename":filename=value;break;
          case "SHA256":sha=value;break;
          case "Description":desc=value;break;
          case "Size":try{size=Long.parseLong(value);}catch(NumberFormatException ignored){}break;
          case "Depends":for(String group:value.split(","))deps.put(group.trim());break;
        }
      }
      if(!name.isEmpty()&&!filename.isEmpty())index.put(name,PiRuntime.json("name",name,"version",version,
        "filename",filename,"sha256",sha,"description",desc,"size",size,"depends",deps));
    }
    return index;
  }

  private static JSONArray packageArray(JSONObject index) {
    JSONArray out=new JSONArray();
    for(java.util.Iterator<String> it=index.keys();it.hasNext();)out.put(index.optJSONObject(it.next()));
    return out;
  }

  private static File download(Context context,JSONObject item)throws Exception {
    cacheDir(context).mkdirs();
    String filename=item.optString("filename");
    File output=new File(cacheDir(context),new File(filename).getName()+".deb"),partial=new File(cacheDir(context),new File(filename).getName()+".part");
    URL url=new URL(REPO_ROOT+filename);
    HttpURLConnection connection=(HttpURLConnection)url.openConnection();
    connection.setConnectTimeout(10_000);connection.setReadTimeout(60_000);connection.setInstanceFollowRedirects(true);
    try {
      connection.connect();
      if(connection.getResponseCode()/100!=2)throw new Exception("下载 HTTP "+connection.getResponseCode());
      try(InputStream input=connection.getInputStream();OutputStream out=new FileOutputStream(partial)) {
        byte[] buffer=new byte[32768];int count;long total=0,last=0;
        while((count=input.read(buffer))>=0){NativeToolExecution.checkpoint();total+=count;if(total>MAX_DEB)throw new IOException("文件超过大小限制");out.write(buffer,0,count);long now=android.os.SystemClock.uptimeMillis();if(now-last>600){last=now;report("下载 "+name(item)+" · "+(total/1024)+" / "+(item.optLong("size")/1024)+" KB");}}
      }
      connection.disconnect();
    }finally{connection.disconnect();}
    String actual=sha256(partial);
    if(!item.optString("sha256").equalsIgnoreCase(actual))throw new Exception(name(item)+" SHA-256 不匹配，安装已拒绝");
    if(output.exists())output.delete();Files.move(partial.toPath(),output.toPath());
    return output;
  }

  private static File splitDeb(File deb,File stage)throws Exception {
    byte[] all=Files.readAllBytes(deb.toPath());int off=8;
    if(all.length<8||!new String(all,0,8,StandardCharsets.US_ASCII).equals("!<arch>\n"))throw new Exception("不是 Debian 包");
    while(off+60<=all.length) {
      String name=new String(all,off,16,StandardCharsets.US_ASCII).trim().replace("/","");
      long size=Long.parseLong(new String(all,off+48,10,StandardCharsets.US_ASCII).trim());
      off+=60;
      if(name.equals("data.tar.xz")||name.equals("data.tar.zst")||name.equals("data.tar.gz")) {
        File output=new File(stage,name);output.getParentFile().mkdirs();
        try(OutputStream out=new FileOutputStream(output)) {out.write(all,off,(int)size);}
        String controlName=name.replace("data","control");
        int cursor=8;
        while(cursor+60<=all.length) {
          String member=new String(all,cursor,16,StandardCharsets.US_ASCII).trim().replace("/","");
          long memberSize=Long.parseLong(new String(all,cursor+48,10,StandardCharsets.US_ASCII).trim());
          cursor+=60;
          if(member.equals(controlName)) {
            File control=new File(stage,controlName);control.getParentFile().mkdirs();
            try(OutputStream out=new FileOutputStream(control)) {out.write(all,cursor,(int)memberSize);}
            break;
          }
          cursor+=(int)memberSize+(memberSize%2);
        }
        return output;
      }
      off+=(int)size+(size%2);
    }
    throw new Exception("Debian 包缺少 data tar");
  }

  private static JSONArray alternatives(Context context,File postinst)throws IOException {
    JSONArray added=new JSONArray();
    if(!postinst.isFile())return added;
    String script=Files.readString(postinst.toPath(),StandardCharsets.UTF_8);
    String old="/data/data/com.termux/files/usr",replacement=usr(context).getCanonicalPath();
    java.util.regex.Matcher matcher=java.util.regex.Pattern
      .compile("--install\\s+\"([^\"]+)\"\\s+\"[^\"]+\"\\s+\"([^\"]+)\"")
      .matcher(script);
    while(matcher.find()) {
      File destination=new File(matcher.group(1).replace(old,replacement));
      File target=new File(matcher.group(2).replace(old,replacement));
      if(!destination.getPath().startsWith(replacement+File.separator)||!target.getPath().startsWith(replacement+File.separator))continue;
      destination.getParentFile().mkdirs();destination.delete();
      Files.createSymbolicLink(destination.toPath(),target.toPath());
      added.put(root(context).toPath().relativize(destination.toPath()).toString().replace('\\','/'));
    }
    return added;
  }

  private static void copyTree(File source,File target)throws IOException {
    if(Files.isSymbolicLink(source.toPath())) {
      if(target.exists()||Files.isSymbolicLink(target.toPath()))deleteQuiet(target);
      target.getParentFile().mkdirs();Files.createSymbolicLink(target.toPath(),Files.readSymbolicLink(source.toPath()));return;
    }
    if(source.isDirectory()) {
      target.mkdirs();
      File[] children=source.listFiles();if(children==null)return;
      for(File child:children)copyTree(child,new File(target,child.getName()));return;
    }
    target.getParentFile().mkdirs();
    Files.copy(source.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.LinkOption.NOFOLLOW_LINKS);
  }

  private static void rewritePrefix(Context context)throws IOException {
    String old="/data/data/com.termux/files/usr",replacement=usr(context).getCanonicalPath();
    try(var paths=Files.walk(usr(context).toPath())) {
      for(Path path:(Iterable<Path>)paths::iterator) {
        if(!Files.isRegularFile(path,java.nio.file.LinkOption.NOFOLLOW_LINKS)||Files.size(path)>8_000_000)continue;
        byte[] value=Files.readAllBytes(path);if(indexOf(value,old.getBytes(StandardCharsets.UTF_8))<0)continue;
        if(!isUtf8(value))continue;
        Files.write(path,new String(value,StandardCharsets.UTF_8).replace(old,replacement).getBytes(StandardCharsets.UTF_8));
      }
    }
  }

  public static JSONObject environmentForExecutable(Context context,File executable)throws IOException {
    File root=root(context),usr=usr(context);
    new File(root,"home").mkdirs();new File(root,"tmp").mkdirs();
    if(!executable.getCanonicalPath().startsWith(root.getPath()+File.separator))return new JSONObject();
    return PiRuntime.json("PATH",usr+"/bin:/system/bin:/system/xbin:/vendor/bin",
      "LD_LIBRARY_PATH",usr+"/lib","HOME",root+"/home","TMPDIR",root+"/tmp","PREFIX",usr,
      "TERMINFO",usr+"/share/terminfo","TERM","xterm-256color");
  }

  public static File ensureBootstrap(Context context)throws IOException {
    File bootstrap=new File(root(context),"bootstrap");bootstrap.mkdirs();
    String nativeDir=context.getApplicationInfo().nativeLibraryDir;
    java.nio.file.Files.copy(new File(nativeDir,"libzfold_xz.so").toPath(),new File(bootstrap,"xz").toPath(),
      java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    java.nio.file.Files.copy(new File(nativeDir,"libzfold_lzma.so").toPath(),new File(bootstrap,"liblzma.so.5").toPath(),
      java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    new File(bootstrap,"xz").setExecutable(true,false);
    new File(bootstrap,"xz").setReadable(true,false);
    new File(bootstrap,"xz").setWritable(false,false);
    new File(bootstrap,"liblzma.so.5").setReadable(true,false);
    new File(bootstrap,"liblzma.so.5").setExecutable(true,false);
    new File(root(context),"home").mkdirs();
    new File(root(context),"tmp").mkdirs();
    return bootstrap.getCanonicalFile();
  }

  public static File executable(Context context,String command)throws IOException {
    File direct=new File(usr(context),"bin/"+command);
    if(direct.isFile()||Files.isSymbolicLink(direct.toPath()))return direct;
    JSONObject state=installed(context);
    File best=null;int bestScore=Integer.MAX_VALUE;
    for(java.util.Iterator<String> it=state.keys();it.hasNext();) {
      JSONObject record=state.optJSONObject(it.next());JSONArray files=record==null?null:record.optJSONArray("files");
      if(files==null)continue;
      for(int i=0;i<files.length();i++) {
        String relative=files.optString(i);
        if(!new File(relative).getName().equals(command))continue;
        File candidate=new File(root(context),relative);
        if(!candidate.isFile()&&!Files.isSymbolicLink(candidate.toPath()))continue;
        int score=relative.startsWith("usr/bin/")?0:relative.contains("/libexec/")?1:relative.contains("/lib/")?2:3;
        if(score<bestScore){best=candidate;bestScore=score;}
      }
    }
    return best;
  }

  /** Bash must not source another app's private system startup files.
   * Only exact, NUL-terminated constants are replaced, without changing ELF size.
   * Normal $HOME/.bashrc and login profiles retain their standard semantics.
   */
  public static synchronized File prepareBash(Context context)throws IOException {
    File bash=executable(context,"bash");if(bash==null)return null;
    File target=bash.getCanonicalFile();
    if(!target.getPath().startsWith(usr(context).getCanonicalPath()+File.separator)||target.length()>16_000_000)return bash;
    byte[] bytes=Files.readAllBytes(target.toPath());
    if(bytes.length<4||bytes[0]!=0x7f||bytes[1]!='E'||bytes[2]!='L'||bytes[3]!='F')return bash;
    int changes=0;
    for(String filename:new String[]{"bash.bashrc","profile"}){
      byte[] old=("/data/data/com.termux/files/usr/etc/"+filename+"\0").getBytes(StandardCharsets.UTF_8);
      byte[] replacement="/dev/null\0".getBytes(StandardCharsets.UTF_8);
      int offset;
      while((offset=indexOf(bytes,old))>=0){Arrays.fill(bytes,offset,offset+old.length,(byte)0);System.arraycopy(replacement,0,bytes,offset,replacement.length);changes++;}
    }
    if(changes>0){
      File temporary=File.createTempFile(".bash-relocate-",".tmp",target.getParentFile());
      try{
        Files.write(temporary.toPath(),bytes);if(!temporary.setReadable(true,false)||!temporary.setExecutable(true,false))throw new IOException("无法设置 Bash 执行权限");
        // Replacing the inode also works while an existing shell is executing it.
        Files.move(temporary.toPath(),target.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }finally{temporary.delete();}
      android.util.Log.i("ZfoldRuntime","Relocated Bash system startup paths: "+changes);
    }
    return bash;
  }

  public static JSONObject installed(Context context) {
    try {return new JSONObject(new String(Files.readAllBytes(stateFile(context).toPath()),StandardCharsets.UTF_8));}
    catch(Exception e){return new JSONObject();}
  }

  private static void writeState(Context context,JSONObject state)throws IOException {
    root(context).mkdirs();Files.write(stateFile(context).toPath(),state.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static void deleteEmptyUsrDirs(Context context)throws IOException {
    try(var paths=Files.walk(usr(context).toPath())) {
      List<Path> dirs=new ArrayList<>();
      for(Path path:(Iterable<Path>)paths::iterator)if(Files.isDirectory(path,java.nio.file.LinkOption.NOFOLLOW_LINKS))dirs.add(path);
      for(int i=dirs.size()-1;i>=0;i--) {File file=dirs.get(i).toFile();String[] children=file.list();if(children!=null&&children.length==0)file.delete();}
    }
  }

  private static void removeOwnedFiles(Context context,JSONObject state,String name)throws IOException {
    JSONObject record=state.optJSONObject(name);
    JSONArray files=record==null?null:record.optJSONArray("files");
    if(files==null)return;
    for(int i=files.length()-1;i>=0;i--) {
      String relative=files.optString(i);
      boolean shared=false;
      for(java.util.Iterator<String> it=state.keys();it.hasNext();) {
        String other=it.next();
        if(other.equals(name))continue;
        JSONObject otherRecord=state.optJSONObject(other);
        JSONArray otherFiles=otherRecord==null?null:otherRecord.optJSONArray("files");
        if(otherFiles==null)continue;
        for(int j=0;j<otherFiles.length();j++)if(relative.equals(otherFiles.optString(j))) {shared=true;break;}
        if(shared)break;
      }
      if(!shared)deleteQuiet(new File(root(context),relative));
    }
  }

  private static void deleteQuiet(File file) {
    if(file==null)return;
    if(file.isDirectory()) {File[] children=file.listFiles();if(children!=null)for(File child:children)deleteQuiet(child);}
    file.delete();
  }

  private static void transfer(InputStream input,OutputStream out,long max)throws IOException {
    byte[] buffer=new byte[65536];long total=0;int n;
    while((n=input.read(buffer))>=0) {NativeToolExecution.checkpoint();total+=n;if(total>max)throw new IOException("文件超过大小限制");out.write(buffer,0,n);}
  }

  private static int indexOf(byte[] value,byte[] needle) {
    outer:for(int i=0;i<=value.length-needle.length;i++) {
      for(int j=0;j<needle.length;j++)if(value[i+j]!=needle[j])continue outer;
      return i;
    }
    return -1;
  }

  private static boolean isUtf8(byte[] value) {
    try {
      java.nio.charset.CharsetDecoder decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
      decoder.decode(java.nio.ByteBuffer.wrap(value));return true;
    } catch(Exception e) {return false;}
  }

  private static String sha256(File file)throws Exception {
    MessageDigest digest=MessageDigest.getInstance("SHA-256");
    try(InputStream input=new BufferedInputStream(new FileInputStream(file),65536)) {
      byte[] buffer=new byte[65536];int n;
      while((n=input.read(buffer))>=0)digest.update(buffer,0,n);
    }
    StringBuilder out=new StringBuilder();for(byte value:digest.digest())out.append(String.format("%02x",value));
    return out.toString();
  }

  private static void read(InputStream input,StringBuilder output) {
    try(BufferedReader reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))) {for(String line;(line=reader.readLine())!=null;)output.append(line).append('\n');}catch(Exception ignored){}
  }

  private static String name(JSONObject item){return item.optString("name");}
}
