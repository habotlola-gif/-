// Тест модуля Velocity без сети.
const assert = require('assert')
const velocity = require('../modules/velocity')

const ctx = (settings) => ({ runtimeId: 7n, settings })
const motion = (id) => ({ runtime_entity_id: id, velocity: { x: 1, y: 1, z: -1 }, tick: 0n })

// своя отдача: горизонталь 40%, вертикаль 100%
let p = motion(7n)
velocity.onClientbound(ctx({ horizontal: 40, vertical: 100 }), 'set_entity_motion', p)
assert.deepStrictEqual(p.velocity, { x: 0.4, y: 1, z: -0.4 })

// полное отключение отдачи
p = motion(7n)
velocity.onClientbound(ctx({ horizontal: 0, vertical: 0 }), 'set_entity_motion', p)
assert.strictEqual(p.velocity.x, 0); assert.strictEqual(p.velocity.y, 0)
assert.ok(Object.is(p.velocity.z, -0) || p.velocity.z === 0)

// чужая сущность не меняется
p = motion(8n)
velocity.onClientbound(ctx({ horizontal: 0, vertical: 0 }), 'set_entity_motion', p)
assert.deepStrictEqual(p.velocity, { x: 1, y: 1, z: -1 })

// другой пакет не трогаем
p = motion(7n)
velocity.onClientbound(ctx({ horizontal: 0 }), 'move_player', p)
assert.deepStrictEqual(p.velocity, { x: 1, y: 1, z: -1 })

// без настроек = без изменений
p = motion(7n)
velocity.onClientbound(ctx({}), 'set_entity_motion', p)
assert.deepStrictEqual(p.velocity, { x: 1, y: 1, z: -1 })
console.log('velocity: OK')
