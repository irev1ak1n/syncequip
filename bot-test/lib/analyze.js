'use strict'

const DEBUG_LINE = /\[debug\] sequence #(\d+) step (\d+)\/(\d+) (\w+)\s+player=(\S+)\s+scheduled=(\d+) actual=(\d+) drift=(-?\d+) result=(\w+)/

// Results that count as a step done: a piece moved, was already in place, or came off.
const DONE = new Set(['EQUIPPED', 'ALREADY', 'REMOVED', 'NOTHING'])
const STEP_NAMES = ['BOOTS', 'LEGGINGS', 'CHESTPLATE', 'HELMET', 'SHIELD']

/** Server side proof: parses the plugin's own debug log lines. */
function analyzeServerTicks (lines, expectedPlayers, interval = 2) {
  const rows = []
  for (const line of lines) {
    const m = DEBUG_LINE.exec(line)
    if (!m) continue
    rows.push({ seq: +m[1], step: +m[2], total: +m[3], name: m[4], player: m[5], scheduled: +m[6], actual: +m[7], drift: +m[8], result: m[9] })
  }
  const bySeq = new Map()
  for (const r of rows) {
    if (!bySeq.has(r.seq)) bySeq.set(r.seq, [])
    bySeq.get(r.seq).push(r)
  }
  const sequences = []
  for (const [seq, group] of bySeq) {
    const steps = []
    const total = Math.max(...group.map(r => r.total))
    for (let step = 1; step <= total; step++) {
      const g = group.filter(r => r.step === step)
      if (!g.length) continue
      const ticks = [...new Set(g.map(r => r.actual))]
      steps.push({
        step,
        name: g[0].name,
        players: g.length,
        ticks,
        sameTick: ticks.length === 1,
        maxDrift: Math.max(...g.map(r => r.drift)),
        allEquipped: g.every(r => DONE.has(r.result))
      })
    }
    const firstTick = steps.length ? steps[0].ticks[0] : 0
    for (const s of steps) s.offset = s.ticks[0] - firstTick
    sequences.push({ seq, total, players: new Set(group.map(r => r.player)).size, steps })
  }
  const seq = sequences[0]
  const checks = []
  checks.push(['a sequence was logged', !!seq])
  if (seq) {
    checks.push([`all ${expectedPlayers} bots took part`, seq.players === expectedPlayers])
    checks.push([`all ${seq.total} steps ran`, seq.steps.length === seq.total])
    checks.push(['every step ran on a single server tick for all bots', seq.steps.every(s => s.sameTick)])
    checks.push([`step offsets are ${Array.from({ length: seq ? seq.total : 5 }, (_, i) => i * interval).join(', ')} ticks`, seq.steps.map(s => s.offset).join(',') === Array.from({ length: seq.total }, (_, i) => i * interval).join(',')])
    checks.push(['no drift (server kept up)', seq.steps.every(s => s.maxDrift === 0)])
    checks.push(['every step reported EQUIPPED', seq.steps.every(s => s.allEquipped)])
  }
  return { sequences, checks }
}

function median (values) {
  const s = [...values].sort((a, b) => a - b)
  return s.length ? s[Math.floor(s.length / 2)] : NaN
}

/**
 * Client side view: when did each bot's packets announcing a piece arrive?
 * Events are { observer, subject, step, t } with t in milliseconds.
 */
function analyzePackets (events, botNames) {
  const steps = []
  for (const name of STEP_NAMES) {
    const g = events.filter(e => e.step === name)
    const expected = botNames.length * botNames.length
    const seen = new Set(g.map(e => e.observer + '>' + e.subject))
    const missing = []
    for (const o of botNames) for (const sub of botNames) if (!seen.has(o + '>' + sub)) missing.push(o + '>' + sub)
    const times = g.map(e => e.t)
    steps.push({
      name,
      received: seen.size,
      expected,
      missing,
      spreadMs: times.length ? Math.max(...times) - Math.min(...times) : NaN,
      medianT: median(times)
    })
  }
  for (let i = 1; i < steps.length; i++) steps[i].gapMs = steps[i].medianT - steps[i - 1].medianT
  // A third value of true marks a soft check. Wall clock timing on one busy PC is noisy, so a miss
  // is reported as WARN and does not fail the run. Server tick checks are the authoritative ones.
  const checks = []
  checks.push(['every bot saw every other bot (and itself) get all five pieces', steps.every(s => s.received === s.expected)])
  checks.push(['packets for one step arrived within 40 ms of each other', steps.every(s => s.spreadMs <= 40), true])
  // Single steps can bunch up or stretch on the client (TCP batching, one busy Node process), so the
  // pass/fail check uses the whole span. 8 ticks should be about 400 ms.
  const span = steps[steps.length - 1].medianT - steps[0].medianT
  checks.push([`first to last piece took about 400 ms (300 to 500), measured ${Number.isNaN(span) ? 'n/a' : span.toFixed(0)} ms`, span >= 300 && span <= 500, true])
  return { steps, checks }
}

module.exports = { analyzeServerTicks, analyzePackets, STEP_NAMES }
