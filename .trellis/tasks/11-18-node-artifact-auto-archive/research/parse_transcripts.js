// Read-only: extract postman/newman related Bash tool_use commands + their tool_result info from Claude transcripts.
const fs = require('fs');
const dir = 'C:/Users/yixi.ning/.claude/projects/D--dida-SupplierV2/';
const files = process.argv.slice(2);
const cmdById = {};
for (const f of files) {
  const lines = fs.readFileSync(dir + f, 'utf8').split('\n').filter(Boolean);
  let postmanCmds = 0, newmanCmds = 0, otherHits = [];
  for (const ln of lines) {
    let o; try { o = JSON.parse(ln); } catch (e) { continue; }
    const msg = o.message;
    if (!msg || !Array.isArray(msg.content)) continue;
    for (const c of msg.content) {
      if (c.type === 'tool_use' && c.name === 'Bash' && c.input && typeof c.input.command === 'string') {
        const cmd = c.input.command;
        if (/postman|newman/i.test(cmd)) {
          const key = /newman/.test(cmd) ? 'newman' : (/postman\s+(collection\s+)?run/i.test(cmd) ? 'postman-run' : 'postman-other');
          if (key === 'newman') newmanCmds++; else postmanCmds++;
          cmdById[c.id] = { f, key, cmd, ts: o.timestamp };
          console.log('---- [' + f.slice(0, 8) + '] ' + (o.timestamp || '') + ' (' + key + ') ----');
          console.log(cmd.length > 700 ? cmd.slice(0, 700) + ' ...[len=' + cmd.length + ']' : cmd);
        }
      }
      if (c.type === 'tool_result' && cmdById[c.tool_use_id]) {
        const src = cmdById[c.tool_use_id];
        let text = '';
        if (typeof c.content === 'string') text = c.content;
        else if (Array.isArray(c.content)) text = c.content.map(x => x.text || '').join('\n');
        console.log('  >>> RESULT for (' + src.key + ') is_error=' + !!c.is_error + ' stdoutLen=' + text.length);
        console.log('      head: ' + JSON.stringify(text.slice(0, 300)));
        console.log('      tail: ' + JSON.stringify(text.slice(-300)));
      }
    }
  }
  console.log('== ' + f + ' summary: postman cmds=' + postmanCmds + ' newman cmds=' + newmanCmds + ' totalLines=' + lines.length);
}
