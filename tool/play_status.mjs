#!/usr/bin/env node
// Read-only: print every track's releases (version codes, status) and listing languages.
//   node tool/play_status.mjs [--key path]
import { createSign } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { homedir } from 'node:os';

const PACKAGE = 'org.softosaurus.reactionspeed';
const API = 'https://androidpublisher.googleapis.com/androidpublisher/v3/applications/' + PACKAGE;
const keyArg = process.argv.indexOf('--key');
const keyPath = keyArg > 0 ? process.argv[keyArg + 1] : process.env.PLAY_SERVICE_ACCOUNT_JSON
  ?? join(homedir(), '.secrets', 'ohmyfridge-play-publisher.json');
const key = JSON.parse(await readFile(keyPath, 'utf8'));
const auth = { Authorization: `Bearer ${await accessToken(key)}` };

const edit = await call('POST', '/edits');
try {
  const { tracks = [] } = await call('GET', `/edits/${edit.id}/tracks`);
  for (const t of tracks) {
    for (const r of t.releases ?? []) {
      console.log(`${t.track.padEnd(12)} ${r.status.padEnd(10)} codes=[${(r.versionCodes ?? []).join(',')}] name="${r.name ?? ''}"`);
    }
  }
  const { listings = [] } = await call('GET', `/edits/${edit.id}/listings`);
  console.log('listings:', listings.map((l) => l.language).join(', '));
  const details = await call('GET', `/edits/${edit.id}/details`);
  console.log('details:', JSON.stringify(details));
} finally {
  await call('DELETE', `/edits/${edit.id}`);
}

async function call(method, path) {
  const res = await fetch(API + path, { method, headers: auth });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} → ${res.status}\n${text}`);
  return text ? JSON.parse(text) : {};
}

async function accessToken(key) {
  const now = Math.floor(Date.now() / 1000);
  const enc = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
  const unsigned = `${enc({ alg: 'RS256', typ: 'JWT' })}.${enc({
    iss: key.client_email, scope: 'https://www.googleapis.com/auth/androidpublisher',
    aud: key.token_uri, iat: now, exp: now + 3600,
  })}`;
  const signature = createSign('RSA-SHA256').update(unsigned).sign(key.private_key, 'base64url');
  const res = await fetch(key.token_uri, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: `${unsigned}.${signature}` }),
  });
  if (!res.ok) throw new Error(`token exchange failed: ${res.status} ${await res.text()}`);
  return (await res.json()).access_token;
}
