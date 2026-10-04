/** Persistent PTY sessions backed by the app-private Termux runtime broker. */
export function createPtyTool({ Type, android, compatExec, resolveRuntimeCommand }) {
  return {
    name:'pty',
    label:'连接 Termux Runtime PTY',
    description:'打开真实 POSIX 伪终端并连接 App 内 Termux package runtime。open 默认 bash --noprofile --norc，避免旧 Termux prefix 的启动文件污染；write/read/status/resize/close 用返回的 id 继续会话。read 返回最多 256KB 输出，安静时可再读一次；一次最多 4 个会话。',
    parameters:Type.Object({
      action:Type.Optional(Type.Union([
        Type.Literal('open'),Type.Literal('write'),Type.Literal('read'),Type.Literal('status'),
        Type.Literal('resize'),Type.Literal('close')
      ])),
      id:Type.Optional(Type.Integer({minimum:1})),
      argv:Type.Optional(Type.Array(Type.String(),{minItems:1,maxItems:64})),
      cwd:Type.Optional(Type.String()),
      env:Type.Optional(Type.Record(Type.String(),Type.String())),
      rows:Type.Optional(Type.Integer({minimum:2,maximum:200})),
      cols:Type.Optional(Type.Integer({minimum:20,maximum:500})),
      data:Type.Optional(Type.String({maxLength:65536})),
      timeoutMs:Type.Optional(Type.Integer({minimum:100,maximum:10000}))
    }),
    async execute(_id,args,signal){
      if(!compatExec)throw new Error('pty 只在 targetSdk 28 compat28 通道可用');
      const action=args.action||'open';
      if(action==='open'){
        const argv=args.argv||['bash','--noprofile','--norc'];
        if(!path.isAbsolute(argv[0]))argv[0]=resolveRuntimeCommand(argv[0]);
        return await android('pty',{...args,action,argv});
      }
      return await android('pty',args);
    }
  };
}
