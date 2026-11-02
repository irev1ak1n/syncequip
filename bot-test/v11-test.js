'use strict'
/*
 * v1.1 check on the isolated Paper bot server: joining blocks (a block material, here obsidian, at several coordinates), per-squad speed, group unequip and saved
 * squad settings across a real server restart. Bots really walk onto the block.
 *
 * Usage: node v11-test.js [bots]   (default 10, 3 to 20)
 */
const fs = require('node:fs')
const path = require('node:path')
const { Vec3 } = require('vec3')
const { TestServer, checkLocalhostOnly } = require('./lib/server')
const { analyzeServerTicks } = require('./lib/analyze')

const BOTS = Math.max(3, Math.min(20, parseInt(process.argv[2] || '10', 10) || 10))
const PORT = 25599
const HOST = '127.0.0.1'
const SERVER_DIR = path.resolve(process.env.BOT_SERVER_DIR || path.join(__dirname, '..', '..', 'syncequip-bot-server'))
const RUN_DIR = path.join(__dirname, '.run')
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const results = []
const check = (label, ok, detail = '') => {
  results.push({ label, ok })
  console.log(`   [${ok ? 'PASS' : 'FAIL'}] ${label}${detail ? ' (' + detail + ')' : ''}`)
}

function waitForMessage (bot, regex, ms, label) {
  return new Promise((resolve, reject) => {
    const recent = []
    const remember = msg => { recent.push(msg); if (recent.length > 6) recent.shift() }
    bot.on('messagestr', remember)
    const timer = setTimeout(() => {
      bot.off('messagestr', on); bot.off('messagestr', remember)
      reject(new Error(`${bot.username}: timed out waiting for "${label}". Recent chat: ${JSON.stringify(recent)}`))
    }, ms)
    const on = msg => { if (regex.test(msg)) { clearTimeout(timer); bot.off('messagestr', on); bot.off('messagestr', remember); resolve(msg) } }
    bot.on('messagestr', on)
  })
}

// Vanilla kicks players who send many commands per second, so pace them.
async function say (bot, command, regex, label, ms = 10000) {
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

async function connect (mineflayer, username) {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username, auth: 'offline', version: '26.3' })
  bot.on('error', e => console.error(`[${username}] error:`, e.message))
  await new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(`${username} did not spawn`)), 30000)
    bot.once('spawn', () => { clearTimeout(t); resolve() })
  })
  return bot
}

/** Walks the bot in a straight line through the pad, like a player running over it. */
async function walkOver (bot, pad, fromX) {
  const y = pad.y + 1
  await bot.lookAt(new Vec3(pad.x + 3.5, y, pad.z + 0.5), true)
  bot.setControlState('forward', true)
  await sleep(1300)
  bot.setControlState('forward', false)
}

