'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { buildReport, dayOf, parseRoute, isValidDate, addDays } = require('../lib/calc');

const trip = (over) => ({
  id: Math.floor(Math.random() * 1e9),
  driverName: 'Иванов И.',
  driverId: 1,
  vehicleNumberPlate: 'А001АА777',
  openDt: '2026-10-25T08:00:00',
  closeDt: '2026-10-25T18:00:00',
  totalPrice: 1000,
  fineSum: null,
  isDeleted: false,
  ...over,
});

test('берёт только закрытые и не удалённые рейсы, считает по водителю', () => {
  const trips = [
    trip({ totalPrice: 1000.01 }),
    trip({ totalPrice: { source: '500,50', parsedValue: 500.5 }, fineSum: 100 }),
    trip({ totalPrice: 777, isDeleted: true }),
    trip({ totalPrice: 333, closeDt: null }),
    trip({ driverId: 2, driverName: 'Петров П.', totalPrice: 200, fineSum: 10.5 }),
  ];
  const r = buildReport(trips, { from: '2026-10-25', to: '2026-10-25', percent: 20 });
  assert.equal(r.drivers.length, 2);
  const ivanov = r.drivers.find((d) => d.driverId === 1);
  assert.equal(ivanov.tripsCount, 2);
  assert.equal(ivanov.sum, 150051);
  assert.equal(ivanov.retention, 30010); // 30 010,2 → 30 010
  assert.equal(ivanov.fines, 10000);
  assert.equal(ivanov.payout, 150051 - 30010 - 10000);
  assert.equal(r.inTransit.length, 1);
  assert.equal(r.stats.skippedDeleted, 1);
  assert.equal(r.totals.payout, r.drivers.reduce((a, d) => a + d.payout, 0));
});

test('итог — сумма округлённых по водителям значений', () => {
  // У каждого 0,03 ₽ → удержание 0,006 ₽ → 0,01 ₽. По сумме было бы 0,02.
  const trips = [1, 2, 3].map((id) => trip({ driverId: id, driverName: `В${id}`, totalPrice: 0.03 }));
  const r = buildReport(trips, { from: '2026-10-25', to: '2026-10-25', percent: 20 });
  assert.equal(r.totals.sum, 9);
  assert.equal(r.totals.retention, 3);
  assert.equal(r.totals.payout, 6);
});

test('граница периода: закрытые по closeDt, незакрытые по openDt', () => {
  const trips = [
    trip({ openDt: '2026-10-24T22:00:00', closeDt: '2026-10-25T02:00:00', totalPrice: 1 }),
    trip({ openDt: '2026-10-25T22:00:00', closeDt: '2026-10-26T02:00:00', totalPrice: 2 }),
    trip({ openDt: '2026-10-25T23:00:00', closeDt: null, totalPrice: 3 }),
    trip({ openDt: '2026-10-26T01:00:00', closeDt: null, totalPrice: 4 }),
  ];
  const r = buildReport(trips, { from: '2026-10-25', to: '2026-10-25', percent: 20 });
  assert.equal(r.totals.sum, 100);
  assert.equal(r.inTransit.length, 1);
  assert.equal(r.inTransit[0].price, 300);
  assert.equal(r.stats.skippedOutside, 2);
});

test('dayOf: без смещения — как есть, со смещением — по Москве', () => {
  assert.equal(dayOf('2026-10-25T23:59:59.123', 'Europe/Moscow'), '2026-10-25');
  assert.equal(dayOf('2026-10-25T21:30:00Z', 'Europe/Moscow'), '2026-10-26');
  assert.equal(dayOf('2026-10-25T21:30:00+03:00', 'Europe/Moscow'), '2026-10-25');
  assert.equal(dayOf(null, 'Europe/Moscow'), null);
});

test('parseRoute', () => {
  assert.deepEqual(parseRoute('СЦ Подольск (123), ПВЗ Ленина 5 (456)'), [
    { name: 'СЦ Подольск', id: '123' },
    { name: 'ПВЗ Ленина 5', id: '456' },
  ]);
  assert.deepEqual(parseRoute(null), []);
});

test('даты', () => {
  assert.ok(isValidDate('2026-10-25'));
  assert.ok(!isValidDate('2026-02-30'));
  assert.ok(!isValidDate('25.10.2026'));
  assert.equal(addDays('2026-10-31', 1), '2026-11-01');
  assert.equal(addDays('2026-12-31', 1), '2027-01-01');
});
