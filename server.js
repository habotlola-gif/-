'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const { KeyStore, extractToken, keyInfo } = require('./lib/keystore');
const { fetchWaysheets, WbError } = require('./lib/wb');
const { buildReport, isValidDate, addDays } = require('./lib/calc');

const PORT = Number(process.env.PORT) || 3000;
const HOST = process.env.HOST || '127.0.0.1';
const DATA_DIR = path.resolve(process.env.DATA_DIR || path.join(__dirname, 'data'));
const TIME_ZONE = process.env.TZ_REPORT || 'Europe/Moscow';
const APP_PASSWORD = process.env.APP_PASSWORD || '';
const CACHE_TTL_MS = 60 * 1000;
const MAX_PERIOD_DAYS = 93;
const PUBLIC_DIR = path.join(__dirname, 'public');
const SETTINGS_FILE = path.join(DATA_DIR, 'settings.json');

const keyStore = new KeyStore(DATA_DIR);

// ---------- настройки ----------

const DEFAULT_SETTINGS = { percent: 20 };

function loadSettings() {
  try {
    return { ...DEFAULT_SETTINGS, ...JSON.parse(fs.readFileSync(SETTINGS_FILE, 'utf8')) };
  } catch {
    return { ...DEFAULT_SETTINGS };
  }
}

function saveSettings(s) {
  fs.mkdirSync(DATA_DIR, { recursive: true, mode: 0o700 });
  fs.writeFileSync(SETTINGS_FILE, JSON.stringify(s, null, 2), { mode: 0o600 });
}

function parsePercent(v) {
  const n = Number(String(v).replace(',', '.'));
  if (!Number.isFinite(n) || n < 0 || n > 100) return null;
  return Math.round(n * 100) / 100;
}

// ---------- кеш запросов к WB ----------
// Один и тот же период запрашиваем у WB не чаще раза в минуту; параллельные
// запросы ждут один общий ответ.

const cache = new Map();

function getWaysheets(from, toExclusive) {
  const cacheKey = `${from}|${toExclusive}`;
  const now = Date.now();
  for (const [k, v] of cache) if (now - v.at > CACHE_TTL_MS) cache.delete(k);

  const hit = cache.get(cacheKey);
  if (hit) return hit.promise;

  const token = keyStore.load();
  if (!token) return Promise.reject(new WbError('NO_KEY', 'Ключ WB не задан. Вставьте его в настройках.'));

  const entry = { at: now, promise: null };
  entry.promise = fetchWaysheets({ from, to: toExclusive, token })
    .then((trips) => ({ trips, fetchedAt: new Date().toISOString() }));
  cache.set(cacheKey, entry);
  return entry.promise;
}

// ---------- HTTP ----------

const SECURITY_HEADERS = {
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Referrer-Policy': 'no-referrer',
  'Content-Security-Policy': "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'",
};

function send(res, status, body, headers = {}) {
  const isJson = typeof body !== 'string' && !Buffer.isBuffer(body);
  res.writeHead(status, {
    ...SECURITY_HEADERS,
    'Cache-Control': 'no-store',
    ...(isJson ? { 'Content-Type': 'application/json; charset=utf-8' } : {}),
    ...headers,
  });
  res.end(isJson ? JSON.stringify(body) : body);
}

function sendError(res, status, code, message) {
  send(res, status, { error: { code, message } });
}

function readJson(req, limit = 64 * 1024) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > limit) { reject(new Error('too large')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => {
      try { resolve(chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : {}); } catch (e) { reject(e); }
    });
    req.on('error', reject);
  });
}

function checkAuth(req) {
  if (!APP_PASSWORD) return true;
  const m = /^Basic\s+(.+)$/i.exec(req.headers.authorization || '');
  if (!m) return false;
  const decoded = Buffer.from(m[1], 'base64').toString('utf8');
  const pass = decoded.slice(decoded.indexOf(':') + 1);
  const a = crypto.createHash('sha256').update(pass).digest();
  const b = crypto.createHash('sha256').update(APP_PASSWORD).digest();
  return crypto.timingSafeEqual(a, b);
}

// Изменяющие запросы принимаем только со своей же страницы.
function sameOrigin(req) {
  const origin = req.headers.origin;
  if (!origin) return true;
  try { return new URL(origin).host === req.headers.host; } catch { return false; }
}

const STATIC = {
  '/': ['index.html', 'text/html; charset=utf-8'],
  '/app.js': ['app.js', 'text/javascript; charset=utf-8'],
  '/style.css': ['style.css', 'text/css; charset=utf-8'],
};

