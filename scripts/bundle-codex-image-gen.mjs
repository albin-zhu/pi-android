import { build } from 'esbuild';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
const outfile = '.build/codex-image-gen.cjs';
fs.mkdirSync('.build', { recursive: true });
await build({ entryPoints: ['scripts/codex-image-gen-entry.mjs'], outfile, bundle: true,
  platform: 'node', format: 'cjs', target: 'node24', external: ['@mariozechner/clipboard'],
  plugins: [{ name: 'single-auth-runtime', setup(builder) {
    builder.onResolve({ filter: /^@earendil-works\/pi-ai(?:\/.*)?$/ }, args => ({ path: fileURLToPath(import.meta.resolve(args.path)) }));
  } }],
  define: { 'import.meta.url': '__zfoldModuleUrl' },
  banner: { js: 'const __zfoldModuleUrl = require("node:url").pathToFileURL(__filename).href;' }, logLevel: 'info' });
console.log(outfile);
