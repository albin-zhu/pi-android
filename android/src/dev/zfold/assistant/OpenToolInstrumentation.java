package dev.zfold.assistant;

import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Android handler verification for URL/file/directory open fallbacks. */
public final class OpenToolInstrumentation extends Instrumentation {
  private Bundle options;
  private String error;private JSONObject fileResult,directoryResult;
  private final CountDownLatch done=new CountDownLatch(1);

  @Override public void onCreate(Bundle arguments){options=arguments;start();}

  @Override public void onStart(){
    if(!"file".equals(options==null?"":options.getString("mode",""))){
      call("open",PiRuntime.json("target","https://example.com/open-zfold"),value->{
        Bundle outcome=new Bundle();outcome.putString("url",String.valueOf(value));outcome.putString("error",error);
        finish(value!=null&&value.optBoolean("opened")&&"uri".equals(value.optString("kind"))?-1:0,outcome);
      });
      return;
    }
    try {
      File directory=new File(android.os.Environment.getExternalStorageDirectory(),"pi/workspace").getCanonicalFile();
      directory.mkdirs();File file=new File(directory,"open-tool.txt");
      try(FileOutputStream out=new FileOutputStream(file)){out.write("Zfold open tool\n".getBytes());}
      android.util.Log.i("ZfoldOpen","created "+file+" exists="+file.isFile());
      call("open",PiRuntime.json("target",file.getPath()),value->{fileResult=value;done.countDown();});
      done.await(10,java.util.concurrent.TimeUnit.SECONDS);
      android.util.Log.i("ZfoldOpen","file result "+fileResult+" error "+error);
      CountDownLatch second=new CountDownLatch(1);
      call("open",PiRuntime.json("target",directory.getPath()),value->{directoryResult=value;second.countDown();});
      second.await(10,java.util.concurrent.TimeUnit.SECONDS);
      android.util.Log.i("ZfoldOpen","directory result "+directoryResult+" error "+error);
      boolean ok=fileResult!=null&&fileResult.optBoolean("opened")&&"file".equals(fileResult.optString("kind"))
        &&directoryResult!=null&&directoryResult.optBoolean("opened")&&"directory".equals(directoryResult.optString("kind"));
      Bundle outcome=new Bundle();
      outcome.putString("file",String.valueOf(fileResult));outcome.putString("directory",String.valueOf(directoryResult));
      outcome.putString("error",error);
      finish(ok?-1:0,outcome);
    }catch(Throwable failure){error=String.valueOf(failure);Bundle outcome=new Bundle();outcome.putString("error",error);finish(0,outcome);}
  }

  private void call(String name,JSONObject args,java.util.function.Consumer<JSONObject> receiver){
    AndroidTools.execute(getTargetContext(),PiRuntime.json("name",name,"args",args),(value,error)->{
      if(error!=null)this.error=this.error==null?error:this.error+"; "+error;
      receiver.accept(value);
    });
  }
}
