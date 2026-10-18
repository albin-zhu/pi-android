package dev.zfold.assistant;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Standalone decoded-path and shared-root checks; Android URI decoding happens exactly once before these APIs. */
public final class PreviewMarkdownPathsCheck {
  interface IoAction {void run()throws IOException;}
  private static void equal(Object wanted,Object actual){if(!wanted.equals(actual))throw new AssertionError("expected "+wanted+", got "+actual);}
  private static void rejects(IoAction action)throws IOException {try{action.run();}catch(IOException expected){return;}throw new AssertionError("unsafe path accepted");}
  public static void main(String[] args)throws Exception {
    String name="中文 空格 100% #.md";
    equal("笔记/"+name,PreviewMarkdownPaths.relative(List.of("files","笔记",name)));
    equal("%2e%2e/%2F.md",PreviewMarkdownPaths.relative(List.of("files","%2e%2e","%2F.md")));
    for(List<String> path:List.of(List.of("files"),List.of("private","x.md"),List.of("files","..","secret.md"),List.of("files",".","x.md"),List.of("files","a/b.md"),List.of("files","a\\b.md"),List.of("files","file:x.md"),List.of("files","a\u0000.md")))rejects(()->PreviewMarkdownPaths.relative(path));
    URI page=new URI("https","preview.invalid","/files/项目/docs/start.md",null);
    URI sibling=page.resolve("../images/%E4%B8%AD%E6%96%87%20%23%20%25.svg");
    equal("项目/images/中文 # %.svg",PreviewMarkdownPaths.relative(Arrays.asList(sibling.getPath().substring(1).split("/"))));
    rejects(()->PreviewMarkdownPaths.relative(Arrays.asList(page.resolve("../../../../private/secret.png").normalize().getPath().substring(1).split("/"))));
    Path root=Files.createTempDirectory("markdown-root-"),outside=Files.createTempDirectory("markdown-outside-");
    try{
      Path docs=Files.createDirectories(root.resolve("项目/docs")),images=Files.createDirectories(root.resolve("项目/images"));
      File document=Files.writeString(docs.resolve(name),"# example").toFile();
      File image=Files.writeString(images.resolve("图 # %.svg"),"<svg/>").toFile();
      equal("项目/docs/"+name,PreviewMarkdownPaths.fileRelative(root.toFile(),document));
      equal(image.getCanonicalFile(),PreviewFiles.resource(root.toFile(),"项目/images/图 # %.svg"));
      File literal=Files.writeString(images.resolve("%2e%2e.svg"),"<svg/>").toFile();
      equal(literal.getCanonicalFile(),PreviewFiles.resource(root.toFile(),"项目/images/%2e%2e.svg"));
      File secret=Files.writeString(outside.resolve("secret.svg"),"<svg/>").toFile();
      Files.createSymbolicLink(root.resolve("escape"),outside);
      rejects(()->PreviewFiles.resource(root.toFile(),"escape/secret.svg"));
      rejects(()->PreviewMarkdownPaths.fileRelative(root.toFile(),secret));
      rejects(()->PreviewMarkdownPaths.fileRelative(root.toFile(),root.resolve("escape/secret.svg").toFile()));
      equal(true,PreviewMarkdownPaths.image("image/svg+xml"));
      for(String mime:new String[]{"text/html","application/javascript","text/css","application/octet-stream","image/svg+xml;script=true"})equal(false,PreviewMarkdownPaths.image(mime));
      System.out.println("Markdown root-relative Unicode/percent/fragment paths and canonical boundaries passed");
    }finally{
      for(Path directory:List.of(root,outside))try(var paths=Files.walk(directory)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}
    }
  }
}
