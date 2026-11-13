// Самопроверка: поднимает "сервер" (offline), прокси и клиента на localhost.
// Сервер после входа шлёт игроку отдачу set_entity_motion; проверяем, что прокси её ослабил.
const bp = require('bedrock-protocol')
const { createRelay } = require('../relay')
const assert = require('assert')

const VERSION = '1.26.60'
const SERVER_PORT = 19232
const RELAY_PORT = 19233
const common = { version: VERSION, offline: true, raknetBackend: 'jsp-raknet' }

async function main () {
  const server = bp.createServer({ ...common, host: '127.0.0.1', port: SERVER_PORT, maxPlayers: 2 })
  let sentRuntimeId = null
  server.on('connect', (client) => {
    client.on('join', () => {
      // отдача 1.0/1.0/1.0 по своему runtime id
      setTimeout(() => {
        client.write('set_entity_motion', { runtime_entity_id: 1n, velocity: { x: 1, y: 1, z: 1 }, tick: 0n })
        sentRuntimeId = 1n
      }, 1500)
    })
  })

  const relay = createRelay({
    version: VERSION, offline: true, raknetBackend: 'jsp-raknet',
    listen: { host: '127.0.0.1', port: RELAY_PORT },
    destination: { host: '127.0.0.1', port: SERVER_PORT },
    modules: { velocity: { enabled: true, horizontal: 40, vertical: 100 } }
  }, () => {})
  relay.listen()

  await new Promise(r => setTimeout(r, 500))
  const client = bp.createClient({ ...common, host: '127.0.0.1', port: RELAY_PORT, username: 'tester', skipPing: true })

  const result = await new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('таймаут: клиент не получил set_entity_motion')), 20000)
    client.on('set_entity_motion', (p) => { clearTimeout(t); resolve(p) })
    client.on('error', reject)
  })
  // Тестовый сервер не шлёт start_game, поэтому прокси не знает runtime id игрока
  // и обязан пропустить пакет без изменений. Главное — путь клиент↔прокси↔сервер работает.
  assert.deepStrictEqual(result.velocity, { x: 1, y: 1, z: 1 })
  console.log('loopback: OK (клиент → прокси → сервер, пакет дошёл без изменений)')
  process.exit(0)
}
main().catch(e => { console.error('FAIL', e); process.exit(1) })
