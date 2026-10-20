package dev.zfold.assistant;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.OpenableColumns;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
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
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Private workspace preview. The WebView has a synthetic, per-render offline origin. */
public final class PreviewActivity extends NavigableActivity {
  private static final String EXTRA_PATH="dev.zfold.assistant.preview.PATH";
  private static final String CSP="default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
    +"img-src 'self' data:; font-src 'self' data:; media-src 'self'; connect-src 'self'; object-src 'none'; "
    +"frame-src 'none'; child-src 'none'; worker-src 'none'; form-action 'none'; base-uri 'none'; frame-ancestors 'none'";
  private final ExecutorService loader=Executors.newSingleThreadExecutor();
  private Future<?> pending;
  private CancellationSignal readCancellation;
  private Ui.Theme theme;
  private TextView title,pathLabel,status;
  private Button mode,folder,previous;
  private ProgressBar progress;
  private FrameLayout content;
  private ScrollView scroll;
  private WebView web;
  private File file;
  private Uri documentUri;
  private String documentMime;
  private String path="",text,fragment="";
  private String documentName="document.md",treeRelative;
  private Uri treeUri,folderUri;
  private File sharedRoot;
  private PreviewTree selectedTree;
  private PreviewMarkdownWeb markdownWeb;
  private boolean imageDocument,restoreScroll;
  private final ArrayList<Bundle> history=new ArrayList<>();
  private static final int REQUEST_TREE=81;
  private Future<?> folderPending;
  private CancellationSignal folderCancellation;
  private int folderGeneration;
  private PreviewFiles.Kind kind;
  private boolean source,fromOverlay,destroyed;
  private int generation,sourceY,renderY,renderX;

  public static void open(Context context,File file)throws Exception {
    File checked=ArtifactProvider.checked(context,file.getPath());
    boolean overlay=FloatingNavigation.suspend(context);
    Intent intent=new Intent(context,PreviewActivity.class).putExtra(EXTRA_PATH,checked.getPath())
      .putExtra("from_overlay",overlay).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
    if(!(context instanceof Activity)||overlay)intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    ActivityOptions options=ActivityOptions.makeBasic();
    if(context instanceof Activity&&((Activity)context).isInMultiWindowMode())
      options.setLaunchBounds(((Activity)context).getWindowManager().getCurrentWindowMetrics().getBounds());
    try{context.startActivity(intent,options.toBundle());}
    catch(Exception error){if(overlay)FloatingNavigation.restore(context);throw error;}
  }

  @Override protected void onCreate(Bundle saved){
    super.onCreate(saved);theme=new Ui.Theme(this);
    readTarget(getIntent());
    fromOverlay=getIntent().getBooleanExtra("from_overlay",false);
    if(saved!=null){
      source=saved.getBoolean("source");fromOverlay=saved.getBoolean("overlay",fromOverlay);
      sourceY=saved.getInt("sourceY");renderY=saved.getInt("renderY");renderX=saved.getInt("renderX");fragment=saved.getString("fragment","");
      restoreTarget(saved.getBundle("target"));
      ArrayList<Bundle> previous=saved.getParcelableArrayList("history");
      if(previous!=null)history.addAll(previous);
      restoreScroll=true;
    }
    build();load();
  }

  @Override protected void onNewIntent(Intent intent){
    super.onNewIntent(intent);
    if(!intent.hasExtra(EXTRA_PATH)&&!Intent.ACTION_VIEW.equals(intent.getAction()))return;
    setIntent(intent);fromOverlay|=intent.getBooleanExtra("from_overlay",false);
    readTarget(intent);
    cancelFolder();
    history.clear();treeUri=folderUri=null;treeRelative=null;selectedTree=null;imageDocument=false;restoreScroll=false;
    source=false;sourceY=renderY=renderX=0;fragment="";load();
  }

  private void readTarget(Intent intent){
    documentUri=Intent.ACTION_VIEW.equals(intent.getAction())?intent.getData():null;
    documentMime=documentUri==null?null:intent.getType();
    path=documentUri==null?intent.getStringExtra(EXTRA_PATH):"";
    if(path==null)path="";
  }

