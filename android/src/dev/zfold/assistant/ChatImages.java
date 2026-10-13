package dev.zfold.assistant;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.util.concurrent.*;

/** Local, bounded decoding. No network fetch or external URI permission required. */
final class ChatImages {
  static final ExecutorService loader=Executors.newSingleThreadExecutor();
  static File checked(Context c,String value)throws Exception {
    String target=value.trim();if(target.startsWith("file://"))target=Uri.parse(target).getPath();
    for(String alias:new String[]{"/storage/pi","/sdcard/pi","/storage/emulated/0/pi"})if(target.startsWith(alias+"/")){target=new File(ArtifactProvider.root(c),target.substring(alias.length()+1)).getPath();break;}
    File file=new File(target);if(!file.isAbsolute())file=new File(target.startsWith("workspace/")||target.startsWith("workspaces/")?ArtifactProvider.root(c):RuntimeEnvironment.workingDirectory(c,null),target);
    return ArtifactProvider.checked(c,file.getPath());
  }
  static java.util.Set<String> paths(String text){
    java.util.Set<String> result=new java.util.LinkedHashSet<>();
    java.util.regex.Matcher markdown=java.util.regex.Pattern.compile("!\\[[^\\]]*\\]\\(([^)]+)\\)").matcher(text);
    while(markdown.find()){String value=markdown.group(1).trim();if(!value.matches("(?i)^(https?:|data:|content:).*"))result.add(value);if(result.size()>=4)return result;}
    java.util.regex.Matcher local=java.util.regex.Pattern.compile("(?:file:///|/storage/[^\\s<>`]*?/|(?:workspace/)?generated_images/)[^\\s<>`\\\"()]+?\\.(?:png|jpe?g|webp|gif|bmp)",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(text);
    while(local.find()){result.add(local.group());if(result.size()>=4)break;}return result;
  }
  static Bitmap decode(File file,int max){
    BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),options);
    if(options.outWidth<=0||options.outHeight<=0||file.length()>64L*1024*1024)return null;
    options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>max)options.inSampleSize*=2;
    options.inJustDecodeBounds=false;return BitmapFactory.decodeFile(file.getPath(),options);
  }
  static View preview(Context c,String path){
    LinearLayout box=new LinearLayout(c);box.setOrientation(LinearLayout.VERTICAL);
    TextView state=Ui.text(c,"正在加载图片…",12,new Ui.Theme(c).muted);box.addView(state);
    ImageView image=new ImageView(c);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setAdjustViewBounds(true);image.setMaxHeight(Ui.dp(c,300));image.setMinimumHeight(Ui.dp(c,48));image.setContentDescription("打开图片大图");image.setFocusable(true);box.addView(image,new LinearLayout.LayoutParams(-1,-2));
    loader.execute(()->{Bitmap bitmap=null;try{bitmap=decode(checked(c,path),1024);}catch(Exception ignored){}final Bitmap result=bitmap;image.post(()->{if(result==null){state.setText("图片无法读取或已移除");image.setVisibility(View.GONE);}else{state.setVisibility(View.GONE);image.setImageBitmap(result);image.setOnClickListener(v->open(c,path));}});});return box;
  }
  static void open(Context c,String path){show(c,path,null);}
  static void openBitmap(Context c,String encoded){show(c,null,encoded);}
  private static void show(Context c,String path,String encoded){
    Dialog dialog=new Dialog(c,android.R.style.Theme_Material_NoActionBar);
    LinearLayout root=new LinearLayout(c);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.BLACK);
    LinearLayout toolbar=new LinearLayout(c);root.addView(toolbar,new LinearLayout.LayoutParams(-1,-2));
    Button close=new Button(c);close.setText("关闭");toolbar.addView(close,new LinearLayout.LayoutParams(0,Ui.dp(c,56),1));close.setOnClickListener(v->dialog.dismiss());
    Button save=new Button(c);save.setText("保存到本地");save.setEnabled(false);toolbar.addView(save,new LinearLayout.LayoutParams(0,Ui.dp(c,56),2));
    if(path!=null){Button external=new Button(c);external.setText("外部打开");toolbar.addView(external,new LinearLayout.LayoutParams(0,Ui.dp(c,56),2));external.setOnClickListener(v->{try{OpenTargets.open(c,checked(c,path).getPath(),"",true);}catch(Exception error){Toast.makeText(c,"无法打开："+error.getMessage(),Toast.LENGTH_LONG).show();}});}
    ImageView image=new ImageView(c);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setContentDescription("图片大图");root.addView(image,new LinearLayout.LayoutParams(-1,0,1));dialog.setContentView(root);dialog.show();dialog.getWindow().setLayout(-1,-1);
    save.setOnClickListener(v->{save.setEnabled(false);save.setText("正在保存…");loader.execute(()->{
      String message;try{ImageDownloads.save(c.getApplicationContext(),path,encoded);message="已保存到相册 Pictures/橙橙";}catch(Exception error){message="保存失败："+error.getMessage();}
      final String receipt=message;save.post(()->{Toast.makeText(c,receipt,Toast.LENGTH_LONG).show();if(dialog.isShowing()){save.setText("保存到本地");save.setEnabled(true);}});
    });});
    loader.execute(()->{Bitmap bitmap=null;try{bitmap=path!=null?decode(checked(c,path),2048):Ui.thumbnail(encoded,2048);}catch(Exception ignored){}final Bitmap result=bitmap;
      image.post(()->{if(!dialog.isShowing())return;if(result==null){dialog.dismiss();Toast.makeText(c,"图片无法读取或已移除",Toast.LENGTH_SHORT).show();}else{image.setImageBitmap(result);save.setEnabled(true);}});
    });
  }
}
