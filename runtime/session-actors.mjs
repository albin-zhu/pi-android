/** Session lifecycle ownership. Selection never aborts or disposes another actor.
 * Factory must provide isolated SDK session/tool/context/queue state per actor.
 * Deliberately independent of Android UI and global runtime configuration.
 */
export class SessionActors {
  constructor({create,emit}){this.create=create;this.emit=emit;this.actors=new Map();this.selected=null;this.loading=new Map();}
  async get(id){
    if(this.actors.has(id))return this.actors.get(id);
    if(!this.loading.has(id))this.loading.set(id,Promise.resolve().then(()=>this.create(id)).then(session=>{
      const actor={id,session,busy:false,turnId:null,queue:[]};this.actors.set(id,actor);return actor;
    }).finally(()=>this.loading.delete(id)));
    return this.loading.get(id);
  }
  async select(id){const actor=await this.get(id);this.selected=id;return actor;}
  publish(actor,event){this.emit({...event,sessionId:actor.id,turnId:event.turnId??actor.turnId,background:this.selected!==actor.id});}
  async prompt(id,{turnId,text,options}){
    const actor=await this.get(id);if(actor.busy)throw new Error('这个会话正在执行');
    actor.busy=true;actor.turnId=turnId;this.publish(actor,{type:'turn_start'});
    try{await actor.session.prompt(text,options);this.publish(actor,{type:'turn_end'});}
    catch(error){this.publish(actor,{type:'error',message:error.message});}
    finally{actor.busy=false;actor.turnId=null;}
  }
  async stop(id){const actor=this.actors.get(id);if(actor?.busy)await actor.session.abort();}
  async dispose(id){const actor=this.actors.get(id);if(!actor)return;if(actor.busy)throw new Error('不能释放仍在执行的会话');actor.session.dispose();this.actors.delete(id);if(this.selected===id)this.selected=null;}
}