async function handleReport(req, res, url) {
  const from = url.searchParams.get('from');
  const to = url.searchParams.get('to') || from;
  if (!isValidDate(from) || !isValidDate(to)) return sendError(res, 400, 'BAD_DATE', 'Неверная дата.');
  if (from > to) return sendError(res, 400, 'BAD_DATE', 'Начало периода позже конца.');
  if (addDays(from, MAX_PERIOD_DAYS) <= to) {
    return sendError(res, 400, 'BAD_DATE', `Период не длиннее ${MAX_PERIOD_DAYS} дней.`);
  }
  const percentParam = url.searchParams.get('percent');
  const percent = percentParam === null ? loadSettings().percent : parsePercent(percentParam);
  if (percent === null) return sendError(res, 400, 'BAD_PERCENT', 'Процент должен быть от 0 до 100.');

  try {
    // WB запрашиваем до следующего дня, затем режем по дате закрытия.
    const { trips, fetchedAt } = await getWaysheets(from, addDays(to, 1));
    const report = buildReport(trips, { from, to, percent, timeZone: TIME_ZONE });
    send(res, 200, { ...report, fetchedAt, cacheTtlSec: CACHE_TTL_MS / 1000 });
  } catch (e) {
    if (e instanceof WbError) {
      const status = e.code === 'KEY_EXPIRED' || e.code === 'NO_KEY' ? 409 : 502;
      return sendError(res, status, e.code, e.message);
    }
    console.error('Ошибка расчёта:', e && e.message);
    sendError(res, 500, 'INTERNAL', 'Внутренняя ошибка сервера.');
  }
}

async function handle(req, res) {
  if (!checkAuth(req)) {
    return send(res, 401, 'Нужен пароль', {
      'WWW-Authenticate': 'Basic realm="WB salary", charset="UTF-8"',
      'Content-Type': 'text/plain; charset=utf-8',
    });
  }

  const url = new URL(req.url, 'http://localhost');
  const p = url.pathname;

  if (req.method === 'GET' && STATIC[p]) {
    const [file, type] = STATIC[p];
    return send(res, 200, fs.readFileSync(path.join(PUBLIC_DIR, file)), { 'Content-Type': type, 'Cache-Control': 'no-cache' });
  }

  if (req.method !== 'GET' && !sameOrigin(req)) return sendError(res, 403, 'FORBIDDEN', 'Чужой источник запроса.');

  if (p === '/api/report' && req.method === 'GET') return handleReport(req, res, url);

  if (p === '/api/key' && req.method === 'GET') {
    let token = null;
    try { token = keyStore.load(); } catch {
      return send(res, 200, { configured: false, broken: true });
    }
    return send(res, 200, keyInfo(token));
  }

  if (p === '/api/key' && req.method === 'POST') {
    let body;
    try { body = await readJson(req); } catch { return sendError(res, 400, 'BAD_JSON', 'Неверный запрос.'); }
    const token = extractToken(body && body.value);
    if (!token) {
      return sendError(res, 400, 'BAD_KEY',
        'Не нашёл ключ. Вставьте адрес страницы ol.wildberries.ru целиком (с «bearer=») или сам ключ.');
    }
    const info = keyInfo(token);
    if (info.expired) return sendError(res, 400, 'KEY_EXPIRED', 'Этот ключ уже истёк — возьмите свежий.');
    keyStore.save(token);
    cache.clear();
    return send(res, 200, info);
  }

  if (p === '/api/key' && req.method === 'DELETE') {
    keyStore.remove();
    cache.clear();
    return send(res, 200, { configured: false });
  }

  if (p === '/api/settings' && req.method === 'GET') return send(res, 200, loadSettings());

  if (p === '/api/settings' && req.method === 'POST') {
    let body;
    try { body = await readJson(req); } catch { return sendError(res, 400, 'BAD_JSON', 'Неверный запрос.'); }
    const percent = parsePercent(body && body.percent);
    if (percent === null) return sendError(res, 400, 'BAD_PERCENT', 'Процент должен быть от 0 до 100.');
    const s = { ...loadSettings(), percent };
    saveSettings(s);
    return send(res, 200, s);
  }

  sendError(res, 404, 'NOT_FOUND', 'Не найдено.');
}

const server = http.createServer((req, res) => {
  handle(req, res).catch((e) => {
    console.error('Необработанная ошибка:', e && e.message);
    if (!res.headersSent) sendError(res, 500, 'INTERNAL', 'Внутренняя ошибка сервера.');
  });
});

if (require.main === module) {
  server.listen(PORT, HOST, () => {
    console.log(`Расчёт зарплаты: http://${HOST === '0.0.0.0' ? 'localhost' : HOST}:${PORT}`);
    if (HOST !== '127.0.0.1' && HOST !== 'localhost' && !APP_PASSWORD) {
      console.warn('Внимание: сервер доступен из сети без пароля. Задайте APP_PASSWORD.');
    }
    if (!process.env.WB_KEY_SECRET) {
      console.warn('WB_KEY_SECRET не задан — мастер-ключ шифрования лежит в data/master.key.');
    }
  });
}

module.exports = { server, getWaysheets, cache };
