import {test} from 'node:test';
import assert from 'node:assert/strict';
import {changeGoal,currentGoal,GOAL_ENTRY} from '../runtime/goal-state.mjs';
const create=()=>changeGoal(null,{action:'create',title:'消息队列',criteria:'真机编辑和取消成功',steps:['编译','真机验证']});
test('goal lifecycle separates reported completion from user acceptance',()=>{
 let goal=create();
 assert.throws(()=>changeGoal(goal,{action:'accept',revision:1},true),/尚未/);
 assert.throws(()=>changeGoal(goal,{action:'update',revision:1,stepId:'1',status:'done'}),/证据/);
 goal=changeGoal(goal,{action:'update',revision:1,stepId:'1',status:'done',evidence:'build exit 0'});
 assert.equal(goal.status,'active');assert.equal(goal.steps[1].status,'pending');
 goal=changeGoal(goal,{action:'update',revision:2,stepId:'2',status:'done',evidence:'用户确认'});
 assert.throws(()=>changeGoal(goal,{action:'accept',revision:3}),/仅用户/);
 assert.equal(changeGoal(goal,{action:'accept',revision:3},true).status,'accepted');
});
test('reject stale edits, invalid status, unauthorized cancellation and overwrite',()=>{
 const goal=create();
 assert.throws(()=>changeGoal(goal,{action:'update',revision:0}),/改变/);
 assert.throws(()=>changeGoal(goal,{action:'update',revision:1,stepId:'1',status:'verified'}),/无效/);
 assert.throws(()=>changeGoal(goal,{action:'cancel',revision:1}),/仅用户/);
 assert.throws(()=>changeGoal(goal,{action:'create',title:'x',criteria:'y',steps:['z']}),/已有/);
 assert.equal(changeGoal(goal,{action:'cancel',revision:1},true).status,'cancelled');
 assert.equal(goal.status,'active');
});
test('goal reads only current branch and does not leak across sessions',()=>{
 const goal=create(),entry={type:'custom',customType:GOAL_ENTRY,data:goal};
 assert.equal(currentGoal({getBranch:()=>[]}),null);
 assert.deepEqual(currentGoal({getBranch:()=>[entry]}),goal);
 const next=changeGoal(goal,{action:'update',revision:1,stepId:'1',status:'blocked',evidence:'缺依赖'});
 assert.equal(currentGoal({getBranch:()=>[entry,{...entry,data:next}]}).steps[0].status,'blocked');
 assert.equal(currentGoal({getBranch:()=>[entry]}).steps[0].status,'pending');
});

test('a reported-complete goal does not block a new plan or forge acceptance',()=>{
 let old=create();
 for(const step of old.steps)old=changeGoal(old,{action:'update',revision:old.revision,stepId:step.id,status:'done',evidence:'explicit completion evidence'});
 const saved=structuredClone(old);
 const next=changeGoal(old,{action:'create',title:'完整产品设计',criteria:'实机验证',steps:['设计','实现','验证']});
 assert.equal(next.revision,old.revision+1);
 assert.equal(next.status,'active');assert.deepEqual(old,saved);assert.equal(old.status,'active');
 const entries=[{type:'custom',customType:GOAL_ENTRY,data:old},{type:'custom',customType:GOAL_ENTRY,data:next}];
 assert.equal(currentGoal({getBranch:()=>entries}),next);assert.deepEqual(entries[0].data,saved);
});
