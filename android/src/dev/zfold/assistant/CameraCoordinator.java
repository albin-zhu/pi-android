package dev.zfold.assistant;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.*;
import android.os.*;
import android.util.Size;
import android.view.*;
import java.util.*;
import java.util.concurrent.*;

/** One visible camera owner. Preview stays local; JPEG output is requested explicitly. */
final class CameraCoordinator {
  interface Listener {void state(String state,String message);}
  interface Capture {void done(CameraFrame frame,String error);}
  private static CameraCoordinator instance;
  static synchronized CameraCoordinator get(Context context){if(instance==null)instance=new CameraCoordinator(context.getApplicationContext());return instance;}
  static void disableVideo(){if(instance!=null&&!instance.videoSession.isEmpty()){instance.videoSession="";if(instance.videoOwner)instance.disable();}}
  private final Context context;private final Handler main=new Handler(Looper.getMainLooper());
  private final ExecutorService encoder=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"camera-encode");t.setDaemon(true);return t;});
  private CameraDevice device;private CameraCaptureSession captureSession;private ImageReader reader;private Surface previewSurface;
  private TextureView view;private Object owner;private Listener listener;
  private String cameraId="",state="off",videoSession="";private int facing,sensorOrientation,generation,afMode;private Size previewSize;
  private Pending pending;private boolean wanted,visible,ready,videoOwner;
  private CameraCoordinator(Context context){
    this.context=context;facing=context.getSharedPreferences("camera",0).getInt("facing",CameraCharacteristics.LENS_FACING_BACK);
    context.registerReceiver(new BroadcastReceiver(){public void onReceive(Context c,Intent i){pause(owner);}},new IntentFilter(Intent.ACTION_SCREEN_OFF));
    ((android.hardware.display.DisplayManager)context.getSystemService(Context.DISPLAY_SERVICE)).registerDisplayListener(new android.hardware.display.DisplayManager.DisplayListener(){
      public void onDisplayAdded(int id){}public void onDisplayRemoved(int id){}
      public void onDisplayChanged(int id){if(view!=null&&view.getDisplay()!=null&&view.getDisplay().getDisplayId()==id)transform();}
    },main);
  }
  String state(){return state;}String facing(){return facing==CameraCharacteristics.LENS_FACING_FRONT?"front":"back";}
  boolean enabledFor(String session){return !videoSession.isEmpty()&&videoSession.equals(session);}
  boolean owns(Object target){return owner==target;}
  boolean ready(){return ready&&visible&&view!=null&&view.isShown()&&!((android.app.KeyguardManager)context.getSystemService(Context.KEYGUARD_SERVICE)).isKeyguardLocked();}
  void bind(Object owner,TextureView view,Listener listener,String session,boolean video){
    close("相机视图已切换");this.owner=owner;this.view=view;this.listener=listener;visible=true;wanted=true;videoOwner=video;
    if(video){videoSession=session;ScreenContext.setEnabled(false);}
    view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){
      public void onSurfaceTextureAvailable(SurfaceTexture surface,int width,int height){if(CameraCoordinator.this.view==view&&wanted)open();}
      public void onSurfaceTextureSizeChanged(SurfaceTexture surface,int width,int height){transform();}
      public boolean onSurfaceTextureDestroyed(SurfaceTexture surface){if(CameraCoordinator.this.view==view){close("预览已暂停");publish("paused","摄像头已暂停，点击相机恢复");}return true;}
      public void onSurfaceTextureUpdated(SurfaceTexture surface){}
    });
    publish("opening","正在开启摄像头…");if(view.isAvailable())open();
  }
  void pause(Object target){if(target!=owner)return;visible=false;wanted=false;close("摄像头已暂停");publish("paused","摄像头已暂停，点击相机恢复");}
  void detach(Object target){if(target!=owner)return;pause(target);view=null;owner=null;listener=null;}
  void disable(){videoSession="";wanted=false;close("摄像头已关闭");publish("off","摄像头已关闭");}
  void endVideo(Object target){videoSession="";if(owner==target)disable();}
  void resume(){visible=true;wanted=true;if(view!=null&&view.isAvailable())open();}
  void switchLens(){facing=facing==CameraCharacteristics.LENS_FACING_BACK?CameraCharacteristics.LENS_FACING_FRONT:CameraCharacteristics.LENS_FACING_BACK;context.getSharedPreferences("camera",0).edit().putInt("facing",facing).apply();close("镜头切换，本次画面已取消");wanted=true;open();}
  void choose(String value){int desired="front".equals(value)?CameraCharacteristics.LENS_FACING_FRONT:CameraCharacteristics.LENS_FACING_BACK;if(desired!=facing){facing=desired;if(wanted&&view!=null){close("镜头已切换");open();}}}
  private void publish(String state,String message){this.state=state;if(listener!=null)listener.state(state,message);}
  private void open(){
    if(!wanted||!visible||view==null||!view.isAvailable())return;
    close("正在重新连接摄像头");int epoch=generation;view.setAlpha(0);
    if(context.checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){publish("unavailable","需要允许相机权限");return;}
    if(((android.app.KeyguardManager)context.getSystemService(Context.KEYGUARD_SERVICE)).isKeyguardLocked()){publish("paused","解锁后点击相机恢复");return;}
    try{
      CameraManager manager=(CameraManager)context.getSystemService(Context.CAMERA_SERVICE);cameraId="";
      for(String id:manager.getCameraIdList()){Integer direction=manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);if(direction!=null&&direction==facing){cameraId=id;break;}}
      if(cameraId.isEmpty())throw new Exception("所选镜头不可用");
      CameraCharacteristics characteristics=manager.getCameraCharacteristics(cameraId);sensorOrientation=characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
      afMode=CaptureRequest.CONTROL_AF_MODE_OFF;int[] modes=characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);if(modes!=null)for(int mode:modes)if(mode==CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)afMode=mode;
      StreamConfigurationMap map=characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);if(map==null)throw new Exception("相机没有可用输出");
      Size jpeg=chooseSize(map.getOutputSizes(ImageFormat.JPEG),4f/3f);
      previewSize=chooseSize(map.getOutputSizes(SurfaceTexture.class),(float)jpeg.getWidth()/jpeg.getHeight());
      view.getSurfaceTexture().setDefaultBufferSize(previewSize.getWidth(),previewSize.getHeight());previewSurface=new Surface(view.getSurfaceTexture());transform();
      reader=ImageReader.newInstance(jpeg.getWidth(),jpeg.getHeight(),ImageFormat.JPEG,2);ImageReader output=reader;
      reader.setOnImageAvailableListener(source->{
        if(epoch!=generation||source!=reader)return;
        try(Image image=source.acquireLatestImage()){
          if(image==null||epoch!=generation||pending==null)return;
          java.nio.ByteBuffer buffer=image.getPlanes()[0].getBuffer();byte[] bytes=new byte[buffer.remaining()];buffer.get(bytes);
          pending.jpeg=bytes;pending.imageTimestamp=image.getTimestamp();deliver();
        }catch(Exception failure){if(epoch==generation&&source==reader)failCapture("无法读取相机画面");}
      },main);
      publish("opening","正在连接"+(facing==CameraCharacteristics.LENS_FACING_FRONT?"前置":"后置")+"镜头…");
      manager.openCamera(cameraId,new CameraDevice.StateCallback(){
        public void onOpened(CameraDevice opened){
          if(epoch!=generation||!visible||!wanted){opened.close();return;}device=opened;
          try{opened.createCaptureSession(Arrays.asList(previewSurface,output.getSurface()),new CameraCaptureSession.StateCallback(){
            public void onConfigured(CameraCaptureSession session){
              if(epoch!=generation){session.close();return;}captureSession=session;
              try{CaptureRequest.Builder request=opened.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);request.addTarget(previewSurface);request.set(CaptureRequest.CONTROL_AF_MODE,afMode);
                session.setRepeatingRequest(request.build(),new CameraCaptureSession.CaptureCallback(){public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest r,TotalCaptureResult result){if(epoch==generation&&!ready){ready=true;if(view!=null)view.setAlpha(1);publish("preview","本地预览 · "+(facing==CameraCharacteristics.LENS_FACING_FRONT?"前置":"后置"));}}},main);
              }catch(Exception failure){unavailable(epoch,failure.getMessage());}
            }
            public void onConfigureFailed(CameraCaptureSession session){unavailable(epoch,"相机预览配置失败");}
          },main);}catch(Exception failure){unavailable(epoch,failure.getMessage());}
        }
        public void onDisconnected(CameraDevice opened){opened.close();unavailable(epoch,"相机已断开，点击重试");}
        public void onError(CameraDevice opened,int error){opened.close();unavailable(epoch,"相机不可用（"+error+"），可能正被其他应用使用");}
      },main);
    }catch(Exception failure){unavailable(epoch,failure.getMessage());}
  }
  private void unavailable(int epoch,String message){if(epoch!=generation)return;close(message);publish("unavailable",message==null?"摄像头不可用":message);}
  void capture(String requestId,Capture callback){
    if(!ready()){callback.done(null,"摄像头未就绪，请恢复预览后重试");return;}
    if(pending!=null){callback.done(null,"正在读取上一张画面，请稍候");return;}
    Pending job=new Pending(requestId,generation,cameraId,facing(),callback);pending=job;
    main.postDelayed(()->{if(pending==job)failCapture("相机取帧超时，请重试");},8000);
    try{
      try(Image stale=reader.acquireLatestImage()){}
      CaptureRequest.Builder request=device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);request.addTarget(reader.getSurface());
      request.set(CaptureRequest.JPEG_ORIENTATION,rotation());request.set(CaptureRequest.JPEG_QUALITY,(byte)90);request.set(CaptureRequest.CONTROL_AF_MODE,afMode);request.setTag(requestId);
      captureSession.capture(request.build(),new CameraCaptureSession.CaptureCallback(){
        public void onCaptureStarted(CameraCaptureSession session,CaptureRequest request,long timestamp,long frame){if(pending==job){job.sensorTimestamp=timestamp;job.capturedAt=System.currentTimeMillis();deliver();}}
        public void onCaptureFailed(CameraCaptureSession session,CaptureRequest request,CaptureFailure failure){if(pending==job)failCapture("本次拍照失败，请重试");}
      },main);
    }catch(Exception failure){failCapture("拍照失败："+failure.getMessage());}
  }
  private void deliver(){
    Pending job=pending;if(job==null||job.encoding||job.jpeg==null||job.sensorTimestamp==0||job.sensorTimestamp!=job.imageTimestamp)return;job.encoding=true;
    encoder.execute(()->{try{CameraFrame frame=CameraFrame.encode(job.jpeg,job.cameraId,job.facing,job.capturedAt,job.generation);main.post(()->{if(pending!=job)return;if(job.generation!=generation||!ready()){failCapture("相机状态已改变，请重试");return;}pending=null;job.callback.done(frame,null);});}
      catch(Exception failure){main.post(()->{if(pending==job)failCapture(failure.getMessage());});}});
  }
  private void failCapture(String error){Pending job=pending;pending=null;if(job!=null)job.callback.done(null,error);}
  private void close(String reason){generation++;ready=false;failCapture(reason);if(captureSession!=null){captureSession.close();captureSession=null;}if(device!=null){device.close();device=null;}if(reader!=null){reader.close();reader=null;}if(previewSurface!=null){previewSurface.release();previewSurface=null;}}
  private int rotation(){int degrees=view==null||view.getDisplay()==null?0:view.getDisplay().getRotation()*90;return (sensorOrientation+(facing==CameraCharacteristics.LENS_FACING_FRONT?degrees:-degrees)+360)%360;}
  void transform(){
    if(view==null||previewSize==null||view.getWidth()==0||view.getHeight()==0)return;
    try{Integer current=((CameraManager)context.getSystemService(Context.CAMERA_SERVICE)).getCameraCharacteristics(cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION);if(current!=null&&current!=sensorOrientation&&wanted&&visible){open();return;}}catch(Exception ignored){}
    // TextureView already applies sensor rotation and front-camera mirroring.
    // Correct only display rotation and undo its non-uniform default scaling.
    // https://developer.android.com/codelabs/android-camera2-preview
    float w=view.getWidth(),h=view.getHeight();int display=view.getDisplay()==null?0:view.getDisplay().getRotation()*90,angle=(360-display)%360;
    float baseW=sensorOrientation%180==0?previewSize.getWidth():previewSize.getHeight(),baseH=sensorOrientation%180==0?previewSize.getHeight():previewSize.getWidth();
    float rw=angle%180==0?baseW:baseH,rh=angle%180==0?baseH:baseW;
    float[] target=angle==90?new float[]{rw,0,rw,rh,0,0}:angle==180?new float[]{rw,rh,0,rh,rw,0}:angle==270?new float[]{0,rh,0,0,rw,rh}:new float[]{0,0,rw,0,0,rh};
    float scale=Math.min(w/rw,h/rh),dx=(w-rw*scale)/2,dy=(h-rh*scale)/2;
    for(int i=0;i<6;i+=2){target[i]=dx+target[i]*scale;target[i+1]=dy+target[i+1]*scale;}
    Matrix matrix=new Matrix();matrix.setPolyToPoly(new float[]{0,0,w,0,0,h},0,target,0,3);view.setTransform(matrix);
  }
  private static Size chooseSize(Size[] sizes,float ratio)throws Exception {
    if(sizes==null||sizes.length==0)throw new Exception("相机没有可用尺寸");
    return Arrays.stream(sizes).min(Comparator.comparingDouble(s->{double aspect=Math.abs((double)s.getWidth()/s.getHeight()-ratio);int edge=Math.max(s.getWidth(),s.getHeight());return aspect*100000+Math.abs(edge-1280)+(edge>1920?10000:0);})).get();
  }
  private static final class Pending {
    final String requestId,cameraId,facing;final int generation;final Capture callback;byte[] jpeg;long sensorTimestamp,imageTimestamp,capturedAt;boolean encoding;
    Pending(String id,int gen,String camera,String facing,Capture callback){requestId=id;generation=gen;cameraId=camera;this.facing=facing;this.callback=callback;}
  }
}
