'use strict'
/*
 * SyncEquip bot test.
 *
 * Starts the isolated localhost-only Paper server, connects 2 to 10 Mineflayer bots, gives them a
 * netherite kit with the test datapack, runs a squad sequence and checks the result two ways:
 *   1. server side: the plugin's own debug log (scheduled vs actual tick per bot per step)
 *   2. client side: when each bot received the equipment packets
 *
 * Bots cannot see or hear anything. See bot-test/README.md for what this does not prove.
 */
const fs = require('node:fs')
const path = require('node:path')
const { performance } = require('node:perf_hooks')
const { TestServer, checkLocalhostOnly } = require('./lib/server')
const { analyzeServerTicks, analyzePackets } = require('./lib/analyze')
const { startDashboard } = require('./lib/dashboard')

const args = process.argv.slice(2)
const flag = name => args.includes('--' + name)
const option = (name, def) => {
  const i = args.indexOf('--' + name)
  return i >= 0 && args[i + 1] ? args[i + 1] : def
}

const BOTS = Math.max(2, Math.min(10, parseInt(option('bots', '5'), 10) || 5))
const PORT = 25599
const HOST = '127.0.0.1'
const SERVER_DIR = path.resolve(process.env.BOT_SERVER_DIR || path.join(__dirname, '..', '..', 'syncequip-bot-server'))
const RUN_DIR = path.join(__dirname, '.run')
const REPORT_DIR = path.join(__dirname, 'reports')
const VIEWER_PORT = 3007
const DASHBOARD_PORT = 3008

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const log = (...a) => console.log('[bot-test]', ...a)

function fail (message) {
  console.error('[bot-test] ERROR: ' + message)
  process.exitCode = 1
}

function requireIsolatedServer () {
  const props = path.join(SERVER_DIR, 'server.properties')
  if (!fs.existsSync(props)) throw new Error(`Bot server not found at ${SERVER_DIR}. See bot-test/README.md.`)
  const text = fs.readFileSync(props, 'utf8')
  const get = key => (text.match(new RegExp('^' + key + '=(.*)$', 'm')) || [])[1]
  if (get('server-ip') !== HOST) throw new Error(`Refusing to run: server-ip must be ${HOST} in ${props}`)
  if (get('server-port') !== String(PORT)) throw new Error(`Refusing to run: server-port must be ${PORT} in ${props}`)
}

function waitForMessage (bot, regex, ms, label) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      bot.off('messagestr', onMsg)
      reject(new Error(`${bot.username}: timed out waiting for "${label}"`))
    }, ms)
    const onMsg = msg => {
      if (regex.test(msg)) {
        clearTimeout(timer)
        bot.off('messagestr', onMsg)
        resolve(msg)
      }
    }
    bot.on('messagestr', onMsg)
  })
}

function stepOf (itemName) {
  if (!itemName) return null
  if (itemName.endsWith('_boots')) return 'BOOTS'
  if (itemName.endsWith('_leggings')) return 'LEGGINGS'
  if (itemName.endsWith('_chestplate')) return 'CHESTPLATE'
  if (itemName.endsWith('_helmet')) return 'HELMET'
  if (itemName === 'shield') return 'SHIELD'
  return null
}

