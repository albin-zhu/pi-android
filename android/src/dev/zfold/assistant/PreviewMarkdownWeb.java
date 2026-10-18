package dev.zfold.assistant;

import android.app.Activity;
import android.net.Uri;
import android.os.CancellationSignal;
import android.view.View;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONObject;

/** No native JS bridge, file access, arbitrary scripts, or network fallback. */
final class PreviewMarkdownWeb {
  interface Events {
    void navigate(String relative,String fragment);
    void external(Uri uri);
    void status(String text);
    void failed(String text);
  }
  private static final String CSP="default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; object-src 'none'; frame-src 'none'; child-src 'none'; worker-src 'none'; form-action 'none'; base-uri 'none'; frame-ancestors 'none'";
  private static final String IMAGE_CSP="default-src 'none'; script-src 'none'; style-src 'unsafe-inline'; img-src 'self' data:; sandbox";
  final WebView view;
  private final Activity activity;
  private final Events events;
  private final File root;
  private final PreviewTree tree;
  private final String host="preview-"+UUID.randomUUID()+".invalid";
  private final Uri document;
  private final byte[] json;
  private final boolean image;
  private final ArrayList<CancellationSignal> cancellations=new ArrayList<>();
  private final ArrayList<InputStream> streams=new ArrayList<>();
  private volatile boolean closed;
  private boolean restoreCancelled;

