package dev.zfold.assistant;

import android.app.Activity;
import android.content.*;
import android.graphics.*;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.ArrayList;

public final class CircleOverlay {
  static View captureView(){return bubble;}
  static volatile String selectedImage;
  static View bubble;static WindowManager manager;static boolean accessibilityBubble;
  static int bubbleX=20,bubbleY=280;
  interface Selected {void done(String image);}
  static boolean hasBubble(){return bubble!=null;}
  static void hideBubble(){VoiceStatus.stopListening(null);if(bubble!=null){try{manager.removeView(bubble);}catch(IllegalArgumentException ignored){}bubble=null;manager=null;accessibilityBubble=false;}}
  static void showBubble(Context c){if(hasBubble())hideBubble();else ensureBubble(c);}
  static void ensureBubble(Context c){
    if(bubble!=null)return;
    PhoneAccessibility service=PhoneAccessibility.instance;
    Context context=service!=null?service:c.getApplicationContext();
    if(service==null&&!Settings.canDrawOverlays(context)){Toast.makeText(context,"请先允许 Zfold 显示悬浮窗",Toast.LENGTH_LONG).show();return;}
    WindowManager wm=(WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
    Ui.Bubble view=new Ui.Bubble(context);view.setBusy(PiRuntime.get(context).busy);
    int size=Math.round(52*context.getResources().getDisplayMetrics().density);
    int type=service!=null?WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY:WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    WindowManager.LayoutParams p=new WindowManager.LayoutParams(size,size,type,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);p.gravity=Gravity.TOP|Gravity.RIGHT;p.y=bubbleY;p.x=bubbleX;
    view.setOnTouchListener(new View.OnTouchListener(){float sx,sy;int ox,oy;boolean moved;long down;
      public boolean onTouch(View v,android.view.MotionEvent e){if(e.getAction()==0){sx=e.getRawX();sy=e.getRawY();ox=p.x;oy=p.y;moved=false;down=e.getEventTime();return true;}if(e.getAction()==2){float dx=e.getRawX()-sx,dy=e.getRawY()-sy;if(Math.abs(dx)+Math.abs(dy)>12)moved=true;p.x=Math.max(0,ox-(int)dx);p.y=Math.max(0,oy+(int)dy);bubbleX=p.x;bubbleY=p.y;wm.updateViewLayout(view,p);return true;}if(e.getAction()==1){if(!moved){
        boolean busy=PiRuntime.get(context).busy;
        if(busy&&e.getEventTime()-down>=500){PiRuntime.get(context).send(PiRuntime.json("type","stop"));PhoneHud.show(context,"正在停止手机操作");}
        else{hideBubble();if(!busy&&e.getEventTime()-down>=500)begin(context,data->AssistantOverlay.show(context,data));else AssistantOverlay.show(context);}
      }return true;}return true;}});
    try{
      wm.addView(view,p);manager=wm;bubble=view;accessibilityBubble=service!=null;
      VoiceStatus.listen((state,message,previous)->{
        view.setVoiceState(state,message);
        String label;
        if(state==VoiceStatus.State.LISTENING)label="正在听";
        else if(state==VoiceStatus.State.THINKING)label="思考中";
        else if(state==VoiceStatus.State.SPEAKING)label="正在播报";
        else if(state==VoiceStatus.State.TASK)label="正在操作手机";
        else if(state==VoiceStatus.State.ERROR)label="语音需要处理";
        else label="语音已结束";
        Toast.makeText(context,label,Toast.LENGTH_SHORT).show();
      });
    }catch(RuntimeException error){Toast.makeText(context,"悬浮入口未能显示，请检查手机能力",Toast.LENGTH_LONG).show();}
  }
  static void setBusy(boolean busy){if(bubble instanceof Ui.Bubble)((Ui.Bubble)bubble).setBusy(busy);}
  static void accessibilityConnected(Context context){if(bubble!=null&&!accessibilityBubble){hideBubble();ensureBubble(context);}}
  static void accessibilityDisconnected(Context context){if(accessibilityBubble){hideBubble();if(Settings.canDrawOverlays(context))ensureBubble(context);}}
  static void begin(Context context){
    begin(context,data->{selectedImage=data;context.startActivity(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));});
  }
  static void begin(Context context,Selected selected){
    if(!Settings.canDrawOverlays(context)){Toast.makeText(context,"请先在手机能力中允许悬浮窗",Toast.LENGTH_LONG).show();return;}
    PhoneAccessibility service=PhoneAccessibility.instance;if(service==null){Toast.makeText(context,"请先开启 Zfold 无障碍服务",Toast.LENGTH_LONG).show();return;}
    if(context instanceof Activity)((Activity)context).moveTaskToBack(true);
    new Handler(Looper.getMainLooper()).postDelayed(()->service.capture((bitmap,error)->{
      if(error!=null){Toast.makeText(context,error,Toast.LENGTH_LONG).show();return;}
      showSelection(context,bitmap,selected);
    }),350);
  }
  static void showSelection(Context context,Bitmap bitmap,Selected selected){
    Context app=context.getApplicationContext();
    if(!Settings.canDrawOverlays(app)){bitmap.recycle();Toast.makeText(app,"请先允许悬浮窗",Toast.LENGTH_LONG).show();return;}
    WindowManager wm=(WindowManager)app.getSystemService(Context.WINDOW_SERVICE);
    Selection view=new Selection(app,bitmap,wm,selected);WindowManager.LayoutParams p=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;wm.addView(view,p);
  }
  static class Selection extends FrameLayout {
    final Bitmap screenshot;final WindowManager wm;final Selected selected;Runnable dismissed=()->{};
    final RectF bounds=new RectF(),imageRect=new RectF();final Paint paint=new Paint(3);final Path trace=new Path();final ArrayList<PointF> points=new ArrayList<>();
    final View canvasView;final TextView instruction;Ui.Action accept,reset;boolean drawing,valid;
    int dp(float value){return Ui.dp(getContext(),value);}
    void remove(){if(wm!=null)wm.removeView(this);else dismissed.run();}
    void cancel(){remove();if(!screenshot.isRecycled())screenshot.recycle();}
    Selection(Context context,Bitmap bitmap,WindowManager manager,Selected selected){
      super(context);this.screenshot=bitmap;wm=manager;this.selected=selected;setFocusableInTouchMode(true);requestFocus();
      canvasView=new View(context){
        @Override protected void onDraw(Canvas c){drawSelection(c);}
        @Override public boolean onTouchEvent(MotionEvent event){return selectTouch(event);}
        @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){float scale=Math.min(w/(float)screenshot.getWidth(),h/(float)screenshot.getHeight());float bw=screenshot.getWidth()*scale,bh=screenshot.getHeight()*scale;imageRect.set((w-bw)/2,(h-bh)/2,(w+bw)/2,(h+bh)/2);if(oldw!=0&&(w!=oldw||h!=oldh)){valid=false;bounds.setEmpty();trace.reset();points.clear();accept.setEnabled(false);}}
      };addView(canvasView,new FrameLayout.LayoutParams(-1,-1));
      LinearLayout head=new LinearLayout(context);head.setGravity(Gravity.CENTER_VERTICAL);head.setPadding(dp(14),dp(8),dp(8),dp(8));head.setBackground(Ui.shape(context,0xee202820,22,0));
      LinearLayout labels=new LinearLayout(context);labels.setOrientation(LinearLayout.VERTICAL);instruction=Ui.text(context,"沿着内容画一圈",15,Color.WHITE);instruction.setTypeface(Typeface.create("sans-serif-medium",0));labels.addView(instruction);TextView help=Ui.text(context,"画好后检查边界，再交给助手",12,0xffc7cfc1);help.setPadding(0,dp(5),0,0);labels.addView(help);head.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
      head.addView(new Ui.Action(context,R.drawable.ic_x,"","取消圈选",Color.WHITE,Color.TRANSPARENT,this::cancel),new LinearLayout.LayoutParams(dp(48),dp(48)));
      FrameLayout.LayoutParams hp=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);hp.leftMargin=hp.rightMargin=dp(16);hp.topMargin=dp(48);addView(head,hp);
      LinearLayout toolbar=new LinearLayout(context);toolbar.setPadding(dp(6),dp(6),dp(6),dp(6));toolbar.setGravity(Gravity.CENTER);toolbar.setBackground(Ui.shape(context,0xee202820,22,0));
      reset=new Ui.Action(context,R.drawable.ic_arrow_clockwise,"重选","重新圈选",Color.WHITE,Color.TRANSPARENT,()->{valid=false;bounds.setEmpty();trace.reset();points.clear();accept.setEnabled(false);instruction.setText("沿着内容画一圈");canvasView.invalidate();});
      toolbar.addView(reset,new LinearLayout.LayoutParams(0,-2,1));Ui.Theme theme=new Ui.Theme(context);
      accept=new Ui.Action(context,R.drawable.ic_check,"使用选区","使用当前选区",theme.onAccent,theme.accent,this::commit);accept.setEnabled(false);toolbar.addView(accept,new LinearLayout.LayoutParams(0,-2,1));
      FrameLayout.LayoutParams tp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);tp.leftMargin=tp.rightMargin=dp(20);tp.bottomMargin=dp(32);addView(toolbar,tp);
      setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars());int statusId=getResources().getIdentifier("status_bar_height","dimen","android");int topInset=Math.max(bars.top,statusId>0?getResources().getDimensionPixelSize(statusId):dp(24));hp.topMargin=topInset+dp(12);tp.bottomMargin=bars.bottom+dp(16);head.setLayoutParams(hp);toolbar.setLayoutParams(tp);return insets;});
    }
    void drawSelection(Canvas c){
      c.drawColor(0xff171c18);paint.setStyle(Paint.Style.FILL);paint.setAlpha(255);c.drawBitmap(screenshot,null,imageRect,paint);
      Path mask=new Path();mask.setFillType(Path.FillType.EVEN_ODD);mask.addRect(0,0,getWidth(),getHeight(),Path.Direction.CW);if(valid||drawing)mask.addPath(trace);paint.setColor(0x99000000);c.drawPath(mask,paint);
      if(valid||drawing){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setStrokeJoin(Paint.Join.ROUND);paint.setStrokeCap(Paint.Cap.ROUND);paint.setColor(0xffffbb93);c.drawPath(trace,paint);paint.setStyle(Paint.Style.FILL);}
    }
    void point(float x,float y){
      if(!points.isEmpty()){PointF previous=points.get(points.size()-1);if(Math.hypot(x-previous.x,y-previous.y)<dp(1.5f))return;trace.lineTo(x,y);bounds.union(x,y);}
      else{trace.moveTo(x,y);bounds.set(x,y,x,y);}points.add(new PointF(x,y));
    }
    float area(){float sum=0;for(int i=0;i<points.size();i++){PointF a=points.get(i),b=points.get((i+1)%points.size());sum+=a.x*b.y-b.x*a.y;}return Math.abs(sum)*.5f;}
    boolean selectTouch(MotionEvent event){
      float x=Math.max(imageRect.left,Math.min(imageRect.right,event.getX())),y=Math.max(imageRect.top,Math.min(imageRect.bottom,event.getY()));
      switch(event.getActionMasked()){
        case MotionEvent.ACTION_DOWN:drawing=true;valid=false;trace.reset();points.clear();point(x,y);accept.setEnabled(false);break;
        case MotionEvent.ACTION_MOVE:if(drawing){for(int i=0;i<event.getHistorySize();i++)point(Math.max(imageRect.left,Math.min(imageRect.right,event.getHistoricalX(i))),Math.max(imageRect.top,Math.min(imageRect.bottom,event.getHistoricalY(i))));point(x,y);}break;
        case MotionEvent.ACTION_UP:if(drawing){point(x,y);drawing=false;valid=points.size()>=3&&bounds.width()>=dp(12)&&bounds.height()>=dp(12)&&area()>=dp(18)*dp(18);if(valid)trace.close();accept.setEnabled(valid);instruction.setText(valid?"这个形状，交给助手？":"范围太小，请重新圈选");if(valid)performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);}break;
        case MotionEvent.ACTION_CANCEL:drawing=false;valid=false;points.clear();trace.reset();accept.setEnabled(false);break;
      }canvasView.invalidate();return true;
    }
    static Bitmap maskedCrop(Bitmap screenshot,Path trace,RectF bounds,RectF imageRect){
      float sx=screenshot.getWidth()/imageRect.width(),sy=screenshot.getHeight()/imageRect.height();
      int left=Math.max(0,(int)Math.floor((bounds.left-imageRect.left)*sx)),top=Math.max(0,(int)Math.floor((bounds.top-imageRect.top)*sy));
      int right=Math.min(screenshot.getWidth(),(int)Math.ceil((bounds.right-imageRect.left)*sx)),bottom=Math.min(screenshot.getHeight(),(int)Math.ceil((bounds.bottom-imageRect.top)*sy));
      int width=Math.max(1,right-left),height=Math.max(1,bottom-top);
      Bitmap output=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(output);
      Path shape=new Path(trace);Matrix toCrop=new Matrix();toCrop.setRectToRect(bounds,new RectF(0,0,width,height),Matrix.ScaleToFit.FILL);shape.transform(toCrop);
      Paint mask=new Paint(Paint.ANTI_ALIAS_FLAG);mask.setColor(Color.WHITE);mask.setStyle(Paint.Style.FILL);canvas.drawPath(shape,mask);
      mask.setFilterBitmap(true);mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
      canvas.drawBitmap(screenshot,new Rect(left,top,right,bottom),new Rect(0,0,width,height),mask);mask.setXfermode(null);
      return output;
    }
    void commit(){
      if(!valid)return;Bitmap crop=maskedCrop(screenshot,trace,bounds,imageRect);String data=PhoneAccessibility.encode(crop);remove();if(!screenshot.isRecycled())screenshot.recycle();selected.done(data);
    }
    @Override public boolean onKeyDown(int key,android.view.KeyEvent event){if(key==android.view.KeyEvent.KEYCODE_BACK){cancel();return true;}return super.onKeyDown(key,event);}
  }
}
