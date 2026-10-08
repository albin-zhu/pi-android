package dev.zfold.assistant;
import java.io.File;
import java.nio.file.*;
public final class DirectoryChoicesCheck {
  public static void main(String[] args)throws Exception{
    Path root=Files.createTempDirectory("directory-choice-"),outside=Files.createTempDirectory("directory-outside-");
    try{
      Files.createDirectory(root.resolve("B"));Files.createDirectory(root.resolve("a"));Files.writeString(root.resolve("file"),"x");Files.createSymbolicLink(root.resolve("escape"),outside);
      var choices=DirectoryChoices.list(root.toFile(),root.toFile());if(choices.size()!=2||!choices.get(0).getName().equals("a"))throw new AssertionError("sorted directories only");
      for(Path bad:new Path[]{root.resolve("missing"),root.resolve("file"),root.resolve("escape"),outside}){boolean failed=false;try{DirectoryChoices.validate(root.toFile(),bad.toFile());}catch(java.io.IOException expected){failed=true;}if(!failed)throw new AssertionError("accepted "+bad);}
      File created=DirectoryChoices.create(root.toFile(),root.toFile(),"新项目");if(!created.isDirectory())throw new AssertionError("folder not created");
      for(String name:new String[]{"", ".", "..", "../outside", "nested/child", "bad\\name", "bad\nname", "新项目", "file", "escape"}){
        boolean rejected=false;try{DirectoryChoices.create(root.toFile(),root.toFile(),name);}catch(java.io.IOException expected){rejected=true;}if(!rejected)throw new AssertionError("accepted folder name: "+name);
      }
      boolean rejected=false;try{DirectoryChoices.create(root.toFile(),root.resolve("escape").toFile(),"x");}catch(java.io.IOException expected){rejected=true;}if(!rejected||Files.exists(outside.resolve("x")))throw new AssertionError("created outside root");
      DirectoryChoices.validate(root.toFile(),root.resolve("a/..").toFile());System.out.println("directory listing, sorting, traversal boundary and creation, duplicates and final selection checks passed");
    }finally{try(var paths=Files.walk(root)){for(Path p:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(p);}Files.delete(outside);}
  }
}
