import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';

const state = JSON.parse(await readFile(process.env.E2E_MEDIA_STATE, 'utf8'));
const base = process.env.E2E_BASE_URL + '/api/v1';
const cookies = new Map();
async function request(path, options = {}) {
  const response = await fetch(base + path, {
    ...options,
    headers: { Cookie: [...cookies.values()].join('; '), ...options.headers },
  });
  for (const cookie of response.headers.getSetCookie()) {
    const pair = cookie.split(';', 1)[0];
    cookies.set(pair.split('=', 1)[0], pair);
  }
  assert.ok(response.ok, `HTTP ${response.status} for ${path}`);
  return response;
}
const csrf = await (await request('/auth/csrf')).json();
await request('/auth/login', {
  method: 'POST',
  headers: { [csrf.headerName]: csrf.token },
  body: new URLSearchParams({ username: 'owner', password: process.env.OWNER_PASSWORD }),
});
const bags = await (await request('/bags?q=' + encodeURIComponent(state.bagName))).json();
assert.deepEqual(bags[0].photos.ids, state.ids);
for (const [suffix, expected] of [['', state.full], ['?thumbnail=true', state.thumb]]) {
  const photo = await request('/media/' + state.ids[0] + suffix);
  assert.equal(createHash('sha256').update(Buffer.from(await photo.arrayBuffer())).digest('hex'), expected);
}
console.log('PASS: cover order, full image and thumbnail survived application restart');

const ownerProfile = await (await request('/profile')).json();
cookies.clear();
const otherCsrf = await (await request('/auth/csrf')).json();
await request('/auth/login', {
  method: 'POST',
  headers: { [otherCsrf.headerName]: otherCsrf.token },
  body: new URLSearchParams({ username: 'photo-test-owner', password: process.env.OWNER_PASSWORD }),
});
assert.equal((await (await request('/auth/me')).json()).login, 'photo-test-owner');
const token = await (await request('/auth/csrf')).json();
const headers = { Cookie: [...cookies.values()].join('; '), [token.headerName]: token.token };
for (const [method, path, body] of [
  ['GET', '/media/' + state.ids[0]],
  ['GET', '/media/' + state.ids[0] + '?thumbnail=true'],
  ['DELETE', '/media/' + state.ids[0]],
  ['PUT', '/bags/' + state.ownBag + '/photos', { mediaIds: state.ids }],
  ['PUT', '/bags/' + state.bagId + '/photos', { mediaIds: [] }],
]) {
  const denied = await fetch(base + path, {
    method,
    headers: { ...headers, 'Content-Type': 'application/json' },
    body: body && JSON.stringify(body),
  });
  assert.equal(denied.status, 404, method + ' ' + path);
}
console.log('PASS: a second owner cannot read, delete or attach another owner’s photos');

const profiles = await (await request('/brew-profiles')).json();
assert.deepEqual(profiles, []);
const ownProfile = await (await request('/brew-profiles', {
  method: 'POST',
  headers: { ...headers, 'Content-Type': 'application/json' },
  body: JSON.stringify({ name: 'Isolation profile', defaultCupVolumeMl: 180 }),
})).json();
assert.notEqual(ownProfile.owner_id, ownerProfile.owner_id);
assert.deepEqual((await (await request('/brew-profiles')).json()).map(p => p.id), [ownProfile.id]);
for (const [method, path, body] of [
  ['PATCH', '/brew-profiles/' + ownerProfile.id, { name: 'Other name', version: ownerProfile.version, defaultCupVolumeMl: 180 }],
  ['DELETE', '/brew-profiles/' + ownerProfile.id + '?version=' + ownerProfile.version],
  ['PUT', '/profile/default-brew-profile', { profileId: ownerProfile.id }],
  ['POST', '/consumptions', { clientEventId: crypto.randomUUID(), profileId: ownerProfile.id, volumeMl: 180 }],
]) {
  const denied = await fetch(base + path, {
    method,
    headers: { ...headers, 'Content-Type': 'application/json' },
    body: body && JSON.stringify(body),
  });
  assert.equal(denied.status, 404, method + ' ' + path);
}
cookies.clear();
const finalCsrf = await (await request('/auth/csrf')).json();
await request('/auth/login', {
  method: 'POST',
  headers: { [finalCsrf.headerName]: finalCsrf.token },
  body: new URLSearchParams({ username: 'owner', password: process.env.OWNER_PASSWORD }),
});
assert.deepEqual(await (await request('/profile')).json(), ownerProfile);
assert.ok(!(await (await request('/brew-profiles')).json()).some(profile => profile.id === ownProfile.id));
console.log('PASS: profile lists and mutations are isolated between owners');

