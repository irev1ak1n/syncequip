'use strict'
/*
 * Demo Mode check on a real Paper server (the isolated localhost-only bot server).
 *
 * Bot1 (made operator) spawns the demo husks and runs the sequence. Bot2 stands nearby as an
 * observer. We check the plugin's debug log for same-tick execution, what the observer's client
 * received, the real entity data of a husk, a replay after reset, cleanup, and that husks are not
 * saved across a server restart.
 *
 * Usage: node demo-test.js [mobs]   (default 10)
 */
const fs = require('node:fs')
const path = require('node:path')
const { performance } = require('node:perf_hooks')
const { TestServer, checkLocalhostOnly } = require('./lib/server')
const { analyzeServerTicks, STEP_NAMES } = require('./lib/analyze')

const MOBS = Math.max(1, Math.min(20, parseInt(process.argv[2] || '10', 10) || 10))
const PORT = 25599
const HOST = '127.0.0.1'
const SERVER_DIR = path.resolve(process.env.BOT_SERVER_DIR || path.join(__dirname, '..', '..', 'syncequip-bot-server'))
const RUN_DIR = path.join(__dirname, '.run')
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const log = (...a) => console.log('[demo-test]', ...a)
const results = []
const check = (label, ok, detail = '') => {
  results.push({ label, ok })
  console.log(`   [${ok ? 'PASS' : 'FAIL'}] ${label}${detail ? ' (' + detail + ')' : ''}`)
}

function stepOf (name) {
  if (!name) return null
  if (name.endsWith('_boots')) return 'BOOTS'
  if (name.endsWith('_leggings')) return 'LEGGINGS'
  if (name.endsWith('_chestplate')) return 'CHESTPLATE'
  if (name.endsWith('_helmet')) return 'HELMET'
  if (name === 'shield') return 'SHIELD'
  return null
}

function waitForMessage (bot, regex, ms, label) {
  return new Promise((resolve, reject) => {
    const recent = []
    const remember = msg => { recent.push(msg); if (recent.length > 5) recent.shift() }
    bot.on('messagestr', remember)
    const timer = setTimeout(() => { bot.off('messagestr', on); bot.off('messagestr', remember); reject(new Error(`${bot.username}: timed out waiting for "${label}". Recent chat: ${JSON.stringify(recent)}`)) }, ms)
    const on = msg => { if (regex.test(msg)) { clearTimeout(timer); bot.off('messagestr', on); resolve(msg) } }
    bot.on('messagestr', on)
  })
}

async function say (bot, command, regex, label, ms = 8000) {
  const waiting = waitForMessage(bot, regex, ms, label)
  bot.chat(command)
  await sleep(450)
  return waiting
}

