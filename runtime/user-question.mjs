const clean=(value,max,label)=>{if(typeof value!=='string'||!value.trim()||Array.from(value.trim()).length>max)throw new Error(`${label}格式无效`);return value.trim();};
export function normalizeQuestions(raw){
  if(!Array.isArray(raw)||raw.length<1||raw.length>4)throw new Error('每次提问需要 1–4 个问题');
  const ids=new Set();return raw.map((q,index)=>{
    if(!q||typeof q!=='object')throw new Error('问题格式无效');
    const id=q.id===undefined?`question_${index+1}`:clean(q.id,48,'问题 ID');if(ids.has(id))throw new Error('问题 ID 不能重复');ids.add(id);
    if(q.multiSelect!==undefined&&typeof q.multiSelect!=='boolean')throw new Error('multiSelect 需要布尔值');
    const options=q.options??[];if(!Array.isArray(options)||options.length===1||options.length>6)throw new Error('选项需要 2–6 项，或省略以使用文字回答');
    const names=new Set();return {id,question:clean(q.question,800,'问题'),header:q.header?clean(q.header,24,'标题'):'',multiSelect:Boolean(q.multiSelect),options:options.map(option=>{
      const label=clean(option?.label,80,'选项');if(names.has(label))throw new Error('选项不能重复');names.add(label);
      return {label,description:option.description?clean(option.description,200,'选项说明'):''};
    })};
  });
}
export function validateAnswers(questions,result){
  if(result?.status==='declined')return {status:'declined',answers:[],message:'用户选择跳过本次提问；这不是同意，不要代用户选择。'};
  if(result?.status!=='answered'||!Array.isArray(result.answers)||result.answers.length!==questions.length)throw new Error('用户回答不完整或已经失效');
  const used=new Set();return {status:'answered',answers:questions.map(question=>{
    const answer=result.answers.find(item=>item.id===question.id);if(!answer||used.has(answer.id))throw new Error('回答与问题不匹配');used.add(answer.id);
    const selected=answer.selected??[];if(!Array.isArray(selected)||new Set(selected).size!==selected.length||(!question.multiSelect&&selected.length>1)||selected.some(label=>!question.options.some(option=>option.label===label)))throw new Error('回答包含无效选项');
    const text=typeof answer.text==='string'?answer.text.trim():'';if(Array.from(text).length>4000||(!selected.length&&!text))throw new Error('每个问题需要选择或填写回答');
    return {id:question.id,question:question.question,selected,text};
  })};
}
export function createAskUserQuestionTool({Type,call,sessionId}){
  return {name:'ask_user_question',label:'向用户提问',executionMode:'sequential',
    description:'缺少用户选择或关键信息时，展示 1–4 个问题并等待用户回答。支持单选、多选和自行填写。此调用完成前不要执行依赖答案的操作；不预选、不把跳过当同意。不用于收集密码、验证码或 API Key，账号授权走专门的登录界面。',
    parameters:Type.Object({questions:Type.Array(Type.Object({id:Type.Optional(Type.String({minLength:1,maxLength:48})),header:Type.Optional(Type.String({maxLength:24})),question:Type.String({minLength:1,maxLength:800}),options:Type.Optional(Type.Array(Type.Object({label:Type.String({minLength:1,maxLength:80}),description:Type.Optional(Type.String({maxLength:200}))}),{minItems:2,maxItems:6})),multiSelect:Type.Optional(Type.Boolean())}),{minItems:1,maxItems:4})}),
    async execute(toolCallId,args,signal){
      const questions=normalizeQuestions(args.questions);
      const receipt=await call('ask_user_question',{questions,sessionId:sessionId(),toolCallId},signal);
      const details=validateAnswers(questions,receipt);return {content:[{type:'text',text:JSON.stringify(details)}],details};
    }
  };
}

/** The model must read the answer before choosing dependent actions. */
export class QuestionToolGate {
  observe(event){
    if(event.type==='message_start'&&event.message?.role==='assistant'){this.batch=false;this.used=false;}
    if(event.type==='message_end'&&event.message?.role==='assistant')this.batch=event.message.content?.some(part=>part.type==='toolCall'&&part.name==='ask_user_question')||false;
  }
  wrap(tool){return {...tool,execute:(...args)=>{
    if(this.batch){if(tool.name!=='ask_user_question'||this.used)throw new Error('本轮包含向用户提问，请先读取用户回答，再重新调用其他工具；多个问题请合并为一组。');this.used=true;}
    return tool.execute(...args);
  }};}
}
