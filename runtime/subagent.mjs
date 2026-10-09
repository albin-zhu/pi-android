// Android host adapter for Pi's MIT-licensed v1.1.0 subagent example.
// Reference sources are retained unchanged in vendor/pi-subagent-v1.1.0/.
// Uses SDK child sessions rather than spawning the embedded Node library as a CLI.
export const SUBAGENT_ROLES=['scout','planner','reviewer','worker'];
export const MAX_TASKS=8,MAX_CONCURRENCY=4,OUTPUT_CAP=50*1024;
export function capOutput(value,bytes=OUTPUT_CAP){
  const text=String(value||'');if(Buffer.byteLength(text)<=bytes)return text;
  let clipped=Buffer.from(text).subarray(0,bytes).toString('utf8');if(clipped.endsWith('\ufffd'))clipped=clipped.slice(0,-1);
  return clipped+'\n[输出截断；完整子会话保存在本机子任务记录中]';
}
// Matches the official example's bounded worker ordering. Cancellation is checked before admission.
export async function mapWithConcurrencyLimit(items,concurrency,fn){
  const results=new Array(items.length);let next=0;
  await Promise.all(Array.from({length:Math.min(concurrency,items.length)},async()=>{while(true){const index=next++;if(index>=items.length)return;results[index]=await fn(items[index],index);}}));
  return results;
}
function parseTasks(args){
  const modes=Number(Boolean(args.agent&&args.task))+Number(Array.isArray(args.tasks)&&args.tasks.length>0)+Number(Array.isArray(args.chain)&&args.chain.length>0);
  if(modes!==1)throw new Error('仅选一个模式：agent+task、tasks 或 chain');
  const mode=args.chain?.length?'chain':args.tasks?.length?'parallel':'single';
  const items=mode==='single'?[{agent:args.agent,task:args.task,cwd:args.cwd}]:args[mode==='chain'?'chain':'tasks'];
  if(items.length>MAX_TASKS)throw new Error('每次最多8个子任务');
  for(const item of items){if(!SUBAGENT_ROLES.includes(item.agent))throw new Error('可用子Agent：'+SUBAGENT_ROLES.join(', '));if(typeof item.task!=='string'||!item.task.trim()||item.task.length>16000)throw new Error('子任务需1–16000字符');}
  return {mode,items};
}
export function createSubagentTool({Type,getParent,runChild,validateCwd,timeoutMs=120000}){
  const item=Type.Object({agent:Type.Union(SUBAGENT_ROLES.map(v=>Type.Literal(v))),task:Type.String({minLength:1,maxLength:16000}),cwd:Type.Optional(Type.String({maxLength:1024}))},{additionalProperties:false});
  return {
    name:'subagent',label:'子Agent · 官方流程',
    description:'按Pi官方subagent流程委托独立上下文。内置scout（查代码）、planner（规划）、reviewer（审查）、worker（综合分析）。首版都仅read/ls/grep/find，不写文件、不执行命令、不操作手机、不递归。单个用agent+task；并行用tasks（最多8个/4并发）；链式用chain，任务可用{previous}引用上一步结果。沿用当前模型与思考档位，会产生额外模型请求。只提供本任务所需信息，子Agent不继承主聊天。',
    parameters:Type.Object({agent:Type.Optional(item.properties.agent),task:Type.Optional(item.properties.task),cwd:Type.Optional(item.properties.cwd),tasks:Type.Optional(Type.Array(item,{minItems:1,maxItems:MAX_TASKS})),chain:Type.Optional(Type.Array(item,{minItems:1,maxItems:MAX_TASKS}))},{additionalProperties:false}),
    async execute(id,args,signal,onUpdate){
      let parsed,parent;
      try{parsed=parseTasks(args);parent=getParent();if(!parent?.model)throw new Error('请先连接模型');parsed.items=parsed.items.map(task=>({...task,cwd:validateCwd(task.cwd||parent.cwd,parent.cwd)}));}
      catch(error){return {content:[{type:'text',text:error.message}],details:{status:'failed',results:[]},isError:true};}
      const {mode,items}=parsed,controller=new AbortController();let timedOut=false;
      const abort=()=>controller.abort();if(signal?.aborted)abort();else signal?.addEventListener('abort',abort,{once:true});
      const timer=setTimeout(()=>{timedOut=true;abort();},timeoutMs);timer.unref?.();
      const results=items.map((task,index)=>({index,agent:task.agent,task:task.task,status:'pending',text:'',model:parent.model.id}));
      let lastUpdate=0;
      const update=(force=false)=>{const now=Date.now();if(!force&&now-lastUpdate<200)return;lastUpdate=now;onUpdate?.({content:[{type:'text',text:results.map((r,i)=>`${i+1}. ${r.agent} · ${{pending:'待开始',running:'执行中',done:'已返回',failed:'失败',cancelled:'已停止'}[r.status]}${r.preview?' · '+capOutput(r.preview,240):''}`).join('\n')}],details:{mode,status:'running',results:results.map(r=>({...r,text:undefined}))}});};
      const run=async(task,index)=>{
        const r=results[index];if(controller.signal.aborted){r.status='cancelled';return r;}
        r.status='running';r.task=task.task;update(true);
        try{
          const answer=await runChild({id,index,agent:task.agent,task:task.task,cwd:task.cwd,parent,signal:controller.signal,onProgress:p=>{r.preview=String(p.text||p.tool||'');r.turns=p.turns;r.usage=p.usage;update();}});
          Object.assign(r,answer,{text:capOutput(answer.text),status:controller.signal.aborted?'cancelled':answer.failed?'failed':'done'});
        }catch(error){r.status=controller.signal.aborted?'cancelled':'failed';r.text=capOutput(error.message,2000);}
        update(true);return r;
      };
      try{
        update(true);
        if(mode==='chain'){
          let previous='';for(let i=0;i<items.length;i++){
            const r=await run({...items[i],task:items[i].task.replaceAll('{previous}',previous)},i);
            if(r.status!=='done'){for(let j=i+1;j<results.length;j++){results[j].status='cancelled';results[j].text='前序失败或停止，未执行';}break;}
            previous=r.text;
          }
        }else await mapWithConcurrencyLimit(items,MAX_CONCURRENCY,run);
        const failed=results.some(r=>r.status!=='done'),cancelled=controller.signal.aborted;
        const status=cancelled?'cancelled':failed?'failed':'completed';
        return {content:[{type:'text',text:(timedOut?'子任务达到120秒总时限，已停止。\n':'')+results.map((r,i)=>`## ${i+1}. ${r.agent} · ${r.status}\n${r.text||'(无输出)'}\n`).join('\n')}],details:{mode,status,results},isError:failed||cancelled};
      }finally{clearTimeout(timer);signal?.removeEventListener('abort',abort);}
    }
  };
}
