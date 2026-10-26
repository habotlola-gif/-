'use strict';

// Все суммы внутри приложения — целые копейки.

const DECIMAL_RE = /^([+-]?)(\d*)(?:[.,](\d*))?$/;

// Переводит рублёвую строку в копейки без плавающей точки.
// Третий и дальше знаки после запятой округляются половиной вверх.
function parseRubString(str) {
  const clean = String(str).replace(/[\s  ₽]/g, '').replace(/руб\.?$/i, '');
  const m = DECIMAL_RE.exec(clean);
  if (!m || (m[2] === '' && (m[3] === undefined || m[3] === ''))) return null;
  const sign = m[1] === '-' ? -1 : 1;
  const intPart = m[2] === '' ? 0 : Number(m[2]);
  const frac = (m[3] || '').padEnd(3, '0');
  let kop = intPart * 100 + Number(frac.slice(0, 2));
  if (Number(frac[2]) >= 5) kop += 1;
  return sign * kop;
}

function numberToKopecks(n) {
  if (!Number.isFinite(n)) return null;
  // toFixed(6) убирает хвосты вида 0.29 * 100 = 28.999999999999996
  return parseRubString(n.toFixed(6));
}

// totalPrice бывает числом, строкой или объектом {source, parsedValue}.
function toKopecks(value) {
  if (value === null || value === undefined || value === '') return 0;
  if (typeof value === 'number') return numberToKopecks(value) ?? 0;
  if (typeof value === 'string') return parseRubString(value) ?? 0;
  if (typeof value === 'object') {
    for (const field of ['parsedValue', 'source', 'value']) {
      const v = value[field];
      if (v === null || v === undefined || v === '') continue;
      const kop = typeof v === 'number' ? numberToKopecks(v) : parseRubString(v);
      if (kop !== null) return kop;
    }
  }
  return 0;
}

// Целочисленное деление с округлением половины от нуля.
function divRound(numerator, denominator) {
  const sign = Math.sign(numerator) || 1;
  const abs = Math.abs(numerator);
  let q = Math.floor(abs / denominator);
  if ((abs % denominator) * 2 >= denominator) q += 1;
  return sign * q;
}

// Процент от суммы в копейках, округлённый до копейки.
// Процент допускает до двух знаков после запятой (12.5, 7.25).
function percentOf(kopecks, percent) {
  const hundredths = Math.round(Number(percent) * 100);
  return divRound(kopecks * hundredths, 10000);
}

function formatRub(kopecks) {
  const sign = kopecks < 0 ? '-' : '';
  const abs = Math.abs(kopecks);
  const rub = String(Math.floor(abs / 100)).replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
  const kop = String(abs % 100).padStart(2, '0');
  return `${sign}${rub},${kop}`;
}

module.exports = { toKopecks, parseRubString, divRound, percentOf, formatRub };
