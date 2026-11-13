import assert from "node:assert/strict";
import fs from "node:fs";
const base = process.env.BASE_URL || "http://127.0.0.1:8080/api/v1";
let cookie = "",
  token = "",
  header = "";
async function request(path, method = "GET", body, extra = {}) {
  const r = await fetch(base + path, {
    method,
    headers: {
      cookie,
      ...(token ? { [header]: token } : {}),
      ...(body ? { "Content-Type": "application/json" } : {}),
      ...extra,
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const c = r.headers.getSetCookie();
  if (c.length) cookie = c.map((x) => x.split(";")[0]).join("; ");
  const text = await r.text();
  let data;
  try {
    data = JSON.parse(text);
  } catch {
    data = text;
  }
  return { status: r.status, data };
}
async function ok(path, method = "GET", body, extra) {
  const r = await request(path, method, body, extra);
  assert.ok(
    r.status < 300,
    `${method} ${path}: ${r.status} ${JSON.stringify(r.data)}`,
  );
  return r.data;
}
assert.equal((await request("/bags")).status, 401);
let csrf = await ok("/auth/csrf");
token = csrf.token;
header = csrf.headerName;
const password =
  process.env.OWNER_PASSWORD || fs.readFileSync(".tools/test-password", "utf8");
let r = await fetch(base + "/auth/login", {
  method: "POST",
  headers: {
    cookie,
    [header]: token,
    "Content-Type": "application/x-www-form-urlencoded",
  },
  body: new URLSearchParams({ username: "owner", password }),
});
assert.equal(r.status, 200);
cookie = r.headers
  .getSetCookie()
  .map((x) => x.split(";")[0])
  .join("; ");
csrf = await ok("/auth/csrf");
token = csrf.token;
header = csrf.headerName;
const id = () => crypto.randomUUID(),
  create = (kind, body, key = id()) =>
    ok("/" + kind, "POST", body, { "Idempotency-Key": key });
const bag = await create("bags", { name: "TEST — Гуджи" });
assert.equal(bag.status, "ACTIVE");
assert.equal(Number(bag.mass_g), 250);
const bag2 = await create("bags", { name: "TEST — Бразилия" });
assert.equal((await ok("/bags/" + bag.id)).status, "ACTIVE");
const repeat = await create("bags", { lotId: bag.lot_id });
assert.equal(repeat.lot_id, bag.lot_id);
assert.notEqual(repeat.id, bag.id);
const event = { clientEventId: id(), bagId: bag.id, volumeMl: 250 };
const first = await create("consumptions", event);
const again = await create("consumptions", event);
assert.equal(first.id, again.id);
assert.equal(
  (await request("/consumptions", "POST", { ...event, volumeMl: 200 })).status,
  409,
);
await create("consumptions", {
  clientEventId: id(),
  bagId: bag2.id,
  volumeMl: 250,
});
const unknown = await create("consumptions", {
  clientEventId: id(),
  volumeMl: 200,
});
assert.equal(unknown.bag_id, null);
const date = new Intl.DateTimeFormat("sv-SE", {
  timeZone: "Asia/Yekaterinburg",
}).format(new Date());
const stats = () => ok(`/consumption-stats?start=${date}&end=${date}`);
const before = await stats();
assert.ok(before.cups >= 3);
const tasting = await create("tastings", {
  bagId: bag.id,
  overallRating: 4,
  consumptionEventId: first.id,
});
assert.equal((await stats()).cups, before.cups);
assert.equal(tasting.sensory.acidity.status, "UNANSWERED");
assert.equal(
  (
    await request(
      "/tastings",
      "POST",
      { bagId: bag.id, overallRating: 6 },
      { "Idempotency-Key": id() },
    )
  ).status,
  422,
);
assert.equal(
  (
    await request(
      "/tastings",
      "POST",
      {
        bagId: bag.id,
        overallRating: 4,
        sensory: { acidity: { status: "RATED" } },
      },
      { "Idempotency-Key": id() },
    )
  ).status,
  422,
);
await ok(`/tastings/${tasting.id}?version=0`, "DELETE");
assert.equal((await stats()).cups, before.cups);
await ok(`/bags/${bag.id}?version=0`, "DELETE");
assert.equal((await stats()).cups, before.cups);
await ok(`/consumptions/${unknown.id}?version=0`, "DELETE");
assert.equal((await stats()).cups, before.cups - 1);
await ok(`/consumptions/${unknown.id}/restore`, "POST", { version: 1 });
assert.equal((await stats()).cups, before.cups);
const p = await ok("/profile");
await ok("/profile", "PATCH", {
  name: p.name,
  defaultCupVolumeMl: 200,
  version: p.version,
});
assert.equal((await ok("/consumptions/" + first.id)).volume_ml, 250);
const newest = await create("consumptions", { clientEventId: id() });
assert.equal(newest.volume_ml, 200);
await ok("/profile", "PATCH", {
  name: p.name,
  defaultCupVolumeMl: p.default_cup_volume_ml,
  version: p.version + 1,
});
const finished = await ok(`/bags/${bag2.id}/status`, "POST", {
  status: "FINISHED",
  version: 0,
});
assert.ok(finished.finished_at);
const reopened = await ok(`/bags/${bag2.id}/status`, "POST", {
  status: "ACTIVE",
  version: 1,
});
assert.equal(reopened.finished_at, null);
assert.equal(reopened.opened_at, bag2.opened_at);
assert.equal(
  (
    await request(`/bags/${bag2.id}/status`, "POST", {
      status: "STOCK",
      version: 0,
    })
  ).status,
  409,
);
const forbidden = await fetch(base + "/bags", {
  method: "POST",
  headers: { cookie, "Content-Type": "application/json" },
  body: JSON.stringify({ name: "CSRF" }),
});
assert.equal(forbidden.status, 403);
await ok("/auth/logout", "POST");
assert.equal((await request("/bags")).status, 401);
console.log(
  "PASS: session, CSRF, atomic bag/lot, repeat purchase, multiple active bags, idempotency, conflicting retry, unknown coffee, independent tasting/consumption, deletion/restore, volume snapshots, status history, optimistic locking, validation.",
);
