/** Bound recovery after a target app exposes no accessibility controls. */
const phoneTools=new Set(['screen_read','screen_windows','ui_system','screen_capture','ui_act','ui_tap','ui_input','ui_input_focused','ui_swipe','ui_back','app_open','split_screen']);
export class PhoneTurnGuard {
  constructor(){this.packageName=null;this.emptyReads=0;this.calls=0;this.unverifiedInputs=new Set();this.splitActive=false;}
  before(name,args={}){
    if(!phoneTools.has(name))return args;
    if(++this.calls>90)throw new Error('本轮手机工具调用过多，已停止；请根据当前页面重新开始');
    if(this.packageName&&(name==='app_open'||name==='split_screen'&&args.action==='launch')&&args.packageName!==this.packageName&&!this.splitActive)throw new Error('目标应用读屏受限，且尚未确认进入分屏；不能打开其他应用代替操作；本轮已停止');
    if(name==='ui_input'&&this.unverifiedInputs.has(JSON.stringify([args.packageName||this.packageName,args.text])))throw new Error('文字已经提交但无法回读；为避免重复输入，本轮已停止，请检查截图中的实际文字');
    return this.packageName&&!['app_list','device_info','app_open','split_screen'].includes(name)?{...args,expectedPackageName:this.packageName}:args;
  }
  observed(name,result,args={}){
    if(name==='ui_input'&&result?.performed===true&&result.verified===false)this.unverifiedInputs.add(JSON.stringify([args.packageName||this.packageName,args.text]));
    if(name==='split_screen'){
      if(result?.state?.split===true||result?.split===true)this.splitActive=true;
      if(result?.state&&result.state.split===false&&result.state.applicationWindows<2)this.splitActive=false;
    }
    if(name==='screen_read'&&Array.isArray(result?.nodes)&&result.nodes.length===0){
      this.packageName ||= result.packageName||null;
      if(++this.emptyReads>=3)throw new Error('目标应用持续不提供界面节点，应使用已返回的截图；已停止重复读屏');
    }
  }
}