  private void build(){
    getWindow().setDecorFitsSystemWindows(false);getWindow().setStatusBarColor(theme.page);getWindow().setNavigationBarColor(theme.page);
    getWindow().getDecorView().setSystemUiVisibility(theme.dark?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(theme.page);
    root.setOnApplyWindowInsetsListener((view,insets)->{Insets bars=insets.getInsets(WindowInsets.Type.systemBars());root.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
    LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);
    previous=button(heading,"上一文档",this::previousDocument);previous.setEnabled(!history.isEmpty());
    title=Ui.text(this,"文件预览",20,theme.ink);title.setMaxLines(2);heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));
    button(heading,"关闭",this::finish);root.addView(heading);
    pathLabel=Ui.text(this,"",12,theme.muted);pathLabel.setTextIsSelectable(true);pathLabel.setMaxLines(3);pathLabel.setPadding(dp(16),dp(4),dp(16),dp(6));root.addView(pathLabel);
    HorizontalScrollView toolbar=new HorizontalScrollView(this);toolbar.setHorizontalScrollBarEnabled(true);
    LinearLayout actions=new LinearLayout(this);actions.setPadding(dp(10),0,dp(10),0);toolbar.addView(actions);
    button(actions,"刷新",()->{rememberPosition();restoreScroll=true;load();});
    mode=button(actions,"查看源码",()->{rememberPosition();restoreScroll=true;source=!source;showDocument();});
    folder=button(actions,"授权文件夹",this::openFolder);folder.setVisibility(documentUri==null?View.GONE:View.VISIBLE);
    button(actions,"外部打开",this::openExternal);
    button(actions,"说明",()->new android.app.AlertDialog.Builder(this).setTitle("预览范围")
      .setMessage("离线预览 · 文本最大 2 MiB · UTF-8 / 带 BOM 的 UTF-16。\n\nMarkdown 支持表格、授权目录内的相对 Markdown 链接和图片。文件夹授权会在提供方支持时记住，下次打开同一提供方的文档自动复用；不支持记忆时会提示。若无法确认原文件身份，可从“打开文件夹”选择已授权文件夹内的文档，无需重复授权。不会访问网络图片。\n\n顶部“上一文档”返回跳转前的文档和阅读位置；“关闭”退出预览。系统返回先退页内跳转，再退文档，最后返回上一级；从桌面直接打开时返回功能中心。文件夹内返回先回上级文件夹，根目录返回只关闭选择器。\n\nHTML/SVG 可运行本地脚本、读取同目录及子目录资源。网络、跨目录、嵌入页面、表单和下载已禁用。需要完整浏览器能力时可选择外部打开。")
      .setPositiveButton("知道了",null).show());root.addView(toolbar);
    progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);root.addView(progress,new LinearLayout.LayoutParams(-1,dp(3)));
    status=Ui.text(this,"",12,theme.muted);status.setPadding(dp(16),dp(8),dp(16),dp(8));status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);root.addView(status);
    content=new FrameLayout(this);content.setBackgroundColor(theme.surface);root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
    root.setContentDescription("文件预览页面");setContentView(root);
  }

  private Button button(LinearLayout parent,String label,Runnable action){
    Button button=new Button(this);button.setText(label);button.setTextColor(theme.accent);button.setAllCaps(false);button.setOnClickListener(v->action.run());
    parent.addView(button,new LinearLayout.LayoutParams(-2,dp(48)));return button;
  }

  private void load(){
    final int ticket=++generation;final String requested=path;final Uri requestedUri=documentUri;final String requestedMime=documentMime;
    final Uri requestedTree=treeUri;final String requestedRelative=treeRelative;final boolean requestedImage=imageDocument;
    cancelRead();cancelFolder();
    previous.setEnabled(!history.isEmpty());
    final CancellationSignal cancellation=new CancellationSignal();readCancellation=cancellation;
    disposeWeb();content.removeAllViews();scroll=null;text=null;file=null;kind=null;sharedRoot=null;selectedTree=null;
    String name=requestedUri!=null||requested.isEmpty()?"文件预览":new File(requested).getName();title.setText(name);setTitle(name);pathLabel.setText(requestedUri==null?requested:requestedUri.toString());
    mode.setEnabled(false);progress.setVisibility(View.VISIBLE);status.setTextColor(theme.muted);status.setText("正在读取文件…");
    folder.setVisibility(requestedUri==null&&requestedTree==null&&folderUri==null?View.GONE:View.VISIBLE);
    folder.setText(folderUri==null&&PreviewTree.savedFolders(this).isEmpty()?"授权文件夹":"打开文件夹");
    pending=loader.submit(()->{
      try{
        if(requestedTree!=null&&requestedRelative!=null){
          PreviewTree tree=new PreviewTree(this,requestedTree);
          PreviewTree.Entry entry=tree.resolve(requestedRelative,cancellation);
          if(entry.directory())throw new IOException("请选择 Markdown 文件而不是文件夹");
          boolean isImage=PreviewMarkdownPaths.image(entry.mime);
          if(!isImage&&!entry.markdown())throw new IOException("仅支持 Markdown 文档和本地图片");
          String loaded=isImage?"":tree.read(entry,cancellation);
          cancellation.throwIfCanceled();
          runOnUiThread(()->{
            if(!current(ticket))return;
            selectedTree=tree;documentUri=entry.uri;documentMime=entry.mime;documentName=entry.name;
            text=loaded;kind=PreviewFiles.Kind.MARKDOWN;imageDocument=isImage;title.setText(entry.name);setTitle(entry.name);
            pathLabel.setText("授权文件夹 / "+entry.relative);showDocument();
          });
          return;
        }
        if(requestedUri!=null){
          loadDocument(ticket,requestedUri,requestedMime,cancellation);
          return;
        }
        if(requested.isEmpty())throw new IOException("缺少文件路径");
        File checked=ArtifactProvider.checked(getApplicationContext(),requested);
        File root=ArtifactProvider.root(getApplicationContext());
        boolean isImage=requestedImage&&PreviewMarkdownPaths.image(ArtifactProvider.mime(checked));
        if(requestedImage&&!isImage)throw new IOException("此链接不是支持的本地图片");
        String loaded=isImage?"":PreviewFiles.readText(checked);PreviewFiles.Kind format=PreviewFiles.kind(checked);
        runOnUiThread(()->{
          if(!current(ticket))return;
          file=checked;sharedRoot=root;text=loaded;kind=format;imageDocument=isImage;documentName=checked.getName();pathLabel.setText(checked.getPath());
          showDocument();
        });
      }catch(Exception error){runOnUiThread(()->{if(current(ticket))showError(message(error));});}
    });
  }

  private void loadDocument(int ticket,Uri uri,String mime,CancellationSignal cancellation)throws Exception {
    // Recheck permission on refresh too: providers may revoke temporary grants.
    MarkdownEntryActivity.validate(this,new Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
    final String resolvedMime=mime==null?getContentResolver().getType(uri):mime;
    cancellation.throwIfCanceled();
    if(!PreviewFiles.markdownRequestMime(resolvedMime))throw new IOException("文件提供方未返回支持的 Markdown 文档类型");
    String name=null;
    try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null,cancellation)){
      if(cursor!=null&&cursor.moveToFirst()){
        int column=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        if(column>=0&&!cursor.isNull(column))name=cursor.getString(column);
      }
    }
    if(name!=null&&name.length()>1024)throw new IOException("文档名称过长，无法预览");
    if(!PreviewFiles.supportsMarkdownDocument(name,resolvedMime))
      throw new IOException("此入口仅支持 Markdown。text/plain 文档的名称必须以 .md、.markdown、.mdown 或 .mkd 结尾；不支持普通 .txt 或二进制文件。");
    final String label=name==null||name.isEmpty()?"Markdown 文档":name;
    final String loaded;
    try(AssetFileDescriptor descriptor=getContentResolver().openAssetFileDescriptor(uri,"r",cancellation)){
      if(descriptor==null)throw new IOException("文件提供方未返回可读内容");
      try(InputStream input=descriptor.createInputStream()){
        cancellation.setOnCancelListener(()->{try{input.close();}catch(IOException ignored){}});
        cancellation.throwIfCanceled();
        loaded=PreviewFiles.readText(input);
      }finally{cancellation.setOnCancelListener(null);}
    }
    PreviewTree.Entry remembered=PreviewTree.rememberedDocument(this,uri,cancellation);
    PreviewTree rememberedTree=remembered==null?null:new PreviewTree(this,remembered.tree);
    cancellation.throwIfCanceled();
    runOnUiThread(()->{
      if(!current(ticket))return;
      documentMime=resolvedMime;documentName=label;text=loaded;kind=PreviewFiles.Kind.MARKDOWN;imageDocument=false;
      if(remembered!=null){
        selectedTree=rememberedTree;treeUri=folderUri=remembered.tree;treeRelative=remembered.relative;
        documentUri=remembered.uri;folder.setText("打开文件夹");pathLabel.setText("已记住的文件夹 / "+remembered.relative);
      }
      title.setText(label);setTitle(label);showDocument();
    });
  }

  private void cancelRead(){
    if(pending!=null)pending.cancel(true);
    if(readCancellation!=null){readCancellation.cancel();readCancellation=null;}
  }

  private boolean current(int ticket){return !destroyed&&!isFinishing()&&ticket==generation;}

  private void showDocument(){
    if(text==null)return;
    disposeWeb();content.removeAllViews();scroll=null;
    boolean renderable=kind!=PreviewFiles.Kind.TEXT||imageDocument;
    mode.setEnabled(renderable&&!imageDocument);mode.setText(source?"查看渲染":"查看源码");
    if(!renderable)mode.setText("纯文本");
    progress.setVisibility(View.GONE);status.setTextColor(theme.muted);
    status.setText(imageDocument?"本地图片 · 隔离离线模式":(source||!renderable?"文本 / 源码":kind==PreviewFiles.Kind.MARKDOWN?"Markdown · 离线模式":"HTML / SVG · 隔离离线模式")+(text.isEmpty()&&!imageDocument?" · 空文件":""));
    if(!source&&(kind==PreviewFiles.Kind.MARKDOWN||imageDocument)){
      try{showMarkdown();}catch(Exception error){showError("Markdown 无法启动："+message(error));}
      return;
    }
    if(!source&&(kind==PreviewFiles.Kind.HTML||kind==PreviewFiles.Kind.SVG)){
      try{showWeb();}catch(RuntimeException error){showError("WebView 无法启动："+message(error));}
      return;
    }
    scroll=new ScrollView(this);scroll.setFillViewport(true);
    TextView body=Ui.text(this,"",source||!renderable?14:16,theme.ink);body.setPadding(dp(16),dp(16),dp(16),dp(24));body.setTextIsSelectable(true);body.setLineSpacing(dp(4),1);
    body.setTypeface(Typeface.MONOSPACE);body.setText(text);
    scroll.addView(body,new ScrollView.LayoutParams(-1,-2));content.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
    ScrollView shown=scroll;int y=source||!renderable?sourceY:renderY;
    shown.post(()->{if(scroll==shown)shown.scrollTo(0,y);});
  }

  private void showMarkdown()throws IOException {
    String relative;
    if(selectedTree!=null)relative=treeRelative;
    else if(file!=null)relative=PreviewMarkdownPaths.fileRelative(sharedRoot,file);
    else{
      // A single granted document may link to itself by name without sibling access.
      try{relative=PreviewMarkdownPaths.segment(documentName);}
      catch(IOException invalidName){relative="document.md";}
    }
    final int ticket=generation;
    markdownWeb=new PreviewMarkdownWeb(this,sharedRoot,selectedTree,relative,text,theme.dark,imageDocument,renderX,renderY,restoreScroll,fragment,new PreviewMarkdownWeb.Events(){
      @Override public void navigate(String relative,String anchor){if(current(ticket))navigateMarkdown(relative,anchor);}
      @Override public void external(Uri uri){if(current(ticket))openLink(uri);}
      @Override public void status(String detail){if(current(ticket))status.setText(detail);}
      @Override public void failed(String detail){if(current(ticket))showError(detail);}
    });
    web=markdownWeb.view;content.addView(web,new FrameLayout.LayoutParams(-1,-1));
    if(sharedRoot==null&&selectedTree==null)status.setText("Markdown · 单文件授权；请从上方文件夹入口选择已授权文档，或首次授权文件夹");
  }

  private void navigateMarkdown(String relative,String anchor){
    if(sharedRoot==null&&selectedTree==null){
      status.setText("请从上方文件夹入口打开已授权目录中的 Markdown，或首次授权所在文件夹");
      Toast.makeText(this,"相对链接需要文件夹读取授权",Toast.LENGTH_LONG).show();return;
    }
    if(sharedRoot!=null){
      File target=new File(sharedRoot,relative);
      if(PreviewFiles.kind(target)!=PreviewFiles.Kind.MARKDOWN&&!PreviewMarkdownPaths.image(ArtifactProvider.mime(target))){
        status.setText("Markdown 预览仅打开授权目录内的 Markdown 和图片；其他文件请使用外部应用");return;
      }
    }
    rememberPosition();history.add(snapshotTarget());
    if(selectedTree!=null){treeRelative=relative;documentUri=null;documentMime=null;imageDocument=false;}
    else{path=new File(sharedRoot,relative).getPath();imageDocument=PreviewMarkdownPaths.image(ArtifactProvider.mime(new File(path)));}
    source=false;sourceY=renderX=renderY=0;restoreScroll=false;fragment=anchor==null?"":anchor;load();
  }

  private Bundle snapshotTarget(){
    Bundle state=new Bundle();state.putString("path",path);state.putParcelable("uri",documentUri);state.putString("mime",documentMime);
    state.putParcelable("tree",treeUri);state.putParcelable("folder",folderUri);state.putString("relative",treeRelative);state.putString("name",documentName);state.putBoolean("image",imageDocument);
    state.putBoolean("source",source);state.putInt("sourceY",sourceY);state.putInt("renderX",renderX);state.putInt("renderY",renderY);state.putString("fragment",fragment);
    return state;
  }

  private void restoreTarget(Bundle state){
    if(state==null)return;
    path=state.getString("path","");documentUri=state.getParcelable("uri");documentMime=state.getString("mime");
    treeUri=state.getParcelable("tree");folderUri=state.getParcelable("folder");treeRelative=state.getString("relative");documentName=state.getString("name","document.md");imageDocument=state.getBoolean("image");
    source=state.getBoolean("source");sourceY=state.getInt("sourceY");renderX=state.getInt("renderX");renderY=state.getInt("renderY");fragment=state.getString("fragment","");
  }

  private void openFolder(){
    if(folderUri==null){
      List<Uri> saved=PreviewTree.savedFolders(this);
      if(saved.isEmpty()){authorizeFolder();return;}
      if(saved.size()==1){folderUri=saved.get(0);browseFolder(folderUri,"");return;}
      String[] labels=new String[saved.size()];
      for(int i=0;i<saved.size();i++)labels[i]=saved.get(i).getAuthority()+" · "+DocumentsContract.getTreeDocumentId(saved.get(i));
      new android.app.AlertDialog.Builder(this).setTitle("已授权文件夹")
        .setItems(labels,(dialog,which)->{folderUri=saved.get(which);browseFolder(folderUri,"");})
        .setPositiveButton("授权其他文件夹",(dialog,which)->authorizeFolder()).setNegativeButton("取消",null).show();
      return;
    }
    String parent=folderUri.equals(treeUri)&&treeRelative!=null?parentPath(treeRelative):"";
    browseFolder(folderUri,parent);
  }

  private void authorizeFolder(){
    try{
      Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
        |Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
      if(folderUri!=null)intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,folderUri);
      startActivityForResult(intent,REQUEST_TREE);
    }catch(Exception error){status.setText("无法打开文件夹选择器："+message(error));}
  }

  @Override protected void onActivityResult(int request,int result,Intent data){
    super.onActivityResult(request,result,data);
    if(request!=REQUEST_TREE)return;
    if(result!=RESULT_OK||data==null||data.getData()==null){status.setText("未授权文件夹；保留当前文档和原有读取权限");return;}
    if((data.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)==0){status.setText("文件提供方未授予读取权限，请重新授权文件夹");return;}
    final Uri granted=data.getData(),original=documentUri;
    final int grantFlags=data.getFlags();
    cancelFolder();final int ticket=folderGeneration;
    final CancellationSignal cancellation=new CancellationSignal();folderCancellation=cancellation;
    status.setText("正在确认文件夹授权和文档身份…");
    folderPending=loader.submit(()->{
      try{
        PreviewTree tree=new PreviewTree(this,granted);
        // Query the chosen root even when the incoming URI belongs to another provider.
        tree.children(tree.root(),cancellation);
        PreviewTree.Entry identified=tree.identify(this,original,cancellation);
        cancellation.throwIfCanceled();
        final boolean remembered=PreviewTree.remember(this,granted,grantFlags);
        runOnUiThread(()->{
          if(!folderCurrent(ticket))return;
          Toast.makeText(this,remembered?"已记住文件夹读取权限，下次打开会自动复用":"提供方未允许记住权限，本次可用；下次可能需要重新授权",Toast.LENGTH_LONG).show();
          if(identified!=null&&!identified.directory()&&identified.markdown()){
            selectTreeDocument(granted,identified,true);return;
          }
          // Keep the original URI and source. Never substitute a same-name file.
          folderUri=granted;folder.setText("打开文件夹");
          new android.app.AlertDialog.Builder(this).setTitle("请选择 Markdown 文件")
            .setMessage("无法通过文件提供方的文档身份确认原文件在此文件夹中。请明确选择要预览的 Markdown；不会自动打开同名文件。")
            .setPositiveButton("打开文件夹",(dialog,which)->browseFolder(granted,""))
            .setNegativeButton("取消",(dialog,which)->status.setText("已授权文件夹；点“打开文件夹”选择 Markdown 后启用相对资源")).show();
        });
      }catch(Exception error){runOnUiThread(()->{if(folderCurrent(ticket))status.setText("文件夹无法读取或授权已撤销："+message(error)+"；请重新授权");});}
    });
  }

  private void browseFolder(Uri granted,String relative){
    cancelFolder();final int ticket=folderGeneration;
    final CancellationSignal cancellation=new CancellationSignal();folderCancellation=cancellation;
    status.setText("正在读取授权文件夹…");
    folderPending=loader.submit(()->{
      try{
        PreviewTree tree=new PreviewTree(this,granted);PreviewTree.Entry directory=tree.resolve(relative,cancellation);
        if(!directory.directory())throw new IOException("此项不是文件夹");
        List<PreviewTree.Entry> entries=new ArrayList<>();
        for(PreviewTree.Entry entry:tree.children(directory,cancellation))
          if(entry.directory()||entry.markdown())entries.add(entry);
        entries.sort((a,b)->a.directory()!=b.directory()?(a.directory()?-1:1):a.name.compareToIgnoreCase(b.name));
        String[] labels=new String[entries.size()+(relative.isEmpty()?0:1)];int offset=relative.isEmpty()?0:1;
        if(offset==1)labels[0]="返回上级文件夹";
        for(int i=0;i<entries.size();i++)labels[i+offset]=(entries.get(i).directory()?"文件夹 · ":"Markdown · ")+entries.get(i).name;
        cancellation.throwIfCanceled();
        runOnUiThread(()->{
          if(!folderCurrent(ticket))return;
          status.setText(entries.isEmpty()?"此文件夹没有可读取的 Markdown；可返回上级或重新授权":"请选择要预览的 Markdown");
          new android.app.AlertDialog.Builder(this).setTitle(relative.isEmpty()?"授权文件夹":relative)
            .setItems(labels,(dialog,which)->{
              if(offset==1&&which==0){browseFolder(granted,parentPath(relative));return;}
              PreviewTree.Entry chosen=entries.get(which-offset);
              if(chosen.directory())browseFolder(granted,chosen.relative);else selectTreeDocument(granted,chosen);
            }).setOnCancelListener(dialog->{
              if(!folderCurrent(ticket))return;
              if(relative.isEmpty())cancelFolder();else browseFolder(granted,parentPath(relative));
            }).setPositiveButton("重新授权",(dialog,which)->authorizeFolder()).setNegativeButton("取消",null).show();
        });
      }catch(Exception error){runOnUiThread(()->{
        if(!folderCurrent(ticket))return;
        status.setText("文件夹读取失败："+message(error));
        new android.app.AlertDialog.Builder(this).setTitle("需要重新授权文件夹").setMessage(message(error))
          .setPositiveButton("授权文件夹",(dialog,which)->authorizeFolder()).setNegativeButton("取消",null).show();
      });}
    });
  }

  private void selectTreeDocument(Uri granted,PreviewTree.Entry entry){selectTreeDocument(granted,entry,false);}
  private void selectTreeDocument(Uri granted,PreviewTree.Entry entry,boolean sameDocument){
    rememberPosition();if(!sameDocument)history.add(snapshotTarget());cancelFolder();
    treeUri=folderUri=granted;treeRelative=entry.relative;documentUri=entry.uri;documentMime=entry.mime;
    imageDocument=false;restoreScroll=sameDocument;
    if(!sameDocument){source=false;sourceY=renderX=renderY=0;fragment="";}
    load();
  }

  private static String parentPath(String relative){int slash=relative.lastIndexOf('/');return slash<0?"":relative.substring(0,slash);}
  private boolean folderCurrent(int ticket){return !destroyed&&!isFinishing()&&ticket==folderGeneration;}
  private void cancelFolder(){
    ++folderGeneration;if(folderPending!=null){folderPending.cancel(true);folderPending=null;}
    if(folderCancellation!=null){folderCancellation.cancel();folderCancellation=null;}
  }


  private void showWeb(){
    final File document=file,root=file.getParentFile();
    final String host="preview-"+UUID.randomUUID().toString()+".invalid";
    final Uri documentUrl=new Uri.Builder().scheme("https").authority(host).appendPath(file.getName()).build();
    final byte[] documentBytes=text.getBytes(StandardCharsets.UTF_8);
    final String documentMime=kind==PreviewFiles.Kind.SVG?"image/svg+xml":"text/html";
    final int ticket=generation;
    final WebView view=new WebView(this);web=view;
    WebSettings settings=view.getSettings();
    settings.setJavaScriptEnabled(true);settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setSupportMultipleWindows(false);
    settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setAllowFileAccessFromFileURLs(false);settings.setAllowUniversalAccessFromFileURLs(false);
    settings.setBlockNetworkLoads(true);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    settings.setDomStorageEnabled(false);settings.setDatabaseEnabled(false);settings.setGeolocationEnabled(false);settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
    settings.setMediaPlaybackRequiresUserGesture(true);settings.setSupportZoom(true);settings.setBuiltInZoomControls(true);settings.setDisplayZoomControls(false);
    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(view,false);
    // Unstyled documents use black browser text, so a dark native canvas would hide it.
    view.setBackgroundColor(android.graphics.Color.WHITE);view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
    view.setWebChromeClient(new WebChromeClient(){
      @Override public void onPermissionRequest(PermissionRequest request){request.deny();}
      @Override public void onGeolocationPermissionsShowPrompt(String origin,GeolocationPermissions.Callback callback){callback.invoke(origin,false,false);}
      @Override public boolean onShowFileChooser(WebView webView,ValueCallback<Uri[]> callback,FileChooserParams params){callback.onReceiveValue(null);return true;}
    });
    view.setDownloadListener((url,agent,disposition,mime,size)->Toast.makeText(this,"预览不下载文件；请使用外部应用",Toast.LENGTH_LONG).show());
    view.setWebViewClient(new WebViewClient(){
      private boolean restored;
      private boolean local(Uri uri){return "https".equals(uri.getScheme())&&host.equals(uri.getEncodedAuthority());}
      private boolean sameDocument(Uri uri){return local(uri)&&documentUrl.getEncodedPath().equals(uri.getEncodedPath());}
      @Override public WebResourceResponse shouldInterceptRequest(WebView ignored,WebResourceRequest request){
        Uri uri=request.getUrl();
        if(!local(uri)||!"GET".equals(request.getMethod()))return denied();
        try{
          if(!root.equals(root.getCanonicalFile()))throw new IOException("文档目录已改变");
          String decoded=uri.getPath();
          if(decoded==null||!decoded.startsWith("/"))return denied();
          File resource=PreviewFiles.resource(root,decoded.substring(1));
          resource=ArtifactProvider.checked(getApplicationContext(),resource.getPath());
          if(sameDocument(uri)){
            if(!resource.equals(document))return denied();
            return response(documentMime,"UTF-8",new ByteArrayInputStream(documentBytes));
          }
          if(request.isForMainFrame())return denied();
          String mime=resourceMime(resource);
          if(mime.startsWith("text/")||mime.equals("application/javascript")||mime.equals("image/svg+xml"))
            return response(mime,"UTF-8",new ByteArrayInputStream(PreviewFiles.readText(resource).getBytes(StandardCharsets.UTF_8)));
          return response(mime,null,new FileInputStream(resource));
        }catch(Exception error){
          runOnUiThread(()->{if(web==view&&current(ticket))status.setText("本地资源无法读取或已被拦截；可刷新或查看源码");});
          return denied();
        }
      }
      @Override public boolean shouldOverrideUrlLoading(WebView ignored,WebResourceRequest request){
        Uri uri=request.getUrl();
        if(request.isForMainFrame()&&sameDocument(uri))return false;
        if(request.isForMainFrame()&&request.hasGesture()&&!local(uri)&&isWebLink(uri))openLink(uri);
        else if(request.hasGesture())Toast.makeText(PreviewActivity.this,"仅支持文内锚点和手动打开 http(s) 链接",Toast.LENGTH_SHORT).show();
        return true;
      }
      @Override public boolean shouldOverrideUrlLoading(WebView ignored,String url){
        // Old callbacks cannot prove a user gesture, so they may never launch an Intent.
        return !sameDocument(Uri.parse(url));
      }
      @Override public void onPageFinished(WebView ignored,String url){
        if(web!=view||!current(ticket))return;
        progress.setVisibility(View.GONE);
        if(!restored){restored=true;view.post(()->{if(web==view&&current(ticket))view.scrollTo(renderX,renderY);});}
      }
      @Override public void onReceivedError(WebView ignored,WebResourceRequest request,WebResourceError error){
        if(request.isForMainFrame()&&web==view&&current(ticket))showError("页面加载失败："+error.getDescription());
      }
      @Override public void onReceivedHttpError(WebView ignored,WebResourceRequest request,WebResourceResponse response){
        if(request.isForMainFrame()&&web==view&&current(ticket))showError("文件已移除、改变或无法读取，请刷新");
      }
      @Override public boolean onRenderProcessGone(WebView ignored,RenderProcessGoneDetail detail){
        if(web==view&&current(ticket))showError("WebView 渲染进程已停止；请刷新、查看源码或使用外部应用");
        return true;
      }
    });
    content.addView(view,new FrameLayout.LayoutParams(-1,-1));progress.setVisibility(View.VISIBLE);
    view.loadUrl(fragment.isEmpty()?documentUrl.toString():documentUrl.buildUpon().fragment(fragment).build().toString());
  }

  private static WebResourceResponse response(String mime,String encoding,InputStream stream){
    Map<String,String> headers=new HashMap<>();headers.put("Content-Security-Policy",CSP);headers.put("X-Content-Type-Options","nosniff");
    headers.put("Cache-Control","no-store");headers.put("Referrer-Policy","no-referrer");
    return new WebResourceResponse(mime,encoding,200,"OK",headers,stream);
  }

  private static WebResourceResponse denied(){
    return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",java.util.Collections.singletonMap("Content-Security-Policy","default-src 'none'"),new ByteArrayInputStream(new byte[0]));
  }

  private static String resourceMime(File file){
    String name=file.getName().toLowerCase(Locale.ROOT);
    if(name.endsWith(".js")||name.endsWith(".mjs")||name.endsWith(".cjs"))return "application/javascript";
    if(name.endsWith(".css"))return "text/css";
    if(name.endsWith(".svg"))return "image/svg+xml";
    return ArtifactProvider.mime(file);
  }

  private static boolean isWebLink(Uri uri){return ("http".equalsIgnoreCase(uri.getScheme())||"https".equalsIgnoreCase(uri.getScheme()))&&uri.getHost()!=null&&uri.getUserInfo()==null;}

  private void openLink(Uri uri){
    try{OpenTargets.open(this,uri.toString(),"",true);}
    catch(Exception error){Toast.makeText(this,"无法打开链接："+message(error),Toast.LENGTH_LONG).show();}
  }

  private void openExternal(){
    try{
      if(documentUri==null){OpenTargets.open(this,new File(path).getPath(),"",true);return;}
      Intent view=new Intent(Intent.ACTION_VIEW).setDataAndType(documentUri,documentMime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      view.setClipData(ClipData.newRawUri("Markdown",documentUri));
      boolean available=false;
      for(ResolveInfo handler:getPackageManager().queryIntentActivities(view,PackageManager.MATCH_DEFAULT_ONLY))
        if(!getPackageName().equals(handler.activityInfo.packageName)){available=true;break;}
      if(!available)throw new IOException("没有可打开此文档的外部应用");
      Intent chooser=Intent.createChooser(view,"外部打开 Markdown");
      chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS,new ComponentName[]{new ComponentName(this,MarkdownEntryActivity.class)});
      startActivity(chooser);
    }
    catch(Exception error){Toast.makeText(this,"无法外部打开："+message(error),Toast.LENGTH_LONG).show();status.setText("外部打开失败："+message(error));}
  }

  private void showError(String detail){
    disposeWeb();content.removeAllViews();scroll=null;progress.setVisibility(View.GONE);
    mode.setEnabled(text!=null&&kind!=PreviewFiles.Kind.TEXT);status.setTextColor(theme.error);status.setText("无法预览");
    ScrollView errorScroll=new ScrollView(this);TextView error=Ui.text(this,detail+"\n\n刷新可重新读取已生成的文件，也可尝试外部打开。预览不会截断文件或将二进制伪装成文本。",16,theme.ink);
    error.setTextIsSelectable(true);error.setPadding(dp(20),dp(20),dp(20),dp(20));errorScroll.addView(error);content.addView(errorScroll,new FrameLayout.LayoutParams(-1,-1));
  }

  private void rememberPosition(){
    if(web!=null){renderX=web.getScrollX();renderY=web.getScrollY();String url=web.getUrl();String anchor=url==null?null:Uri.parse(url).getFragment();fragment=anchor==null?"":anchor;}
    else if(scroll!=null){if(source||kind==PreviewFiles.Kind.TEXT)sourceY=scroll.getScrollY();else renderY=scroll.getScrollY();}
  }

  private void disposeWeb(){
    if(markdownWeb!=null){markdownWeb.close();markdownWeb=null;}
    WebView old=web;web=null;
    if(old!=null){old.stopLoading();old.setWebChromeClient(null);old.setWebViewClient(new WebViewClient());old.setDownloadListener(null);
      if(old.getParent() instanceof ViewGroup)((ViewGroup)old.getParent()).removeView(old);old.removeAllViews();old.destroy();}
  }

  @Override protected void onSaveInstanceState(Bundle state){
    rememberPosition();state.putBoolean("source",source);state.putBoolean("overlay",fromOverlay);state.putInt("sourceY",sourceY);state.putInt("renderY",renderY);state.putInt("renderX",renderX);state.putString("fragment",fragment);
    state.putBundle("target",snapshotTarget());state.putParcelableArrayList("history",history);super.onSaveInstanceState(state);
  }
  @Override protected void onResume(){super.onResume();if(web!=null)web.onResume();}
  @Override protected void onPause(){rememberPosition();if(web!=null)web.onPause();SystemWindowNavigation.remember(this);super.onPause();}
  @Override protected void onDestroy(){
    destroyed=true;++generation;cancelRead();cancelFolder();loader.shutdownNow();disposeWeb();
    if(isFinishing()&&fromOverlay&&!isReturningToMenu())FloatingNavigation.restore(getApplicationContext());super.onDestroy();
  }
  @Override public void onBackPressed(){
    cancelFolder();
    if(web!=null&&web.canGoBack()){web.goBack();return;}
    if(previousDocument())return;
    navigateUp();
  }
  private boolean previousDocument(){
    if(history.isEmpty())return false;
    cancelFolder();restoreTarget(history.remove(history.size()-1));restoreScroll=true;load();return true;
  }
  private int dp(float value){return Ui.dp(this,value);}
  private static String message(Exception error){return error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();}
}
