import path from 'node:path';
import { ModelRuntime } from '@earendil-works/pi-coding-agent';
import { registerBundledOAuthFlowLoaders, openaiCodexOAuth } from '../runtime/oauth-bundle.mjs';
import { parseArgs, runImageScript } from '../runtime/codex-image-script.mjs';

registerBundledOAuthFlowLoaders({ openaiCodex: () => openaiCodexOAuth });
async function main() {
const controller = new AbortController();
const timer = setTimeout(() => controller.abort(), 300000);
process.once('SIGTERM', () => controller.abort());
process.once('SIGINT', () => controller.abort());
try {
  const options = parseArgs(process.argv.slice(2));
  const result = await runImageScript(options, {
    signal: controller.signal,
    async getAuth(signal) {
      // Framework owns reading/refreshing credentials. No tokens in argv, stdout or shared storage.
      const directory = process.env.ZFOLD_CREDENTIALS_DIR || '/data/user/0/dev.zfold.assistant/no_backup/credentials';
      const runtime = await ModelRuntime.create({
        authPath: path.join(directory, 'auth.json'), modelsPath: path.join(directory, 'models.json'),
        modelsStorePath: path.join(directory, 'models-store.json'), allowModelNetwork: false, signal
      });
      if (!runtime.isUsingOAuth('openai-codex')) throw new Error('OAuth required');
      return (await runtime.getAuth('openai-codex', { signal }))?.auth?.apiKey;
    }
  });
  console.log(JSON.stringify(result));
} catch (error) {
  // Only the core's controlled errors reach here. Do not include stacks.
  console.error(JSON.stringify({ status: 'error', message: error.message }));
  process.exitCode = 1;
} finally { clearTimeout(timer); }
}
void main();
