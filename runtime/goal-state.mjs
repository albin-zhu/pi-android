// Goal records are session-branch entries, not global mutable state.
export const GOAL_ENTRY='zfold.goal.v1';
const statuses=new Set(['pending','in_progress','blocked','verification','done']);
const text=(value,max=2000)=>{if(typeof value!=='string'||!value.trim()||value.length>max)throw new Error('目标字段为空或过长');return value.trim();};
export function currentGoal(manager){
  return [...(manager?.getBranch()||[])].reverse().find(e=>e.type==='custom'&&e.customType===GOAL_ENTRY)?.data||null;
}
export function changeGoal(previous,command,user=false){
  if(command.action==='create'){
    if(previous&&previous.status==='active')throw new Error('已有目标，请先取消或验收');
    if(!Array.isArray(command.steps)||command.steps.length<1||command.steps.length>20)throw new Error('需要 1–20 个步骤');
    return {revision:(previous?.revision||0)+1,title:text(command.title,300),criteria:text(command.criteria),status:'active',steps:command.steps.map((s,i)=>({id:String(i+1),text:text(s,500),status:'pending',evidence:''}))};
  }
  if(!previous)throw new Error('当前会话没有目标');
  if(command.revision!==previous.revision)throw new Error('目标已改变，请先重新读取');
  const next=structuredClone(previous);
  if(command.action==='update'){
    if(previous.status!=='active')throw new Error('目标已关闭');
    const step=next.steps.find(s=>s.id===command.stepId);if(!step||!statuses.has(command.status))throw new Error('无效步骤或状态');
    const evidence=command.evidence||'';if(typeof evidence!=='string'||evidence.length>4000)throw new Error('证据过长');
    if(command.status==='done'&&!evidence.trim())throw new Error('完成步骤必须填写证据；尚未验证请标记 verification');
    step.status=command.status;step.evidence=evidence;
  }else if(command.action==='accept'){
    if(!user)throw new Error('仅用户可以验收');
    if(previous.status!=='active'||!next.steps.every(s=>s.status==='done'))throw new Error('步骤尚未全部完成');
    next.status='accepted';
  }else if(command.action==='cancel'){
    if(!user)throw new Error('仅用户可以取消目标');next.status='cancelled';
  }else throw new Error('未知目标操作');
  next.revision++;return next;
}
