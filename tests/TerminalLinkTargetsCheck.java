package dev.zfold.assistant;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

public final class TerminalLinkTargetsCheck {
  private static final String ROOT="/storage/emulated/0/pi";
  private static void expect(String target,String expected)throws Exception {
    String actual=TerminalLinkTargets.androidTarget(target,ROOT);
    if(!expected.equals(actual))throw new AssertionError(target+": "+actual+" != "+expected);
  }
  private static void reject(String target)throws Exception {
    try{TerminalLinkTargets.androidTarget(target,ROOT);}
    catch(IllegalArgumentException|java.net.URISyntaxException expected){return;}
    throw new AssertionError("Accepted ambiguous file link: "+target);
  }
  private static void expectLocal(String target,File root,String relative)throws Exception {
    File expected=new File(root,relative).getCanonicalFile();
    for(String input:new String[]{target,TerminalLinkTargets.androidTarget(target,root.getPath())}){
      File actual=TerminalLinkTargets.localFile(input,root);
      if(!expected.equals(actual))throw new AssertionError(input+": "+actual+" != "+expected);
    }
  }
  private static void rejectLocal(String target,File root)throws Exception {
    for(boolean terminal:new boolean[]{false,true}){
      try{
        String input=terminal?TerminalLinkTargets.androidTarget(target,root.getPath()):target;
        TerminalLinkTargets.localFile(input,root);
      }catch(IOException|IllegalArgumentException|java.net.URISyntaxException expected){
        continue;
      }
      throw new AssertionError("Accepted unshared local file: "+target);
    }
  }
  private static void checkLocalBoundary()throws Exception {
    Path temporary=Files.createTempDirectory("terminal-link-targets-");
    try{
      File root=Files.createDirectory(temporary.resolve("shared")).toFile().getCanonicalFile();
      Path outside=Files.createDirectory(temporary.resolve("outside"));
      Files.createFile(outside.resolve("private.md"));
      Files.createSymbolicLink(root.toPath().resolve("escape"),outside);
      expectLocal("/workspace",root,"");
      expectLocal("/workspace/notes.md",root,"notes.md");
      expectLocal("file:///workspace/%E7%AC%94%E8%AE%B0%20%23%201.md",root,"笔记 # 1.md");
      expectLocal("file://localhost/workspace/a%2520b.md",root,"a%20b.md");
      expectLocal("FILE:///workspace/a%252Fb.md",root,"a%2Fb.md");
      expectLocal("/workspace/a%20b.md",root,"a%20b.md");
      expectLocal("file:///workspace/%252e%252e/private.md",root,"%2e%2e/private.md");
      expectLocal("/workspace/nested/../notes.md",root,"notes.md");
      expectLocal(root.getPath()+"/notes.md",root,"notes.md");
      for(String alias:new String[]{"/storage/pi","/sdcard/pi","/storage/emulated/0/pi"}){
        expectLocal(alias,root,"");
        expectLocal(alias+"/notes.md",root,"notes.md");
        expectLocal("file://"+alias+"/notes%20one.md",root,"notes one.md");
        rejectLocal(alias+"/../outside/private.md",root);
      }
      rejectLocal("/workspace/../outside/private.md",root);
      rejectLocal("file:///workspace/%2e%2e/outside/private.md",root);
      rejectLocal("/workspace/escape/private.md",root);
      rejectLocal(root.getPath()+"-sibling/private.md",root);
      rejectLocal("/workspace-other/private.md",root);
      rejectLocal("/root/private.md",root);
      rejectLocal(outside.resolve("private.md").toString(),root);
      rejectLocal("file://other-host/workspace/notes.md",root);
      rejectLocal("file://localhost.evil/workspace/notes.md",root);
      rejectLocal("file:///workspace/notes.md?download=1",root);
      rejectLocal("file:///workspace/notes.md#fragment",root);
      rejectLocal("file:notes.md",root);
      rejectLocal("notes.md",root);
    }finally{
      try(Stream<Path> paths=Files.walk(temporary)){
        for(Path path:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(path);
      }
    }
  }
  public static void main(String[] args)throws Exception {
    expect("/workspace",ROOT);
    expect("file:///workspace/workspace/%E5%9B%BE%20%23%201.png",ROOT+"/workspace/图 # 1.png");
    expect("file://localhost/workspace/a%2520b.txt",ROOT+"/a%20b.txt");
    expect("/workspace-other/a.png","/workspace-other/a.png");
    expect("/root/private.txt","/root/private.txt"); // Native OpenTargets rejects guest-private files, never remap them.
    expect("https://example.com/a?q=/workspace#section","https://example.com/a?q=/workspace#section");
    reject("file://other-host/workspace/a.txt");
    reject("file:///workspace/a.txt?download=1");
    reject("file:///workspace/a.txt#different-file");
    reject("file:relative.txt");
    reject("relative.txt");
    checkLocalBoundary();
    System.out.println("TerminalLinkTargetsCheck: shared boundary, single decoding, containment and URL preservation passed");
  }
}
