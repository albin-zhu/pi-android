package dev.zfold.assistant;

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
    System.out.println("TerminalLinkTargetsCheck: shared mount, URL preservation and ambiguous file targets passed");
  }
}
