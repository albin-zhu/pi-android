package dev.zfold.assistant;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Build;
import android.view.*;
import android.widget.*;
import java.util.List;

/** Shared native design tokens and accessible controls. */
public final class Ui {
  public static int dp(Context c,float n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
  public static final class Theme {
    public final boolean dark;
    public final int page,surface,card,field,ink,muted,line,accent,accentSoft,onAccent,error,success;
    public Theme(Context c){dark=(c.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
      page=dark?0xff101012:0xfff6f5f3;surface=dark?0xff1c1c1e:0xffffffff;card=dark?0xff242426:0xffffffff;field=dark?0xff2c2c2e:0xffeeedeb;
      ink=dark?0xfff2f2f7:0xff1c1c1e;muted=dark?0xffaeaeb2:0xff636366;line=dark?0xff38383a:0xffdeddda;
      accent=dark?0xffffa45c:0xffac4c12;accentSoft=dark?0xff38271d:0xffffeee2;onAccent=dark?0xff1c1c1e:0xffffffff;
      error=dark?0xffff9696:0xffd0342c;success=dark?0xff77c79a:0xff1e7c4f;
    }
  }
  public static GradientDrawable shape(Context c,int fill,int radius,int stroke){return shape(c,fill,radius,stroke,0);}
  public static GradientDrawable shape(Context c,int fill,int radius,int stroke,int color){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(c,radius));if(stroke!=0)d.setStroke(dp(c,1),color==0?(fill&0x00ffffff)|0x22000000:color);return d;}
  static GradientDrawable card(Context c,int fill,int radius,int stroke){
    GradientDrawable d=shape(c,fill,radius,stroke);float r=dp(c,radius);
    d.setCornerRadii(new float[]{r,r,r,r,r,r,r,r});return d;
  }
  static GradientDrawable sheet(Context c,int fill,int radius,int stroke){GradientDrawable d=shape(c,fill,0,stroke);float r=dp(c,radius);d.setCornerRadii(new float[]{r,r,r,r,0,0,0,0});return d;}
  static GradientDrawable circle(Context c,int fill,int stroke){GradientDrawable d=new GradientDrawable();d.setShape(GradientDrawable.OVAL);d.setColor(fill);if(stroke!=0)d.setStroke(dp(c,1),stroke);return d;}
  public static Drawable ripple(Context c,int fill,int radius,int foreground){return new RippleDrawable(ColorStateList.valueOf((foreground&0x00ffffff)|0x18000000),shape(c,fill,radius,0),shape(c,Color.WHITE,radius,0));}
  public static Drawable icon(Context c,int resource,int color){Drawable d=c.getDrawable(resource).mutate();d.setTint(color);return d;}
  static Bitmap thumbnail(String encoded,int maxSide){
    if(encoded==null||encoded.isEmpty())return null;
    try{byte[] bytes=android.util.Base64.decode(encoded,android.util.Base64.DEFAULT);BitmapFactory.Options size=new BitmapFactory.Options();size.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,size);if(size.outWidth<=0||size.outHeight<=0)return null;
      BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=Math.max(1,(int)Math.ceil(Math.max(size.outWidth,size.outHeight)/(double)maxSide));return BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
    }catch(RuntimeException ignored){return null;}
  }
  static View agentMark(Context c,String avatar,int size){
    Bitmap bitmap=thumbnail(avatar,size);
    if(bitmap!=null){
      ImageView image=new ImageView(c);image.setImageBitmap(bitmap);image.setScaleType(ImageView.ScaleType.CENTER_CROP);
      GradientDrawable circle=new GradientDrawable();circle.setShape(GradientDrawable.OVAL);circle.setColor(0xffffffff);
      image.setBackground(circle);image.setClipToOutline(true);image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      return image;
    }
    return new Mark(c);
  }
  public static TextView text(Context c,String text,int size,int color){TextView t=new TextView(c);t.setText(text);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);t.setFontFeatureSettings("kern");return t;}
  static boolean motion(){return ValueAnimator.areAnimatorsEnabled();}
  static String errorMessage(String raw){
    if(raw.contains("401")||raw.contains("403"))return "模型服务没有通过鉴权，请检查 API Key 和服务权限。";
    if(raw.contains("429"))return "模型服务暂时繁忙，请稍后重试。";
    if(raw.toLowerCase(java.util.Locale.ROOT).contains("fetch failed")||raw.contains("ECONNREFUSED"))return "暂时连接不到模型服务，请检查网络和接口地址。";
    int begin=raw.indexOf('{');if(begin>=0)try{org.json.JSONObject object=new org.json.JSONObject(raw.substring(begin));org.json.JSONObject nested=object.optJSONObject("error");String message=(nested==null?object:nested).optString("message");if(!message.isEmpty())return message;}catch(Exception ignored){}
    return raw;
  }
  static void arrive(View v){if(!motion())return;v.animate().cancel();v.setAlpha(0);v.setTranslationY(dp(v.getContext(),10));v.animate().alpha(1).translationY(0).setDuration(180).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();}
  public static void click(View v){v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);}
  public static final class Action extends LinearLayout {
    final ImageView image;final TextView label;int tint;private boolean compact;private boolean compactApplied;
    public Action(Context c,int resource,String text,String description,int foreground,int fill,Runnable action){super(c);tint=foreground;setOrientation(HORIZONTAL);setGravity(Gravity.CENTER);setMinimumHeight(dp(c,48));setMinimumWidth(dp(c,48));setPadding(dp(c,text.isEmpty()?12:14),dp(c,10),dp(c,text.isEmpty()?12:14),dp(c,10));
      image=new ImageView(c);image.setImageDrawable(icon(c,resource,foreground));image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);addView(image,new LinearLayout.LayoutParams(dp(c,21),dp(c,21)));
      label=Ui.text(c,text,13,foreground);label.setTypeface(Typeface.create("sans-serif-medium",0));label.setSingleLine(true);label.setEllipsize(android.text.TextUtils.TruncateAt.END);label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.leftMargin=dp(c,7);addView(label,lp);label.setVisibility(text.isEmpty()?GONE:VISIBLE);
      setContentDescription(description);setBackground(ripple(c,fill,14,foreground));setClickable(true);setFocusable(true);setOnClickListener(v->{if(isEnabled()){click(v);action.run();}});
    }
    @Override public void setPressed(boolean pressed){
      super.setPressed(pressed);
      // Immediate down feedback; native click cancellation remains authoritative.
      if(image!=null)image.setAlpha(pressed?.65f:1f);if(label!=null)label.setAlpha(pressed?.65f:1f);
    }
    void setIcon(int resource){image.setImageDrawable(icon(getContext(),resource,tint));}
    void setText(String text){if(!text.contentEquals(label.getText()))label.setText(text);label.setVisibility(compact||text.isEmpty()?GONE:VISIBLE);if(!text.isEmpty())setContentDescription(text);}
    public void setCompact(boolean value){
      if(compactApplied&&compact==value)return;compact=value;compactApplied=true;
      boolean hasText=!label.getText().toString().isEmpty();
      label.setVisibility(value||!hasText?GONE:VISIBLE);
      int horizontal=dp(getContext(),value?5:14);
      setPadding(horizontal,dp(getContext(),value?6:10),horizontal,dp(getContext(),value?6:10));
      setMinimumWidth(dp(getContext(),48));
      LinearLayout.LayoutParams imageParams=(LinearLayout.LayoutParams)image.getLayoutParams();
      imageParams.width=dp(getContext(),value?18:21);imageParams.height=imageParams.width;
      image.setLayoutParams(imageParams);
      if(getLayoutParams()!=null)requestLayout();
    }
    @Override public void setEnabled(boolean enabled){super.setEnabled(enabled);setAlpha(enabled?1f:.36f);}
    @Override public CharSequence getAccessibilityClassName(){return Button.class.getName();}
  }
  static final class Mark extends View {
    final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);final Theme theme;
    Mark(Context c){super(c);theme=new Theme(c);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    @Override protected void onDraw(Canvas c){float w=getWidth(),h=getHeight();p.setColor(theme.accentSoft);p.setStyle(Paint.Style.FILL);c.drawRoundRect(0,0,w,h,w*.34f,h*.34f,p);p.setColor(theme.accent);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(w*.065f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);Path path=new Path();path.moveTo(w*.32f,h*.32f);path.lineTo(w*.68f,h*.32f);path.lineTo(w*.32f,h*.68f);path.lineTo(w*.68f,h*.68f);c.drawPath(path,p);c.drawLine(w*.5f,h*.24f,w*.5f,h*.76f,p);}
  }
  static final class AgentAvatar extends FrameLayout {
    private final PiRuntime runtime;private final int size;
    AgentAvatar(Context context,PiRuntime runtime,int size){super(context);this.runtime=runtime;this.size=size;update();}
    void update(){
      removeAllViews();
      View mark=agentMark(getContext(),runtime.agentAvatar,dp(getContext(),size));
      addView(mark,new LayoutParams(dp(getContext(),size),dp(getContext(),size)));
    }
  }
}
