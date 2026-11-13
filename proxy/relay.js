#!/usr/bin/env node
// HvH Proxy: ретранслятор Minecraft Bedrock с модулями.
// Игра подключается к прокси (127.0.0.1:19133), прокси — к настоящему серверу.
//   node relay.js [--config config.json] [--host H] [--port P] [--listen-port L] [--offline]
const fs = require('fs')
const path = require('path')
const { Relay } = require('bedrock-protocol')
const bpOptions = require('bedrock-protocol/src/options')
// Исходящее подключение Relay не принимает raknetBackend, поэтому меняем умолчание библиотеки:
// чистый JS-RakNet работает без нативной сборки (в т.ч. на телефоне в Termux).
bpOptions.defaultOptions.raknetBackend = 'jsp-raknet'
const modules = require('./modules')

function parseArgs (argv) {
  const out = {}
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i]
    if (a === '--offline') out.offline = true
    else if (a.startsWith('--')) out[a.slice(2)] = argv[++i]
  }
  return out
}

function loadConfig (args) {
  const file = args.config || path.join(__dirname, 'config.json')
  const cfg = JSON.parse(fs.readFileSync(file, 'utf8'))
  if (args.host) cfg.destination.host = args.host
  if (args.port) cfg.destination.port = Number(args.port)
  if (args['listen-port']) cfg.listen.port = Number(args['listen-port'])
  if (args.offline) cfg.offline = true
  return cfg
}

function createRelay (cfg, log = console.log) {
  const relay = new Relay({
    version: cfg.version,
    host: cfg.listen.host,
    port: cfg.listen.port,
    offline: cfg.offline,
    raknetBackend: cfg.raknetBackend || 'jsp-raknet', // чистый JS, работает без нативной сборки (в т.ч. в Termux)
    destination: { host: cfg.destination.host, port: cfg.destination.port },
    profilesFolder: path.join(__dirname, '.auth'), // сюда кэшируется вход Microsoft
    onMsaCode: (code, ds) => {
      log('\n[вход Microsoft] открой', code.verification_uri, 'и введи код', code.user_code)
      ds.disconnect('Войди в Microsoft:\n' + code.verification_uri + '\nКод: ' + code.user_code + '\nПотом зайди снова.')
    }
  })

  relay.on('join', (player) => {
    const ctx = { runtimeId: null, settings: {}, log }
    log('[прокси] игрок подключился')

    player.on('clientbound', (data) => {
      if (data.name === 'start_game') {
        ctx.runtimeId = data.params.runtime_entity_id
        log('[прокси] runtime id игрока:', ctx.runtimeId)
      }
      for (const m of modules) {
        const s = cfg.modules?.[m.name]
        if (!s?.enabled || !m.onClientbound) continue
        ctx.settings = s
        try { m.onClientbound(ctx, data.name, data.params) } catch (e) { log(`[${m.name}] ошибка:`, e.message) }
      }
    })

    player.on('serverbound', (data) => {
      for (const m of modules) {
        const s = cfg.modules?.[m.name]
        if (!s?.enabled || !m.onServerbound) continue
        ctx.settings = s
        try { m.onServerbound(ctx, data.name, data.params) } catch (e) { log(`[${m.name}] ошибка:`, e.message) }
      }
    })
  })
  return relay
}

module.exports = { createRelay, loadConfig }

if (require.main === module) {
  const cfg = loadConfig(parseArgs(process.argv))
  const relay = createRelay(cfg)
  relay.listen()
  console.log(`HvH Proxy: слушаю ${cfg.listen.host}:${cfg.listen.port} → ${cfg.destination.host}:${cfg.destination.port} (версия ${cfg.version}${cfg.offline ? ', offline' : ''})`)
  console.log('Модули:', Object.entries(cfg.modules || {}).filter(([, v]) => v.enabled).map(([k]) => k).join(', ') || 'нет')
  console.log('В игре: добавь сервер 127.0.0.1, порт', cfg.listen.port)
}
