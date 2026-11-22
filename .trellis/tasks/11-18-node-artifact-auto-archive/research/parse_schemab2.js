// Read-only: Schema B success exec.response field shapes from cli-run-1.json.
const fs = require('fs');
const j = JSON.parse(fs.readFileSync('D:/dida/SupplierV2/.trellis/tasks/archive/2026-11/11-05-yoyo-oversea-cli-live-test/research/cli-run-1.json', 'utf8'));
const ex = j.run.executions;
const e = ex[0];
console.log('exec[0] keys:', Object.keys(e).join(','));
console.log('requestExecuted keys:', Object.keys(e.requestExecuted).join(','));
console.log('requestExecuted.url:', JSON.stringify(e.requestExecuted.url).slice(0, 300));
console.log('requestExecuted.headers[0..2]:', JSON.stringify((e.requestExecuted.headers || []).slice(0, 3)).slice(0, 300));
console.log('requestExecuted.body?:', e.requestExecuted.body === undefined ? 'absent' : JSON.stringify(e.requestExecuted.body).slice(0, 200));
console.log('response keys:', Object.keys(e.response).join(','));
for (const k of Object.keys(e.response)) {
  const v = e.response[k];
  console.log('  .' + k + ' : ' + (v === null ? 'null' : Array.isArray(v) ? 'array[' + v.length + ']' : typeof v) + ' = ' + JSON.stringify(v).slice(0, 160));
}
console.log('tests[0..1]:', JSON.stringify((e.tests || []).slice(0, 2)).slice(0, 500));
console.log('errors:', JSON.stringify(e.errors));
// find one with request body (POST)
const post = ex.find(x => x.requestExecuted && x.requestExecuted.method === 'POST');
if (post) {
  console.log('');
  console.log('POST exec name=' + post.requestExecuted.name);
  console.log('  requestExecuted keys:', Object.keys(post.requestExecuted).join(','));
  if (post.requestExecuted.body) console.log('  body:', JSON.stringify(post.requestExecuted.body).slice(0, 300));
  console.log('  response keys:', Object.keys(post.response).join(','));
  if (post.response.stream) console.log('  stream decode:', Buffer.from(post.response.stream.data || []).toString('utf8').slice(0, 150));
  if (post.response.body !== undefined) console.log('  response.body typeof=' + typeof post.response.body + ' head=' + JSON.stringify(post.response.body).slice(0, 150));
  console.log('  response.code=' + post.response.code + ' status=' + JSON.stringify(post.response.status) + ' responseTime=' + post.response.responseTime + ' responseSize=' + post.response.responseSize);
}
// histogram of codes and presence of stream/body across execs
const codes = {}; let hasStream = 0, hasBody = 0;
for (const x of ex) { if (x.response) { codes[x.response.code] = (codes[x.response.code] || 0) + 1; if (x.response.stream) hasStream++; if (x.response.body !== undefined) hasBody++; } }
console.log('');
console.log('codes:', JSON.stringify(codes), 'hasStream=' + hasStream, 'hasBody=' + hasBody, 'total=' + ex.length);
