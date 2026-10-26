'use strict';

const { toKopecks, percentOf } = require('./money');

const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;
const HAS_OFFSET_RE = /(?:Z|[+-]\d{2}:?\d{2})$/i;

function isValidDate(s) {
  if (typeof s !== 'string' || !DATE_RE.test(s)) return false;
  const d = new Date(`${s}T00:00:00Z`);
  return !Number.isNaN(d.getTime()) && d.toISOString().slice(0, 10) === s;
}

function addDays(dateStr, days) {
  const d = new Date(`${dateStr}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

// Календарный день метки времени. Время без смещения WB отдаёт по своему
// (московскому) часовому поясу — берём дату как есть. Время со смещением
// переводим в заданный часовой пояс.
function dayOf(dt, timeZone) {
  if (!dt || typeof dt !== 'string') return null;
  const s = dt.trim();
  if (!HAS_OFFSET_RE.test(s)) return DATE_RE.test(s.slice(0, 10)) ? s.slice(0, 10) : null;
  const d = new Date(s);
  if (Number.isNaN(d.getTime())) return null;
  return new Intl.DateTimeFormat('en-CA', {
    timeZone, year: 'numeric', month: '2-digit', day: '2-digit',
  }).format(d);
}

// «Склад А (123), ПВЗ Б (456)» → [{name, id}]
function parseRoute(routeName) {
  if (!routeName || typeof routeName !== 'string') return [];
  return routeName.split(', ').map((part) => {
    const m = /^(.*?)\s*\((\d+)\)\s*$/.exec(part.trim());
    return m ? { name: m[1], id: m[2] } : { name: part.trim(), id: null };
  }).filter((p) => p.name || p.id);
}

function tripView(t) {
  return {
    id: t.id,
    openDt: t.openDt || null,
    closeDt: t.closeDt || null,
    vehicleNumberPlate: t.vehicleNumberPlate || '',
    price: toKopecks(t.totalPrice),
    fine: toKopecks(t.fineSum),
    isFine: Boolean(t.isFine),
    isFineUnderConsideration: Boolean(t.isFineUnderConsideration),
    isApproved: t.isApproved ?? null,
    shksCount: t.shksCount ?? null,
    boxCountTotal: t.boxCountTotal ?? null,
    boxCountDelivered: t.boxCountDelivered ?? null,
    planMileage: t.planMileage ?? null,
    totalSumReturn: t.totalSumReturn === null || t.totalSumReturn === undefined
      ? null : toKopecks(t.totalSumReturn),
    logRouteId: t.logRouteId ?? null,
    logRouteName: t.logRouteName || '',
    routePoints: parseRoute(t.logRouteName),
    srcOfficeName: t.srcOfficeName || '',
  };
}

function driverKey(t) {
  if (t.driverId !== null && t.driverId !== undefined && t.driverId !== '') return `id:${t.driverId}`;
  return `name:${t.driverName || '—'}`;
}

/**
 * Считает ведомость.
 * trips    — массив рейсов из WB (как пришёл).
 * from, to — включительные границы периода, YYYY-MM-DD.
 * percent  — процент удержания.
 */
function buildReport(trips, { from, to, percent, timeZone = 'Europe/Moscow' }) {
  const drivers = new Map();
  const inTransit = [];
  let skippedOutside = 0;
  let skippedDeleted = 0;

  for (const t of Array.isArray(trips) ? trips : []) {
    if (!t || typeof t !== 'object') continue;
    if (t.isDeleted === true) { skippedDeleted += 1; continue; }

    // Закрытые — по дате закрытия, незакрытые — по дате выезда.
    const day = dayOf(t.closeDt || t.openDt, timeZone);
    if (!day || day < from || day > to) { skippedOutside += 1; continue; }

    if (!t.closeDt) {
      inTransit.push({ ...tripView(t), driverName: t.driverName || '—', driverId: t.driverId ?? null });
      continue;
    }

    const key = driverKey(t);
    let d = drivers.get(key);
    if (!d) {
      d = { key, driverId: t.driverId ?? null, driverName: t.driverName || '—', plates: new Set(), trips: [] };
      drivers.set(key, d);
    }
    if (t.vehicleNumberPlate) d.plates.add(t.vehicleNumberPlate);
    d.trips.push(tripView(t));
  }

  const rows = [];
  for (const d of drivers.values()) {
    d.trips.sort((a, b) => String(a.closeDt).localeCompare(String(b.closeDt)));
    const sum = d.trips.reduce((acc, t) => acc + t.price, 0);
    const fines = d.trips.reduce((acc, t) => acc + t.fine, 0);
    // Округляем удержание по каждому водителю отдельно.
    const retention = percentOf(sum, percent);
    rows.push({
      driverId: d.driverId,
      driverName: d.driverName,
      plates: [...d.plates],
      tripsCount: d.trips.length,
      sum,
      retention,
      fines,
      payout: sum - retention - fines,
      hasFineUnderConsideration: d.trips.some((t) => t.isFineUnderConsideration),
      trips: d.trips,
    });
  }
  rows.sort((a, b) => a.driverName.localeCompare(b.driverName, 'ru'));
  inTransit.sort((a, b) => String(a.openDt).localeCompare(String(b.openDt)));

  // Итог — сумма уже округлённых значений по водителям.
  const totals = rows.reduce((acc, r) => ({
    tripsCount: acc.tripsCount + r.tripsCount,
    sum: acc.sum + r.sum,
    retention: acc.retention + r.retention,
    fines: acc.fines + r.fines,
    payout: acc.payout + r.payout,
  }), { tripsCount: 0, sum: 0, retention: 0, fines: 0, payout: 0 });

  return {
    from, to, percent, drivers: rows, totals, inTransit,
    stats: { received: Array.isArray(trips) ? trips.length : 0, skippedOutside, skippedDeleted },
  };
}

module.exports = { buildReport, dayOf, parseRoute, isValidDate, addDays };
