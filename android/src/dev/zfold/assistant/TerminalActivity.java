package dev.zfold.assistant;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.net.Uri;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import com.termux.terminal.TerminalSession;
import com.termux.view.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Real terminal surface: back detaches; ending a process is a named action. */
public final class TerminalActivity extends NavigableActivity implements TerminalViewClient,TerminalSessions.Listener {
  static void open(Context context){SystemWindowNavigation.open(context,TerminalActivity.class);}
  @Override protected void onNewIntent(Intent intent){
    super.onNewIntent(intent);setIntent(intent);
    if(sessions!=null)openRequestedSession();
    if(circlePanel!=null)circlePanel.refresh(true);
  }
  private TerminalSessions sessions;private TerminalSessions.Job current;
  private TerminalView terminal;private TextView control,keyboard,installStatus;private boolean ctrl,tapToShowKeyboard;
  private TerminalTabs tabs;
  private TerminalWorkspaces workspaces;
  private TerminalCirclePanel circlePanel;
  private LinearLayout empty;private ScrollView emptyScroll;private TextView emptyDetail;private Dialog workspacePanel;
  private TerminalSessions.Job pendingViewport;
  private static final int PICK_PREVIEW=42;
  private float fontSize=14;private LinearLayout root;private boolean installing;
  private static final int PICK_ATTACHMENTS=41;
  private final ExecutorService attachmentWorker=Executors.newSingleThreadExecutor(r->new Thread(r,"terminal-attachments"));
  private Ui.Action files;private TextView attachmentStatus;
  private String pendingPickerHandle;
  private TerminalSessions.Job attachmentTarget;
  private CancellationSignal attachmentCancellation;
  private TerminalAttachments.Result preparedAttachments;
  private AlertDialog attachmentConfirmation;
  private boolean attachmentSwitched,resumed,destroyed;
  private static final int INK=0xffe5e9ed,MUTED=0xff95a1ad,BG=0xff0c1117,SURFACE=0xff17212c;

