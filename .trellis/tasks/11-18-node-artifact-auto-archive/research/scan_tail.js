// Read-only: scan transcripts for entries after a cutoff timestamp; dump Bash commands + final assistant text lengths.
const fs = require('fs');
const dir = 'C:/Users/yixi.ning/.claude/projects/D--dida-SupplierV2/';
const files = process.argv.slice(2);
const CUTOFF = '2026-11-18T05:00';
for (const f of files) {
  const lines = fs.readFileSync(dir + f, 'utf8').split('\n').filter(Boolean);
  console.log('##### ' + f + ' lines=' + lines.length);
  let first = null, last = null;
  for (const ln of lines) {
    let o; try { o = JSON.parse(ln); } catch (e) { continue; }
    if (o.timestamp) { if (!first) first = o.timestamp; last = o.timestamp; }
    if (!o.timestamp || o.timestamp < CUTOFF) continue;
    const msg = o.message;
    if (!msg) continue;
    if (Array.isArray(msg.content)) {
      for (const c of msg.content) {
        if (c.type === 'tool_use' && c.name === 'Bash' && c.input && c.input.command) {
          const cmd = c.input.command;
          console.log('[' + o.timestamp + '] BASH: ' + (cmd.length > 400 ? cmd.slice(0, 400) + '...[len=' + cmd.length + ']' : cmd));
        } else if (c.type === 'tool_use') {
          console.log('[' + o.timestamp + '] TOOL: ' + c.name);
        } else if (c.type === 'text' && o.type === 'assistant' && c.text && c.text.length > 200) {
          console.log('[' + o.timestamp + '] ASSISTANT TEXT len=' + c.text.length + ' head=' + JSON.stringify(c.text.slice(0, 150)));
        }
      }
    }
  }
  console.log('  span: ' + first + ' .. ' + last);
}
