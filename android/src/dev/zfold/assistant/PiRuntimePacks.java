package dev.zfold.assistant;

import android.content.Context;
import android.os.Environment;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** User-approved installation of pinned Android/bionic tool packs. */
public final class PiRuntimePacks {
  public static final int MAX_TOTAL_BYTES=2_000_000_000, MAX_FILE_BYTES=1_200_000_000, MAX_ENTRIES=120_000;
  private PiRuntimePacks(){}

  public static File packsRoot()throws IOException {
    return new File(Environment.getExternalStorageDirectory(),"pi/runtime-packs").getCanonicalFile();
  }
  public static File cache(Context context)throws IOException {
    return new File(context.getFilesDir(),"runtime/runtime-exec-test").getCanonicalFile();
  }
  private static File stateFile(Context context)throws IOException {return new File(cache(context),"installed.json");}

  public static JSONObject installed(Context context){
    try {return new JSONObject(new String(Files.readAllBytes(stateFile(context).toPath()),StandardCharsets.UTF_8));}
    catch(Exception e){return new JSONObject();}
  }

  public static JSONObject environment(Context context){
    JSONObject merged=new JSONObject();
    JSONObject state=installed(context);
    for(java.util.Iterator<String> iterator=state.keys();iterator.hasNext();) {
      String name=iterator.next();
      JSONObject env=state.optJSONObject(name)==null?null:state.optJSONObject(name).optJSONObject("environment");
      if(env==null)continue;
      for(java.util.Iterator<String> envIterator=env.keys();envIterator.hasNext();){String key=envIterator.next();if(!merged.has(key))putQuiet(merged,key,String.valueOf(env.opt(key)));}
    }
    return merged;
  }

  /** Scoped packs own conflicting values such as glibc versus bionic LD_LIBRARY_PATH. */
  public static JSONObject environmentForExecutable(Context context,File executable)throws IOException {
    File cache=cache(context);
    String relative=executable.getCanonicalPath();
    String cachePath=cache.getCanonicalPath();
    if(!relative.startsWith(cachePath+File.separator))return environment(context);
    relative=relative.substring(cachePath.length()+1);
    JSONObject state=installed(context);
    for(java.util.Iterator<String> iterator=state.keys();iterator.hasNext();) {
      String name=iterator.next();
      JSONObject record=state.optJSONObject(name);
      JSONObject scope=record==null?null:record.optJSONObject("environmentScope");
      if(scope==null)continue;
      JSONArray roots=scope.optJSONArray("roots");
      if(roots==null)continue;
      for(int i=0;i<roots.length();i++) {
        String root=roots.optString(i);
        if(root.isEmpty())continue;
        if(relative.equals(root)||relative.startsWith(root+File.separator)) {
          JSONObject env=record.optJSONObject("environment");
          if(env==null)return new JSONObject();
          try {return new JSONObject(env.toString());}
          catch(Exception ignored){return new JSONObject();}
        }
      }
    }
    return environment(context);
  }

  public static JSONObject available(Context context)throws Exception {
    JSONObject state=installed(context);JSONArray archives=new JSONArray();
    File root=packsRoot();File[] files=root.listFiles();
    if(files!=null) {
      Arrays.sort(files,Comparator.comparing(File::getName));
      for(File file:files) {
        if(!file.isFile()||!file.getName().toLowerCase(Locale.ROOT).endsWith(".tar.gz"))continue;
        JSONObject item=PiRuntime.json("name",file.getName(),"path",file.getCanonicalPath(),"size",file.length(),"sha256",sha256(file));
    for(java.util.Iterator<String> iterator=state.keys();iterator.hasNext();) {
      String key=iterator.next();
          JSONObject installed=state.optJSONObject(key);
          if(installed!=null&&file.getName().equals(installed.optString("archive")))putQuiet(item,"installed",true);
        }
        archives.put(item);
      }
    }
    return PiRuntime.json("root",root.getPath(),"installed",state,"archives",archives);
  }

