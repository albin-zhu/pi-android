package dev.zfold.assistant;

import android.content.Context;
import java.io.File;
import java.nio.file.Files;
import org.json.JSONObject;

/** Reads the saved identity without starting the runtime (also usable by widgets). */
final class AgentIdentity {
  static String name(Context context) {
    try {
      File file=new File(CredentialStore.directory(context),"agent.json");
      if(!Files.isSymbolicLink(file.toPath())&&file.isFile()&&file.length()<=65536){
        String name=new JSONObject(Files.readString(file.toPath())).optString("name","Zfold").trim();
        if(!name.isEmpty())return name.substring(0,Math.min(48,name.length()));
      }
    }catch(Exception ignored){}
    return "Zfold";
  }
}
