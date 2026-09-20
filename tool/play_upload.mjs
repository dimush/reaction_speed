#!/usr/bin/env node
// Upload an AAB to a Play Console track via the Android Publisher API.
// No dependencies: the service-account JWT is signed with node:crypto.
//
//   node tool/play_upload.mjs --track production [--aab path] [--status draft|completed]
//                             [--notes-dir dir] [--key path]
//
// Key file: --key, else $PLAY_SERVICE_ACCOUNT_JSON, else
// %USERPROFILE%/.secrets/ohmyfridge-play-publisher.json. Never inside the repo.
// Release notes: every <lang>.txt in --notes-dir (e.g. en-US.txt) becomes a
// localized note; default dir is tool/play_notes if it exists.

import { createSign } from 'node:crypto';
import { readFile, readdir, stat } from 'node:fs/promises';
import { basename, join, resolve } from 'node:path';
import { homedir } from 'node:os';

const PACKAGE = 'org.softosaurus.reactionspeed';
const API = 'https://androidpublisher.googleapis.com';

const args = parseArgs(process.argv.slice(2));
const track = args.track ?? 'production';
const status = args.status ?? 'completed';
const aabPath = resolve(args.aab ?? 'app/build/outputs/bundle/release/app-release.aab');
const keyPath = args.key ?? process.env.PLAY_SERVICE_ACCOUNT_JSON
  ?? join(homedir(), '.secrets', 'ohmyfridge-play-publisher.json');
const notesDir = args['notes-dir'] ?? 'tool/play_notes';

const key = JSON.parse(await readFile(keyPath, 'utf8'));
const token = await accessToken(key);
const auth = { Authorization: `Bearer ${token}` };

const aab = await readFile(aabPath);
console.log(`Uploading ${basename(aabPath)} (${(aab.length / 1048576).toFixed(1)} MB) to track "${track}" as ${status}`);

const edit = await call('POST', `/androidpublisher/v3/applications/${PACKAGE}/edits`, {});
const editId = edit.id;

const bundle = await call('POST',
  `/upload/androidpublisher/v3/applications/${PACKAGE}/edits/${editId}/bundles?uploadType=media`,
  aab, 'application/octet-stream');
console.log(`Uploaded versionCode ${bundle.versionCode} (sha256 ${bundle.sha256.slice(0, 12)}…)`);

const releaseNotes = await loadNotes(notesDir);
const release = {
  name: `${bundle.versionCode} (${args.name ?? await gradleVersionName()})`,
  versionCodes: [String(bundle.versionCode)],
  status,
  ...(releaseNotes.length ? { releaseNotes } : {}),
};
await call('PUT', `/androidpublisher/v3/applications/${PACKAGE}/edits/${editId}/tracks/${track}`,
  { track, releases: [release] });

await call('POST', `/androidpublisher/v3/applications/${PACKAGE}/edits/${editId}:validate`, {});
const committed = await call('POST', `/androidpublisher/v3/applications/${PACKAGE}/edits/${editId}:commit`, {});
console.log(`Committed edit ${committed.id}: release "${release.name}" on "${track}" is ${status}`);

// ---------------------------------------------------------------------------

async function call(method, path, body, contentType = 'application/json') {
  const res = await fetch(API + path, {
    method,
    headers: { ...auth, 'Content-Type': contentType },
    body: contentType === 'application/json' ? JSON.stringify(body) : body,
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} → ${res.status}\n${text}`);
  return text ? JSON.parse(text) : {};
}

async function accessToken(key) {
  const now = Math.floor(Date.now() / 1000);
  const enc = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
  const unsigned = `${enc({ alg: 'RS256', typ: 'JWT' })}.${enc({
    iss: key.client_email,
    scope: 'https://www.googleapis.com/auth/androidpublisher',
    aud: key.token_uri,
    iat: now,
    exp: now + 3600,
  })}`;
  const signature = createSign('RSA-SHA256').update(unsigned).sign(key.private_key, 'base64url');
  const res = await fetch(key.token_uri, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion: `${unsigned}.${signature}`,
    }),
  });
  if (!res.ok) throw new Error(`token exchange failed: ${res.status} ${await res.text()}`);
  return (await res.json()).access_token;
}

async function loadNotes(dir) {
  try { if (!(await stat(dir)).isDirectory()) return []; } catch { return []; }
  const notes = [];
  for (const f of (await readdir(dir)).filter((f) => f.endsWith('.txt')).sort()) {
    notes.push({ language: f.slice(0, -4), text: (await readFile(join(dir, f), 'utf8')).trim() });
  }
  return notes;
}

async function gradleVersionName() {
  const m = (await readFile('app/build.gradle', 'utf8')).match(/versionName\s+"([^"]+)"/);
  return m ? m[1] : '';
}

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    if (argv[i].startsWith('--')) out[argv[i].slice(2)] = argv[i + 1]?.startsWith('--') || argv[i + 1] === undefined ? true : argv[++i];
  }
  return out;
}
