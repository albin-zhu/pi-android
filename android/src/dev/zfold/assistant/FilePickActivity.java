package dev.zfold.assistant;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

/** Transparent SAF host so overlays can pick user-selected files without broad storage access. */
public final class FilePickActivity extends Activity {
  interface Files {void picked(List<Uri> uris);}
  private static volatile Files pending;

  static void request(Context context,Files callback){
    pending=callback;
    context.startActivity(new Intent(context,FilePickActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  }

  @Override protected void onCreate(Bundle state){
    super.onCreate(state);
    Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
      .putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true)
      .putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/*","audio/*","video/*","text/*","application/pdf",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation","application/json","application/xml","text/csv"});
    try{startActivityForResult(picker,71);}
    catch(RuntimeException error){deliver(new ArrayList<>());}
  }

  @Override protected void onActivityResult(int request,int result,Intent data){
    super.onActivityResult(request,result,data);
    List<Uri> uris=new ArrayList<>();
    if(request==71&&result==RESULT_OK&&data!=null){
      if(data.getData()!=null)uris.add(data.getData());
      ClipData clip=data.getClipData();
      if(clip!=null)for(int i=0;i<clip.getItemCount()&&uris.size()<LocalFiles.MAX_FILES;i++){Uri uri=clip.getItemAt(i).getUri();if(uri!=null)uris.add(uri);}
    }
    deliver(uris);
  }

  private void deliver(List<Uri> uris){Files callback=pending;pending=null;if(callback!=null)callback.picked(uris);finish();}
}