async function main () {
  requireIsolatedServer()
  fs.mkdirSync(RUN_DIR, { recursive: true })
  fs.mkdirSync(REPORT_DIR, { recursive: true })
  fs.writeFileSync(path.join(RUN_DIR, 'harness.pid'), String(process.pid))

  const mineflayer = require('@wp2508/mineflayer')
  const server = new TestServer({
    dir: SERVER_DIR,
    jar: 'paper-26.3-231.jar',
    port: PORT,
    pidFile: path.join(RUN_DIR, 'server.pid')
  })

  const bots = []
  let exiting = false
  const cleanup = async () => {
    if (exiting) return
    exiting = true
    for (const b of bots) { try { b.quit() } catch {} }
    if (!flag('keep') || process.exitCode) await server.stop()
    fs.rmSync(path.join(RUN_DIR, 'harness.pid'), { force: true })
  }
  process.on('SIGINT', async () => { await cleanup(); process.exit(1) })

  try {
    log(`Starting isolated server in ${SERVER_DIR}`)
    server.start()
    await server.waitForLine(/Done \(/, 180000, 'server start')
    const reach = await checkLocalhostOnly(PORT)
    log(`Reachable on 127.0.0.1: ${reach.loopback}. Reachable on this PC's LAN addresses (${reach.lan.join(', ') || 'none'}): ${reach.reachable.length ? reach.reachable.join(', ') : 'no'}`)
    if (!reach.loopback || reach.reachable.length) throw new Error('Server is not reachable on localhost only. Stopping.')
    await server.waitForLine(/Enabling SyncEquip/, 1000, 'SyncEquip enabled').catch(() => { throw new Error('SyncEquip did not enable') })

    const names = Array.from({ length: BOTS }, (_, i) => `Bot${i + 1}`)
    log(`Connecting ${BOTS} bots with the fork @wp2508/mineflayer (Minecraft 26.3, offline mode, localhost)`)
    for (const username of names) {
      const bot = mineflayer.createBot({ host: HOST, port: PORT, username, auth: 'offline', version: '26.3', checkTimeoutInterval: 60000 })
      bot.on('error', e => console.error(`[${username}] error:`, e.message))
      bot.on('kicked', r => console.error(`[${username}] kicked:`, JSON.stringify(r)))
      bot.chatLog = []
      bot.on('messagestr', m => bot.chatLog.push(m))
      bots.push(bot)
      await new Promise((resolve, reject) => {
        const t = setTimeout(() => reject(new Error(`${username} did not spawn in 30s`)), 30000)
        bot.once('spawn', () => { clearTimeout(t); resolve() })
      })
      await sleep(250)
    }
    log('All bots spawned')

    // Line the bots up, two blocks apart, so they all see each other.
    const base = bots[0].entity.position
    const y = Math.floor(base.y)
    names.forEach((n, i) => server.command(`tp ${n} ${Math.floor(base.x) + 0.5 + i * 2} ${y} ${Math.floor(base.z) + 0.5}`))
    await sleep(1500)

    // Give every bot the kit using the test datapack.
    server.command('function syncequip_test:kit_all')
    await sleep(1500)
    const kitProblems = []
    for (const bot of bots) {
      const items = bot.inventory.items().map(i => i.name)
      for (const need of ['netherite_sword', 'netherite_boots', 'netherite_leggings', 'netherite_chestplate', 'netherite_helmet', 'shield']) {
        if (!items.includes(need)) kitProblems.push(`${bot.username} is missing ${need}`)
      }
      for (const slot of [5, 6, 7, 8, 45]) {
        if (bot.inventory.slots[slot]) kitProblems.push(`${bot.username} slot ${slot} is not empty`)
      }
    }
    log(kitProblems.length ? `Kit problems: ${kitProblems.join('; ')}` : 'Kit check passed: every bot has the full netherite kit and empty equipment slots')

    if (flag('viewer')) await startViewer(bots[0])
    if (flag('dashboard') || flag('keep')) {
      startDashboard(bots, DASHBOARD_PORT, HOST)
      log(`Status page at http://localhost:${DASHBOARD_PORT} (localhost only)`)
    }

    // Start recording packet arrival times.
    const events = []
    const seen = new Set()
    const record = (observer, subject, step) => {
      const key = `${observer}|${subject}|${step}`
      if (seen.has(key)) return
      seen.add(key)
      events.push({ observer, subject, step, t: performance.now() })
    }
    for (const bot of bots) {
      bot.on('entityEquip', entity => {
        if (flag('debug-equip') && bot.username === 'Bot1') console.log('[equip]', entity.type, entity.username, entity.name, (entity.equipment || []).map(i => i && i.name))
        if (!entity.username || !Array.isArray(entity.equipment)) return
        for (const item of entity.equipment) {
          const step = item && stepOf(item.name)
          if (step) record(bot.username, entity.username, step)
        }
      })
      bot.inventory.on('updateSlot', (slot, oldItem, newItem) => {
        if (![5, 6, 7, 8, 45].includes(slot)) return
        const step = newItem && stepOf(newItem.name)
        if (step) record(bot.username, bot.username, step)
      })
    }

    // Squad: Bot1 leads, everyone else is invited and joins.
    const leader = bots[0]
    // Vanilla kicks players who send many commands per second, so pace them like a human would.
    const send = async (bot, cmd) => { bot.chat(cmd); await sleep(450) }
    const sendAndWait = async (bot, cmd, observer, regex, label) => {
      const waiting = waitForMessage(observer, regex, 8000, label)
      await send(bot, cmd)
      await waiting
    }
    await sendAndWait(leader, '/syncequip squad create', leader, /Squad created/, 'squad created')
    for (const bot of bots.slice(1)) {
      await sendAndWait(leader, `/syncequip squad invite ${bot.username}`, bot, /invited you/, 'invite received')
      await sendAndWait(bot, `/syncequip squad join ${leader.username}`, leader, new RegExp(`${bot.username} joined the squad`), 'join confirmed')
    }
    log(`Squad formed with ${BOTS} members. Starting the sequence.`)
    const startedAt = performance.now()
    await send(leader, '/syncequip squad start')
    await server.waitForLine(/sequence #\d+ finished: COMPLETED/, 40000, 'sequence finished')
    await sleep(800)
    log(`Sequence finished about ${Math.round(performance.now() - startedAt)} ms after the start command (includes the countdown)`)

    // Final inventory state.
    const stateProblems = []
    const expected = { 5: 'netherite_helmet', 6: 'netherite_chestplate', 7: 'netherite_leggings', 8: 'netherite_boots', 45: 'shield' }
    for (const bot of bots) {
      for (const [slot, name] of Object.entries(expected)) {
        const item = bot.inventory.slots[slot]
        if (!item || item.name !== name) stateProblems.push(`${bot.username} slot ${slot} is ${item ? item.name : 'empty'}, expected ${name}`)
      }
      const inStorage = bot.inventory.items().map(i => i.name)
      if (!inStorage.includes('netherite_sword')) stateProblems.push(`${bot.username} lost the sword`)
      if (bot.heldItem?.name !== 'netherite_sword') stateProblems.push(`${bot.username} is not holding the sword`)
      const leftovers = inStorage.filter(n => stepOf(n))
      if (leftovers.length) stateProblems.push(`${bot.username} still has ${leftovers.join(', ')} in the inventory`)
    }

    const serverResult = analyzeServerTicks(server.lines, BOTS)
    const packetResult = analyzePackets(events, names)
    printReport({ names, kitProblems, stateProblems, serverResult, packetResult })

    fs.writeFileSync(path.join(REPORT_DIR, 'latest.json'), JSON.stringify({
      when: new Date().toISOString(), bots: BOTS, kitProblems, stateProblems, serverResult, packetResult
    }, null, 2))
    fs.writeFileSync(path.join(REPORT_DIR, 'latest-debug-lines.txt'),
      server.lines.filter(l => /\[debug\]/.test(l)).join('\n') + '\n')

    const allChecks = [...serverResult.checks, ...packetResult.checks]
    if (kitProblems.length || stateProblems.length || allChecks.some(c => !c[1] && !c[2])) process.exitCode = 1

    if (flag('keep')) {
      log(`--keep: server and bots stay running. Status page: http://localhost:${DASHBOARD_PORT}. Run stop-bot-test.bat to stop.`)
      log('Press Enter in this window to give everyone a fresh kit and run the squad sequence again.')
      let busy = false
      process.stdin.on('data', async () => {
        if (busy) return
        busy = true
        server.command('function syncequip_test:kit_all')
        await sleep(3500)
        leader.chat('/syncequip squad start')
        await sleep(6000)
        busy = false
      })
      await new Promise(() => {})
    }
  } catch (e) {
    fail(e.message)
  } finally {
    await cleanup()
  }
}

function printReport ({ names, kitProblems, stateProblems, serverResult, packetResult }) {
  const line = '-'.repeat(72)
  const mark = (ok, soft) => (ok ? 'PASS' : soft ? 'WARN' : 'FAIL')
  console.log('\n' + line + '\nSyncEquip bot test report\n' + line)
  console.log(`Bots: ${names.join(', ')}`)
  console.log('\n1. Server side (the plugin debug log, one line per bot per step)')
  for (const s of serverResult.sequences) {
    console.log(`   sequence #${s.seq} with ${s.players} players`)
    for (const st of s.steps) {
      console.log(`     step ${st.step} ${st.name.padEnd(10)} tick ${st.ticks.join('/')} (+${st.offset})  ${st.players} bots  ${st.sameTick ? 'same tick' : 'DIFFERENT TICKS'}  max drift ${st.maxDrift}`)
    }
  }
  for (const [label, ok, soft] of serverResult.checks) console.log(`   [${mark(ok, soft)}] ${label}`)
  console.log('\n2. Client side (when each bot received the equipment packets)')
  for (const s of packetResult.steps) {
    console.log(`     ${s.name.padEnd(10)} received ${s.received}/${s.expected}  spread ${Number.isNaN(s.spreadMs) ? 'n/a' : s.spreadMs.toFixed(1)} ms` +
      (s.gapMs !== undefined ? `  gap from previous step ${s.gapMs.toFixed(0)} ms` : ''))
    if (s.missing.length) console.log(`       missing (observer>subject): ${s.missing.join(' ')}`)
  }
  for (const [label, ok, soft] of packetResult.checks) console.log(`   [${mark(ok, soft)}] ${label}`)
  console.log('\n3. Inventory state')
  console.log(`   [${mark(!kitProblems.length)}] kit given by the datapack`)
  console.log(`   [${mark(!stateProblems.length)}] final equipment and sword as expected`)
  for (const p of [...kitProblems, ...stateProblems]) console.log('     - ' + p)
  console.log('\nNot verified by bots: animations, rendering, how it looks, and every sound.')
  console.log(line)
}

async function startViewer (bot) {
  const http = require('node:http')
  const originalListen = http.Server.prototype.listen
  try {
    // prismarine-viewer listens on every network interface by default. Force it onto localhost.
    http.Server.prototype.listen = function (port, ...rest) {
      if (typeof port === 'number') return originalListen.call(this, port, HOST, ...rest.filter(r => typeof r === 'function'))
      return originalListen.call(this, port, ...rest)
    }
    // The viewer's server side requires the native 'canvas' module, which we do not install (it
    // downloads a binary at install time). Only headless rendering uses it, so a stub is enough.
    const Module = require('node:module')
    const resolve = Module._resolveFilename
    Module._resolveFilename = function (request, ...rest) {
      return request === 'canvas' ? require.resolve('./stubs/canvas.js') : resolve.call(this, request, ...rest)
    }
    const { mineflayer: viewer } = require('prismarine-viewer')
    Module._resolveFilename = resolve
    viewer(bot, { port: VIEWER_PORT, firstPerson: false, viewDistance: 4 })
    await sleep(1500)
    const reach = await checkLocalhostOnly(VIEWER_PORT)
    if (reach.reachable.length) throw new Error(`viewer is reachable on ${reach.reachable.join(', ')}`)
    log(`Prismarine Viewer started at http://localhost:${VIEWER_PORT} (localhost only)`)
  } catch (e) {
    log(`Prismarine Viewer unavailable (${e.message}). Continuing without it.`)
  } finally {
    http.Server.prototype.listen = originalListen
  }
}

main()
