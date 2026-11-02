'use strict'
/*
 * Guided tour host. Starts the isolated localhost-only bot server, connects TourBot1..N and keeps them
 * online. You join with your own Minecraft client at localhost:25599 and type /se demo tour.
 *
 * The plugin tells each bot to walk with a private chat line "[SE-TOUR] goto <x> <z>". Everything else
 * (equipping, squads, titles) is done by the plugin on the server.
 *
 * Usage: node tour.js --player YourMinecraftName [--bots 5]
 */
const fs = require('node:fs')
const path = require('node:path')
const { TestServer, checkLocalhostOnly } = require('./lib/server')

const arg = (name, fallback) => {
  const i = process.argv.indexOf(`--${name}`)
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback
}
const PLAYER = arg('player', process.env.TOUR_PLAYER)
const BOTS = Math.max(2, Math.min(10, parseInt(arg('bots', '5'), 10) || 5))
const PORT = parseInt(process.env.TOUR_PORT || '25599', 10)
const HOST = '127.0.0.1'
const SERVER_DIR = path.resolve(process.env.BOT_SERVER_DIR || path.join(__dirname, '..', '..', 'syncequip-bot-server'))
const RUN_DIR = process.env.TOUR_RUN_DIR || path.join(__dirname, '.run')
const JAR = path.join(__dirname, '..', 'build', 'libs', 'SyncEquip-1.1.2.jar')
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

/* Walks to the middle of a block column: faces it, walks forward, hops over small steps, stops on arrival. */
function walkTo (bot, x, z) {
  if (bot.tourWalk) clearInterval(bot.tourWalk)
  const started = Date.now()
  const stop = () => {
    clearInterval(bot.tourWalk)
    bot.tourWalk = null
    bot.clearControlStates()
  }
  bot.tourWalk = setInterval(() => {
    const p = bot.entity.position
    const dx = x - p.x
    const dz = z - p.z
    if (Math.hypot(dx, dz) < 0.3 || Date.now() - started > 8000) return stop()
    bot.look(Math.atan2(-dx, -dz), 0, true)
    bot.setControlState('forward', true)
    bot.setControlState('jump', !!bot.entity.isCollidedHorizontally)
  }, 50)
}

async function main () {
  if (!PLAYER || !/^[A-Za-z0-9_]{3,16}$/.test(PLAYER)) {
    throw new Error('Pass your Minecraft name: node tour.js --player YourName')
  }
  fs.mkdirSync(RUN_DIR, { recursive: true })
  const props = fs.readFileSync(path.join(SERVER_DIR, 'server.properties'), 'utf8')
  if (!/^server-ip=127\.0\.0\.1$/m.test(props)) throw new Error('Refusing to run: server-ip must be 127.0.0.1')
  if (!fs.existsSync(JAR)) throw new Error('Build the plugin first (gradlew jar).')
  const mineflayer = require('@wp2508/mineflayer')

  // Two SyncEquip jars in plugins/ are ambiguous, so older versions are removed first.
  for (const f of fs.readdirSync(path.join(SERVER_DIR, 'plugins'))) {
    if (/^SyncEquip-.*\.jar$/.test(f) && f !== path.basename(JAR)) fs.rmSync(path.join(SERVER_DIR, 'plugins', f))
  }
  fs.copyFileSync(JAR, path.join(SERVER_DIR, 'plugins', path.basename(JAR)))
  fs.writeFileSync(path.join(RUN_DIR, 'harness.pid'), String(process.pid))
  const server = new TestServer({ dir: SERVER_DIR, jar: 'paper-26.3-231.jar', port: PORT, pidFile: path.join(RUN_DIR, 'server.pid') })
  server.start()
  await server.waitForLine(/Done \(/, 180000, 'server start')
  const reach = await checkLocalhostOnly(PORT)
  if (!reach.loopback || reach.reachable.length) {
    await server.stop()
    throw new Error('Server is reachable from the network. Stopped.')
  }
  server.command('difficulty peaceful')
  server.command(`op ${PLAYER}`)
  server.command('time set day')
  server.command('weather clear')

  const bots = []
  for (let i = 1; i <= BOTS; i++) {
    const username = `TourBot${i}`
    const bot = mineflayer.createBot({ host: HOST, port: PORT, username, auth: 'offline', version: '26.3' })
    bot.on('error', e => console.error(`[${username}] error:`, e.message))
    bot.on('kicked', r => console.error(`[${username}] kicked:`, JSON.stringify(r)))
    bot.on('messagestr', msg => {
      const m = /\[SE-TOUR\] goto (-?[\d.]+) (-?[\d.]+)/.exec(msg)
      if (m) walkTo(bot, parseFloat(m[1]), parseFloat(m[2]))
    })
    bot.on('death', () => bot.respawn && bot.respawn())
    bots.push(bot)
    await new Promise((resolve, reject) => {
      const t = setTimeout(() => reject(new Error(`${username} did not spawn`)), 30000)
      bot.once('spawn', () => { clearTimeout(t); resolve() })
    })
    await sleep(300)
  }

  console.log('')
  console.log('==============================================================')
  console.log(` ${BOTS} test players are online. Server is localhost only.`)
  console.log('')
  console.log(' 1. Open Minecraft (Java, same version as this server, 26.3)')
  console.log(` 2. Multiplayer -> Direct Connection -> localhost:${PORT}`)
  console.log(` 3. You will join as "${PLAYER}" and are an operator here.`)
  console.log(' 4. Face an open flat area and type:  /se demo tour')
  console.log('    Also: /se demo tour pause | resume | restart | stop')
  console.log('')
  console.log(' Press Ctrl+C in this window (or run stop-bot-test.bat) to stop.')
  console.log('==============================================================')

  const shutdown = async () => {
    console.log('Stopping bots and server...')
    for (const bot of bots) { try { bot.quit() } catch {} }
    await server.stop().catch(() => {})
    fs.rmSync(path.join(RUN_DIR, 'harness.pid'), { force: true })
    process.exit(0)
  }
  process.on('SIGINT', shutdown)
  process.on('SIGTERM', shutdown)
  server.on('exit', () => { console.log('Server exited.'); process.exit(0) })
  setInterval(() => {}, 1 << 30)
}

main().catch(e => { console.error(e.message); process.exit(1) })
