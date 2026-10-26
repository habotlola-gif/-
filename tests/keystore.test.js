'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { KeyStore, extractToken, keyInfo } = require('../lib/keystore');

const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
const makeJwt = (exp) => `${b64({ alg: 'HS256' })}.${b64({ exp })}.c2lnbmF0dXJl`;

test('extractToken из адреса страницы, строки Bearer и голого токена', () => {
  const jwt = makeJwt(2000000000);
  assert.equal(extractToken(`https://ol.wildberries.ru/waysheets?foo=1&bearer=${jwt}&x=2`), jwt);
  assert.equal(extractToken(`https://ol.wildberries.ru/#/page?bearer=${jwt}`), jwt);
  assert.equal(extractToken(`Bearer ${jwt}`), jwt);
  assert.equal(extractToken(`  ${jwt}  `), jwt);
  assert.equal(extractToken('https://ol.wildberries.ru/'), null);
  assert.equal(extractToken('мусор'), null);
});

test('keyInfo читает exp и не отдаёт сам ключ', () => {
  const now = Date.UTC(2026, 9, 26);
  const info = keyInfo(makeJwt(Math.floor(now / 1000) + 10 * 86400), now);
  assert.equal(info.configured, true);
  assert.equal(info.expired, false);
  assert.equal(info.daysLeft, 10);
  assert.ok(!JSON.stringify(info).includes('c2lnbmF0dXJl'));
  assert.equal(keyInfo(makeJwt(Math.floor(now / 1000) - 1), now).expired, true);
});

test('ключ хранится зашифрованным и читается обратно', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'wbkey-'));
  const jwt = makeJwt(2000000000);
  const store = new KeyStore(dir, 'test-secret');
  store.save(jwt);
  const raw = fs.readFileSync(path.join(dir, 'wb-key.enc'), 'utf8');
  assert.ok(!raw.includes(jwt));
  assert.ok(!raw.includes(jwt.split('.')[1]));
  assert.equal(new KeyStore(dir, 'test-secret').load(), jwt);
  assert.throws(() => new KeyStore(dir, 'wrong-secret').load());

  const auto = new KeyStore(path.join(dir, 'auto'), null);
  auto.save(jwt);
  assert.equal(new KeyStore(path.join(dir, 'auto'), null).load(), jwt);
  const mode = fs.statSync(path.join(dir, 'auto', 'master.key')).mode & 0o777;
  assert.equal(mode, 0o600);
  fs.rmSync(dir, { recursive: true, force: true });
});
