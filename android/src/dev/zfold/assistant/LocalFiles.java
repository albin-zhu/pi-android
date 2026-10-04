package dev.zfold.assistant;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.pdf.PdfRenderer;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Reads user-selected local documents; SAF grants are used immediately, not persisted. */
final class LocalFiles {
  static final int MAX_FILES=6;
  private static final int TEXT_LIMIT=24_000, READ_LIMIT=16_000_000;

  static JSONArray toJson(List<LocalFileAttachment> files){
    JSONArray out=new JSONArray();
    if(files==null)return out;
    for(LocalFileAttachment file:files)out.put(PiRuntime.json("name",file.name,"mime",file.mime,"kind",file.kind,"size",file.size,"detail",file.detail,"excerpt",file.excerpt));
    return out;
  }

  static List<LocalFileAttachment> prepare(Context context,List<Uri> uris,Consumer<String> progress)throws Exception{
    List<LocalFileAttachment> out=new ArrayList<>();
    int count=Math.min(MAX_FILES,uris.size());
    for(int index=0;index<count;index++){
      Uri uri=uris.get(index);
      if(progress!=null)progress.accept("正在读取文件 "+(index+1)+" / "+count);
      out.add(read(context,uri));
    }
    return out;
  }

  private static LocalFileAttachment read(Context context,Uri uri)throws Exception{
    String name=queryName(context,uri),mime=safeMime(context,uri),lower=name.toLowerCase(Locale.ROOT);
    long size=querySize(context,uri);
    String kind=kind(mime,lower);String excerpt="",image=null;List<String> images=new ArrayList<>();String detail=humanSize(size)+" · "+mime;
    if("image".equals(kind)){
      if(size>7_000_000)throw new IllegalArgumentException(name+" 超过 7 MB，图片暂不发送");
      image=android.util.Base64.encodeToString(readAll(context,uri,7_000_001),android.util.Base64.NO_WRAP);
      detail+=" · 已作为图片发送";
    }else if("pdf".equals(kind)){
      try(ParcelFileDescriptor descriptor=context.getContentResolver().openFileDescriptor(uri,"r")){
        PdfRenderer renderer=new PdfRenderer(descriptor);
        detail+=" · "+renderer.getPageCount()+" 页";
        int pages=Math.min(3,renderer.getPageCount());
        for(int i=0;i<pages;i++){
          PdfRenderer.Page page=renderer.openPage(i);
          int scale=Math.min(1,1000/Math.max(page.getWidth(),page.getHeight()));
          Bitmap bitmap=Bitmap.createBitmap(page.getWidth()*scale,page.getHeight()*scale,Bitmap.Config.ARGB_8888);
          Canvas canvas=new Canvas(bitmap);canvas.drawColor(0xffffffff);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);page.close();
          ByteArrayOutputStream out=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,82,out);bitmap.recycle();
          images.add(android.util.Base64.encodeToString(out.toByteArray(),android.util.Base64.NO_WRAP));
        }
        renderer.close();excerpt="PDF 已渲染前 "+pages+" 页为图片。";detail+=" · 已作为图片发送";
      }
    }else if(lower.endsWith(".xlsx")||mime.contains("spreadsheetml")){
      excerpt=xlsx(context,uri);detail+=" · 已提取表格文本";
    }else if(lower.endsWith(".docx")||mime.contains("wordprocessingml")){
      excerpt=ooxmlText(context,uri,"word/document.xml","p","t");detail+=" · 已提取文档文本";
    }else if(lower.endsWith(".pptx")||mime.contains("presentationml")){
      excerpt=pptx(context,uri);detail+=" · 已提取演示文本";
    }else if("audio".equals(kind)||"video".equals(kind)){
      detail+="\n"+mediaDetail(context,uri);excerpt="本机暂时没有离线转写模型，已提供媒体元数据。";
    }else if("text".equals(kind)||mime.contains("json")||mime.contains("xml")||mime.contains("csv")||isCode(lower)){
      byte[] bytes=readAll(context,uri,1_200_001);excerpt=new String(bytes,java.nio.charset.StandardCharsets.UTF_8).replace('\0',' ').trim();
      detail+=" · 已提取文本";
    }else{
      byte[] head=readAll(context,uri,4097);
      excerpt=new String(head,java.nio.charset.StandardCharsets.UTF_8).replaceAll("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f]","·");
      detail+=" · 未识别格式，只提供文件头";
    }
    return new LocalFileAttachment(name,mime,kind,size,detail,excerpt,image,images);
  }

  private static byte[] readAll(Context context,Uri uri,long limit)throws Exception{
    try(InputStream input=context.getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
      byte[] buffer=new byte[16384];int total=0,count;
      while((count=input.read(buffer))>=0){total+=count;if(total>limit)throw new IllegalArgumentException("文件超过读取上限");out.write(buffer,0,count);}
      return out.toByteArray();
    }
  }

  private static String xlsx(Context context,Uri uri)throws Exception{
    List<String> shared=new ArrayList<>();
    try(ZipInputStream zip=zip(context,uri)){
      ZipEntry entry;
      while((entry=zip.getNextEntry())!=null){
        String name=entry.getName();
        if(name.equals("xl/sharedStrings.xml"))shared=sharedStrings(zip);
        else if(name.startsWith("xl/worksheets/")&&name.endsWith(".xml"))break;
      }
    }
    StringBuilder out=new StringBuilder();int sheets=0;
    try(ZipInputStream zip=zip(context,uri)){
      ZipEntry entry;
      while((entry=zip.getNextEntry())!=null){
        String name=entry.getName();
        if(!name.startsWith("xl/worksheets/")||!name.endsWith(".xml"))continue;
        sheets++;out.append("\n[工作表 ").append(sheets).append("]\n");appendSheet(zip,shared,out);
        if(out.length()>TEXT_LIMIT)break;
      }
    }
    return clip("XLSX 工作簿，共 "+sheets+" 个工作表。\n"+out.toString());
  }

  private static void appendSheet(InputStream input,List<String> shared,StringBuilder out)throws Exception{
    XmlPullParser parser=android.util.Xml.newPullParser();parser.setInput(input,null);
    int row=0,cell=0;String cellValue="",cellType="",cellRef="";
    for(int type=parser.getEventType();type!=XmlPullParser.END_DOCUMENT;type=parser.next()){
      String tag=parser.getName()==null?"":parser.getName();
      if(type==XmlPullParser.START_TAG&&tag.equals("row")){
        row++;cell=0;if(row>240)return;out.append("\n");
      }else if(type==XmlPullParser.START_TAG&&tag.equals("c")){
        cellType=parser.getAttributeValue(null,"t");cellRef=parser.getAttributeValue(null,"r");cellValue="";
      }else if(type==XmlPullParser.TEXT&&(tag.equals("v")||tag.equals("t"))){
        cellValue+=parser.getText()==null?"":parser.getText();
      }else if(type==XmlPullParser.END_TAG&&tag.equals("c")){
        String value=cellValue.trim();
        if("s".equals(cellType)&&!value.isEmpty()){try{int at=Integer.parseInt(value);value=at>=0&&at<shared.size()?shared.get(at):"";}catch(NumberFormatException ignored){}}
        int blank=columnIndex(cellRef,cell);while(cell>blank){out.append('\t');cell++;}
        if(!value.isEmpty())out.append(value.replace('\n',' '));
        cell++;
      }
    }
  }

  private static List<String> sharedStrings(InputStream input)throws Exception{
    List<String> values=new ArrayList<>();XmlPullParser parser=android.util.Xml.newPullParser();parser.setInput(input,null);
    boolean inItem=false,inText=false;String item="",text="";
    for(int type=parser.getEventType();type!=XmlPullParser.END_DOCUMENT;type=parser.next()){
      String tag=parser.getName()==null?"":parser.getName();
      if(type==XmlPullParser.START_TAG&&tag.equals("si")){inItem=true;item="";}
      else if(type==XmlPullParser.START_TAG&&tag.equals("t")){inText=true;text="";}
      else if(type==XmlPullParser.TEXT&&inText)item+=parser.getText()==null?"":parser.getText();
      else if(type==XmlPullParser.END_TAG&&tag.equals("t"))inText=false;
      else if(type==XmlPullParser.END_TAG&&tag.equals("si")){inItem=false;values.add(item);}
    }
    return values;
  }

  private static String ooxmlText(Context context,Uri uri,String entryName,String paragraphTag,String textTag)throws Exception{
    StringBuilder out=new StringBuilder();
    try(ZipInputStream zip=zip(context,uri)){ZipEntry entry;
      while((entry=zip.getNextEntry())!=null){
        if(!entry.getName().equals(entryName))continue;
        XmlPullParser parser=android.util.Xml.newPullParser();parser.setInput(zip,null);
        boolean text=false;
        for(int type=parser.getEventType();type!=XmlPullParser.END_DOCUMENT;type=parser.next()){
          String tag=parser.getName()==null?"":parser.getName();
          if(type==XmlPullParser.START_TAG&&tag.equals(textTag))text=true;
          else if(type==XmlPullParser.TEXT&&text)out.append(parser.getText()==null?"":parser.getText());
          else if(type==XmlPullParser.END_TAG&&(tag.equals(textTag)||tag.equals(paragraphTag))){text=false;if(tag.equals(paragraphTag))out.append('\n');}
        }
        break;
      }
    }
    return clip(out.toString());
  }

  private static String pptx(Context context,Uri uri)throws Exception{
    StringBuilder out=new StringBuilder();
    try(ZipInputStream zip=zip(context,uri)){
      ZipEntry entry;List<String> names=new ArrayList<>();
      while((entry=zip.getNextEntry())!=null)if(entry.getName().matches("ppt/slides/slide\\d+\\.xml"))names.add(entry.getName());
      Collections.sort(names);zip.closeEntry();
      for(String name:names){
      ZipInputStream slide=zip(context,uri);ZipEntry slideEntry;
      while((slideEntry=slide.getNextEntry())!=null){
        if(!slideEntry.getName().equals(name))continue;
          out.append("\n[幻灯片 ").append(out.length()/1200+1).append("]\n");
          XmlPullParser parser=android.util.Xml.newPullParser();parser.setInput(slide,null);
          for(int type=parser.getEventType();type!=XmlPullParser.END_DOCUMENT;type=parser.next()){
            String tag=parser.getName()==null?"":parser.getName();
            if(type==XmlPullParser.START_TAG&&tag.equals("t"))out.append(text(parser)).append('\n');
          }
          break;
        }
        slide.close();if(out.length()>TEXT_LIMIT)break;
      }
    }
    return clip(out.toString());
  }

  private static String text(XmlPullParser parser){try{return parser.getText()==null?"":parser.getText();}catch(Exception ignored){return "";}}
  private static ZipInputStream zip(Context context,Uri uri)throws Exception{return new ZipInputStream(context.getContentResolver().openInputStream(uri));}
  private static int columnIndex(String reference,int fallback){
    if(reference==null||reference.isEmpty())return fallback;
    int value=0;for(char c:reference.toCharArray()){if(Character.isLetter(c))value=value*26+Character.toUpperCase(c)-'A'+1;else break;}
    return Math.max(0,value-1);
  }
  private static String mediaDetail(Context context,Uri uri){
    try(MediaMetadataRetriever retriever=new MediaMetadataRetriever()){
      retriever.setDataSource(context,uri);
      long duration=parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
      String title=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
      String size=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)+"×"+retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
      return "时长 "+(duration/1000)+" 秒"+(title==null||title.isBlank()?"":" · 标题 "+title)+" · "+size;
    }catch(Exception ignored){return "媒体元数据不可用";}
  }
  private static long parseLong(String value){try{return value==null?0:Long.parseLong(value);}catch(Exception ignored){return 0;}}
  private static String kind(String mime,String name){
    mime=mime==null?"":mime.toLowerCase(Locale.ROOT);
    if(mime.startsWith("image/"))return "image";if(mime.equals("application/pdf")||name.endsWith(".pdf"))return "pdf";
    if(mime.startsWith("audio/"))return "audio";if(mime.startsWith("video/"))return "video";
    if(mime.startsWith("text/")||name.endsWith(".txt")||name.endsWith(".md")||name.endsWith(".csv")||name.endsWith(".json"))return "text";
    return "file";
  }
  private static boolean isCode(String name){return name.endsWith(".js")||name.endsWith(".ts")||name.endsWith(".py")||name.endsWith(".java")||name.endsWith(".kt")||name.endsWith(".swift")||name.endsWith(".m")||name.endsWith(".html")||name.endsWith(".css");}
  private static String safeMime(Context context,Uri uri){String value=context.getContentResolver().getType(uri);return value==null||value.isBlank()?"application/octet-stream":value;}
  private static String queryName(Context context,Uri uri){
    try(android.database.Cursor cursor=context.getContentResolver().query(uri,null,null,null,null)){
      if(cursor!=null&&cursor.moveToFirst()){int at=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(at>=0&&!cursor.isNull(at))return cursor.getString(at);}
    }catch(Exception ignored){}
    String last=uri.getLastPathSegment();return last==null?"文件":last.substring(Math.max(0,last.lastIndexOf('/')+1));
  }
  private static long querySize(Context context,Uri uri){
    try(android.database.Cursor cursor=context.getContentResolver().query(uri,null,null,null,null)){
      if(cursor!=null&&cursor.moveToFirst()){int at=cursor.getColumnIndex(OpenableColumns.SIZE);if(at>=0&&!cursor.isNull(at))return cursor.getLong(at);}
    }catch(Exception ignored){}
    return 0;
  }
  private static String humanSize(long size){if(size<1024)return size+" B";if(size<1024*1024)return String.format(Locale.ROOT,"%.1f KB",size/1024d);return String.format(Locale.ROOT,"%.1f MB",size/1024d/1024d);}
  private static String clip(String value){value=value.replace('\0',' ').trim();return value.length()>TEXT_LIMIT?value.substring(0,TEXT_LIMIT)+"\n…（已截断）":value;}
  private LocalFiles(){}
}
