package dev.zfold.assistant;
import android.os.SystemClock;
import org.json.JSONObject;

/** One screenshot's coordinate space. A visual mutation consumes the frame. */
final class VisualFrame {
  final String id,packageName;final int window,width,height,imageWidth,imageHeight,rotation;
  final long captured=SystemClock.uptimeMillis();boolean consumed;
  VisualFrame(String id,String pkg,int window,int width,int height,int imageWidth,int imageHeight,int rotation){this.id=id;packageName=pkg;this.window=window;this.width=width;this.height=height;this.imageWidth=imageWidth;this.imageHeight=imageHeight;this.rotation=rotation;}
  JSONObject metadata(){return PiRuntime.json("screenshotId",id,"packageName",packageName,"imageWidth",imageWidth,"imageHeight",imageHeight,"screenWidth",width,"screenHeight",height,"coordinateSpace","screenshot_pixels","maxAgeMs",30000);}
  void validate(JSONObject args,String foreground,int currentWindow,int w,int h,int currentRotation)throws Exception{
    String reason=consumed?"frame_consumed":!id.equals(args.optString("screenshotId"))?"frame_id_mismatch":SystemClock.uptimeMillis()-captured>30000?"frame_expired":!packageName.equals(foreground)?"foreground_changed":window!=currentWindow?"window_changed":w!=width||h!=height||rotation!=currentRotation?"display_changed":null;
    if(reason!=null)throw new Exception("截图不可操作 ["+reason+"]，请重新观察；未执行点击");
    if(args.has("packageName")&&!packageName.equals(args.optString("packageName")))throw new Exception("截图不属于目标应用");
  }
  float x(double x)throws Exception{if(!Double.isFinite(x)||x<0||x>=imageWidth)throw new Exception("横坐标必须使用截图像素，且在图片范围内");return (float)(x*width/imageWidth);}
  float y(double y)throws Exception{if(!Double.isFinite(y)||y<0||y>=imageHeight)throw new Exception("纵坐标必须使用截图像素，且在图片范围内");return (float)(y*height/imageHeight);}
}
