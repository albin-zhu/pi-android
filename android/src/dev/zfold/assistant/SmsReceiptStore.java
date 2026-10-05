package dev.zfold.assistant;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.*;

/** Durable SMS request receipts: a replay returns its receipt and never sends again. */
final class SmsReceiptStore {
  private static AtomicFile file(Context context,String id)throws IOException {
    if(id==null||!id.matches("[A-Za-z0-9_.:-]{1,96}"))throw new IOException("operationId 需要 1–96 个英文字母、数字或 . _ : -");
    File directory=new File(context.getNoBackupFilesDir(),"sms-receipts");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("无法建立短信回执目录");
    return new AtomicFile(new File(directory,id+".json"));
  }
  static String digest(String number,String text,int subscription)throws Exception {
    byte[] bytes=MessageDigest.getInstance("SHA-256").digest((number+"\n"+text+"\n"+subscription).getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format("%02x",b));return out.toString();
  }
  static synchronized JSONObject get(Context context,String id)throws Exception {
    AtomicFile file=file(context,id);if(!file.getBaseFile().exists())return null;
    return new JSONObject(new String(file.readFully(),StandardCharsets.UTF_8));
  }
  static synchronized JSONObject reserve(Context context,String id,String digest,String number,int subscription,int parts)throws Exception {
    JSONObject existing=get(context,id);
    if(existing!=null){if(!digest.equals(existing.optString("digest")))throw new IOException("operationId 已用于不同的短信；本次未发送");return existing;}
    JSONArray statuses=new JSONArray();for(int i=0;i<parts;i++)statuses.put(0);
    JSONObject record=PiRuntime.json("operationId",id,"digest",digest,"number",number,"subscriptionId",subscription,"parts",parts,"results",statuses,"status","dispatching","performed",true,"sent",false,"deliveryConfirmed",false,"createdAt",System.currentTimeMillis());
    write(context,id,record);return record;
  }
  static synchronized void part(Context context,String id,int index,int code)throws Exception {
    JSONObject record=get(context,id);if(record==null)return;JSONArray results=record.getJSONArray("results");
    if(index<0||index>=results.length()||results.optInt(index)!=0)return;
    // Encode success as 1 and failures as negative values; zero is unacknowledged.
    results.put(index,code==android.app.Activity.RESULT_OK?1:-Math.max(1,code));
    int success=0,failed=0;for(int i=0;i<results.length();i++){if(results.optInt(i)>0)success++;else if(results.optInt(i)<0)failed++;}
    String state=success==results.length()?"sent":failed+success==results.length()?(success>0?"partial":"failed"):"dispatching";
    record.put("status",state);record.put("sent",state.equals("sent"));record.put("sentParts",success);record.put("failedParts",failed);write(context,id,record);
  }
  static synchronized void failure(Context context,String id,String message,boolean dispatched)throws Exception {
    JSONObject record=get(context,id);if(record==null)return;record.put("status",dispatched?"unknown":"failed");record.put("performed",dispatched);record.put("error",message);write(context,id,record);
  }
  static JSONObject publicReceipt(JSONObject record)throws Exception {
    JSONObject copy=new JSONObject(record.toString());copy.remove("digest");
    if(copy.optString("status").equals("dispatching")&&System.currentTimeMillis()-copy.optLong("createdAt")>30_000)copy.put("status",copy.optInt("sentParts")>0?"partial_unknown":"unknown");
    return copy;
  }
  private static void write(Context context,String id,JSONObject record)throws Exception {
    AtomicFile file=file(context,id);FileOutputStream out=null;
    try{out=file.startWrite();out.write(record.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}catch(Exception error){if(out!=null)file.failWrite(out);throw error;}
  }
}
