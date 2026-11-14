import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';

const [phase, statePath] = process.argv.slice(2);
assert.ok(['seed', 'verify'].includes(phase) && statePath && process.env.E2E_BASE_URL && process.env.OWNER_PASSWORD,
  'Set E2E_BASE_URL and OWNER_PASSWORD for a dedicated Compose test deployment and pass seed|verify and a state path.');
const base = process.env.E2E_BASE_URL + '/api/v1';
const cookies = new Map();
let csrf;
async function request(path, { method = 'GET', body, headers = {} } = {}) {
  const response = await fetch(base + path, {
    method,
    body,
    headers: { Cookie: [...cookies.values()].join('; '),
      ...(csrf && method !== 'GET' ? { [csrf.headerName]: csrf.token } : {}),
      ...headers },
  });
  for (const cookie of response.headers.getSetCookie()) {
    const pair = cookie.split(';', 1)[0];
    cookies.set(pair.split('=', 1)[0], pair);
  }
  assert.ok(response.ok, 'HTTP ' + response.status + ' for ' + path);
  return response;
}
function json(method, data) {
  return { method, headers: { 'Content-Type': 'application/json', 'Idempotency-Key': randomUUID() },
    body: JSON.stringify(data) };
}
async function snapshot(bagName) {
  const [bag] = await (await request('/bags?q=' + encodeURIComponent(bagName))).json();
  assert.equal(bag.name, bagName);
  assert.equal(bag.photos.ids.length, 2);
  assert.equal(bag.photo_id, bag.photos.ids[0]);
  const hashes = [];
  for (const id of bag.photos.ids) {
    for (const suffix of ['', '?thumbnail=true']) {
      const response = await request('/media/' + id + suffix);
      assert.equal(response.headers.get('content-type'), 'image/png');
      const bytes = Buffer.from(await response.arrayBuffer());
      hashes.push(createHash('sha256').update(bytes).digest('hex'));
    }
  }
  return { bagName, bagId: bag.id, ids: bag.photos.ids, hashes };
}
csrf = await (await request('/auth/csrf')).json();
await request('/auth/login', {
  method: 'POST', body: new URLSearchParams({ username: process.env.OWNER_LOGIN || 'owner',
    password: process.env.OWNER_PASSWORD }),
});
csrf = await (await request('/auth/csrf')).json();

if (phase === 'seed') {
  const bagName = 'Compose media ' + randomUUID();
  const bag = await (await request('/bags', json('POST', { name: bagName }))).json();
  const ids = [];
  for (const color of [
    'iVBORw0KGgoAAAANSUhEUgAAAAMAAAACCAYAAACddGYaAAAAAXNSR0IArs4c6QAAAARnQU1BAACxjwv8YQUAAAAJcEhZcwAADsMAAA7DAcdvqGQAAAASSURBVBhXY/jPwPAfhpE4DP8Bm34L9Z3rwBIAAAAASUVORK5CYII=',
    'iVBORw0KGgoAAAANSUhEUgAAAAMAAAACCAYAAACddGYaAAAAAXNSR0IArs4c6QAAAARnQU1BAACxjwv8YQUAAAAJcEhZcwAADsMAAA7DAcdvqGQAAAASSURBVBhXY2Bg+P8fgeGM//8Bj4oL9RcJ2u8AAAAASUVORK5CYII=',
  ]) {
    const form = new FormData();
    form.set('file', new Blob([Buffer.from(color, 'base64')], { type: 'image/png' }), 'compose.png');
    ids.push((await (await request('/media', { method: 'POST', body: form })).json()).id);
  }
  await request('/bags/' + bag.id + '/photos', json('PUT', { mediaIds: ids.reverse() }));
  await writeFile(statePath, JSON.stringify(await snapshot(bagName)));
  console.log('Recorded the cover, photo order and hashes before recreation');
} else {
  const expected = JSON.parse(await readFile(statePath, 'utf8'));
  assert.deepEqual(await snapshot(expected.bagName), expected);
  console.log('PASS: Compose recreation preserved the bag, cover order, full images and thumbnails');
}