async function startServer () {
  const server = new TestServer({ dir: SERVER_DIR, jar: 'paper-26.3-231.jar', port: PORT, pidFile: path.join(RUN_DIR, 'server.pid') })
  server.start()
  await server.waitForLine(/Done \(/, 180000, 'server start')
  const reach = await checkLocalhostOnly(PORT)
  if (!reach.loopback || reach.reachable.length) throw new Error('Server is not reachable on localhost only. Stopping.')
  return server
}

async function main () {
  fs.mkdirSync(RUN_DIR, { recursive: true })
  const props = fs.readFileSync(path.join(SERVER_DIR, 'server.properties'), 'utf8')
  if (!/^server-ip=127\.0\.0\.1$/m.test(props)) throw new Error('Refusing to run: server-ip must be 127.0.0.1')
  const mineflayer = require('@wp2508/mineflayer')

  let server = await startServer()
  const bots = []
  try {
    await server.waitForLine(/Enabling SyncEquip/, 1000, 'SyncEquip enabled')
    // The world file remembers its difficulty, and husks cannot exist on Peaceful.
    server.command('difficulty easy')
    await sleep(500)
    log(`Server up on localhost only. Connecting two bots. Demo size: ${MOBS} husks.`)
    for (const username of ['Bot1', 'Bot2']) {
      const bot = mineflayer.createBot({ host: HOST, port: PORT, username, auth: 'offline', version: '26.3' })
      bot.on('error', e => console.error(`[${username}] error:`, e.message))
      bots.push(bot)
      await new Promise((resolve, reject) => {
        const t = setTimeout(() => reject(new Error(`${username} did not spawn`)), 30000)
        bot.once('spawn', () => { clearTimeout(t); resolve() })
      })
      await sleep(250)
    }
    const [director, observer] = bots
    server.command('op Bot1')
    await sleep(500)
    const spot = director.entity.position
    server.command(`tp Bot2 ${Math.floor(spot.x) + 0.5} ${Math.floor(spot.y)} ${Math.floor(spot.z) + 8.5}`)
    await sleep(1000)

    // Record equipment packets for husks, as seen by the observer and by the director.
    const events = []
    const seen = new Set()
    let recording = false
    for (const bot of bots) {
      bot.on('entityEquip', entity => {
        if (!recording || entity.name !== 'husk') return
        for (const item of entity.equipment || []) {
          const step = item && stepOf(item.name)
          const key = `${bot.username}|${entity.id}|${step}`
          if (step && !seen.has(key)) { seen.add(key); events.push({ observer: bot.username, subject: entity.id, step, t: performance.now() }) }
        }
      })
    }
    const husksOf = bot => Object.values(bot.entities).filter(e => e.name === 'husk')
    const wornBy = e => (e.equipment || []).filter(i => i && stepOf(i.name)).length

    const itemsBefore = director.inventory.items().map(i => i.name + "x" + i.count).sort().join(",")
    console.log('\nSpawn')
    await say(director, `/syncequip demo spawn ${MOBS}`, /Spawned \d+ demo husks/, 'spawn message')
    await sleep(1500)
    check(`${MOBS} husks exist for the director's client`, husksOf(director).length === MOBS, `saw ${husksOf(director).length}`)
    check(`${MOBS} husks exist for the observer's client`, husksOf(observer).length === MOBS, `saw ${husksOf(observer).length}`)
    check('every husk starts with no armor or shield', husksOf(observer).every(e => wornBy(e) === 0))
    const ys = new Set(husksOf(observer).map(e => e.position.y.toFixed(2)))
    check('all husks stand at one height in a row', ys.size === 1, [...ys].join(','))

    const lineCount = server.lines.length
    server.command('data get entity @e[type=minecraft:husk,limit=1]')
    const nbtLine = await server.waitForLine(/has the following entity data/, 5000, 'husk entity data')
    check('real entity data: NoAI', /NoAI: 1b/.test(nbtLine))
    check('real entity data: Invulnerable', /Invulnerable: 1b/.test(nbtLine))
    check('real entity data: Silent', /Silent: 1b/.test(nbtLine))
    check('real entity data: not set to burn or hold a target', !/Fire: [1-9]/.test(nbtLine))
    void lineCount

    console.log('\nRun 1')
    recording = true
    const startedAt = performance.now()
    await say(director, '/syncequip demo start', /Demo started with \d+ mobs/, 'start message')
    await server.waitForLine(/sequence #\d+ finished: COMPLETED/, 40000, 'run finished')
    await sleep(800)
    log(`Run took about ${Math.round(performance.now() - startedAt)} ms including the countdown`)
    const run1 = analyzeServerTicks(server.lines.filter(l => /sequence #1 /.test(l)), MOBS, 4)
    for (const [label, ok] of run1.checks) check(label, ok)
    const seq = run1.sequences[0]
    if (seq) console.log('     ' + seq.steps.map(s => `${s.name}@${s.ticks[0]}`).join('  '))
    for (const name of STEP_NAMES) {
      const g = events.filter(e => e.step === name && e.observer === 'Bot2')
      const subjects = new Set(g.map(e => e.subject))
      const spread = g.length ? Math.max(...g.map(e => e.t)) - Math.min(...g.map(e => e.t)) : NaN
      check(`observer saw ${name} appear on all ${MOBS} husks`, subjects.size === MOBS, `${subjects.size} husks, spread ${Number.isNaN(spread) ? 'n/a' : spread.toFixed(1)} ms`)
    }
    check('every husk ends fully dressed (5 pieces)', husksOf(observer).every(e => wornBy(e) === 5))
    check("the director's own inventory is untouched", director.inventory.items().map(i => i.name + "x" + i.count).sort().join(",") === itemsBefore)

    console.log('\nReset and replay')
    recording = false
    await say(director, '/syncequip demo reset', /Removed the equipment from \d+/, 'reset message')
    await sleep(800)
    check('reset removes every piece', husksOf(observer).every(e => wornBy(e) === 0))
    const idsBefore = new Set(husksOf(observer).map(e => e.id))
    seen.clear(); events.length = 0; recording = true
    await say(director, '/syncequip demo speed 2', /interval set to 2 ticks/, 'speed message')
    await say(director, '/syncequip demo start', /Demo started with \d+ mobs/, 'second start')
    await server.waitForLine(/sequence #2 finished: COMPLETED/, 40000, 'second run finished')
    await sleep(800)
    const run2 = analyzeServerTicks(server.lines.filter(l => /sequence #2 /.test(l)), MOBS, 2)
    for (const [label, ok] of run2.checks) check('replay: ' + label, ok)
    check('replay used the same husks, nothing respawned', husksOf(observer).every(e => idsBefore.has(e.id)) && husksOf(observer).length === MOBS)

    console.log('\nRepeat')
    recording = false
    await say(director, '/syncequip demo repeat 2', /Demo started with \d+ mobs.*2 runs/, 'repeat message')
    await server.waitForLine(/sequence #4 finished: COMPLETED/, 60000, 'repeat finished')
    await sleep(500)
    check('repeat ran two more sequences back to back', server.lines.some(l => /sequence #3 finished: COMPLETED/.test(l)) && server.lines.some(l => /sequence #4 finished: COMPLETED/.test(l)))

    console.log('\nCleanup')
    server.command('execute if entity @e[type=minecraft:husk]')
    check('husks exist on the server before cleanup', !!(await server.waitForLine(/Test passed/, 5000, 'husks present').catch(() => null)))
    await say(director, '/syncequip demo clear', /Removed \d+ demo mobs/, 'clear message')
    await sleep(800)
    check('clear removes every husk for the clients', husksOf(observer).length === 0)
    server.command('execute if entity @e[type=minecraft:husk]')
    await sleep(800)
    check('no husks remain on the server', server.lines.slice(-4).some(l => /Test failed/.test(l)))

    console.log('\nRestart safety')
    await say(director, `/syncequip demo spawn 3`, /Spawned 3/, 'respawn for restart check')
    for (const b of bots) b.quit()
    await sleep(500)
    await server.stop()
    server = await startServer()
    await server.waitForLine(/Enabling SyncEquip/, 1000, 'SyncEquip enabled again')
    await sleep(1500)
    server.command('execute if entity @e[type=minecraft:husk]')
    await sleep(1000)
    check('husks left in the world at shutdown are gone after a restart', server.lines.slice(-6).some(l => /Test failed/.test(l)))
    await server.stop()
  } finally {
    for (const b of bots) { try { b.quit() } catch {} }
    await server.stop()
  }

  const failed = results.filter(r => !r.ok)
  console.log(`\n${results.length - failed.length} of ${results.length} checks passed`)
  console.log('Not verified here: how the armor looks, and every sound. Bots cannot see or hear.')
  process.exitCode = failed.length ? 1 : 0
}

main().catch(e => { console.error('[demo-test] ERROR: ' + e.message); process.exitCode = 1 })
