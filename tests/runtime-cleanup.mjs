// A signal is not an exit receipt: SDK catalog writes can race fixture deletion.
export async function stopRuntimeChild(child){
  if(!child||child.exitCode!==null||child.signalCode!==null)return;
  await new Promise(resolve=>{
    const timer=setTimeout(()=>child.kill('SIGKILL'),3000);
    child.once('close',()=>{clearTimeout(timer);resolve();});
    child.kill();
  });
}