  public static synchronized JSONObject install(Context context,String sourceName,String expectedSha256)throws Exception {
    if(context.getApplicationInfo().targetSdkVersion!=28)throw new Exception("Runtime Pack 安装需要 compat28 通道");
    if(sourceName==null||!sourceName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}\\.tar\\.gz"))throw new Exception("Runtime Pack 文件名非法");
    File root=packsRoot(),archive=new File(root,sourceName).getCanonicalFile();
    if(!archive.getPath().startsWith(root.getPath()+File.separator)||!archive.isFile())throw new Exception("找不到 Runtime Pack");
    String digest=sha256(archive);
    if(expectedSha256==null||!digest.equalsIgnoreCase(expectedSha256))throw new Exception("SHA-256 不匹配，安装已拒绝");
    Set<String> entries=validateArchive(archive);
    if(!entries.contains("manifest.json")||!entries.contains("usr"))throw new Exception("Runtime Pack 缺少 manifest.json 或 usr");
    File cache=cache(context);cache.mkdirs();
    runTarExtract(archive,cache);
    JSONObject manifest=readManifest(new File(cache,"manifest.json"));
    if(!"1".equals(manifest.optString("schema"))||!"arm64".equals(manifest.optString("architecture")))throw new Exception("Runtime Pack 架构不受支持");
    String cachePath=cache.getCanonicalPath();
    substitutePrefix(cache,"@@PI_RUNTIME_USR@@","/usr");
    String replacePrefix=manifest.optString("replacePrefix");
    if(replacePrefix.startsWith("/data/data/com.termux/files/usr/"))substituteTextPrefix(cache,replacePrefix,cachePath+"/usr"+replacePrefix.substring("/data/data/com.termux/files/usr".length()));
    ensureDirectories(cache);
    JSONObject record=PiRuntime.json("name",manifest.optString("name",sourceName.replace(".tar.gz","")),
      "archive",sourceName,"sha256",digest,"installedAt",System.currentTimeMillis(),
      "packages",manifest.optJSONArray("packages"),"binaries",manifest.optJSONArray("binaries"),
      "entry",manifest.opt("entry"),"environment",expandEnvironment(manifest.optJSONObject("environment"),cachePath),
      "environmentScope",manifest.optJSONObject("environmentScope"));
    JSONObject state=installed(context);state.put(record.optString("name"),record);
    Files.write(stateFile(context).toPath(),state.toString().getBytes(StandardCharsets.UTF_8));
    return PiRuntime.json("installed",record,"state",state);
  }

  private static void runTarExtract(File archive,File target)throws Exception {
    ProcessBuilder builder=new ProcessBuilder("/system/bin/tar","-xzf",archive.getAbsolutePath(),"-C",target.getAbsolutePath()).redirectErrorStream(false);
    StringBuilder output=new StringBuilder();
    java.lang.Process process=builder.start();
    Thread reader=new Thread(()->readProcess(process.getErrorStream(),output));reader.start();
    if(!process.waitFor(600,java.util.concurrent.TimeUnit.SECONDS)) {process.destroyForcibly();throw new Exception("Runtime Pack 解压超时");}
    reader.join(1000);
    if(process.exitValue()!=0)throw new Exception("解压失败（"+process.exitValue()+"）："+output);
  }

