function normalize(value) {
  return String(value ?? '').normalize('NFKC').toLocaleLowerCase('en-US')
    .replace(/[\s\u200b\u200c\u200d\-‐‑–—_.,，。:：、()（）]+/g, '');
}

function isUsable(node, screen) {
  if(node.enabled===false||node.password===true)return false;
  const b=node.bounds;
  return Array.isArray(b)&&b.length===4&&b[2]>b[0]&&b[3]>b[1]&&
    (!Number.isFinite(screen.height)||b[3]-b[1]<screen.height*.45);
}

/** Choose a uniquely labelled clickable node; never guess between distinct controls. */
export function choosePhoneTarget(screen, targets, packageName) {
  const nodes=Array.isArray(screen?.nodes)?screen.nodes:[];
  const byId=new Map(nodes.map(node=>[node.nodeId,node]));
  const wanted=[...new Set((Array.isArray(targets)?targets:[]).map(normalize).filter(Boolean))].slice(0,6);
  const matches=new Map();let visibleMatches=0;
  for(const node of nodes) {
    if(node.enabled===false||node.password===true)continue;
    if(packageName&&node.package!==packageName)continue;
    const labels=[node.text,node.description].map(normalize).filter(Boolean);
    let score=0;
    for(const label of labels)for(const target of wanted){
      if(label===target)score=Math.max(score,100);
      else if(target.length>=2&&label.includes(target))score=Math.max(score,60);
    }
    if(!score)continue;
    visibleMatches++;
    let action=node;
    for(let depth=0;depth<10&&action&&!action.clickable;depth++)action=byId.get(action.parentId);
    if(!action||!isUsable(action,screen)||(packageName&&action.package!==packageName))continue;
    const previous=matches.get(action.nodeId);
    if(!previous||score>previous.score)matches.set(action.nodeId,{nodeId:action.nodeId,text:node.text,description:node.description,package:node.package,bounds:action.bounds,score});
  }
  if(matches.size===0)return {reason:visibleMatches?'not_clickable':'not_found',candidates:[]};
  const ordered=[...matches.values()].sort((a,b)=>b.score-a.score);
  if(ordered.length>1&&ordered[0].score===ordered[1].score)return {reason:'ambiguous',candidates:ordered.filter(item=>item.score===ordered[0].score).slice(0,8)};
  return {match:ordered[0]};
}
