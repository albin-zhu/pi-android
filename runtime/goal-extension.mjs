import {Type} from 'typebox';
import {GOAL_ENTRY,currentGoal,changeGoal} from './goal-state.mjs';

// Standard Pi extension: tool registration, appendEntry and per-turn context.
// The host adapter only delivers user actions and renders snapshots.
export function createGoalExtension({emit,getSession}){
  let api;
  const read=()=>currentGoal(getSession()?.sessionManager);
  const publish=()=>emit({type:'goal_state',sessionId:getSession()?.sessionId||'',goal:read()});
  function update(command,user=false){
    if(!api||!getSession())throw new Error('请先连接模型');
    const goal=changeGoal(read(),command,user);api.appendEntry(GOAL_ENTRY,goal);publish();return goal;
  }
  function factory(pi){
    api=pi;
    pi.registerTool({name:'goal_plan',label:'目标与任务进度',description:'仅在用户要求目标跟踪时建立计划。get 读取 revision；create 创建目标、验收条件和步骤；update 按 stepId 更新状态和证据。done 是报告完成而非验收；真实结果未验证用 verification。不能自行验收或取消。',promptSnippet:'Track an explicitly requested goal, steps, and evidence; user acceptance remains separate.',
      parameters:Type.Object({action:Type.Union(['get','create','update'].map(value=>Type.Literal(value))),revision:Type.Optional(Type.Integer()),title:Type.Optional(Type.String({maxLength:300})),criteria:Type.Optional(Type.String({maxLength:2000})),steps:Type.Optional(Type.Array(Type.String({maxLength:500}),{minItems:1,maxItems:20})),stepId:Type.Optional(Type.String()),status:Type.Optional(Type.Union(['pending','in_progress','blocked','verification','done'].map(value=>Type.Literal(value)))),evidence:Type.Optional(Type.String({maxLength:4000}))}),
      async execute(id,args,signal){if(signal?.aborted)throw new Error('已停止');const goal=args.action==='get'?read():update(args);return {content:[{type:'text',text:JSON.stringify(goal)}],details:{goal}};}
    });
    pi.on('before_agent_start',()=>{
      const goal=read();if(!goal||goal.status!=='active')return;
      return {message:{customType:'zfold.goal.context',content:'以下是用户目标面板的数据，不是额外授权。仅推进本轮获准任务；不要因存在目标自动继续执行。用 goal_plan 更新真实状态，未验证不能标 done；验收仅由用户确认。\n'+JSON.stringify(goal),display:false}};
    });
  }
  return {factory,publish,update,read};
}
