package dev.zfold.assistant;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Real files, streaming failure/cancellation and shell parsing; no Android dependency. */
public final class TerminalAttachmentFilesCheck {
  interface Checked {void run()throws Exception;}
  static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
  static void rejects(Checked operation)throws Exception{try{operation.run();}catch(IOException expected){return;}throw new AssertionError("Invalid file path accepted");}
  static void clean(Path root)throws IOException{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
  public static void main(String[] args)throws Exception{
    Path temp=Files.createTempDirectory("terminal-attachments-");
    try{
      File storage=Files.createDirectories(temp.resolve("phone storage")).toFile();
      File root=Files.createDirectories(storage.toPath().resolve("pi")).toFile();
      Path photo=Files.createDirectories(storage.toPath().resolve("DCIM")).resolve("中文 空格 '图.png");
      byte[] pixels=new byte[90001];new Random(19).nextBytes(pixels);Files.write(photo,pixels);
      check(TerminalAttachmentFiles.primaryDocument(storage,"primary:DCIM/中文 空格 '图.png").equals(photo.toFile()),"Primary document identity lost");
      check(TerminalAttachmentFiles.path(photo.toFile(),root,storage,true).equals("/sdcard/DCIM/中文 空格 '图.png"),"Ubuntu primary storage alias incorrect");
      check(TerminalAttachmentFiles.path(photo.toFile(),root,storage,false).equals("/sdcard/DCIM/中文 空格 '图.png"),"Android and Ubuntu aliases differ");
      check(TerminalAttachmentFiles.primaryDocument(storage,"image:123")==null,"Opaque media ID guessed as a path");
      check(TerminalAttachmentFiles.primaryDocument(storage,"primary:../outside.png")==null,"Parent traversal accepted");
      check(TerminalAttachmentFiles.primaryDocument(storage,"primary:/DCIM/a.png")==null,"Absolute document path accepted");
      check(TerminalAttachmentFiles.primaryDocument(storage,"primary:DCIM")==null,"Directory treated as attachment");
      Path outside=Files.write(temp.resolve("outside.png"),pixels);
      Files.createSymbolicLink(storage.toPath().resolve("escape.png"),outside);
      check(TerminalAttachmentFiles.primaryDocument(storage,"primary:escape.png")==null,"Primary symlink escaped storage");
      rejects(()->TerminalAttachmentFiles.path(outside.toFile(),root,storage,true));

      File batch=TerminalAttachmentFiles.newBatch(root);
      InputStream shortReads=new ByteArrayInputStream(pixels){boolean zero=true;@Override public synchronized int read(byte[] b,int off,int len){if(zero){zero=false;return 0;}return super.read(b,off,Math.min(len,113));}};
      File imported=TerminalAttachmentFiles.copy(shortReads,batch,"../../恶意\n名字.png",1,()->{});
      check(Arrays.equals(pixels,Files.readAllBytes(imported.toPath())),"Import corrupted short/zero-read stream");
      check(imported.getCanonicalFile().getParentFile().equals(batch.getCanonicalFile()),"Provider name escaped import batch");
      String guest=TerminalAttachmentFiles.path(imported,root,storage,true);
      check(guest.startsWith("/workspace/workspace/attachments/")&&!guest.contains("\n"),"Imported image inaccessible or unsafe in guest");
      check(TerminalAttachmentFiles.path(imported,root,storage,false).startsWith("/sdcard/pi/workspace/attachments/"),"Imported image inaccessible from Android");
      File longName=TerminalAttachmentFiles.copy(new ByteArrayInputStream(new byte[]{8,9}),batch,"图😀".repeat(150)+".png",2,()->{});
      check(longName.getName().endsWith(".png")&&Arrays.equals(new byte[]{8,9},Files.readAllBytes(longName.toPath())),"Long Unicode name lost image type or content");
      File empty=TerminalAttachmentFiles.copy(new ByteArrayInputStream(new byte[0]),batch,"empty.txt",3,()->{});
      check(empty.isFile()&&Files.size(empty.toPath())==0,"Empty file was not imported");
      rejects(()->TerminalAttachmentFiles.copy(new ByteArrayInputStream(new byte[]{1}),batch,"empty.txt",3,()->{}));
      check(Files.size(empty.toPath())==0,"Collision overwrote existing attachment");

      InputStream broken=new InputStream(){int calls;public int read()throws IOException{if(calls++<7)return 42;throw new IOException("provider disconnected");}};
      rejects(()->TerminalAttachmentFiles.copy(broken,batch,"broken.bin",4,()->{}));
      check(!new File(batch,"4-broken.bin").exists(),"Failed provider left a partial attachment");
      boolean[] cancelled={false};
      InputStream cancellable=new ByteArrayInputStream(pixels){@Override public synchronized int read(byte[] b,int off,int len){int n=super.read(b,off,Math.min(len,500));cancelled[0]=true;return n;}};
      try{TerminalAttachmentFiles.copy(cancellable,batch,"cancelled.bin",5,()->{if(cancelled[0])throw new CancellationException();});throw new AssertionError("Cancelled import succeeded");}catch(CancellationException expected){}
      check(!new File(batch,"5-cancelled.bin").exists(),"Cancellation left a partial attachment");
      TerminalAttachmentFiles.rollback(batch,Arrays.asList(imported,longName,empty));
      check(!batch.exists(),"Failed batch cleanup left imported files");

      File privateRoot=Files.createDirectories(temp.resolve("private-workspace")).toFile();
      File privateBatch=TerminalAttachmentFiles.newBatch(privateRoot);
      File privateFile=TerminalAttachmentFiles.copy(new ByteArrayInputStream(pixels),privateBatch,"private.png",1,()->{});
      check(TerminalAttachmentFiles.path(privateFile,privateRoot,storage,true).startsWith("/workspace/workspace/attachments/"),"Private Android workspace did not map through Ubuntu bind");
      check(TerminalAttachmentFiles.path(privateFile,privateRoot,storage,false).equals(privateFile.getCanonicalPath()),"Private import path changed for Android");
      File badRoot=Files.createDirectories(temp.resolve("symlink-root")).toFile();
      Files.createSymbolicLink(badRoot.toPath().resolve("workspace"),temp);
      rejects(()->TerminalAttachmentFiles.newBatch(badRoot));check(!Files.exists(temp.resolve("attachments")),"Import created directory through escaped workspace symlink");


      String circle=TerminalAttachmentFiles.circleText("/workspace/圈选 '图.png","看这里\t第一行\r\n第二行\u0085第三行\u2028第四行\u2029末尾");
      check(circle.startsWith("'/workspace/圈选 '\\''图.png' ")&&!circle.contains("\n")&&!circle.contains("\r")&&!circle.contains("\t"),"Circle insertion changed quoting or submitted a line");
      check(circle.endsWith("第四行 末尾"),"Circle line separators not flattened");
      for(String question:new String[]{"bad\u001b[31m","bad\u0000","bad\u007f","bad\u0007","bad\u202e","x".repeat(8001)})rejects(()->TerminalAttachmentFiles.circleText("/image.png",question));
      String circleId="12345678-1234-1234-1234-123456789abc";
      File circleFile=new File(privateBatch,"circle-"+circleId+".png");
      Files.write(circleFile.toPath(),new byte[]{1,2,3});
      check(TerminalAttachmentFiles.ownedCircle(privateRoot,circleFile,circleId).equals(circleFile),"Generated circle file ownership lost");
      rejects(()->TerminalAttachmentFiles.ownedCircle(privateRoot,privateFile,circleId));
      rejects(()->TerminalAttachmentFiles.ownedCircle(privateRoot,circleFile,"../bad"));
      rejects(()->TerminalAttachmentFiles.ownedCircle(root,circleFile,circleId));
      Path linkedCircle=privateBatch.toPath().resolve("circle-aaaaaaaa-1234-1234-1234-123456789abc.png");
      Files.createSymbolicLink(linkedCircle,outside);
      rejects(()->TerminalAttachmentFiles.ownedCircle(privateRoot,linkedCircle.toFile(),"aaaaaaaa-1234-1234-1234-123456789abc"));
      Path marker=temp.resolve("must-not-run");
      String hostile="/sdcard/中文 a'b $(touch "+marker+") `touch "+marker+"`; *.png";
      Process process=new ProcessBuilder("/bin/sh","-c","printf '%s' "+TerminalAttachmentFiles.quote(hostile)).start();
      String decoded=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
      check(process.waitFor()==0&&decoded.equals(hostile)&&!Files.exists(marker),"Pasted path became shell syntax or lost filename bytes");
      for(String path:new String[]{"relative.png","/sdcard/line\nbreak.png","/sdcard/return\r.png","/sdcard/escape\u001b[31m.png","/sdcard/control\u0085.png","/sdcard/separator\u2028.png"})rejects(()->TerminalAttachmentFiles.quote(path));
      System.out.println("PASS: real file import bytes, empty/short/zero reads, path aliases, private-workspace fallback, opaque/traversal/symlink boundaries, Unicode names, collision preservation, failed/cancelled stream cleanup and real-shell-safe path insertion");
    }finally{clean(temp);}
  }
}
