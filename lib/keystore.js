'use strict';

// Хранение ключа WB на сервере в зашифрованном виде (AES-256-GCM).
// Мастер-ключ берётся из переменной окружения WB_KEY_SECRET, а если её нет —
// генерируется один раз в data/master.key с правами 0600.

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const JWT_RE = /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*$/;

class KeyStore {
  constructor(dataDir, secret = process.env.WB_KEY_SECRET) {
    this.dataDir = dataDir;
    this.file = path.join(dataDir, 'wb-key.enc');
    this.secret = secret || null;
    this.cachedKey = undefined;
  }

  ensureDir() {
    fs.mkdirSync(this.dataDir, { recursive: true, mode: 0o700 });
  }

  masterKey() {
    if (this.secret) return crypto.scryptSync(this.secret, 'wb-driver-salary/v1', 32);
    this.ensureDir();
    const file = path.join(this.dataDir, 'master.key');
    try {
      const buf = Buffer.from(fs.readFileSync(file, 'utf8').trim(), 'base64');
      if (buf.length === 32) return buf;
    } catch (e) {
      if (e.code !== 'ENOENT') throw e;
    }
    const key = crypto.randomBytes(32);
    fs.writeFileSync(file, key.toString('base64'), { mode: 0o600 });
    return key;
  }

  save(token) {
    this.ensureDir();
    const iv = crypto.randomBytes(12);
    const cipher = crypto.createCipheriv('aes-256-gcm', this.masterKey(), iv);
    const data = Buffer.concat([cipher.update(token, 'utf8'), cipher.final()]);
    const payload = {
      v: 1,
      iv: iv.toString('base64'),
      tag: cipher.getAuthTag().toString('base64'),
      data: data.toString('base64'),
      savedAt: new Date().toISOString(),
    };
    const tmp = `${this.file}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(payload), { mode: 0o600 });
    fs.renameSync(tmp, this.file);
    this.cachedKey = token;
  }

  load() {
    if (this.cachedKey !== undefined) return this.cachedKey;
    let payload;
    try {
      payload = JSON.parse(fs.readFileSync(this.file, 'utf8'));
    } catch (e) {
      if (e.code === 'ENOENT') { this.cachedKey = null; return null; }
      throw e;
    }
    const decipher = crypto.createDecipheriv('aes-256-gcm', this.masterKey(), Buffer.from(payload.iv, 'base64'));
    decipher.setAuthTag(Buffer.from(payload.tag, 'base64'));
    const token = Buffer.concat([
      decipher.update(Buffer.from(payload.data, 'base64')),
      decipher.final(),
    ]).toString('utf8');
    this.cachedKey = token;
    return token;
  }

  remove() {
    try { fs.unlinkSync(this.file); } catch (e) { if (e.code !== 'ENOENT') throw e; }
    this.cachedKey = null;
  }
}

// Достаёт JWT из того, что вставил пользователь: полный адрес страницы
// ol.wildberries.ru с «bearer=…», строку «Bearer …» или сам токен.
function extractToken(input) {
  if (typeof input !== 'string') return null;
  let s = input.trim();
  const m = /[?&#]bearer=([^&#\s]+)/i.exec(s) || /^bearer=([^&#\s]+)/i.exec(s);
  if (m) {
    s = m[1];
    try { s = decodeURIComponent(s); } catch { /* оставляем как есть */ }
  }
  s = s.replace(/^bearer\s+/i, '').trim();
  return JWT_RE.test(s) ? s : null;
}

// Только чтение полей, подпись не проверяем — её проверяет WB.
function jwtPayload(token) {
  try {
    const part = token.split('.')[1];
    return JSON.parse(Buffer.from(part, 'base64url').toString('utf8'));
  } catch {
    return null;
  }
}

// То, что можно показать в браузере: без самого ключа.
function keyInfo(token, now = Date.now()) {
  if (!token) return { configured: false };
  const p = jwtPayload(token);
  const exp = p && Number.isFinite(p.exp) ? p.exp * 1000 : null;
  return {
    configured: true,
    expiresAt: exp ? new Date(exp).toISOString() : null,
    expired: exp ? exp <= now : false,
    daysLeft: exp ? Math.floor((exp - now) / 86400000) : null,
  };
}

module.exports = { KeyStore, extractToken, jwtPayload, keyInfo };