  @Override protected void onCreate(Bundle state){
    super.onCreate(state);getWindow().setDecorFitsSystemWindows(false);
    getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
    WindowManager.LayoutParams attributes=getWindow().getAttributes();attributes.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;getWindow().setAttributes(attributes);
    fontSize=getPreferences(MODE_PRIVATE).getFloat("fontSize",14);
    tapToShowKeyboard=getPreferences(MODE_PRIVATE).getBoolean("tapToShowKeyboard",false);
    if(state!=null){getIntent().setAction(null);pendingPickerHandle=state.getString("terminal_attachment_picker");attachmentSwitched=state.getBoolean("terminal_attachment_switched");}
    sessions=TerminalSessions.get(this);workspaces=TerminalWorkspaces.get(this);sessions.observe(this);
    circlePanel=new TerminalCirclePanel(this,sessions,()->resumed&&!destroyed&&!isFinishing()&&!attachmentsBusy(),this::insertCircle,this::newSession,state);build();
    TerminalSessions.Job restored=state==null?null:sessions.jobs.get(state.getString("terminal_session"));
    boolean interrupted=state!=null&&state.getBoolean("terminal_attachment_importing");
    if(restored!=null)attach(restored);
    else if(pendingPickerHandle!=null||interrupted){
      TerminalSessions.Job target=sessions.jobs.get(pendingPickerHandle);
      if(target!=null)attach(target);
      else{pendingPickerHandle=null;showEmpty();attachmentNotice("原终端进程已不存在，未插入文件路径。请明确创建新终端后重试。");}
    }else openRequestedSession();
    if(pendingPickerHandle!=null){files.setEnabled(false);setAttachmentStatus("正在选择本地文件…");}
    if(interrupted)attachmentNotice("文件导入已因界面重建取消，请重新选择本地文件。");
  }
  private boolean isEnvironmentEntry(){
    String action=getIntent().getAction();
    return LauncherActivity.ACTION_UBUNTU_TERMINAL.equals(action)||LauncherActivity.ACTION_ANDROID_TERMINAL.equals(action);
  }
  private void openRequestedSession(){
    boolean explicit=isEnvironmentEntry()&&LauncherActivity.currentProcessEntry(getIntent());
    boolean ubuntu=LauncherActivity.ACTION_UBUNTU_TERMINAL.equals(getIntent().getAction());
    // Recents may retain an old shortcut intent after process death; it is not a new launch request.
    getIntent().setAction(null);
    try{
      TerminalSessions.Job job=explicit?sessions.interactive(ubuntu):sessions.selected();
      if(job==null)showEmpty();else attach(job);
    }catch(Exception error){if(current==null)showEmpty();showStartError(error);}
  }
  @Override protected void onSaveInstanceState(Bundle state){
    super.onSaveInstanceState(state);
    if(current!=null)state.putString("terminal_session",current.session.mHandle);
    if(current!=null)current.topRow=terminal.getTopRow();
    state.putString("terminal_attachment_picker",pendingPickerHandle);
    state.putBoolean("terminal_attachment_switched",attachmentSwitched);
    state.putBoolean("terminal_attachment_importing",attachmentCancellation!=null);
    if(circlePanel!=null)circlePanel.saveState(state);
  }
  private void build(){
    root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);
    root.setOnApplyWindowInsetsListener((view,insets)->{
      Insets safe=insets.getInsets(WindowInsets.Type.displayCutout()),ime=insets.getInsets(WindowInsets.Type.ime());
      // Fill the top edge, including the camera area; transient system bars overlay rather than resize.
      root.setPadding(safe.left,0,safe.right,Math.max(safe.bottom,ime.bottom));return insets;
    });
    tabs=new TerminalTabs(this,sessions,this::attach,this::showTabOptions,this::newSession,this::showOptions,this::showWorkspaces);root.addView(tabs);
    installStatus=Ui.text(this,"",12,MUTED);installStatus.setPadding(dp(16),dp(8),dp(16),dp(8));installStatus.setBackgroundColor(SURFACE);installStatus.setVisibility(View.GONE);root.addView(installStatus);
    root.addView(circlePanel.reopen);
    terminal=new TerminalView(this,null);terminal.setTerminalViewClient(this);terminal.setTextSize(sp(fontSize));terminal.setTypeface(Typeface.MONOSPACE);
    terminal.setFocusable(true);terminal.setFocusableInTouchMode(true);
    terminal.setBackgroundColor(BG);terminal.setVerticalScrollBarEnabled(true);terminal.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
    terminal.addOnLayoutChangeListener((view,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom)->restoreViewport());
    FrameLayout terminalHost=new FrameLayout(this);terminalHost.setPadding(dp(12),dp(8),dp(12),dp(4));terminalHost.addView(terminal,new FrameLayout.LayoutParams(-1,-1));
    empty=new LinearLayout(this);empty.setOrientation(LinearLayout.VERTICAL);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(24),dp(24),dp(24),dp(24));
    TextView title=Ui.text(this,"TUI 工作台",24,INK);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);empty.addView(title);
    emptyDetail=Ui.text(this,"",14,MUTED);emptyDetail.setGravity(Gravity.CENTER);emptyDetail.setPadding(0,dp(16),0,dp(24));emptyDetail.setLineSpacing(dp(5),1);empty.addView(emptyDetail);
    Ui.Action create=new Ui.Action(this,R.drawable.ic_terminal,"新建终端","创建工作区终端",INK,0xff294437,this::newSession);empty.addView(create,new LinearLayout.LayoutParams(-2,dp(48)));
    TextView projects=key("选择工作区","选择工作区",this::showWorkspaces);empty.addView(projects,new LinearLayout.LayoutParams(-2,dp(48)));
    emptyScroll=new ScrollView(this);emptyScroll.setFillViewport(true);emptyScroll.addView(empty,new ScrollView.LayoutParams(-1,-2));
    terminalHost.addView(emptyScroll,new FrameLayout.LayoutParams(-1,-1));emptyScroll.setVisibility(View.GONE);
    root.addView(terminalHost,new LinearLayout.LayoutParams(-1,0,1));
    attachmentStatus=Ui.text(this,"",12,MUTED);attachmentStatus.setPadding(dp(16),dp(8),dp(16),dp(8));attachmentStatus.setBackgroundColor(SURFACE);attachmentStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);attachmentStatus.setVisibility(View.GONE);root.addView(attachmentStatus);
    HorizontalScrollView rail=new HorizontalScrollView(this);rail.setHorizontalScrollBarEnabled(false);rail.setBackgroundColor(SURFACE);
    LinearLayout keys=new LinearLayout(this);keys.setGravity(Gravity.CENTER_VERTICAL);keys.setPadding(dp(4),0,dp(4),0);
    files=iconKey(R.drawable.ic_file,"本地文件：选择图片或文件，仅插入路径，不发送",this::chooseAttachments);keys.addView(files);
    control=key("Ctrl","切换 Ctrl 修饰键",()->{ctrl=!ctrl;updateControl();});keys.addView(control);
    keys.addView(key("⌃C","中断当前命令",()->{if(current!=null)current.session.writeCodePoint(false,3);clearCtrl();}));
    keys.addView(key("Esc","发送 Escape",()->sendKey(KeyEvent.KEYCODE_ESCAPE)));
    keys.addView(key("Tab","Tab 补全",()->sendKey(KeyEvent.KEYCODE_TAB)));
    keys.addView(iconKey(R.drawable.ic_return,"回车 / 执行当前输入",()->sendKey(KeyEvent.KEYCODE_ENTER)));
    keys.addView(key("↑","上一条命令或向上",()->sendKey(KeyEvent.KEYCODE_DPAD_UP)));
    keys.addView(key("↓","下一条命令或向下",()->sendKey(KeyEvent.KEYCODE_DPAD_DOWN)));
    keys.addView(key("←","光标向左",()->sendKey(KeyEvent.KEYCODE_DPAD_LEFT)));
    keys.addView(key("→","光标向右",()->sendKey(KeyEvent.KEYCODE_DPAD_RIGHT)));
    keys.addView(iconKey(R.drawable.ic_clipboard,"粘贴到终端",()->{if(current!=null)paste(current);}));
    keyboard=key("键盘","点击终端弹出键盘",this::toggleKeyboardMode);updateKeyboardMode();keys.addView(keyboard);
    rail.addView(keys);root.addView(rail,new LinearLayout.LayoutParams(-1,dp(48)));
    setContentView(root);
  }
  private void enterFullscreen(){
    WindowInsetsController controller=getWindow().getInsetsController();
    if(controller==null)return;
    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    controller.setSystemBarsAppearance(0,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
    controller.hide(WindowInsets.Type.systemBars());
  }
  private TextView key(String label,String description,Runnable action){
    TextView value=Ui.text(this,label,13,INK);value.setGravity(Gravity.CENTER);value.setContentDescription(description);value.setFocusable(true);value.setMinimumWidth(dp(48));value.setMinimumHeight(dp(48));
    value.setPadding(dp(12),0,dp(12),0);value.setBackground(Ui.ripple(this,Color.TRANSPARENT,10,INK));
    value.setOnClickListener(v->{Ui.click(v);action.run();});return value;
  }
  private Ui.Action iconKey(int icon,String description,Runnable action){
    Ui.Action value=new Ui.Action(this,icon,"",description,INK,Color.TRANSPARENT,action);
    value.setCompact(true);value.setTooltipText(description);
    value.setLayoutParams(new LinearLayout.LayoutParams(dp(48),dp(48)));return value;
  }
  private void attach(TerminalSessions.Job job){
    if((attachmentTarget!=null&&attachmentTarget!=job)||(pendingPickerHandle!=null&&!pendingPickerHandle.equals(job.session.mHandle)))attachmentSwitched=true;
    if(current!=null&&current!=job)current.topRow=terminal.getTopRow();
    boolean switching=terminal.getCurrentSession()!=job.session;
    terminal.setVisibility(View.VISIBLE);emptyScroll.setVisibility(View.GONE);
    terminal.attachSession(job.session);
    current=job;sessions.select(job);
    if(switching){pendingViewport=job;restoreViewport();}
    ctrl=job.ctrl;updateControl();terminal.requestFocus();tabs.sync(current);changed(job);
    try{TerminalWorkspaces.Entry saved=workspaces.find(job.workspace.id);if(saved!=null&&!workspaces.selected().id.equals(saved.id))workspaces.select(saved.id);}
    catch(Exception error){attachmentNotice(error.getMessage());}
  }
  private void restoreViewport(){
    TerminalSessions.Job job=pendingViewport;
    if(job==null||job!=current||terminal.getCurrentSession()!=job.session||terminal.getWidth()==0||terminal.getHeight()==0)return;
    pendingViewport=null;terminal.setTopRow(job.topRow);terminal.onScreenUpdated();
  }
  private void newSession(){
    if(sessions.liveCount()>=4){showStartError(new IllegalStateException("已有 4 个运行中的终端。请先明确关闭一个；不会自动结束已有标签。"));return;}
    TerminalWorkspacePanel.configure(this,workspaces,true,entry->attach(sessions.create(entry)));
  }
  private void showEmpty(){
    current=null;pendingViewport=null;tabs.sync(null);terminal.attachSession(null);terminal.clearFocus();
    ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(terminal.getWindowToken(),0);
    terminal.setVisibility(View.INVISIBLE);emptyScroll.setVisibility(View.VISIBLE);clearCtrl();
    String message=sessions.interrupted?"上次终端进程已不在，未恢复或重放旧命令。\n请选择目录和环境，明确创建新的 Shell。":"选择项目目录和运行环境，开始一个终端。\n已有安装可复用，不复制 Ubuntu 或项目。";
    try{TerminalWorkspaces.Entry entry=workspaces.selected();message+="\n\n"+entry.name+" · "+(entry.ubuntu?"Ubuntu":"Android");}
    catch(Exception error){message+="\n\n"+error.getMessage();}
    emptyDetail.setText(message);
  }
  private void showWorkspaces(){
    if(workspacePanel!=null&&workspacePanel.isShowing())return;
    workspacePanel=TerminalWorkspacePanel.show(this,workspaces,sessions,this::attach,()->{if(current==null)showEmpty();},this::newSession,this::choosePreview);
  }
  private void choosePreview(){
    Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
      .putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"text/markdown","text/x-markdown","text/plain","application/markdown","application/x-markdown","image/*"})
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    try{startActivityForResult(picker,PICK_PREVIEW);}catch(ActivityNotFoundException|SecurityException error){attachmentNotice("无法打开文件选择器："+error.getMessage());}
  }
  private void showStartError(Exception error){
    new AlertDialog.Builder(this).setTitle("无法启动新终端").setMessage(error.getMessage()).setPositiveButton("选择工作区",(dialog,which)->showWorkspaces()).setNegativeButton("返回",null).show();
  }
  private void confirmClose(TerminalSessions.Job job){
    if(sessions.find(job.session)!=job)return;
    new AlertDialog.Builder(this).setTitle("结束终端及进程？")
      .setMessage(job.name+" · "+job.workspace.name+"\n将结束此 PTY 及其受控进程，不是隐藏标签；未保存的程序状态可能丢失。其他终端不受影响。")
      .setPositiveButton("结束进程",(dialog,which)->job.close()).setNegativeButton("保留",null).show();
  }
  private String details(TerminalSessions.Job job){
    return job.name+" · "+(job.ubuntu?"Ubuntu":"Android")+"\n工作区："+job.workspace.name+"\n"+job.status+"\n启动目录："+(job.cwd.isEmpty()?job.workspace.path:job.cwd);
  }
  private void showTabOptions(TerminalSessions.Job job){
    if(sessions.find(job.session)!=job)return;
    new AlertDialog.Builder(this).setTitle(details(job)).setItems(new String[]{"重命名","关闭会话及进程"},(dialog,which)->{
      if(which==0)renameSession(job);else confirmClose(job);
    }).setNegativeButton("返回",null).show();
  }
  private void renameSession(TerminalSessions.Job job){
    EditText name=new EditText(this);name.setSingleLine(true);name.setText(job.name);name.setSelectAllOnFocus(true);name.setContentDescription("会话名称");
    LinearLayout body=new LinearLayout(this);body.setPadding(dp(20),0,dp(20),0);body.addView(name,new LinearLayout.LayoutParams(-1,-2));
    AlertDialog dialog=new AlertDialog.Builder(this).setTitle("重命名会话").setView(body).setPositiveButton("保存",null).setNegativeButton("取消",null).create();dialog.show();
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String value=name.getText().toString().trim();if(value.isEmpty()||value.codePointCount(0,value.length())>80){name.setError("请输入 1–80 个字符");return;}sessions.rename(job,value);dialog.dismiss();});
    name.requestFocus();dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
  }
  private void showOptions(){
    String[] items={tapToShowKeyboard?"关闭点击弹键盘":"开启点击弹键盘","复制当前终端记录","放大文字","缩小文字","安装 / 更新 Bash","结束当前会话及进程","收起终端，保留会话","启动设置","设置与手机工具"};
    new AlertDialog.Builder(this).setTitle(current==null?"终端选项":details(current)).setItems(items,(dialog,which)->{
      switch(which){
        case 0:dialog.dismiss();terminal.post(this::toggleKeyboardMode);break;
        case 1:if(current!=null&&current.session.getEmulator()!=null){sessions.onCopyTextToClipboard(current.session,current.session.getEmulator().getScreen().getTranscriptText());Toast.makeText(this,"已复制终端记录",Toast.LENGTH_SHORT).show();}break;
        case 2:changeFont(1);break;
        case 3:changeFont(-1);break;
        case 4:installBash();break;
        case 5:if(current!=null)confirmClose(current);break;
        case 6:finish();break;
        case 7:StartupPreferences.show(this);break;
        case 8:startActivity(new Intent(this,SettingsActivity.class));break;
      }
    }).setNegativeButton("返回",null).show();
  }
  private void installBash(){
    if(installing)return;
    if(getApplicationInfo().targetSdkVersion!=28){new AlertDialog.Builder(this).setTitle("当前是受限运行时").setMessage("安装的本地程序需要 compat28 运行时版本。当前仍可使用系统 Shell。").setPositiveButton("知道了",null).show();return;}
    installing=true;installStatus.setText("正在安装 Bash…");installStatus.setVisibility(View.VISIBLE);
    new Thread(()->{
      try{PiPackageManager.install(this,"bash",message->runOnUiThread(()->{if(!isFinishing()&&installing)installStatus.setText(message);}));runOnUiThread(()->{installing=false;installStatus.setVisibility(View.GONE);if(isFinishing())return;Toast.makeText(this,"Bash 已就绪，正在打开新会话",Toast.LENGTH_SHORT).show();newSession();});}
      catch(Exception error){runOnUiThread(()->{installing=false;installStatus.setVisibility(View.GONE);if(!isFinishing())new AlertDialog.Builder(this).setTitle("Bash 安装未完成").setMessage(error.getMessage()).setPositiveButton("重试",(d,w)->installBash()).setNegativeButton("返回",null).show();});}
    },"terminal-install-bash").start();
  }
  private boolean liveAttachmentTarget(TerminalSessions.Job job){
    return job!=null&&sessions.find(job.session)==job&&!job.closing&&job.session.isRunning()&&job.session.getEmulator()!=null;
  }
  private boolean attachmentsBusy(){return pendingPickerHandle!=null||attachmentCancellation!=null||attachmentConfirmation!=null;}
  private void refreshCircle(){if(circlePanel!=null)circlePanel.refresh(false);}
  private void insertCircle(TerminalCircleDraft.Draft draft,TerminalSessions.Job target)throws java.io.IOException{
    if(!resumed||destroyed||attachmentsBusy()||!liveAttachmentTarget(target))throw new java.io.IOException("目标终端已关闭或当前正在导入文件，未插入圈选。");
    String text=TerminalCircleDraft.insertion(this,draft,target.ubuntu);
    // Keep the exact Job identity: neither the current tab nor a replacement is an implicit target.
    if(!liveAttachmentTarget(target))throw new java.io.IOException("原目标已关闭，请重新选择终端。");
    if(!TerminalCircleDraft.consume(this,draft.id))throw new java.io.IOException("草稿已变化或已插入，请重新打开。");
    attach(target);clearCtrl();target.session.getEmulator().paste(text);
    attachmentNotice("已插入圈选图片路径与问题，尚未发送。");restoreAttachmentFocus();
  }
  private void chooseAttachments(){
    if(pendingPickerHandle!=null||attachmentCancellation!=null)return;
    if(!liveAttachmentTarget(current)){attachmentNotice("终端尚未就绪或已关闭，无法插入文件路径。");return;}
    clearCtrl();pendingPickerHandle=current.session.mHandle;attachmentSwitched=false;files.setEnabled(false);setAttachmentStatus("正在选择本地文件…");
    Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
    picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    try{startActivityForResult(picker,PICK_ATTACHMENTS);}
    catch(ActivityNotFoundException|SecurityException error){pendingPickerHandle=null;files.setEnabled(true);attachmentNotice("无法打开系统文件选择器："+error.getMessage());}
  }
  @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
    super.onActivityResult(requestCode,resultCode,data);
    if(root!=null)root.post(this::refreshCircle);
    if(requestCode==PICK_PREVIEW){
      if(resultCode==RESULT_OK&&data!=null&&data.getData()!=null&&!destroyed){
        Uri uri=data.getData();
        if(!"content".equals(uri.getScheme())){attachmentNotice("请选择系统文件选择器提供的文件。");return;}
        try{
          if((data.getFlags()&Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)!=0)
            getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
          startActivity(new Intent(this,PreviewActivity.class).setAction(Intent.ACTION_VIEW)
            .setDataAndType(uri,getContentResolver().getType(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        }catch(Exception error){attachmentNotice("无法预览："+error.getMessage());}
      }
      return;
    }
    if(requestCode!=PICK_ATTACHMENTS||destroyed)return;
    String handle=pendingPickerHandle;pendingPickerHandle=null;files.setEnabled(true);setAttachmentStatus("");
    if(resultCode!=RESULT_OK){restoreAttachmentFocus();return;}
    if(handle==null){attachmentNotice("没有可接收文件的原终端会话，请重新选择本地文件。");return;}
    TerminalSessions.Job target=sessions.jobs.get(handle);
    if(!liveAttachmentTarget(target)){attachmentNotice("原终端会话已关闭或尚未就绪，无法插入；请在目标终端重新选择文件。");return;}
    LinkedHashSet<Uri> selected=new LinkedHashSet<>();
    if(data!=null){
      ClipData clips=data.getClipData();
      if(clips!=null)for(int i=0;i<clips.getItemCount();i++)if(clips.getItemAt(i).getUri()!=null)selected.add(clips.getItemAt(i).getUri());
      if(data.getData()!=null)selected.add(data.getData());
    }
    if(selected.isEmpty()){attachmentNotice("未收到可读取的文件，请重新选择。");return;}
    if(selected.size()>TerminalAttachments.MAX_FILES){attachmentNotice("一次最多选择 "+TerminalAttachments.MAX_FILES+" 个文件，已取消本次插入，请重新选择。");return;}
    for(Uri uri:selected)if(!"content".equals(uri.getScheme())){attachmentNotice("文件选择器未提供可读取的文档，请重新选择。");return;}
    if(attachmentCancellation!=null){attachmentNotice("正在处理上一批文件，请稍后重新选择。");return;}
    Context context=getApplicationContext();
    CancellationSignal cancellation=new CancellationSignal();
    attachmentTarget=target;attachmentCancellation=cancellation;files.setEnabled(false);setAttachmentStatus("正在准备文件路径…");
    ArrayList<Uri> uris=new ArrayList<>(selected);
    attachmentWorker.execute(()->{
      try{
        TerminalAttachments.Result result=TerminalAttachments.prepare(context,uris,target.ubuntu,cancellation,message->runOnUiThread(()->{
          if(acceptsAttachmentResult(cancellation))setAttachmentStatus(message);
        }));
        runOnUiThread(()->{
          if(!acceptsAttachmentResult(cancellation))return;
          preparedAttachments=result;completeAttachments();
        });
      }catch(Exception error){
        runOnUiThread(()->{
          if(!acceptsAttachmentResult(cancellation))return;
          finishAttachments();attachmentNotice("无法准备文件："+(error.getMessage()==null?"读取失败，请重新选择。":error.getMessage()));
        });
      }
    });
  }
  private boolean acceptsAttachmentResult(CancellationSignal cancellation){
    return cancellation!=null&&!destroyed&&!isFinishing()&&attachmentCancellation==cancellation&&!cancellation.isCanceled();
  }
  private void completeAttachments(){
    if(!resumed||preparedAttachments==null||attachmentConfirmation!=null||!acceptsAttachmentResult(attachmentCancellation))return;
    if(!liveAttachmentTarget(attachmentTarget)){finishAttachments();attachmentNotice("原终端会话已关闭，未插入文件路径；请在目标终端重新选择文件。");return;}
    if(current!=attachmentTarget||attachmentSwitched){
      setAttachmentStatus("文件路径已准备好，等待确认目标终端。");
      attachmentConfirmation=new AlertDialog.Builder(this).setTitle("确认插入文件路径")
        .setMessage("选择文件后终端标签发生了切换。切换到「"+attachmentTarget.name+"」并插入路径？不会自动发送或回车。")
        .setPositiveButton("切换并插入",(dialog,which)->{
          attachmentConfirmation=null;
          if(!resumed||!acceptsAttachmentResult(attachmentCancellation))return;
          if(!liveAttachmentTarget(attachmentTarget)){finishAttachments();attachmentNotice("原终端会话已关闭，未插入文件路径。");return;}
          attach(attachmentTarget);insertAttachments();
        })
        .setNegativeButton("不插入",(dialog,which)->discardAttachments())
        .setOnCancelListener(dialog->discardAttachments()).create();
      attachmentConfirmation.show();return;
    }
    insertAttachments();
  }
  private void insertAttachments(){
    if(!resumed||preparedAttachments==null||!acceptsAttachmentResult(attachmentCancellation))return;
    if(current!=attachmentTarget||!liveAttachmentTarget(attachmentTarget)){finishAttachments();attachmentNotice("目标终端已变化，未插入；请重新选择本地文件。");return;}
    TerminalAttachments.Result result=preparedAttachments;
    preparedAttachments=null;clearCtrl();attachmentTarget.session.getEmulator().paste(result.text);
    finishAttachments();attachmentNotice("已插入 "+result.selectedCount+" 个文件路径"+(result.importedCount>0?"（已导入 "+result.importedCount+" 个）":"")+"，尚未发送。");restoreAttachmentFocus();
  }
  private void discardAttachments(){
    attachmentConfirmation=null;finishAttachments();attachmentNotice("未插入文件路径；需要时请在目标终端重新选择本地文件。");restoreAttachmentFocus();
  }
  private void finishAttachments(){
    attachmentTarget=null;attachmentCancellation=null;preparedAttachments=null;attachmentSwitched=false;files.setEnabled(true);setAttachmentStatus("");
    root.post(this::refreshCircle);
  }
  private void setAttachmentStatus(String message){attachmentStatus.setText(message);attachmentStatus.setVisibility(message.isEmpty()?View.GONE:View.VISIBLE);}
  private void attachmentNotice(String message){setAttachmentStatus("");Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
  private void restoreAttachmentFocus(){if(current!=null){terminal.requestFocus();if(tapToShowKeyboard)showKeyboard();}}
  private void sendKey(int code){if(current!=null)terminal.onKeyDown(code,new KeyEvent(0,0,KeyEvent.ACTION_DOWN,code,0,ctrl?KeyEvent.META_CTRL_ON:0));clearCtrl();}
  private void clearCtrl(){ctrl=false;updateControl();}
  private void updateControl(){if(current!=null)current.ctrl=ctrl;control.setTextColor(ctrl?0xffa5e1b4:INK);control.setBackground(Ui.ripple(this,ctrl?0xff294437:Color.TRANSPARENT,10,INK));control.setSelected(ctrl);control.setStateDescription(ctrl?"已开启":"已关闭");}
  private void showKeyboard(){if(current==null)return;terminal.requestFocus();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(terminal,InputMethodManager.SHOW_IMPLICIT);}
  private void toggleKeyboardMode(){
    tapToShowKeyboard=!tapToShowKeyboard;getPreferences(MODE_PRIVATE).edit().putBoolean("tapToShowKeyboard",tapToShowKeyboard).apply();updateKeyboardMode();
    if(tapToShowKeyboard)showKeyboard();else ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(terminal.getWindowToken(),0);
  }
  private void updateKeyboardMode(){
    keyboard.setSelected(tapToShowKeyboard);keyboard.setStateDescription(tapToShowKeyboard?"已开启":"已关闭");
    keyboard.setTextColor(tapToShowKeyboard?0xffa5e1b4:INK);keyboard.setBackground(Ui.ripple(this,tapToShowKeyboard?0xff294437:Color.TRANSPARENT,10,INK));
  }
  private void changeFont(float delta){fontSize=Math.max(10,Math.min(24,fontSize+delta));terminal.setTextSize(sp(fontSize));getPreferences(MODE_PRIVATE).edit().putFloat("fontSize",fontSize).apply();}
  private int dp(float value){return Ui.dp(this,value);}private int sp(float value){return Math.round(value*getResources().getDisplayMetrics().scaledDensity);}

  @Override public void changed(TerminalSessions.Job job){if(isFinishing())return;tabs.update(job,current);if(job==current)terminal.onScreenUpdated();}
  @Override public void removed(TerminalSessions.Job job,TerminalSessions.Job selected){
    if(isFinishing())return;
    if(!job.session.isRunning()&&job.session.getExitStatus()!=0)Toast.makeText(this,job.name+" · "+job.status,Toast.LENGTH_LONG).show();
    if(job==current){current=null;if(selected==null)showEmpty();else attach(selected);}
    else tabs.sync(current);
    refreshCircle();
  }
  @Override public void paste(TerminalSessions.Job job){ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(clipboard.hasPrimaryClip()&&clipboard.getPrimaryClip().getItemCount()>0&&job.session.getEmulator()!=null)job.session.getEmulator().paste(clipboard.getPrimaryClip().getItemAt(0).coerceToText(this).toString());}
  @Override protected void onResume(){super.onResume();resumed=true;enterFullscreen();if(sessions!=null)sessions.observe(this);if(tabs!=null)tabs.sync(current);if(terminal!=null)terminal.setTerminalCursorBlinkerState(true,true);completeAttachments();refreshCircle();}
  @Override protected void onPause(){resumed=false;if(circlePanel!=null)circlePanel.pause();SystemWindowNavigation.remember(this);if(terminal!=null)terminal.setTerminalCursorBlinkerState(false,false);super.onPause();}
  @Override protected void onDestroy(){
    destroyed=true;
    if(circlePanel!=null)circlePanel.pause();
    if(current!=null&&terminal!=null)current.topRow=terminal.getTopRow();
    if(workspacePanel!=null){workspacePanel.dismiss();workspacePanel=null;}
    if(attachmentCancellation!=null)attachmentCancellation.cancel();
    attachmentWorker.shutdownNow();
    if(attachmentConfirmation!=null){attachmentConfirmation.dismiss();attachmentConfirmation=null;}
    if(sessions!=null)sessions.detach(this);super.onDestroy();
  }
  @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused)enterFullscreen();}
  @Override public void onBackPressed(){showWorkspaces();}
  public float onScale(float scale){if(scale>1.1f){changeFont(1);return 1;}if(scale<.9f){changeFont(-1);return 1;}return scale;}
  public void onSingleTapUp(MotionEvent event){
    if(tapToShowKeyboard)showKeyboard();
  }
  public void onOpenLink(String target){
    try{OpenTargets.open(this,TerminalLinkTargets.androidTarget(target,ArtifactProvider.root(this).getPath()),"",false);}
    catch(Exception error){Toast.makeText(this,"无法打开："+error.getMessage(),Toast.LENGTH_LONG).show();}
  }
  public void onOpenImage(android.graphics.Bitmap bitmap){ChatImages.openTerminalImage(this,bitmap);}
  public boolean shouldBackButtonBeMappedToEscape(){return false;}
  public boolean shouldEnforceCharBasedInput(){return false;}
  public boolean shouldUseCtrlSpaceWorkaround(){return true;}
  public boolean isTerminalViewSelected(){return current!=null&&terminal.hasFocus();}
  public void copyModeChanged(boolean copying){}
  public boolean onKeyDown(int keyCode,KeyEvent event,TerminalSession session){return false;}
  public boolean onKeyUp(int keyCode,KeyEvent event){return super.onKeyUp(keyCode,event);}
  public boolean onLongPress(MotionEvent event){return false;}
  public boolean readControlKey(){return ctrl;}
  public boolean readAltKey(){return false;}
  public boolean readShiftKey(){return false;}
  public boolean readFnKey(){return false;}
  public boolean onCodePoint(int codePoint,boolean ctrlDown,TerminalSession session){
    if(ctrl){TerminalSessions.Job target=sessions.find(session);if(target!=null)target.ctrl=false;terminal.post(()->{if(current==target)clearCtrl();});}
    return false;
  }
  public void onEmulatorSet(){}
  public void logError(String tag,String message){android.util.Log.e(tag,message);}
  public void logWarn(String tag,String message){android.util.Log.w(tag,message);}
  public void logInfo(String tag,String message){android.util.Log.i(tag,message);}
  public void logDebug(String tag,String message){android.util.Log.d(tag,message);}
  public void logVerbose(String tag,String message){}
  public void logStackTraceWithMessage(String tag,String message,Exception e){android.util.Log.w(tag,message,e);}
  public void logStackTrace(String tag,Exception e){android.util.Log.w(tag,"Terminal",e);}
}
