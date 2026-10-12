import { randomUUID } from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';

const BASE = 'https://chatgpt.com/backend-api/codex/images';
const MAX = 32 * 1024 * 1024;
const PNG = Buffer.from([137,80,78,71,13,10,26,10]);

export function parseArgs(argv) {
  const options = { references: [] };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--status' || arg === '--help') options[arg.slice(2)] = true;
    else if (['--prompt-file', '--reference', '--output-dir'].includes(arg)) {
      const value = argv[++i];
      if (!value || value.startsWith('--')) throw new Error(`Missing value for ${arg}`);
      if (arg === '--reference') options.references.push(value);
      else options[arg === '--prompt-file' ? 'promptFile' : 'outputDir'] = value;
    } else throw new Error('Unknown argument; use --help.');
  }
  if (options.references.length > 5) throw new Error('At most five reference images are supported.');
  if (!options.help && !options.status && !options.promptFile) throw new Error('--prompt-file is required.');
  return options;
}

export function accountClaims(token) {
  try {
    const parts = token.split('.');
    if (parts.length !== 3) throw new Error();
    const claims = JSON.parse(Buffer.from(parts[1], 'base64url').toString())['https://api.openai.com/auth'];
    if (!claims || typeof claims.chatgpt_account_id !== 'string' || !claims.chatgpt_account_id) throw new Error();
    return { accountId: claims.chatgpt_account_id };
  } catch { throw new Error('OpenAI Codex OAuth unavailable. Log in to OpenAI Codex in Zfold.'); }
}

async function limitedFile(file, max) {
  const stats = await fs.stat(file);
  if (!stats.isFile() || stats.size > max) throw new Error('Input must be a regular file within the size limit.');
  const bytes = await fs.readFile(file);
  if (bytes.length > max) throw new Error('Input exceeds the size limit.');
  return bytes;
}

export function imageMime(bytes) {
  if (bytes.subarray(0,8).equals(PNG)) return 'image/png';
  if (bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255) return 'image/jpeg';
  if (bytes.subarray(0,4).toString() === 'RIFF' && bytes.subarray(8,12).toString() === 'WEBP') return 'image/webp';
  throw new Error('Reference images must be PNG, JPEG or WebP.');
}

async function realDirectory(directory) {
  const absolute = path.resolve(directory);
  // Reject symlinks at every existing component, not only the final directory.
  let current = path.parse(absolute).root;
  for (const part of absolute.slice(current.length).split(path.sep).filter(Boolean)) {
    current = path.join(current, part);
    try { await fs.mkdir(current); } catch (error) { if (error.code !== 'EEXIST') throw error; }
    const stat = await fs.lstat(current);
    if (!stat.isDirectory() || stat.isSymbolicLink()) throw new Error('Output directory must not contain symlinks.');
  }
  return absolute;
}

export async function runImageScript(options, { getAuth, fetchImpl = fetch, signal = AbortSignal.timeout(300000) }) {
  if (options.help) return { usage: 'node codex-image-gen.cjs --status | --prompt-file FILE [--reference FILE (up to 5)] [--output-dir DIR]' };
  let prompt, images;
  if (!options.status) {
    prompt = (await limitedFile(options.promptFile, 64 * 1024)).toString('utf8').trim();
    if (!prompt) throw new Error('Prompt must not be empty.');
    images = [];
    if ((options.references || []).length > 5) throw new Error('At most five reference images are supported.');
    for (const file of options.references || []) {
      const bytes = await limitedFile(file, MAX);
      images.push({ image_url: `data:${imageMime(bytes)};base64,${bytes.toString('base64')}` });
    }
  }
  let token;
  try { token = await getAuth(signal); } catch { throw new Error('OpenAI Codex OAuth unavailable. Log in to OpenAI Codex in Zfold.'); }
  if (!token) throw new Error('OpenAI Codex OAuth unavailable. Log in to OpenAI Codex in Zfold.');
  const { accountId } = accountClaims(token);
  if (options.status) return { status: 'oauth_ready', entitlementVerified: false };
  const directory = await realDirectory(options.outputDir || path.join(process.cwd(), 'generated_images'));
  const operation = images.length ? 'edit' : 'generate';
  const payload = { model: 'gpt-image-2', prompt, background: 'auto', quality: 'auto', size: 'auto' };
  if (images.length) payload.images = images;
  let response;
  try {
    response = await fetchImpl(`${BASE}/${images.length ? 'edits' : 'generations'}`, {
      method: 'POST', redirect: 'error', signal,
      headers: { Authorization: `Bearer ${token}`, 'chatgpt-account-id': accountId, originator: 'pi',
        'User-Agent': 'zfold-codex-image-script/1.0', 'content-type': 'application/json', accept: 'application/json',
        'x-codex-image-turn-id': randomUUID() },
      body: JSON.stringify(payload)
    });
  } catch { throw new Error('ImageGen network request failed or timed out. Do not automatically retry: the request may have been processed.'); }
  // Never print raw response bodies or auth-library exceptions (may contain credentials).
  if (!response.ok) {
    const messages = { 401: 'Log in to OpenAI Codex again in Zfold.', 403: 'This account cannot use Codex ImageGen.', 429: 'Codex ImageGen usage limit reached; try later.' };
    throw new Error(`ImageGen HTTP ${response.status}. ${messages[response.status] || 'Request refused or failed; do not automatically retry.'}`);
  }
  const chunks = []; let size = 0;
  for await (const chunk of response.body) {
    size += chunk.length;
    if (size > Math.ceil(MAX / 3) * 4 + 1024 * 1024) throw new Error('ImageGen response exceeds the size limit.');
    chunks.push(chunk);
  }
  let body;
  try { body = JSON.parse(Buffer.concat(chunks).toString()); } catch { throw new Error('ImageGen returned invalid JSON.'); }
  const base64 = body?.data?.[0]?.b64_json;
  if (typeof base64 !== 'string' || base64.length > Math.ceil(MAX / 3) * 4 + 4) throw new Error('ImageGen returned no valid image.');
  const bytes = Buffer.from(base64, 'base64');
  if (!bytes.length || bytes.length > MAX || !bytes.subarray(0,8).equals(PNG)) throw new Error('ImageGen returned invalid PNG data.');
  const savedPath = path.join(directory, `${Date.now()}-${randomUUID()}.png`);
  await fs.writeFile(savedPath, bytes, { flag: 'wx' });
  return { status: 'completed', operation, savedPath, bytes: bytes.length };
}