  private static Set<String> validateArchive(File archive)throws Exception {
    Set<String> entries=new HashSet<>();long total=0;int count=0;
    try(GZIPInputStream input=new GZIPInputStream(new BufferedInputStream(new FileInputStream(archive),65536),65536)) {
      String override=null;
      while(true) {
        byte[] header=readFully(input,512);if(header==null)break;
        if(isZero(header))continue;
        verifyChecksum(header);
        int size=(int)parseOctal(header,124,12);int type=header[156];
        String headerName=text(header,0,100),prefix=text(header,345,155);
        String name=headerName.isEmpty()?override:(prefix.isEmpty()?headerName:prefix+"/"+headerName);
        byte[] data=size==0?new byte[0]:readFully(input,size);
        if(data==null)throw new Exception("Runtime Pack tar 数据不完整");
        int padding=(512-size%512)%512;
        if(padding>0&&readFully(input,padding)==null)throw new Exception("Runtime Pack tar padding incomplete");
        if(type=='L') {override=new String(data,StandardCharsets.UTF_8).trim();continue;}
        if(type=='x'||type=='g') {
          if(type=='x')override=paxPath(data);
          continue;
        }
        override=null;name=normalize(name);
        if(name.isEmpty())continue;
        if(!isAllowed(name))throw new Exception("Runtime Pack 路径非法："+name);
        if(data.length>MAX_FILE_BYTES||total+size>MAX_TOTAL_BYTES||++count>MAX_ENTRIES)throw new Exception("Runtime Pack 超过安全大小限制");
        total+=size;entries.add(name);
        if(type=='0'||type=='\0'||type=='7'||type=='5')continue;
        if(type=='1'||type=='2') {
          String link=text(header,157,100);
          if(link.startsWith("/")||linkTargetEscapes(name,link))throw new Exception("Runtime Pack 链接非法："+name);
          continue;
        }
        throw new Exception("Runtime Pack tar 类型不支持："+(char)type);
      }
    }
    if(entries.isEmpty())throw new Exception("Runtime Pack 是空的");
    return entries;
  }

  private static boolean isAllowed(String path) {
    return path.equals("manifest.json")||path.equals("usr")||path.equals("home")||path.equals("tmp")||path.startsWith("usr/")||path.startsWith("home/")||path.startsWith("tmp/");
  }
  private static boolean linkTargetEscapes(String entry,String link) {
    LinkedList<String> parts=new LinkedList<>(Arrays.asList(entry.split("/+")));if(!parts.isEmpty())parts.removeLast();
    for(String part:link.split("/+")) {
      if(part.isEmpty()||part.equals("."))continue;
      if(part.equals("..")) {if(parts.isEmpty())return true;parts.removeLast();}
      else parts.add(part);
    }
    return false;
  }
  private static String normalize(String value) {
    List<String> parts=new ArrayList<>();
    for(String part:value.split("/+")) {
      if(part.isEmpty()||part.equals("."))continue;
      if(part.equals(".."))throw new IllegalArgumentException("path escape");
      parts.add(part);
    }
    return String.join("/",parts);
  }
  private static String paxPath(byte[] data) {
    for(String record:new String(data,StandardCharsets.UTF_8).split("\n"))if(record.startsWith("path="))return record.substring(5);
    return null;
  }
  private static byte[] readFully(InputStream input,int length)throws IOException {
    byte[] value=new byte[length];int offset=0;
    while(offset<length) {int n=input.read(value,offset,length-offset);if(n<0)return offset==0?null:Arrays.copyOf(value,offset);offset+=n;}
    return value;
  }
  private static boolean isZero(byte[] value){for(byte item:value)if(item!=0)return false;return true;}
  private static void verifyChecksum(byte[] header)throws IOException {
    long expected;try{expected=parseOctal(header,148,8);}catch(NumberFormatException e){throw new IOException("tar checksum invalid");}
    byte[] copy=header.clone();Arrays.fill(copy,148,156,(byte)' ');long actual=0;for(byte item:copy)actual+=item&255;
    if(actual!=expected)throw new IOException("tar checksum mismatch");
  }
  private static long parseOctal(byte[] value,int offset,int length) {
    String raw=new String(value,offset,offset+length>value.length?value.length-offset:length,StandardCharsets.ISO_8859_1).trim();
    raw=raw.replace("\0"," ");if(raw.isEmpty())return 0;return Long.parseLong(raw.trim(),8);
  }
  private static String text(byte[] value,int offset,int length) {
    int end=Math.min(value.length,offset+length),boundary=end;
    for(int i=offset;i<end;i++)if(value[i]==0){boundary=i;break;}
    return new String(value,offset,boundary-offset,StandardCharsets.UTF_8).trim();
  }
  private static JSONObject readManifest(File file)throws Exception {
    if(!file.isFile())throw new Exception("Runtime Pack manifest missing");
    return new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
  }
  private static void substitutePrefix(File cache,String placeholder,String suffix)throws IOException {
    byte[] needle=placeholder.getBytes(StandardCharsets.UTF_8);
    try(var paths=Files.walk(cache.toPath())) {
      for(Path path:(Iterable<Path>)paths::iterator) {
        Path file=path.toFile().isFile()?path:null;if(file==null)continue;
        long size=Files.size(path);if(size>4_000_000)continue;
        byte[] value=Files.readAllBytes(path);
        if(value.length<2||value[0]!='#'||value[1]!='!')continue;
        int at=indexOf(value,needle);if(at<0)continue;
        String replacement=cache.getCanonicalPath()+suffix;value=new String(value,StandardCharsets.UTF_8).replace(placeholder,replacement).getBytes(StandardCharsets.UTF_8);
        Files.write(path,value);
      }
    }
  }

