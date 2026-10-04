import {decidePhoneAction} from '../runtime/phone-task.mjs';
import {JEV_MODEL} from '../runtime/jev-client.mjs';
const pkg='test.settings';
const node=(path,text,extra={})=>({nodeId:'1:'+path,path,parentId:null,package:pkg,resourceId:pkg+':id/'+path,text,description:'',class:'android.widget.Button',enabled:true,clickable:true,checked:false,checkable:false,selected:false,editable:false,focused:false,password:false,scrollable:false,actions:[16],bounds:[0,0,300,80],...extra});
const cases=[
  {name:'中文标签与完整控件',goal:'Open Bluetooth settings by tapping the visible 蓝牙 row.',nodes:[node('wifi','Wi-Fi'),node('bt','蓝牙')],expected:'tap',path:'bt'},
  {name:'目标缺失',goal:'Tap the Bluetooth (蓝牙) row on this screen only. If absent, stop; do not navigate elsewhere.',nodes:[node('wifi','Wi-Fi'),node('sound','声音')],expected:'blocked'},
  {name:'开关已开启',goal:'Ensure 蓝牙 is enabled, then finish.',nodes:[node('bt','蓝牙',{checkable:true,checked:true})],expected:'done'},
];
let failed=0;
for(const item of cases){
  const result=await decidePhoneAction({screen:{packageName:pkg,generation:1,width:400,height:900,nodes:item.nodes},task:{goal:item.goal,allowedPackages:[pkg]},apps:[],history:[],config:{enabled:true,apiKey:process.env.JEV_API_KEY,model:JEV_MODEL}});
  const pass=result.action.kind===item.expected&&(!item.path||result.action.target.path===item.path);if(!pass)failed++;
  console.log(JSON.stringify({case:item.name,pass,action:result.action.kind,target:result.action.target?.path,confidence:result.confidence,margin:result.margin,latencyMs:result.latencyMs,model:result.model,wouldExecute:result.confidence>=0.75&&result.margin>=0.2}));
}
process.exitCode=failed?1:0;