async function createRecord(path, data, token) {
  return (await request(path, {
    method: 'POST',
    headers: { [token.headerName]: token.token, 'Content-Type': 'application/json',
      'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify(data),
  })).json();
}

function linkOptions(cup, tasting, csrfToken, sessionCookies) {
  return {
    method: 'PUT',
    headers: { Cookie: sessionCookies, [csrfToken.headerName]: csrfToken.token, 'Content-Type': 'application/json' },
    body: JSON.stringify({ version: cup.version, tastingId: tasting.id }),
  };
}
const sessionCookies = () => [...cookies.values()].join('; ');
const cupPath = cup => '/consumptions/' + cup.id;
const link = (cup, tasting, csrfToken, session) =>
  fetch(base + cupPath(cup) + '/tasting-link', linkOptions(cup, tasting, csrfToken, session));

const raceToken = await (await request('/auth/csrf')).json();
const raceCup = await (await request('/bags?q=' + encodeURIComponent(state.bagName))).json();
const bagId = raceCup[0].id;
const rating = await createRecord('/tastings', { bagId, overallRating: 4 }, raceToken);
const candidates = [];
for (let index = 0; index < 2; index++) {
  candidates.push(await createRecord('/consumptions', {
    bagId, volumeMl: 250, clientEventId: crypto.randomUUID(),
  }, raceToken));
}
const ownerSession = sessionCookies();
const replies = await Promise.all(candidates.map(cup => link(cup, rating, raceToken, ownerSession)));
assert.deepEqual(replies.map(response => response.status).sort(), [200, 409]);
for (const [i, reply] of replies.entries()) {
  const persisted = await (await request(cupPath(candidates[i]))).json();
  if (reply.ok) {
    assert.equal((await reply.json()).tasting_id, rating.id);
    assert.equal(persisted.tasting_id, rating.id);
    assert.equal(persisted.version, candidates[i].version + 1);
  } else {
    assert.deepEqual(persisted, candidates[i]);
  }
}
assert.deepEqual(await (await request('/tastings/' + rating.id)).json(), rating);
console.log('PASS: two concurrent requests link one tasting to exactly one cup');

const ownerFreeRating = await createRecord('/tastings', { bagId, overallRating: 5 }, raceToken);
const ownerCup = candidates[replies.findIndex(reply => reply.status === 409)];
cookies.clear();
const guestCsrf = await (await request('/auth/csrf')).json();
await request('/auth/login', {
  method: 'POST',
  headers: { [guestCsrf.headerName]: guestCsrf.token },
  body: new URLSearchParams({ username: 'photo-test-owner', password: process.env.OWNER_PASSWORD }),
});
const guestToken = await (await request('/auth/csrf')).json();
const guestRating = await createRecord('/tastings', { bagId: state.ownBag, overallRating: 3 }, guestToken);
const guestCup = await createRecord('/consumptions', {
  bagId: state.ownBag, volumeMl: 180, clientEventId: crypto.randomUUID(),
}, guestToken);
const guestSession = sessionCookies();
assert.equal((await link(guestCup, ownerFreeRating, guestToken, guestSession)).status, 404);
assert.equal((await link(ownerCup, guestRating, raceToken, ownerSession)).status, 404);
assert.deepEqual(await (await request(cupPath(guestCup))).json(), guestCup);
assert.deepEqual(await (await request('/tastings/' + guestRating.id)).json(), guestRating);
async function getAsOwner(path) {
  const response = await fetch(base + path, { headers: { Cookie: ownerSession } });
  assert.ok(response.ok);
  return response.json();
}
assert.deepEqual(await getAsOwner(cupPath(ownerCup)), ownerCup);
assert.deepEqual(await getAsOwner('/tastings/' + ownerFreeRating.id), ownerFreeRating);
console.log('PASS: neither owner can attach the other owner’s tasting');