async function main () {
  fs.mkdirSync(RUN_DIR, { recursive: true })
  const props = fs.readFileSync(path.join(SERVER_DIR, 'server.properties'), 'utf8')
  if (!/^server-ip=127\.0\.0\.1$/m.test(props)) throw new Error('Refusing to run: server-ip must be 127.0.0.1')
  const mineflayer = require('@wp2508/mineflayer')
  // Start every run without squads saved by an earlier one.
  fs.rmSync(path.join(SERVER_DIR, 'plugins', 'SyncEquip', 'squads.yml'), { force: true })

  let server = await startServer()
  let bots = []
  try {
    await server.waitForLine(/Enabling SyncEquip/, 1000, 'SyncEquip enabled')
    server.command('gamerule send_command_feedback false')
    server.command('difficulty peaceful')
    console.log(`\nSetup (${BOTS} bots)`)
    const names = Array.from({ length: BOTS }, (_, i) => `Bot${i + 1}`)
    for (const name of names) {
      const bot = await connect(mineflayer, name)
      bot.chatLog = []
      bot.on('messagestr', m => bot.chatLog.push(m))
      bots.push(bot)
      await sleep(250)
    }
    const leader = bots[0]
    const base = leader.entity.position
    const y0 = Math.floor(base.y) - 1
    const pad = { x: Math.floor(base.x) + 20, y: y0, z: Math.floor(base.z) }
    // Obsidian blocks at several different coordinates. A stone block of the same shape is the decoy.
    const spots = [0, 14, 28, 42].map((dz, i) => ({ x: pad.x + i * 9, y: y0, z: pad.z + dz }))
    for (const sp of spots) server.command(`setblock ${sp.x} ${sp.y} ${sp.z} minecraft:obsidian`)
    server.command(`setblock ${pad.x + 5} ${pad.y} ${pad.z + 3} minecraft:stone`)
    await sleep(600)
    // The leader stands on one obsidian block and lets the plugin read the material under their feet.
    server.command(`tp Bot1 ${spots[0].x + 0.5} ${y0 + 1} ${spots[0].z + 0.5}`)
    await sleep(1200)
    await say(leader, '/syncequip squad create NULL', /Squad created/, 'squad created')
    await say(leader, '/syncequip squad block set', /is now obsidian/, 'block set')
    check('leader set obsidian from the block under their feet', true)
    await say(leader, '/syncequip squad block info', /Joining block of NULL/, 'block info')
    server.command(`tp Bot1 ${pad.x - 10} ${y0 + 1} ${pad.z + 60}`)

    console.log('\nJoining by walking onto the block')
    // Walking over stone (not the squad's material) must NOT work.
    const decoy = bots[1]
    server.command(`tp ${decoy.username} ${pad.x + 1.5} ${pad.y + 1} ${pad.z + 3.5}`)
    await sleep(1000)
    await decoy.lookAt(new Vec3(pad.x + 9.5, pad.y + 1, pad.z + 3.5), true)
    decoy.setControlState('forward', true)
    await sleep(900)
    decoy.setControlState('forward', false)
    await sleep(600)
    server.command('execute if entity @a[name=Bot2]')
    const decoyJoined = decoy.chatLog?.some(m => /You joined/.test(m))
    check('walking over a stone block does not join', !decoyJoined)

    const joinedAt = []
    const usedSpots = new Set()
    for (const [k, bot] of bots.slice(1).entries()) {
      const spot = spots[k % spots.length]
      usedSpots.add(`${spot.x},${spot.z}`)
      server.command(`tp ${bot.username} ${spot.x - 3.5} ${spot.y + 1} ${spot.z + 0.5}`)
      await sleep(700)
      const waiting = waitForMessage(bot, /You joined squad NULL/, 8000, 'join message')
      await walkOver(bot, spot)
      await waiting
      joinedAt.push(bot.username)
      server.command(`tp ${bot.username} ${pad.x - 20} ${pad.y + 1} ${pad.z + (bots.indexOf(bot) * 2)}`)
    }
    check(`all ${BOTS - 1} bots joined squad NULL by walking onto obsidian at ${usedSpots.size} different coordinates`, joinedAt.length === BOTS - 1 && usedSpots.size > 1)
    await sleep(500)
    const infoPromise = waitForMessage(leader, new RegExp(`${BOTS}/`), 6000, 'member count')
    leader.chat('/syncequip squad info')
    await infoPromise.then(() => check(`squad info shows ${BOTS} members`, true), () => check(`squad info shows ${BOTS} members`, false))
    await sleep(500)

    console.log('\nEquip at speed 4 (default loadout: sword, shield, 4 armor pieces)')
    await say(leader, '/syncequip squad speed 4', /speed set to 4 ticks/, 'speed set')
    // Everyone gets the netherite kit: sword in the selected hotbar slot, armor and shield in the inventory.
    server.command('function syncequip_test:kit_all')
    await sleep(2000)
    await say(leader, '/syncequip squad start', /started the squad sequence/, 'start')
    await server.waitForLine(/sequence #1 finished: COMPLETED/, 40000, 'equip finished')
    await sleep(600)
    const run1 = analyzeServerTicks(server.lines.filter(l => /sequence #1 /.test(l)), BOTS, 4)
    for (const [label, ok] of run1.checks) check(label, ok)
    const armorSlots = { 5: 'netherite_helmet', 6: 'netherite_chestplate', 7: 'netherite_leggings', 8: 'netherite_boots', 45: 'shield' }
    const dressed = bots.every(b => Object.entries(armorSlots).every(([slot, name]) => b.inventory.slots[slot]?.name === name))
    check('every bot wears the full set and holds the shield', dressed)
    check('every bot still holds the sword in the same hotbar slot', bots.every(b => b.heldItem?.name === 'netherite_sword' && b.quickBarSlot === 0))

    console.log('\nGroup unequip')
    await say(leader, '/syncequip squad unequip', /started the squad unequip/, 'unequip start')
    await server.waitForLine(/sequence #2 finished: COMPLETED/, 40000, 'unequip finished')
    await sleep(600)
    const run2 = analyzeServerTicks(server.lines.filter(l => /sequence #2 /.test(l)), BOTS, 4)
    for (const [label, ok] of run2.checks) check('unequip: ' + label, ok)
    const first = run2.sequences[0]
    check('unequip order is sword, shield, helmet, chestplate, leggings, boots',
      first && first.steps.map(s => s.name).join(',') === 'MAIN_HAND,SHIELD,HELMET,CHESTPLATE,LEGGINGS,BOOTS',
      first ? first.steps.map(s => s.name).join(',') : '')
    const bare = bots.every(b => [5, 6, 7, 8, 45].every(slot => !b.inventory.slots[slot]) && !b.heldItem)
    check('every bot has nothing equipped and an empty hand', bare)
    const names6 = ['netherite_sword', 'netherite_boots', 'netherite_leggings', 'netherite_chestplate', 'netherite_helmet', 'shield']
    check('all six items are back in every inventory', bots.every(b => names6.every(n => b.inventory.items().some(i => i.name === n))))
    check('the selected hotbar slot did not change', bots.every(b => b.quickBarSlot === 0))

    console.log('\nCustom loadout (chestplate and shield only, speed 2)')
    await say(leader, '/syncequip squad speed 2', /speed set to 2 ticks/, 'speed set')
    for (const slot of ['helmet', 'leggings', 'boots', 'mainhand']) {
      await say(leader, `/syncequip squad loadout set ${slot} none`, /now needs/, 'loadout set')
    }
    await say(leader, '/syncequip squad start', /started the squad sequence/, 'start custom')
    await server.waitForLine(/sequence #3 finished: COMPLETED/, 40000, 'custom finished')
    await sleep(600)
    const run3 = analyzeServerTicks(server.lines.filter(l => /sequence #3 /.test(l)), BOTS, 2)
    for (const [label, ok] of run3.checks) check('custom: ' + label, ok)
    check('custom: only chestplate and shield were equipped',
      run3.sequences[0] && run3.sequences[0].steps.map(s => s.name).join(',') === 'CHESTPLATE,SHIELD',
      run3.sequences[0] ? run3.sequences[0].steps.map(s => s.name).join(',') : '')
    check('custom: helmet stayed in the inventory', bots.every(b => !b.inventory.slots[5] && b.inventory.items().some(i => i.name === 'netherite_helmet')))

    console.log('\nSaved settings survive a real server restart')
    await sleep(1500)
    for (const b of bots) b.quit()
    bots = []
    await sleep(700)
    await server.stop()
    const saved = fs.readFileSync(path.join(SERVER_DIR, 'plugins', 'SyncEquip', 'squads.yml'), 'utf8')
    check('squads.yml was written with the block material, speed and loadout', /material: obsidian/.test(saved) && /speed: 2/.test(saved) && /helmet: none/.test(saved))
    check('squads.yml holds no player names', !/Bot\d/.test(saved))
    server = await startServer()
    await server.waitForLine(/Enabling SyncEquip/, 1000, 'SyncEquip enabled again')
    const b1 = await connect(mineflayer, 'Bot1')
    const b2 = await connect(mineflayer, 'Bot2')
    bots = [b1, b2]
    server.command(`tp Bot2 ${spots[1].x - 3.5} ${spots[1].y + 1} ${spots[1].z + 0.5}`)
    await sleep(900)
    b2.chatLog = []
    b2.on('messagestr', m => b2.chatLog.push(m))
    await walkOver(b2, spots[1])
    await sleep(500)
    check('before the leader returns, the block says the squad is not running', b2.chatLog.some(m => /not running/.test(m)))
    await say(b1, '/syncequip squad create NULL', /restored/, 'squad restored')
    check('the leader got the saved squad back', true)
    // A block placed after the restart, somewhere new, works as well.
    const fresh = { x: pad.x + 80, y: y0, z: pad.z + 5 }
    server.command(`setblock ${fresh.x} ${fresh.y} ${fresh.z} minecraft:obsidian`)
    server.command(`tp Bot2 ${fresh.x - 3.5} ${fresh.y + 1} ${fresh.z + 0.5}`)
    await sleep(1200)
    const rejoin = waitForMessage(b2, /You joined squad NULL/, 8000, 'rejoin')
    await walkOver(b2, fresh)
    await rejoin.then(() => check('the saved obsidian rule works after the restart, on a block placed afterwards', true), () => check('the saved obsidian rule works after the restart, on a block placed afterwards', false))
    const speedShown = waitForMessage(b1, /2 ticks/, 5000, 'speed')
    b1.chat('/syncequip squad speed')
    await speedShown.then(() => check('the saved speed (2 ticks) came back', true), () => check('the saved speed (2 ticks) came back', false))
  } finally {
    for (const b of bots) { try { b.quit() } catch {} }
    await server.stop()
  }

  const failed = results.filter(r => !r.ok)
  console.log(`\n${results.length - failed.length} of ${results.length} checks passed`)
  console.log('Not verified here: how it looks, and every sound. Bots cannot see or hear.')
  process.exitCode = failed.length ? 1 : 0
}

main().catch(e => { console.error('[v11-test] ERROR: ' + e.message); process.exitCode = 1 })
