'use strict'
const { spawn, execFileSync } = require('node:child_process')
const fs = require('node:fs')
const os = require('node:os')
const net = require('node:net')
const path = require('node:path')
const { EventEmitter } = require('node:events')

const ANSI = /\x1b\[[0-9;]*m/g

function findJava () {
  if (process.env.JAVA25_HOME) return path.join(process.env.JAVA25_HOME, 'bin', 'java.exe')
  const jdks = path.join(os.homedir(), '.gradle', 'jdks')
  if (fs.existsSync(jdks)) {
    const hit = fs.readdirSync(jdks).find(d => d.startsWith('eclipse_adoptium-25'))
    if (hit) return path.join(jdks, hit, 'bin', 'java.exe')
  }
  return 'java'
}

/** Runs the isolated Paper server and gives the harness its console. */
class TestServer extends EventEmitter {
  constructor ({ dir, jar, port, memory = '2G', pidFile }) {
    super()
    this.dir = dir
    this.jar = jar
    this.port = port
    this.memory = memory
    this.pidFile = pidFile
    this.lines = []
    this.proc = null
  }

  start () {
    const java = findJava()
    this.proc = spawn(java, [`-Xms1G`, `-Xmx${this.memory}`, '-XX:+UseG1GC', '-jar', this.jar, '--nogui'], {
      cwd: this.dir,
      stdio: ['pipe', 'pipe', 'pipe']
    })
    if (this.pidFile) fs.writeFileSync(this.pidFile, String(this.proc.pid))
    let buffer = ''
    const onData = chunk => {
      buffer += chunk.toString('utf8')
      let i
      while ((i = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, i).replace(/\r$/, '').replace(ANSI, '')
        buffer = buffer.slice(i + 1)
        this.lines.push(line)
        this.emit('line', line)
      }
    }
    this.proc.stdout.on('data', onData)
    this.proc.stderr.on('data', onData)
    this.proc.on('exit', code => this.emit('exit', code))
    return this
  }

  command (text) {
    this.proc.stdin.write(text + '\n')
  }

  waitForLine (regex, timeoutMs, label = String(regex)) {
    const existing = this.lines.find(l => regex.test(l))
    if (existing) return Promise.resolve(existing)
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.off('line', onLine)
        reject(new Error(`Timed out waiting for ${label}`))
      }, timeoutMs)
      const onLine = line => {
        if (regex.test(line)) {
          clearTimeout(timer)
          this.off('line', onLine)
          resolve(line)
        }
      }
      this.on('line', onLine)
    })
  }

  async stop (timeoutMs = 60000) {
    if (!this.proc || this.proc.exitCode !== null) return
    const exited = new Promise(resolve => this.proc.once('exit', resolve))
    this.command('stop')
    const timer = setTimeout(() => {
      try { execFileSync('taskkill', ['/PID', String(this.proc.pid), '/T', '/F'], { stdio: 'ignore' }) } catch {}
    }, timeoutMs)
    await exited
    clearTimeout(timer)
    if (this.pidFile) fs.rmSync(this.pidFile, { force: true })
  }
}


function canConnect (host, port, timeoutMs = 2000) {
  return new Promise(resolve => {
    const socket = net.connect({ host, port })
    const done = ok => { socket.destroy(); resolve(ok) }
    socket.setTimeout(timeoutMs, () => done(false))
    socket.once('connect', () => done(true))
    socket.once('error', () => done(false))
  })
}

/**
 * Proves the server is only reachable through localhost: connecting to 127.0.0.1 must work, and
 * connecting to this PC's own LAN addresses must be refused.
 */
async function checkLocalhostOnly (port) {
  const lan = Object.values(os.networkInterfaces()).flat()
    .filter(i => i && i.family === 'IPv4' && !i.internal).map(i => i.address)
  const loopback = await canConnect('127.0.0.1', port)
  const reachable = []
  for (const addr of lan) if (await canConnect(addr, port)) reachable.push(addr)
  return { loopback, lan, reachable }
}

module.exports = { TestServer, checkLocalhostOnly }