  PreviewMarkdownWeb(Activity activity,File root,PreviewTree tree,String relative,String markdown,boolean dark,boolean image,int x,int y,boolean restore,String fragment,Events events){
    this.activity=activity;this.root=root;this.tree=tree;this.events=events;this.image=image;
    Uri.Builder url=new Uri.Builder().scheme("https").authority(host).appendPath("files");
    for(String segment:relative.split("/",-1))url.appendPath(segment);
    document=url.build();
    JSONObject data=new JSONObject();
    try{data.put("markdown",markdown);data.put("dark",dark);data.put("resourceAccess",root!=null||tree!=null);}catch(org.json.JSONException impossible){throw new IllegalStateException(impossible);}
    json=data.toString().getBytes(StandardCharsets.UTF_8);
    view=new WebView(activity);
    view.setOnTouchListener((v,event)->{restoreCancelled=true;return false;});
    WebSettings settings=view.getSettings();
    settings.setJavaScriptEnabled(true);settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setSupportMultipleWindows(false);
    settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setAllowFileAccessFromFileURLs(false);settings.setAllowUniversalAccessFromFileURLs(false);
    settings.setBlockNetworkLoads(true);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    settings.setDomStorageEnabled(false);settings.setDatabaseEnabled(false);settings.setGeolocationEnabled(false);settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
    settings.setMediaPlaybackRequiresUserGesture(true);settings.setSupportZoom(true);settings.setBuiltInZoomControls(true);settings.setDisplayZoomControls(false);
    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(view,false);
    view.setBackgroundColor(dark?0xff15171a:0xffffffff);view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
    view.setWebChromeClient(new WebChromeClient(){
      @Override public void onPermissionRequest(PermissionRequest request){request.deny();}
      @Override public void onGeolocationPermissionsShowPrompt(String origin,GeolocationPermissions.Callback callback){callback.invoke(origin,false,false);}
      @Override public boolean onShowFileChooser(WebView web,ValueCallback<Uri[]> callback,FileChooserParams parameters){callback.onReceiveValue(null);return true;}
    });
    view.setDownloadListener((u,a,d,m,s)->events.status("预览不下载文件；请使用外部应用"));
    view.setWebViewClient(new WebViewClient(){
      private boolean restoring;
      @Override public WebResourceResponse shouldInterceptRequest(WebView ignored,WebResourceRequest request){return intercept(request);}
      @Override public boolean shouldOverrideUrlLoading(WebView ignored,WebResourceRequest request){
        if(closed)return true;
        Uri target=request.getUrl();
        if(!request.isForMainFrame())return true;
        if(same(target))return false;
        if(!request.hasGesture())return true;
        if(local(target)){
          try{events.navigate(PreviewMarkdownPaths.relative(target.getPathSegments()),target.getFragment());}
          catch(IOException error){events.status("链接超出授权文件夹，已拦截");}
        }else if(("http".equalsIgnoreCase(target.getScheme())||"https".equalsIgnoreCase(target.getScheme()))&&target.getHost()!=null&&target.getUserInfo()==null)events.external(target);
        else events.status("不支持此链接；仅允许授权文件夹内的 Markdown / 图片和手动打开的 http(s) 链接");
        return true;
      }
      @Override public boolean shouldOverrideUrlLoading(WebView ignored,String url){return closed||!same(Uri.parse(url));}
      @Override public void onPageFinished(WebView ignored,String url){
        if(closed||restoring)return;restoring=true;
        if(!restore)return;
        // Native scroll positions are physical pixels, not JS/CSS pixels. Restore only
        // after the trusted renderer has finished image layout and fragment scrolling.
        restorePosition(x,y);
      }
      @Override public void onReceivedError(WebView ignored,WebResourceRequest request,WebResourceError error){if(request.isForMainFrame()&&!closed)events.failed("页面加载失败："+error.getDescription());}
      @Override public void onReceivedHttpError(WebView ignored,WebResourceRequest request,WebResourceResponse response){if(request.isForMainFrame()&&!closed)events.failed("页面无法读取，请刷新或重新授权文件夹");}
      @Override public boolean onRenderProcessGone(WebView ignored,RenderProcessGoneDetail detail){if(!closed)events.failed("WebView 渲染进程已停止，请刷新");return true;}
    });
    view.loadUrl(fragment==null||fragment.isEmpty()?document.toString():document.buildUpon().fragment(fragment).build().toString());
  }
  private void restorePosition(int x,int y){
    if(closed||restoreCancelled)return;
    String ready="("+(image?"true":"document.documentElement.dataset.previewReady==='true'")+")&&Array.from(document.images).every(i=>i.complete)";
    view.evaluateJavascript(ready,value->{
      if(closed||restoreCancelled)return;
      if("true".equals(value))view.post(()->{if(!closed&&!restoreCancelled)view.scrollTo(x,y);});
      else view.postDelayed(()->restorePosition(x,y),40);
    });
  }
  private boolean local(Uri uri){return "https".equals(uri.getScheme())&&host.equals(uri.getEncodedAuthority());}
  private boolean same(Uri uri){return local(uri)&&document.getEncodedPath().equals(uri.getEncodedPath());}
  private WebResourceResponse intercept(WebResourceRequest request){
    Uri uri=request.getUrl();
    if(closed||!local(uri)||!"GET".equals(request.getMethod()))return denied();
    CancellationSignal cancellation=new CancellationSignal();
    synchronized(this){if(closed)return denied();cancellations.add(cancellation);}
    try{
      String route=uri.getEncodedPath();
      if(request.isForMainFrame()){
        if(!same(uri))return denied();
        if(image){
          String html="<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><title>图片预览</title><style>body{margin:16px;background:#eee}img{max-width:100%;height:auto}</style><img alt='图片无法读取，请刷新或重新授权文件夹' src='"+document.toString().replace("&","&amp;").replace("'","&#39;")+"'>";
          return response("text/html","UTF-8",bytes(html),CSP);
        }
        return response("text/html","UTF-8",activity.getAssets().open("preview/markdown.html"),CSP);
      }
      if(!image&&"/__preview__/markdown.js".equals(route))return response("application/javascript","UTF-8",activity.getAssets().open("preview/markdown.js"),CSP);
      if(!image&&"/__preview__/markdown.css".equals(route))return response("text/css","UTF-8",activity.getAssets().open("preview/markdown.css"),CSP);
      if(!image&&"/__preview__/source".equals(route))return response("application/json","UTF-8",new ByteArrayInputStream(json),CSP);
      String relative=PreviewMarkdownPaths.relative(uri.getPathSegments());
      String mime;InputStream input;
      if(root!=null){
        File resource=ArtifactProvider.checked(activity,PreviewFiles.resource(root,relative).getPath());
        mime=ArtifactProvider.mime(resource);
        if(!PreviewMarkdownPaths.image(mime))return denied();
        input=new FileInputStream(resource);
      }else if(tree!=null){
        PreviewTree.Entry resource=tree.resolve(relative,cancellation);mime=resource.mime;
        if(!PreviewMarkdownPaths.image(mime))return denied();
        input=tree.open(resource,cancellation);
      }else{report("此文件只有单文件读取授权；请点“授权文件夹”以加载相对图片和 Markdown 链接");return denied();}
      return response(mime,null,track(input),IMAGE_CSP);
    }catch(Exception error){if(!closed)report("本地图片无法读取或已拦截；请刷新，或重新授权文件夹");return denied();}
    finally{synchronized(this){cancellations.remove(cancellation);}}
  }
  private synchronized InputStream track(InputStream input)throws IOException {
    if(closed){input.close();throw new IOException("预览已关闭");}
    FilterInputStream tracked=new FilterInputStream(input){
      @Override public void close()throws IOException {synchronized(PreviewMarkdownWeb.this){streams.remove(this);}super.close();}
    };
    streams.add(tracked);return tracked;
  }
  private void report(String text){activity.runOnUiThread(()->{if(!closed)events.status(text);});}
  void close(){
    ArrayList<CancellationSignal> cancel;ArrayList<InputStream> inputs;
    synchronized(this){closed=true;cancel=new ArrayList<>(cancellations);cancellations.clear();inputs=new ArrayList<>(streams);streams.clear();}
    for(CancellationSignal signal:cancel)signal.cancel();
    for(InputStream input:inputs)try{input.close();}catch(IOException ignored){}
  }
  private static InputStream bytes(String text){return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));}
  private static WebResourceResponse response(String mime,String encoding,InputStream stream,String policy){
    Map<String,String> headers=new HashMap<>();headers.put("Content-Security-Policy",policy);headers.put("X-Content-Type-Options","nosniff");headers.put("Cache-Control","no-store");headers.put("Referrer-Policy","no-referrer");
    return new WebResourceResponse(mime,encoding,200,"OK",headers,stream);
  }
  private static WebResourceResponse denied(){return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",Collections.singletonMap("Content-Security-Policy","default-src 'none'"),bytes(""));}
}
