package dev.zfold.assistant;

import android.app.*;
import android.graphics.Color;
import android.os.*;
import android.speech.tts.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;

/** One settings entry for independent ASR/TTS routes, credentials and a bounded preview. */
final class SpeechSettings {
  static AlertDialog show(Activity activity,VoiceTurnController voice,Runnable localSettings){
    SpeechConfig config=SpeechConfig.load(activity);Ui.Theme theme=new Ui.Theme(activity);
    LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);int gap=Ui.dp(activity,18);body.setPadding(gap,Ui.dp(activity,8),gap,Ui.dp(activity,12));
    TextView current=Ui.text(activity,voice.providerStatus(),13,theme.muted);body.addView(current);
    Spinner asr=choice(activity,body,"语音识别 · ASR",new String[]{"系统识别 · 优先本机模型","豆包 ASR 2.0 · 在线"},config.cloudAsr?1:0);
    Spinner tts=choice(activity,body,"语音合成 · TTS",new String[]{"系统播报 · 优先离线中文音色","豆包 TTS 2.0 · 在线"},config.cloudTts?1:0);
    int selected=0;for(int i=0;i<SpeechConfig.VOICES.length;i++)if(SpeechConfig.VOICES[i].equals(config.speaker))selected=i;
    Spinner speaker=choice(activity,body,"豆包音色",SpeechConfig.VOICE_NAMES,selected);
    TextView keyLabel=Ui.text(activity,"豆包语音 API Key",13,theme.ink);keyLabel.setPadding(0,gap,0,Ui.dp(activity,6));body.addView(keyLabel);
    EditText key=new EditText(activity);key.setSingleLine(true);key.setTextSize(14);key.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
    key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);key.setHint(config.hasKey()?"已保存 · 留空保留，输入可替换":"填写豆包语音控制台的 API Key");key.setContentDescription("豆包语音 API Key");body.addView(key,new LinearLayout.LayoutParams(-1,-2));
    TextView help=Ui.text(activity,"在线识别会发送麦克风音频，在线播报会发送需朗读的文本。可混用本机识别和在线音色。保存后结束当前语音通话，Agent 任务继续；下次开始语音时生效。",12,theme.muted);help.setPadding(0,Ui.dp(activity,12),0,Ui.dp(activity,8));help.setLineSpacing(Ui.dp(activity,3),1);body.addView(help);
    TextView status=Ui.text(activity,"",12,theme.muted);body.addView(status);
    Button local=new Button(activity);local.setAllCaps(false);local.setText("本机模型、唤醒与插话设置");body.addView(local);
    ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(false);scroll.addView(body);
    AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("语音服务").setView(scroll).setNegativeButton("关闭",null).setNeutralButton("保存并试听",null).setPositiveButton("保存",null).create();
    final CloudTtsPlayer[] cloud={null};final TextToSpeech[] system={null};final int[] preview={0};final boolean[] wakeHeld={false};Object outputOwner=new Object();Handler main=new Handler(Looper.getMainLooper());
    Runnable stopPreview=()->{preview[0]++;if(cloud[0]!=null){cloud[0].close();cloud[0]=null;}if(system[0]!=null){system[0].stop();system[0].shutdown();system[0]=null;}if(wakeHeld[0]){wakeHeld[0]=false;VoiceWakeService.releaseOutput(activity,outputOwner);}};
    java.util.function.Consumer<Boolean> save=audition->{
      stopPreview.run();
      try{SpeechConfig.save(activity,asr.getSelectedItemPosition()==1,tts.getSelectedItemPosition()==1,speaker.getSelectedItemPosition(),key.getText().toString());}
      catch(Exception error){status.setText("保存失败：检查 API Key 和配置文件");return;}
      key.setText("");key.setHint("已保存 · 留空保留，输入可替换");voice.stopSession(false);current.setText(voice.providerStatus());status.setText("已保存，下次开始语音时生效");
      if(!audition)return;
      VoiceWakeService.suspendOutput(activity,outputOwner);wakeHeld[0]=true;
      int token=preview[0];String id="speech-preview",text="你好，我是橙橙。你先忙，我来处理，完成后告诉你。";
      status.setText("正在准备试听…");
      if(tts.getSelectedItemPosition()==1){
        cloud[0]=new CloudTtsPlayer(activity,new CloudTtsPlayer.Callback(){
          public void start(String value){if(token==preview[0])status.setText("豆包音色试听中 · 关闭即停止");}
          public void done(String value){if(token==preview[0]){status.setText("试听完成");stopPreview.run();}}
          public void error(String value,String reason){if(token==preview[0]){status.setText("试听失败："+reason);stopPreview.run();}}
        },false);cloud[0].begin(id);if(token==preview[0]&&cloud[0]!=null){cloud[0].text(id,text);cloud[0].finish(id);}
      }else{
        system[0]=new TextToSpeech(activity,result->main.post(()->{if(token!=preview[0]||system[0]==null)return;if(result!=TextToSpeech.SUCCESS){status.setText("系统播报不可用");stopPreview.run();return;}PhoneSpeech.selectOfflineChineseVoice(system[0]);system[0].speak(text,TextToSpeech.QUEUE_FLUSH,null,id);status.setText("系统音色试听中 · 关闭即停止");}));
        system[0].setOnUtteranceProgressListener(new UtteranceProgressListener(){public void onStart(String id){}public void onDone(String id){main.post(()->{if(token==preview[0]){status.setText("试听完成");stopPreview.run();}});}public void onError(String id){main.post(()->{if(token==preview[0]){status.setText("系统播报不可用");stopPreview.run();}});}});
      }
      main.postDelayed(()->{if(token==preview[0]){stopPreview.run();status.setText("试听已结束");}},20000);
    };
    dialog.setOnShowListener(d->{dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->save.accept(false));dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->save.accept(true));});
    dialog.setOnDismissListener(d->stopPreview.run());local.setOnClickListener(v->{dialog.dismiss();localSettings.run();});dialog.show();return dialog;
  }
  private static Spinner choice(Activity activity,LinearLayout parent,String title,String[] values,int selected){
    Ui.Theme theme=new Ui.Theme(activity);TextView label=Ui.text(activity,title,13,theme.ink);label.setPadding(0,Ui.dp(activity,16),0,Ui.dp(activity,5));parent.addView(label);
    Spinner spinner=new Spinner(activity);spinner.setContentDescription(title);ArrayAdapter<String> adapter=new ArrayAdapter<>(activity,android.R.layout.simple_spinner_item,values);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);spinner.setAdapter(adapter);spinner.setSelection(selected);parent.addView(spinner,new LinearLayout.LayoutParams(-1,Ui.dp(activity,48)));return spinner;
  }
}
