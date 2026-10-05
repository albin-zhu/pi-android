package dev.zfold.assistant;

import android.content.*;

public final class SmsSentReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context context,Intent intent){
    if(!"dev.zfold.assistant.SMS_SENT".equals(intent.getAction()))return;
    try{SmsReceiptStore.part(context,intent.getStringExtra("operationId"),intent.getIntExtra("part",-1),getResultCode());}
    catch(Exception failure){android.util.Log.w("ZfoldSms","Unable to record SMS receipt",failure);}
  }
}
