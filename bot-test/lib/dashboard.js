'use strict'
const http = require('node:http')

const PAGE = `<!doctype html><meta charset="utf-8"><title>SyncEquip bot status</title>
<style>
body{font:14px system-ui,sans-serif;margin:24px;background:#14161a;color:#e6e6e6}
h1{font-size:18px;margin:0 0 4px}p{color:#9aa0a6;margin:4px 0 16px;max-width:60em}
table{border-collapse:collapse}td,th{border:1px solid #2c3038;padding:6px 10px;text-align:left}
th{background:#1c1f25}.on{background:#1f4d2e}.off{color:#6b7280}
</style>
<h1>SyncEquip bot status</h1>
<p>Live view of what each bot's own client reports. This is a data table, not a 3D view or a game
screen, so it cannot show animations or play sounds.</p>
<table id="t"></table>
<script>
const slots=[['Boots',8],['Leggings',7],['Chestplate',6],['Helmet',5],['Shield',45]];
async function tick(){
  try{
    const s=await (await fetch('/state')).json();
    let h='<tr><th>Bot</th><th>Position</th><th>Holding</th>'+slots.map(x=>'<th>'+x[0]+'</th>').join('')+'</tr>';
    for(const b of s.bots){
      h+='<tr><td>'+b.name+'</td><td>'+b.pos+'</td><td>'+(b.held||'<span class=off>nothing</span>')+'</td>'+
        slots.map(x=>{const v=b.slots[x[1]];return v?'<td class=on>'+v+'</td>':'<td class=off>empty</td>'}).join('')+'</tr>';
    }
    document.getElementById('t').innerHTML=h;
  }catch(e){}
  setTimeout(tick,200);
}
tick();
</script>`

/** Tiny localhost-only page that shows each bot's equipment as its own client sees it. */
function startDashboard (bots, port, host) {
  const server = http.createServer((req, res) => {
    if (req.url === '/state') {
      const state = bots.map(b => ({
        name: b.username,
        pos: b.entity ? [b.entity.position.x, b.entity.position.y, b.entity.position.z].map(n => n.toFixed(1)).join(', ') : '?',
        held: b.heldItem ? b.heldItem.name : null,
        slots: Object.fromEntries([5, 6, 7, 8, 45].map(i => [i, b.inventory.slots[i] ? b.inventory.slots[i].name : null]))
      }))
      res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' })
      res.end(JSON.stringify({ bots: state }))
    } else {
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' })
      res.end(PAGE)
    }
  })
  server.listen(port, host)
  return server
}

module.exports = { startDashboard }
