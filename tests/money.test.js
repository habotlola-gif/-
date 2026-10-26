'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { toKopecks, percentOf, formatRub } = require('../lib/money');

test('toKopecks: число, строка, объект, null', () => {
  assert.equal(toKopecks(1234.56), 123456);
  assert.equal(toKopecks(0.29), 29);
  assert.equal(toKopecks(1.005), 101);
  assert.equal(toKopecks(100), 10000);
  assert.equal(toKopecks(null), 0);
  assert.equal(toKopecks(undefined), 0);
  assert.equal(toKopecks('1 234,5'), 123450);
  assert.equal(toKopecks({ source: '2 767,87', parsedValue: 2767.87 }), 276787);
  assert.equal(toKopecks({ source: '15,10', parsedValue: null }), 1510);
  assert.equal(toKopecks({ source: null, parsedValue: '99.9' }), 9990);
  assert.equal(toKopecks(-12.34), -1234);
});

test('percentOf округляет до копеек половиной вверх', () => {
  assert.equal(percentOf(8161931, 20), 1632386); // 16 323,862
  assert.equal(percentOf(250, 20), 50);
  assert.equal(percentOf(12, 12.5), 2); // 1.5 → 2
  assert.equal(percentOf(10, 25), 3); // 2.5 → 3
  assert.equal(percentOf(0, 20), 0);
});

test('formatRub', () => {
  assert.equal(formatRub(8161931), '81 619,31');
  assert.equal(formatRub(5), '0,05');
  assert.equal(formatRub(-100), '-1,00');
});
