import {test} from 'node:test';import assert from 'node:assert/strict';
import {SessionActors} from '../runtime/session-actors.mjs';
function deferred(){let resolve;const promise=new Promise(r=>resolve=r);return {promise,resolve};}
test('selection does not abort background execution, results retain session identity, stop targets one actor',async()=>{
 const gates={A:deferred(),B:deferred()},aborts=[],events=[];
 const actors=new SessionActors({emit:e=>events.push(e),create:async id=>({prompt:()=>gates[id].promise,abort:async()=>{aborts.push(id);gates[id].resolve();},dispose(){}})});
 await actors.select('A');const a=actors.prompt('A',{turnId:'turn-a',text:'A'});await Promise.resolve();
 await actors.select('B');assert.deepEqual(aborts,[]);assert.equal(actors.actors.get('A').busy,true);
 const b=actors.prompt('B',{turnId:'turn-b',text:'B'});await Promise.resolve();
 await actors.stop('B');await b;assert.deepEqual(aborts,['B']);assert.equal(actors.actors.get('A').busy,true);
 gates.A.resolve();await a;const end=events.find(e=>e.type==='turn_end'&&e.sessionId==='A');assert.equal(end.turnId,'turn-a');assert.equal(end.background,true);
 await actors.select('A');assert.equal(actors.actors.get('A').busy,false);
});
test('one factory per session, failed selection preserves previous selection, active actor cannot be disposed',async()=>{
 let count=0;const gate=deferred();const actors=new SessionActors({emit(){},create:async id=>{count++;if(id==='invalid')throw new Error('missing');return {prompt:()=>gate.promise,dispose(){},abort(){}};}});
 await Promise.all([actors.get('A'),actors.get('A')]);assert.equal(count,1);await actors.select('A');await assert.rejects(actors.select('invalid'));assert.equal(actors.selected,'A');
 const turn=actors.prompt('A',{turnId:'a',text:'a'});await Promise.resolve();await assert.rejects(actors.dispose('A'));gate.resolve();await turn;await actors.dispose('A');assert.equal(actors.actors.size,0);
});