  private static void substituteTextPrefix(File cache,String prefix,String replacement)throws IOException {
    if(prefix==null||prefix.isBlank()||prefix.equals(replacement))return;
    byte[] needle=prefix.getBytes(StandardCharsets.UTF_8);
    try(var paths=Files.walk(cache.toPath())) {
      for(Path path:(Iterable<Path>)paths::iterator) {
        if(!Files.isRegularFile(path)||Files.size(path)>8_000_000)continue;
        byte[] value=Files.readAllBytes(path);
        int at=indexOf(value,needle);if(at<0)continue;
        if(!isUtf8(value))continue;
        String replacementBytes=String.valueOf(replacement);
        value=new String(value,StandardCharsets.UTF_8).replace(prefix,replacementBytes).getBytes(StandardCharsets.UTF_8);
        Files.write(path,value);
      }
    }
  }

  private static boolean isUtf8(byte[] value) {
    try {
      java.nio.charset.CharsetDecoder decoder=StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
      decoder.decode(java.nio.ByteBuffer.wrap(value));
      return true;
    } catch(Exception e) {return false;}
  }
  private static int indexOf(byte[] value,byte[] needle) {
    outer:for(int i=0;i<=value.length-needle.length;i++) {
      for(int j=0;j<needle.length;j++)if(value[i+j]!=needle[j])continue outer;
      return i;
    }
    return -1;
  }
  private static void ensureDirectories(File cache) {
    new File(cache,"home").mkdirs();new File(cache,"tmp").mkdirs();new File(cache,"usr/bin").mkdirs();
  }
  private static void putQuiet(JSONObject target,String key,Object value){try{target.put(key,value);}catch(Exception ignored){}}
  private static JSONObject expandEnvironment(JSONObject source,String cache) {
    JSONObject value=new JSONObject();
    if(source!=null)for(java.util.Iterator<String> iterator=source.keys();iterator.hasNext();){String key=iterator.next();putQuiet(value,key,String.valueOf(source.opt(key)).replace("{cache}",cache));}
    return value;
  }
  private static String sha256(File file)throws Exception {
    MessageDigest digest=MessageDigest.getInstance("SHA-256");
    try(InputStream input=new BufferedInputStream(new FileInputStream(file),65536)) {
      byte[] buffer=new byte[65536];int n;
      while((n=input.read(buffer))>=0)digest.update(buffer,0,n);
    }
    StringBuilder value=new StringBuilder();for(byte item:digest.digest())value.append(String.format("%02x",item));
    return value.toString();
  }
  private static void readProcess(InputStream input,StringBuilder output) {
    try(BufferedReader reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))) {for(String line;(line=reader.readLine())!=null;)output.append(line).append('\n');}catch(Exception ignored){}
  }
}
