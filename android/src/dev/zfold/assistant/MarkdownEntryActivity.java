package dev.zfold.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import java.io.IOException;

/** Only a granted single document crosses this exported boundary; no caller extras are forwarded. */
public final class MarkdownEntryActivity extends Activity {
  @Override protected void onCreate(Bundle saved){
    super.onCreate(saved);
    try{
      Intent incoming=getIntent();validate(this,incoming);
      Uri uri=incoming.getData();
      Intent preview=new Intent(this,PreviewActivity.class).setAction(Intent.ACTION_VIEW)
        .setDataAndType(uri,incoming.getType()).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      preview.setClipData(ClipData.newRawUri("Markdown",uri));
      // The receiving Activity owns the temporary grant, including across recreation.
      // Never forward prefix/write grants, caller ClipData, extras, or guessed filesystem paths.
      startActivity(preview);finish();
    }catch(Exception error){
      String detail=error.getMessage();
      new AlertDialog.Builder(this).setTitle("无法打开 Markdown")
        .setMessage(detail==null?"无法读取文件；请从文件管理器重新选择并授予读取权限。":detail)
        .setPositiveButton("关闭",(dialog,which)->finish()).setOnCancelListener(dialog->finish()).show();
    }
  }

  static void validate(Context context,Intent intent)throws IOException {
    if(intent==null||!Intent.ACTION_VIEW.equals(intent.getAction()))throw new IOException("仅支持文件管理器的打开文件请求（ACTION_VIEW）");
    Uri uri=intent.getData();
    if(uri==null||!"content".equals(uri.getScheme())||uri.getAuthority()==null||uri.getAuthority().isEmpty())
      throw new IOException("仅支持文件管理器授权的 content:// 文档；不接受文件路径或网络地址");
    // An omitted type may be inferred from the provider; resolve it on the preview worker.
    if(intent.getType()!=null&&!PreviewFiles.markdownRequestMime(intent.getType()))
      throw new IOException("仅支持 Markdown 文档；text/plain 文件需使用 .md、.markdown、.mdown 或 .mkd 扩展名");
    if((intent.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)==0
      ||context.checkUriPermission(uri,android.os.Process.myPid(),android.os.Process.myUid(),Intent.FLAG_GRANT_READ_URI_PERMISSION)!=PackageManager.PERMISSION_GRANTED)
      throw new IOException("没有此文档的读取授权；请从文件管理器重新选择并允许读取");
  }
}
