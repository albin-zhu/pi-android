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
final class Ui {
  static int dp(Context c,float n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
  static final class Theme {
    final boolean dark;
    final int page,surface,card,field,ink,muted,line,accent,accentSoft,onAccent,error,success;
    Theme(Context c){dark=(c.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
      page=dark?0xff101012:0xfff6f5f3;surface=dark?0xff1c1c1e:0xffffffff;card=dark?0xff242426:0xffffffff;field=dark?0xff2c2c2e:0xffeeedeb;
      ink=dark?0xfff2f2f7:0xff1c1c1e;muted=dark?0xffaeaeb2:0xff636366;line=dark?0xff38383a:0xffdeddda;
      accent=dark?0xffffa45c:0xffac4c12;accentSoft=dark?0xff38271d:0xffffeee2;onAccent=dark?0xff1c1c1e:0xffffffff;
      error=dark?0xffff9696:0xffd0342c;success=dark?0xff77c79a:0xff1e7c4f;
    }
  }
  static GradientDrawable shape(Context c,int fill,int radius,int stroke){return shape(c,fill,radius,stroke,0);}
  static GradientDrawable shape(Context c,int fill,int radius,int stroke,int color){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(c,radius));if(stroke!=0)d.setStroke(dp(c,1),color==0?(fill&0x00ffffff)|0x22000000:color);return d;}
  static GradientDrawable card(Context c,int fill,int radius,int stroke){
    GradientDrawable d=shape(c,fill,radius,stroke);float r=dp(c,radius);
    d.setCornerRadii(new float[]{r,r,r,r,r,r,r,r});return d;
  }
  static GradientDrawable sheet(Context c,int fill,int radius,int stroke){GradientDrawable d=shape(c,fill,0,stroke);float r=dp(c,radius);d.setCornerRadii(new float[]{r,r,r,r,0,0,0,0});return d;}
  static GradientDrawable circle(Context c,int fill,int stroke){GradientDrawable d=new GradientDrawable();d.setShape(GradientDrawable.OVAL);d.setColor(fill);if(stroke!=0)d.setStroke(dp(c,1),stroke);return d;}
  static Drawable ripple(Context c,int fill,int radius,int foreground){return new RippleDrawable(ColorStateList.valueOf((foreground&0x00ffffff)|0x18000000),shape(c,fill,radius,0),shape(c,Color.WHITE,radius,0));}
  static Drawable icon(Context c,int resource,int color){Drawable d=c.getDrawable(resource).mutate();d.setTint(color);return d;}
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
  static TextView text(Context c,String text,int size,int color){TextView t=new TextView(c);t.setText(text);t.setTextSize(size);t.setTextColor(color);t.setIncludeFontPadding(false);t.setFontFeatureSettings("kern");return t;}
  static RichBlock rich(Context c,String value,int size,int color){return new RichBlock(c,size,color,value);}
  static CharSequence markdown(String raw){
    String value=raw==null?"":raw;
    String[] lines=value.replace("\r\n","\n").replace('\r','\n').split("\n",-1);
    StringBuilder html=new StringBuilder();boolean inCode=false;
    for(int i=0;i<lines.length;i++){
      String rawLine=lines[i];String line=android.text.Html.escapeHtml(rawLine);
      if(line.matches("^```.*$")){
        if(inCode)html.append("</tt>");
        else html.append(inCode?"":"<tt>");
        inCode=!inCode;if(i>0)html.append("<br>");continue;
      }
      if(inCode){html.append(line.replace(" ","&nbsp;")).append("<br>");continue;}
      line=inlineMarkdown(line);
      String trimmed=line.trim();
      if(trimmed.isEmpty()){html.append("<br>");continue;}
      if(trimmed.matches("^<b>(#+)\\s.*</b>$")||line.matches("^#+\\s+.*$")){
        int level=line.indexOf(' ');int hashes=line.startsWith("#")?line.substring(0,level).length():1;
        String text=line.substring(level).trim();
        if(hashes>=4)html.append("<b><small>").append(text).append("</small></b>");
        else if(hashes==3)html.append("<b>").append(text).append("</b>");
        else if(hashes==2)html.append("<b><big>").append(text).append("</big></b>");
        else html.append("<b><big><big>").append(text).append("</big></big></b>");
      }else if(trimmed.matches("^&gt;.*$")){
        html.append("<font color=\"#5f6b78\">▎ ").append(trimmed.substring(4).trim()).append("</font>");
      }else if(trimmed.matches("^[-*+]\\s+.*$")){
        html.append("•&nbsp;&nbsp;").append(trimmed.substring(trimmed.indexOf(' ')+1).trim());
      }else if(trimmed.matches("^\\d+\\.\\s+.*$")){
        html.append(trimmed.replaceFirst("^(\\d+)\\.\\s+","$1.&nbsp;&nbsp;"));
      }else if(trimmed.startsWith("|")&&trimmed.endsWith("|")&&i+1<lines.length&&lines[i+1].trim().matches("^\\|?[\\s:-]+\\|?[\\s:|-]*$")){
        List<String[]> table=new java.util.ArrayList<>();
        while(i<lines.length){
          String tableLine=lines[i].trim();if(!tableLine.startsWith("|")||!tableLine.endsWith("|"))break;
          if(i+1==table.size()+1&&tableLine.matches("^\\|?[\\s:-]+\\|?[\\s:|-]*$")){i++;continue;}
          table.add(rowCells(android.text.Html.escapeHtml(tableLine)));i++;
        }i--;
        int[] widths=new int[table.get(0).length];for(String[] row:table)for(int c=0;c<row.length&&c<widths.length;c++)widths[c]=Math.max(widths[c],row[c].length());
        for(int r=0;r<table.size();r++){
          String[] row=table.get(r);StringBuilder output=new StringBuilder();
          for(int c=0;c<widths.length;c++){String cell=c<row.length?row[c]:"";output.append(cell).append(" ".repeat(Math.max(1,widths[c]-cell.length())));if(c<widths.length-1)output.append("  │  ");}
          html.append("<tt>").append(output.toString().replace(" ","&nbsp;")).append("</tt>");
          if(r==0)html.append("<br><tt>").append("─".repeat(Math.max(18,totalWidth(widths)))).append("</tt>");
          if(r<table.size()-1)html.append("<br>");
        }
      }else html.append(trimmed);
      if(i<lines.length-1)html.append("<br>");
    }
    if(inCode)html.append("</tt>");
    CharSequence result=android.text.Html.fromHtml(html.toString(),android.text.Html.FROM_HTML_MODE_LEGACY);
    int end=result.length();while(end>0&&Character.isWhitespace(result.charAt(end-1)))end--;
    return result.subSequence(0,end);
  }
  private static int totalWidth(int[] widths){int total=0;for(int width:widths)total+=width+5;return Math.max(0,total-5);}
  private static String[] rowCells(String line){
    String value=line.trim();if(value.startsWith("|"))value=value.substring(1);if(value.endsWith("|"))value=value.substring(0,value.length()-1);
    return tableCells(value);
  }
  private static String[] tableCells(String value){
    java.util.List<String> parts=new java.util.ArrayList<>();StringBuilder cell=new StringBuilder();boolean escaped=false;
    for(int i=0;i<value.length();i++){
      char item=value.charAt(i);
      if(escaped){cell.append('\\').append(item);escaped=false;}
      else if(item=='\\')escaped=true;
      else if(item=='|'){parts.add(cell.toString().trim());cell.setLength(0);}
      else cell.append(item);
    }
    if(escaped)cell.append('\\');
    parts.add(cell.toString().trim());
    return parts.toArray(new String[0]);
  }
  private static String inlineMarkdown(String line){
    line=line.replaceAll("`([^`]+)`","<tt>$1</tt>");
    line=line.replaceAll("\\*\\*([^*\\n]+)\\*\\*","<b>$1</b>");
    line=line.replaceAll("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)","<i>$1</i>");
    line=line.replaceAll("\\[([^\\]]+)\\]\\(((?:https?://|file:///)[^\\s)]+)\\)","<a href=\"$2\">$1</a>");
    line=line.replaceAll("!\\[([^\\]]*)\\]\\((https?://[^\\s)]+)\\)","[图片 $1] $2");
    return line;
  }

  static final class RichBlock extends LinearLayout {
    private final TextView body;
    private String rawText="";
    String getText(){return rawText;}
    RichBlock(Context c,int size,int color,String value){
      super(c);setOrientation(VERTICAL);
      body=text(c,"",size,color);body.setTextIsSelectable(true);body.setLineSpacing(dp(c,5),1f);body.setHighlightColor(0x22000000);
      addView(body,new LayoutParams(-1,-2));setText(value);
    }
    void setText(String raw){
      rawText=raw==null?"":raw;
      removeAllViews();addView(body,new LayoutParams(-1,-2));
      appendMarkdown(this,raw,13,((body.getTextColors()!=null?body.getTextColors().getDefaultColor():0xff101418)));
    }
  }

  private static void appendMarkdown(LinearLayout parent,String raw,int tableSize,int color){
    String value=raw==null?"":raw;int at=tableStart(value);
    if(at<0){
      if(!value.trim().isEmpty())parent.addView(richText(parent.getContext(),value,color));
      return;
    }
    String prefix=at==0?"":value.substring(0,at);
    if(!prefix.trim().isEmpty())parent.addView(richText(parent.getContext(),prefix,color));
    String remainder=value.substring(at),tableText=remainder;
    int end=tableEnd(remainder);String table=remainder;
    if(end>=0&&end<remainder.length()){table=remainder.substring(0,end);tableText=table;}
    parent.addView(tableView(parent.getContext(),table,tableSize));
    String rest=end>=0?remainder.substring(Math.min(end,remainder.length())):"";
    appendMarkdown(parent,rest,tableSize,color);
  }

  private static TextView richText(Context c,String value,int color){
    TextView view=text(c,"",16,color);view.setTextColor(color);view.setLinkTextColor(new Theme(c).accent);view.setTextIsSelectable(true);bindLinks(view,markdown(value));
    view.setLineSpacing(dp(c,5),1f);view.setHighlightColor(0x22000000);
    return view;
  }

  private static void bindLinks(TextView view,CharSequence content){
    android.text.SpannableString text=new android.text.SpannableString(content);
    for(android.text.style.URLSpan span:text.getSpans(0,text.length(),android.text.style.URLSpan.class)){
      int start=text.getSpanStart(span),end=text.getSpanEnd(span);String target=span.getURL();text.removeSpan(span);
      text.setSpan(new android.text.style.ClickableSpan(){
        @Override public void onClick(View widget){
          try{
            if(target.startsWith("file:///"))ArtifactProvider.open(widget.getContext(),target);
            else if(target.startsWith("https://")||target.startsWith("http://"))widget.getContext().startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(target)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            else throw new IllegalArgumentException("不支持的链接");
          }catch(Exception error){Toast.makeText(widget.getContext(),"无法打开："+error.getMessage(),Toast.LENGTH_LONG).show();}
        }
      },start,end,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    view.setText(text);view.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
  }

  private static int tableStart(String raw){
    String[] lines=raw.split("\n",-1);
    int offset=0;
    for(int i=0;i<lines.length-1;i++){
      String line=lines[i].trim();
      if(line.startsWith("|")&&line.endsWith("|")&&isSeparator(lines[i+1].trim()))return offset+lines[i].indexOf(line);
      offset+=lines[i].length()+1;
    }
    return -1;
  }
  private static int tableEnd(String raw){
    String[] lines=raw.split("\n",-1);int index=0;
    for(int i=0;i<lines.length;i++){if(!isRow(lines[i]))return index;index+=lines[i].length()+1;}
    return raw.length();
  }
  private static boolean isRow(String value){String line=value.trim();return line.startsWith("|")&&line.endsWith("|")&&line.length()>1;}
  private static boolean isSeparator(String value){
    if(!isRow(value))return false;
    String cells=value.substring(1,value.length()-1);
    for(String cell:cells.split("\\|",-1)){String item=cell.trim();if(!item.isEmpty()&&!item.matches(":?-+:?"))return false;}
    return !cells.isEmpty();
  }
  private static View tableView(Context context,String raw,int tableSize){
    android.widget.HorizontalScrollView scroll=new android.widget.HorizontalScrollView(context);
    android.widget.TableLayout table=new android.widget.TableLayout(context);
    Theme theme=new Theme(context);
    scroll.setBackground(card(context,theme.surface,14,theme.line));
    scroll.setClipToOutline(true);scroll.setHorizontalScrollBarEnabled(true);scroll.setFillViewport(false);
    java.util.List<String[]> rows=new java.util.ArrayList<>();
    for(String line:raw.split("\n",-1)){
      String item=line.trim();
      if(!isRow(item)||isSeparator(item))continue;
      String value=item.substring(1,item.length()-1);
      String[] cells=tableCells(value);
      for(int i=0;i<cells.length;i++)cells[i]=inlineMarkdown(android.text.Html.escapeHtml(cells[i].trim()));
      rows.add(cells);
    }
    int columns=0;for(String[] row:rows)columns=Math.max(columns,row.length);
    for(int r=0;r<rows.size();r++){
      android.widget.TableRow row=new android.widget.TableRow(context);
      row.setBackgroundColor(r==0?theme.field:theme.surface);
      for(int col=0;col<columns;col++){
        String value=col<rows.get(r).length?rows.get(r)[col]:"";
        TextView cell=text(context,android.text.Html.fromHtml(value,android.text.Html.FROM_HTML_MODE_LEGACY).toString(),tableSize,r==0?theme.ink:theme.ink);
        cell.setTypeface(android.graphics.Typeface.create("sans-serif-medium",r==0?0:400));
        cell.setPadding(dp(context,r==0?12:14),dp(context,10),dp(context,r==0?12:14),dp(context,10));
        cell.setMinWidth(dp(context,64));cell.setMaxWidth(dp(context,270));cell.setLineSpacing(dp(context,2),1f);
        row.addView(cell,new android.widget.TableRow.LayoutParams(-2,-2));
      }
      table.addView(row,new android.widget.TableLayout.LayoutParams(-2,-2));
    }
    scroll.addView(table,new FrameLayout.LayoutParams(-2,-2));
    LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(context,9);scroll.setLayoutParams(params);
    return scroll;
  }
  static boolean motion(){return ValueAnimator.areAnimatorsEnabled();}
  static String errorMessage(String raw){
    if(raw.contains("401")||raw.contains("403"))return "模型服务没有通过鉴权，请检查 API Key 和服务权限。";
    if(raw.contains("429"))return "模型服务暂时繁忙，请稍后重试。";
    if(raw.toLowerCase(java.util.Locale.ROOT).contains("fetch failed")||raw.contains("ECONNREFUSED"))return "暂时连接不到模型服务，请检查网络和接口地址。";
    int begin=raw.indexOf('{');if(begin>=0)try{org.json.JSONObject object=new org.json.JSONObject(raw.substring(begin));org.json.JSONObject nested=object.optJSONObject("error");String message=(nested==null?object:nested).optString("message");if(!message.isEmpty())return message;}catch(Exception ignored){}
    return raw;
  }
  static void arrive(View v){if(!motion())return;v.animate().cancel();v.setAlpha(0);v.setTranslationY(dp(v.getContext(),10));v.animate().alpha(1).translationY(0).setDuration(180).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();}
  static void click(View v){v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);}
  static final class Action extends LinearLayout {
    final ImageView image;final TextView label;int tint;private boolean compact;private boolean compactApplied;
    Action(Context c,int resource,String text,String description,int foreground,int fill,Runnable action){super(c);tint=foreground;setOrientation(HORIZONTAL);setGravity(Gravity.CENTER);setMinimumHeight(dp(c,48));setMinimumWidth(dp(c,48));setPadding(dp(c,text.isEmpty()?12:14),dp(c,10),dp(c,text.isEmpty()?12:14),dp(c,10));
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
    void setCompact(boolean value){
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
  static final class Bubble extends FrameLayout {
    final ImageView icon;final StatusRing ring;
    Bubble(Context context){
      super(context);Theme t=new Theme(context);
      setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(t.accentSoft),circle(context,t.surface,t.line),circle(context,Color.WHITE,0)));setElevation(dp(context,12));setClipToOutline(false);
      ring=new StatusRing(context);addView(ring,new LayoutParams(-1,-1));
      icon=new ImageView(context);icon.setImageDrawable(icon(context,R.drawable.ic_microphone,t.muted));
      FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(dp(context,22),dp(context,22),Gravity.CENTER);addView(icon,lp);
      setContentDescription("Zfold 语音助手");setClickable(true);setFocusable(true);
    }
    void setBusy(boolean busy){VoiceStatus.task(busy);}
    void setVoiceState(VoiceStatus.State state,String message){
      Theme t=new Theme(getContext());int color=t.accent;
      if(state==VoiceStatus.State.TASK)color=t.error;
      if(state==VoiceStatus.State.SPEAKING)color=t.success;
      if(state==VoiceStatus.State.IDLE)color=t.muted;
      ring.setState(state,color);
      icon.setImageDrawable(icon(getContext(),state==VoiceStatus.State.TASK?R.drawable.ic_stop:R.drawable.ic_microphone,state==VoiceStatus.State.IDLE?t.muted:color));
      String description;
      if(state==VoiceStatus.State.LISTENING)description="Zfold 正在听，点按展开";
      else if(state==VoiceStatus.State.SPEAKING)description="Zfold 正在播报，点按展开";
      else if(state==VoiceStatus.State.THINKING)description="Zfold 正在思考，点按展开";
      else if(state==VoiceStatus.State.TASK)description="Zfold 正在操作手机，点按查看或停止";
      else if(state==VoiceStatus.State.ERROR)description="Zfold 语音需要处理，点按展开";
      else description="展开 Zfold 语音助手";
      setContentDescription(description+"，长按打开操作菜单");
    }
    private static GradientDrawable circle(Context c,int fill,int stroke){
      GradientDrawable d=new GradientDrawable();d.setShape(GradientDrawable.OVAL);d.setColor(fill);
      if(stroke!=0){d.setStroke(dp(c,1),stroke);}return d;
    }
    private static final class StatusRing extends View {
      final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);VoiceStatus.State state=VoiceStatus.State.IDLE;int color;
      StatusRing(Context c){super(c);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
      void setState(VoiceStatus.State value,int tint){state=value==null?VoiceStatus.State.IDLE:value;color=tint;invalidate();setContentDescription(state.name());}
      @Override protected void onDraw(Canvas canvas){
        float inset=dp(getContext(),3);float size=Math.min(getWidth(),getHeight())-inset*2;
        if(size<=0||state==VoiceStatus.State.IDLE)return;
        paint.setAntiAlias(true);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(getContext(),3));paint.setColor(color);paint.setStrokeCap(Paint.Cap.ROUND);
        float start=-90;
        float sweep=360;
        if(state==VoiceStatus.State.LISTENING)sweep=305;
        else if(state==VoiceStatus.State.THINKING)sweep=215;
        else if(state==VoiceStatus.State.SPEAKING)sweep=335;
        canvas.drawArc(inset,inset,inset+size,inset+size,start,sweep,false,paint);
      }
    }
    @Override public CharSequence getAccessibilityClassName(){return Button.class.getName();}
  }
}
