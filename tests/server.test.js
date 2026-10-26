'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');

const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'wbsrv-'));
process.env.DATA_DIR = dataDir;
process.env.WB_KEY_SECRET = 'test-secret';

const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
const JWT = `${b64({ alg: 'HS256' })}.${b64({ exp: 2000000000, sub: 'x' })}.c2VjcmV0LXNpZ24`;

// Подменяем только запросы к WB, к нашему серверу ходим настоящим fetch.
const realFetch = globalThis.fetch;
const wbCalls = [];
let wbStatus = 200;
globalThis.fetch = async (url, opts) => {
  if (!String(url).startsWith('https://logistics.wb.ru/')) return realFetch(url, opts);
  wbCalls.push({ body: JSON.parse(opts.body), auth: opts.headers.Authorization });
  if (wbStatus !== 200) return new Response('', { status: wbStatus });
  return Response.json([
    { id: 1, driverId: 7, driverName: 'Сидоров', totalPrice: { source: '1000', parsedValue: 1000 },
      fineSum: 50, openDt: '2026-10-25T09:00:00', closeDt: '2026-10-25T19:00:00', isDeleted: false },
    { id: 2, driverId: 7, driverName: 'Сидоров', totalPrice: 500,
      fineSum: null, openDt: '2026-10-25T20:00:00', closeDt: '2026-10-26T01:00:00', isDeleted: false },
  ]);
};

const { server, cache } = require('../server');
let base;

test.before(async () => {
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  base = `http://127.0.0.1:${server.address().port}`;
});
test.after(() => {
  server.close();
  fs.rmSync(dataDir, { recursive: true, force: true });
});

const post = (p, body) => fetch(base + p, {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
});

test('без ключа — NO_KEY', async () => {
  const res = await fetch(`${base}/api/report?from=2026-10-25&to=2026-10-25`);
  assert.equal(res.status, 409);
  assert.equal((await res.json()).error.code, 'NO_KEY');
});

test('сохранение ключа из адреса страницы, ключ не возвращается', async () => {
  const res = await post('/api/key', { value: `https://ol.wildberries.ru/?bearer=${JWT}` });
  assert.equal(res.status, 200);
  const text = await res.text();
  assert.ok(!text.includes(JWT.split('.')[2]));
  const st = await (await fetch(`${base}/api/key`)).text();
  assert.ok(!st.includes(JWT.split('.')[1]));
  assert.ok(!fs.readFileSync(path.join(dataDir, 'wb-key.enc'), 'utf8').includes(JWT));
});

test('расчёт: to+1 в запросе, фильтр по closeDt, кеш на минуту', async () => {
  wbCalls.length = 0;
  const res = await fetch(`${base}/api/report?from=2026-10-25&to=2026-10-25&percent=20`);
  assert.equal(res.status, 200);
  const r = await res.json();
  assert.equal(wbCalls.length, 1);
  assert.equal(wbCalls[0].body.from, '2026-10-25');
  assert.equal(wbCalls[0].body.to, '2026-10-26');
  assert.equal(wbCalls[0].body.isNewMotivation, true);
  assert.equal(wbCalls[0].auth, `Bearer ${JWT}`);
  assert.equal(r.totals.sum, 100000);
  assert.equal(r.totals.retention, 20000);
  assert.equal(r.totals.fines, 5000);
  assert.equal(r.totals.payout, 75000);

  // Повтор с другим процентом — без нового запроса к WB.
  const r2 = await (await fetch(`${base}/api/report?from=2026-10-25&to=2026-10-25&percent=10`)).json();
  assert.equal(wbCalls.length, 1);
  assert.equal(r2.totals.payout, 85000);
});

test('401 от WB → «ключ устарел»', async () => {
  cache.clear();
  wbStatus = 401;
  const res = await fetch(`${base}/api/report?from=2026-10-20&to=2026-10-20`);
  assert.equal(res.status, 409);
  const body = await res.json();
  assert.equal(body.error.code, 'KEY_EXPIRED');
  assert.match(body.error.message, /устарел/);
  wbStatus = 200;
});

test('проверка входных данных и чужого Origin', async () => {
  assert.equal((await fetch(`${base}/api/report?from=2026-13-01`)).status, 400);
  assert.equal((await fetch(`${base}/api/report?from=2026-10-26&to=2026-10-25`)).status, 400);
  assert.equal((await fetch(`${base}/api/report?from=2026-10-25&percent=150`)).status, 400);
  const res = await fetch(`${base}/api/key`, { method: 'DELETE', headers: { Origin: 'https://evil.example' } });
  assert.equal(res.status, 403);
});
