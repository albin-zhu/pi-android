// Filter only live-call frames at request time; persisted conversation history stays intact.
export function cameraWorkingContext(messages) {
  // Pi 1.x normalizes image blocks and drops private properties. The host's
  // persisted camera suffix still identifies live-call user turns after restart.
  const cameraTurn=message=>Array.isArray(message.content)&&message.content.some(part=>part.type==='text'&&part.text?.includes('摄像头，拍摄时间 ')&&part.text?.includes('没有持续视频输入'));
  let latestMessage=-1,latestPart=-1;
  messages.forEach((message,i)=>{
    if(message.role==='user'&&Array.isArray(message.content))message.content.forEach((part,j)=>{
      if(part.type==='image'&&(part._zfoldCameraFrame||cameraTurn(message))){latestMessage=i;latestPart=j;}
    });
  });
  return messages.map((message,i)=>{
    if(message.role!=='user'||!Array.isArray(message.content))return message;
    return {...message,content:message.content.flatMap((part,j)=>{
      if(part.type!=='image'||(!part._zfoldCameraFrame&&!cameraTurn(message)))return [part];
      if(i!==latestMessage||j!==latestPart)return [{type:'text',text:'[此回合的较早摄像头快照未再次附入，历史文字仍保留。]'}];
      const {_zfoldCameraFrame,...image}=part;return [image];
    })};
  });
}
export function cameraPromptSuffix(frame) {
  if(!frame)return '';
  const lens=frame.facing==='front'?'前置':'后置';
  return `\n\n[本次附图来自${lens}摄像头，拍摄时间 ${new Date(frame.capturedAt).toISOString()}。这是用户提问时的一张静态快照；没有持续视频输入，也不提供手机屏幕点击坐标。]`;
}
