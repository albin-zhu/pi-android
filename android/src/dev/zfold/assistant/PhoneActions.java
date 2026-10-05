package dev.zfold.assistant;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.ContactsContract;
import android.telephony.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Explicit phone capabilities, with accurate dispatch/receipt results. */
final class PhoneActions {
  private static final Handler main=new Handler(Looper.getMainLooper());
  private static final Map<String,Request> pending=new ConcurrentHashMap<>();
  private static final ExecutorService files=Executors.newSingleThreadExecutor();
  static Request find(String id){return id==null?null:pending.get(id);}
  static void execute(Context context,String name,JSONObject args,String toolId,AndroidTools.Result callback){
    main.post(()->{Request request=new Request(context.getApplicationContext(),name,args,toolId,callback);pending.put(request.id,request);request.watch();request.perform();});
  }
  static String number(String value)throws Exception {
    String normalized=value==null?"":value.replaceAll("[\\s()\\-]","");
    if(!normalized.matches("\\+?[0-9]{3,20}"))throw new Exception("需要明确电话号码；不支持 USSD、分机控制码或 URI");return normalized;
  }
  static Intent callIntent(String number,boolean direct)throws Exception {return new Intent(direct?Intent.ACTION_CALL:Intent.ACTION_DIAL,Uri.fromParts("tel",number(number),null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);}
  static Intent smsIntent(String number,String text)throws Exception {return new Intent(Intent.ACTION_SENDTO,Uri.fromParts("smsto",number(number),null)).putExtra("sms_body",text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);}
  static JSONObject capabilities(Context context){PackageManager pm=context.getPackageManager();return PiRuntime.json("camera",pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),"telephony",pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY),"cameraPermission",granted(context,Manifest.permission.CAMERA),"callPermission",granted(context,Manifest.permission.CALL_PHONE),"smsPermission",granted(context,Manifest.permission.SEND_SMS),"contactsPermission",granted(context,Manifest.permission.READ_CONTACTS),"defaultSmsSubscriptionId",SubscriptionManager.getDefaultSmsSubscriptionId());}
  private static boolean granted(Context context,String permission){return context.checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED;}
  static final class Request {
    final Context context;final String name,id=UUID.randomUUID().toString(),toolId;final JSONObject args;final AndroidTools.Result callback;
    String permission,operationId;Activity activity;Runnable afterPermission;boolean done,dispatched;long started=SystemClock.elapsedRealtime();
    Request(Context c,String n,JSONObject a,String tool,AndroidTools.Result result){context=c;name=n;args=a;toolId=tool;callback=result;}
    boolean cancelled(){return done||NativeToolExecution.isCancelled(toolId);}
    void watch(){if(done)return;if(NativeToolExecution.isCancelled(toolId)){finish(PiRuntime.json("performed",dispatched,"status",dispatched?"unknown":"cancelled","operationId",operationId));return;}
      if(SystemClock.elapsedRealtime()-started>120000){finish(PiRuntime.json("performed",dispatched,"status",dispatched?"unknown":"timeout","operationId",operationId));return;}main.postDelayed(this::watch,250);}
    void finish(JSONObject result){if(done)return;done=true;pending.remove(id);callback.done(result,null);if(activity!=null&&!activity.isFinishing())activity.finish();}
    void fail(String error){finish(PiRuntime.json("performed",dispatched,"status",dispatched?"unknown":"failed","operationId",operationId,"error",error));}
    void need(String permission,Runnable next){
      if(cancelled())return;
      if(granted(context,permission)){next.run();return;}this.permission=permission;afterPermission=next;
      try{context.startActivity(new Intent(context,PhonePermissionActivity.class).putExtra("request",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}catch(Exception failure){fail("请先打开助手以授予权限："+permission);}
    }
    void permissionGranted(){if(!cancelled()&&afterPermission!=null){Runnable action=afterPermission;afterPermission=null;action.run();}}
    void perform(){
      if(cancelled())return;
      try{
        switch(name){
          case "phone_capabilities":finish(capabilities(context));return;
          case "phone_call":{
            String action=args.optString("action","dial"),number=number(args.getString("number"));
            if(!action.equals("dial")&&!action.equals("call"))throw new Exception("action 只能为 dial 或 call");
            Runnable launch=()->{if(cancelled())return;try{Intent intent=callIntent(number,action.equals("call"));if(intent.resolveActivity(context.getPackageManager())==null)throw new Exception("没有可处理电话的应用");
              if(action.equals("call")){VoiceTurnController voice=VoiceCoordinator.current();if(voice!=null)voice.stopSession(false);}context.startActivity(intent);dispatched=true;
              finish(PiRuntime.json("performed",true,"launched",true,"action",action,"number",number,"status",action.equals("call")?"call_dispatched":"dialer_opened","connected",false));
            }catch(Exception failure){fail(failure.getMessage());}};
            if(action.equals("call"))need(Manifest.permission.CALL_PHONE,launch);else launch.run();return;
          }
          case "contact_search":need(Manifest.permission.READ_CONTACTS,this::contacts);return;
          case "camera_capture":need(Manifest.permission.CAMERA,this::photo);return;
          case "phone_sms":sms();return;
          default:throw new Exception("不支持的手机能力");
        }
      }catch(Exception failure){fail(failure.getMessage());}
    }
    private void contacts(){files.execute(()->{
      try{if(cancelled())return;String query=args.optString("query").trim();if(query.isEmpty()||query.length()>100)throw new Exception("请输入 1–100 字的联系人姓名或号码");JSONArray rows=new JSONArray();
        Uri uri=Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,Uri.encode(query));
        String[] columns={ContactsContract.CommonDataKinds.Phone.CONTACT_ID,ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,ContactsContract.CommonDataKinds.Phone.NUMBER};
        try(Cursor cursor=context.getContentResolver().query(uri,columns,null,null,null)){if(cursor!=null)while(cursor.moveToNext()&&rows.length()<30)rows.put(PiRuntime.json("contactId",cursor.getString(0),"name",cursor.getString(1),"number",cursor.getString(2)));}
        main.post(()->finish(PiRuntime.json("contacts",rows,"query",query,"truncated",rows.length()==30)));
      }catch(Exception failure){main.post(()->fail(failure.getMessage()));}
    });}
    private void photo(){
      if(cancelled())return;CameraCoordinator camera=CameraCoordinator.get(context);String lens=args.optString("facing","back");
      if(!lens.equals("front")&&!lens.equals("back")){fail("facing 只能为 front 或 back");return;}
      if(camera.ready()&&camera.facing().equals(lens)){camera.capture(id,this::photoResult);return;}
      try{context.startActivity(new Intent(context,CameraCaptureActivity.class).putExtra("request",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}catch(Exception failure){fail("请先打开助手再使用相机");}
    }
    void photoResult(CameraFrame frame,String error){
      if(cancelled())return;if(error!=null){fail(error);return;}
      files.execute(()->{try{if(cancelled())return;File directory=new File(RuntimeEnvironment.workingDirectory(context,null),"photos");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("无法建立照片目录");
        File target=new File(directory,"photo-"+frame.id+".jpg");Files.write(target.toPath(),frame.jpeg);JSONObject result=frame.metadata();result.put("path",target.getCanonicalPath());result.put("image",frame.base64());result.put("mimeType","image/jpeg");result.put("performed",true);result.put("status","captured");main.post(()->finish(result));
      }catch(Exception failure){main.post(()->fail(failure.getMessage()));}});
    }
    private void sms()throws Exception {
      String action=args.optString("action","compose");
      if(action.equals("status")){JSONObject record=SmsReceiptStore.get(context,args.getString("operationId"));finish(record==null?PiRuntime.json("performed",false,"status","not_found"):SmsReceiptStore.publicReceipt(record));return;}
      if(action.equals("subscriptions")){need(Manifest.permission.READ_PHONE_STATE,()->{try{JSONArray rows=new JSONArray();SubscriptionManager manager=context.getSystemService(SubscriptionManager.class);List<SubscriptionInfo> active=manager.getActiveSubscriptionInfoList();if(active!=null)for(SubscriptionInfo info:active)rows.put(PiRuntime.json("subscriptionId",info.getSubscriptionId(),"slot",info.getSimSlotIndex()+1,"name",String.valueOf(info.getDisplayName())));finish(PiRuntime.json("subscriptions",rows,"defaultSubscriptionId",SubscriptionManager.getDefaultSmsSubscriptionId()));}catch(Exception failure){fail(failure.getMessage());}});return;}
      String number=number(args.getString("number")),text=args.getString("text");if(text.trim().isEmpty()||text.length()>2000)throw new Exception("短信正文需要 1–2000 个字符");
      if(action.equals("compose")){Intent intent=smsIntent(number,text);if(intent.resolveActivity(context.getPackageManager())==null)throw new Exception("没有短信应用");context.startActivity(intent);finish(PiRuntime.json("performed",true,"status","composer_opened","sent",false,"number",number));return;}
      if(!action.equals("send"))throw new Exception("不支持的短信动作");
      if(!args.has("operationId"))throw new Exception("send 必须提供稳定的 operationId，以便查询回执并避免重复发送");
      operationId=args.getString("operationId");int requested=args.optInt("subscriptionId",-1);String digest=SmsReceiptStore.digest(number,text,requested);
      JSONObject existing=SmsReceiptStore.get(context,operationId);
      if(existing!=null){if(!digest.equals(existing.optString("digest")))throw new Exception("operationId 已用于不同短信，本次未发送");JSONObject result=SmsReceiptStore.publicReceipt(existing);result.put("reused",true);finish(result);return;}
      need(Manifest.permission.SEND_SMS,()->sendSms(number,text,requested,digest));
    }
    private void sendSms(String number,String text,int requested,String digest){
      if(cancelled())return;
      try{
        int subscription=requested>=0?requested:SubscriptionManager.getDefaultSmsSubscriptionId();
        if(!SubscriptionManager.isValidSubscriptionId(subscription)){finish(PiRuntime.json("performed",false,"status","choose_sim","error","没有可用的默认短信 SIM，请选择 SIM 或使用 compose"));return;}
        SmsManager manager=SmsManager.getSmsManagerForSubscriptionId(subscription);ArrayList<String> parts=manager.divideMessage(text);if(parts.size()>10)throw new Exception("短信超过 10 个分段，请缩短正文");
        JSONObject old=SmsReceiptStore.get(context,operationId);if(old!=null){if(!digest.equals(old.optString("digest")))throw new Exception("operationId 冲突");finish(SmsReceiptStore.publicReceipt(old));return;}
        ArrayList<PendingIntent> sent=new ArrayList<>();
        for(int i=0;i<parts.size();i++){Intent receipt=new Intent(context,SmsSentReceiver.class).setAction("dev.zfold.assistant.SMS_SENT").setData(Uri.parse("zfold-sms://receipt/"+Uri.encode(operationId)+"/"+i)).putExtra("operationId",operationId).putExtra("part",i);sent.add(PendingIntent.getBroadcast(context,0,receipt,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));}
        if(cancelled())return;SmsReceiptStore.reserve(context,operationId,digest,number,subscription,parts.size());
        dispatched=true;manager.sendMultipartTextMessage(number,null,parts,sent,null);pollReceipt(SystemClock.elapsedRealtime()+30000);
      }catch(Exception failure){
        dispatched=dispatched&&!(failure instanceof SecurityException)&&!(failure instanceof IllegalArgumentException);
        try{SmsReceiptStore.failure(context,operationId,failure.getMessage(),dispatched);JSONObject receipt=SmsReceiptStore.get(context,operationId);if(receipt!=null){finish(SmsReceiptStore.publicReceipt(receipt));return;}}catch(Exception ignored){}
        fail(failure.getMessage());
      }
    }
    private void pollReceipt(long until){if(done)return;try{JSONObject record=SmsReceiptStore.get(context,operationId);if(record==null){fail("短信回执记录丢失");return;}
      if(!record.optString("status").equals("dispatching")||SystemClock.elapsedRealtime()>=until){JSONObject result=SmsReceiptStore.publicReceipt(record);if(result.optString("status").equals("dispatching"))result.put("status","unknown");finish(result);return;}
      main.postDelayed(()->pollReceipt(until),200);
    }catch(Exception failure){fail(failure.getMessage());}}
  }
}
