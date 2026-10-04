// Real API smoke test. Only synthetic UI is sent; never logs credentials or headers.
import {jevChoice, JEV_MODEL} from '../runtime/jev-client.mjs';
const config={enabled:true,apiKey:process.env.JEV_API_KEY,model:JEV_MODEL};
if(!config.apiKey){console.error('JEV_API_KEY missing');process.exit(1);}
const cases=[
  {name:'中文蓝牙选择',goal:'打开蓝牙设置',criteria:{wifi:'点击 Wi-Fi 设置',bluetooth:'点击 蓝牙 设置',blocked:'当前候选没有匹配目标，停止并交回规划器'},expected:'bluetooth'},
  {name:'缺失目标弃权',goal:'打开蓝牙设置',criteria:{wifi:'点击 Wi-Fi 设置',sound:'点击 声音 设置',blocked:'当前候选没有匹配目标，停止并交回规划器'},expected:'blocked'},
  {name:'已满足目标',goal:'开启蓝牙',state:{bluetoothChecked:true},criteria:{toggle:'点击当前已打开的蓝牙开关，会关闭蓝牙',done:'蓝牙已开启，结束任务',blocked:'无法判断，停止'},expected:'done'},
];
let failures=0;
for(const item of cases){
  const answer=await jevChoice({config,state:{goal:item.goal,screen:item.state||{}},criteria:item.criteria,instructions:'根据当前界面选择推进用户目标的一步。候选不存在则 blocked；已经达成目标则 done，不能把开启的开关再关闭。'});
  const pass=answer.choice===item.expected;if(!pass)failures++;
  console.log(JSON.stringify({case:item.name,pass,choice:answer.choice,confidence:answer.confidence,margin:answer.margin,latencyMs:answer.latencyMs,model:answer.model}));
}
process.exitCode=failures?1:0;
