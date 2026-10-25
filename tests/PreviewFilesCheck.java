package dev.zfold.assistant;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

/** Standalone file-policy, decoding and local-resource boundary checks; no Android dependency. */
public final class PreviewFilesCheck {
  private interface IoAction { void run() throws IOException; }
  private static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
  private static void equal(Object expected,Object actual,String label){
    if(!expected.equals(actual))throw new AssertionError(label+": expected "+expected+", got "+actual);
  }
  private static void rejects(IoAction action,String label)throws IOException {
    try{action.run();}catch(IOException expected){return;}
    throw new AssertionError(label+" was accepted");
  }
  private static File put(Path root,String name,byte[] data)throws IOException {
    return Files.write(root.resolve(name),data).toFile();
  }
  private static byte[] bom(byte[] prefix,byte[] body){
    byte[] result=Arrays.copyOf(prefix,prefix.length+body.length);
    System.arraycopy(body,0,result,prefix.length,body.length);return result;
  }
  private static void remove(Path root)throws IOException {
    try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
  }
  public static void main(String[] args)throws Exception {
    Path root=Files.createTempDirectory("preview-files-"),outside=Files.createTempDirectory("preview-outside-");
    try{
      policies(root);
      decoding(root);
      streams();
      resources(root,outside);
      System.out.println("preview file/content-stream policy, strict Unicode decoding, size/binary rejection and local-resource boundaries passed");
    }finally{remove(root);remove(outside);}
  }
  private static void policies(Path root)throws Exception {
    for(String name:new String[]{"note.md","NOTE.MARKDOWN"})equal(PreviewFiles.Kind.MARKDOWN,PreviewFiles.kind(root.resolve(name).toFile()),name);
    for(String name:new String[]{"page.html","PAGE.HTM"})equal(PreviewFiles.Kind.HTML,PreviewFiles.kind(root.resolve(name).toFile()),name);
    equal(PreviewFiles.Kind.SVG,PreviewFiles.kind(root.resolve("drawing.SVG").toFile()),"SVG kind");
    for(String extension:new String[]{"md","markdown","html","htm","svg","txt","js","java","py","sh","css","json","yaml","yml","log","csv","xml","gtm"}){
      File file=root.resolve("中文 文件."+extension).toFile();
      check(PreviewFiles.supports(file,"application/octet-stream"),"supported extension: "+extension);
    }
    File unknown=root.resolve("unknown.blob").toFile();
    check(!PreviewFiles.supports(unknown,"application/octet-stream"),"unknown binary MIME must stay external first");
    check(!PreviewFiles.supports(root.resolve("photo.png").toFile(),"image/png"),"raster image is not text");
    check(!PreviewFiles.supports(root.resolve("archive.zip").toFile(),"application/zip"),"archive is not text");
    check(PreviewFiles.supports(unknown,"text/plain"),"text MIME");
    check(PreviewFiles.supports(unknown,"application/json"),"JSON MIME");
    equal(PreviewFiles.Kind.TEXT,PreviewFiles.kind(root.resolve("project.gtm").toFile()),"GTM is readable text, not an invented format");
    for(String mime:new String[]{"text/markdown","text/x-markdown","application/markdown","application/x-markdown","Text/Markdown; charset=UTF-8"}){
      check(PreviewFiles.documentRequestMime(mime),"Markdown request MIME "+mime);
      equal(PreviewFiles.Kind.MARKDOWN,PreviewFiles.documentKind(null,mime),"explicit Markdown MIME needs no filename");
    }
    for(String name:new String[]{"中文 空格.md","NOTE.MARKDOWN","note.mdown","note.mkd"})
      equal(PreviewFiles.Kind.MARKDOWN,PreviewFiles.documentKind(name,"text/plain"),"plain Markdown filename "+name);
    for(String name:new String[]{null,"","notes.txt","data.json","unknown.blob"})
      equal(PreviewFiles.Kind.TEXT,PreviewFiles.documentKind(name,"text/plain"),"literal text document "+name);
    equal(PreviewFiles.Kind.TEXT,PreviewFiles.documentKind("note.md","application/json"),"explicit JSON is not Markdown");
    equal(PreviewFiles.Kind.TEXT,PreviewFiles.documentKind("page.html","text/html"),"external HTML is literal text, not script");
    equal(PreviewFiles.Kind.TEXT,PreviewFiles.documentKind(null,"application/ld+json"),"structured JSON MIME");
    for(String mime:new String[]{null,"application/octet-stream"}){
      equal(PreviewFiles.Kind.TEXT,PreviewFiles.documentKind("data.JSON",mime),"JSON suffix with missing/generic MIME");
      check(PreviewFiles.documentKind("payload.exe",mime)==null,"unknown binary does not gain text fallback");
      check(PreviewFiles.documentKind("page.html",mime)==null,"generic MIME does not enable active HTML");
    }
    for(String name:new String[]{"folder/note.txt","folder\\note.json","x".repeat(1025)})
      check(PreviewFiles.documentKind(name,"text/plain")==null,"display names are not paths");
    for(String mime:new String[]{"","image/svg+xml","application/zip","*/*","text/*","x".repeat(129)})
      check(PreviewFiles.documentKind("note.md",mime)==null,"unsupported external MIME "+mime);
  }
  private static void decoding(Path root)throws Exception {
    String text="中文 空格 café 😀\nsecond\tline\r\n";
    byte[] utf8=text.getBytes(StandardCharsets.UTF_8);
    equal(text,PreviewFiles.readText(put(root,"中文 空格.txt",utf8)),"UTF-8 Unicode");
    equal(text,PreviewFiles.readText(put(root,"bom.txt",bom(new byte[]{(byte)0xef,(byte)0xbb,(byte)0xbf},utf8))),"UTF-8 BOM");
    equal(text,PreviewFiles.readText(put(root,"le.txt",bom(new byte[]{(byte)0xff,(byte)0xfe},text.getBytes(StandardCharsets.UTF_16LE)))),"UTF-16LE BOM");
    equal(text,PreviewFiles.readText(put(root,"be.txt",bom(new byte[]{(byte)0xfe,(byte)0xff},text.getBytes(StandardCharsets.UTF_16BE)))),"UTF-16BE BOM");
    equal("",PreviewFiles.readText(put(root,"empty.txt",new byte[0])),"empty text");
    equal("readable unknown",PreviewFiles.readText(put(root,"unknown.blob","readable unknown".getBytes(StandardCharsets.UTF_8))),"unknown extension readable fallback");
    for(byte[] bytes:new byte[][]{
      {(byte)0xc3,0x28}, {(byte)0xe4,(byte)0xb8}, {(byte)0xc0,(byte)0xaf},
      {(byte)0xef,(byte)0xbb,(byte)0xbf,(byte)0xff},
      {(byte)0xff,(byte)0xfe,0x41}, {(byte)0xfe,(byte)0xff,(byte)0xd8,0},
      {0x41,0,0x42,0}, {0x41,0x1b,0x42}, {0x41,0x07,0x42},
      {(byte)0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a}
    }){
      File bad=put(root,"binary.txt",bytes);
      rejects(()->PreviewFiles.readText(bad),"invalid encoding or binary controls "+Arrays.toString(bytes));
    }
    byte[] limit=new byte[2*1024*1024];Arrays.fill(limit,(byte)'a');
    File exact=put(root,"limit.txt",limit);
    String actual=PreviewFiles.readText(exact);
    check(actual.length()==limit.length&&actual.charAt(actual.length()-1)=='a',"exact 2 MiB is complete, not truncated");
    byte[] over=Arrays.copyOf(limit,limit.length+1);over[over.length-1]='b';
    File oversized=put(root,"oversized.txt",over);
    rejects(()->PreviewFiles.readText(oversized),"over 2 MiB");
    rejects(()->PreviewFiles.readText(root.resolve("missing.txt").toFile()),"missing document");
    rejects(()->PreviewFiles.readText(root.toFile()),"directory document");
  }
  private static void streams()throws Exception {
    String text="# 中文 😀\n\n**Markdown**";
    byte[] utf8=text.getBytes(StandardCharsets.UTF_8);
    equal(text,PreviewFiles.readText(new ByteArrayInputStream(utf8)),"unknown-size UTF-8 stream");
    equal(text,PreviewFiles.readText(new ByteArrayInputStream(bom(new byte[]{(byte)0xef,(byte)0xbb,(byte)0xbf},utf8))),"stream UTF-8 BOM");
    equal(text,PreviewFiles.readText(new ByteArrayInputStream(bom(new byte[]{(byte)0xff,(byte)0xfe},text.getBytes(StandardCharsets.UTF_16LE)))),"stream UTF-16LE");
    equal(text,PreviewFiles.readText(new ByteArrayInputStream(bom(new byte[]{(byte)0xfe,(byte)0xff},text.getBytes(StandardCharsets.UTF_16BE)))),"stream UTF-16BE");
    equal("",PreviewFiles.readText(new ByteArrayInputStream(new byte[0])),"empty unknown-size stream");
    InputStream shortReads=new ByteArrayInputStream(utf8){
      private boolean zero=true;
      @Override public synchronized int read(byte[] bytes,int offset,int length){
        if(zero){zero=false;return 0;}
        return super.read(bytes,offset,Math.min(length,3));
      }
      @Override public synchronized int available(){return Integer.MAX_VALUE;}
    };
    equal(text,PreviewFiles.readText(shortReads),"short and zero reads, untrusted available count");
    byte[] exact=new byte[PreviewFiles.MAX_DOCUMENT_BYTES];Arrays.fill(exact,(byte)'x');
    equal(exact.length,PreviewFiles.readText(new ByteArrayInputStream(exact)).length(),"stream exact 2 MiB");
    final int[] consumed={0};
    InputStream infinite=new InputStream(){
      @Override public int read(){consumed[0]++;return 'x';}
      @Override public int read(byte[] bytes,int offset,int length){
        Arrays.fill(bytes,offset,offset+length,(byte)'x');consumed[0]+=length;return length;
      }
      @Override public int available(){return Integer.MAX_VALUE;}
    };
    rejects(()->PreviewFiles.readText(infinite),"unknown-size oversized stream");
    equal(PreviewFiles.MAX_DOCUMENT_BYTES+1,consumed[0],"oversize stops at first excess byte");
    for(byte[] bytes:new byte[][]{{(byte)0xc3,0x28},{0x41,0,0x42},{(byte)0xff,(byte)0xfe,0x41},{0,0,(byte)0xfe,(byte)0xff}, "%PDF-1.7".getBytes(StandardCharsets.UTF_8)})
      rejects(()->PreviewFiles.readText(new ByteArrayInputStream(bytes)),"stream strict encoding/binary rejection");
    rejects(()->PreviewFiles.readText((InputStream)null),"missing provider stream");
    rejects(()->PreviewFiles.readText(new InputStream(){@Override public int read()throws IOException{throw new IOException("provider denied");}}),"provider read failure");
    Thread.currentThread().interrupt();
    try{rejects(()->PreviewFiles.readText(new ByteArrayInputStream(utf8)),"cancelled stream");}
    finally{Thread.interrupted();}
  }
  private static void resources(Path root,Path outside)throws Exception {
    Path assets=Files.createDirectory(root.resolve("资源 空格"));
    File image=put(assets,"图 像.svg","<svg/>".getBytes(StandardCharsets.UTF_8));
    equal(image.getCanonicalFile(),PreviewFiles.resource(root.toFile(),"资源 空格/图 像.svg"),"decoded Unicode and spaces");
    File percent=put(assets,"100%.txt",new byte[]{'x'});
    equal(percent.getCanonicalFile(),PreviewFiles.resource(root.toFile(),"资源 空格/100%.txt"),"resource path is already decoded");
    File secret=put(outside,"secret.txt",new byte[]{'s'});
    Files.createSymbolicLink(root.resolve("escape"),outside);
    Files.createSymbolicLink(root.resolve("file-escape.txt"),secret.toPath());
    Files.createSymbolicLink(root.resolve("inside.svg"),image.toPath());
    equal(image.getCanonicalFile(),PreviewFiles.resource(root.toFile(),"inside.svg"),"internal symlink remains confined");
    for(String path:new String[]{"../"+outside.getFileName()+"/secret.txt","资源 空格/../../"+outside.getFileName()+"/secret.txt",secret.getPath(),"escape/secret.txt","file-escape.txt","missing.css","资源 空格"}){
      rejects(()->PreviewFiles.resource(root.toFile(),path),"resource boundary: "+path);
    }
  }
}
