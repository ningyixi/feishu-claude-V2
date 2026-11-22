// Read-only: dump Schema B success exec from archived cli-run-1.json + 400-exec detail from sandbox_mtls.
const fs = require('fs');
const p1 = 'D:/dida/SupplierV2/.trellis/tasks/archive/2026-11/11-05-yoyo-oversea-cli-live-test/research/cli-run-1.json';
const j = JSON.parse(fs.readFileSync(p1, 'utf8'));
console.log('== cli-run-1.json (Schema B candidate) ==');
console.log('top:', Object.keys(j));
const run = j.run;
console.log('run keys:', Object.keys(run));
console.log('run.meta:', JSON.stringify(run.meta));
if (run.summary) {
  console.log('summary keys:', Object.keys(run.summary));
  console.log('summary.executedRequests:', JSON.stringify(run.summary.executedRequests));
  console.log('summary.tests:', JSON.stringify(run.summary.tests));
}
const ex = run.executions || [];
console.log('executions:', ex.length, ex.length ? 'exec keys: ' + Object.keys(ex[0]).join(',') : '');
const ok = ex.find(x => x.requestExecuted && x.requestExecuted.response);
if (ok) {
  const re = ok.requestExecuted;
  console.log('SUCCESS exec name=' + re.name);
  console.log('requestExecuted keys:', Object.keys(re).join(','));
  console.log('response keys:', Object.keys(re.response).join(','));
  for (const k of Object.keys(re.response)) {
    const v = re.response[k];
    console.log('  .' + k + ' : ' + (v === null ? 'null' : Array.isArray(v) ? 'array[' + v.length + ']' : typeof v) + ' = ' + JSON.stringify(v).slice(0, 150));
  }
  console.log('tests:', JSON.stringify(ok.tests).slice(0, 400));
  console.log('errors field:', JSON.stringify(ok.errors).slice(0, 120));
} else {
  console.log('NO success exec found. exec[0].requestExecuted keys: ' + Object.keys((ex[0] || {}).requestExecuted || {}).join(','));
}
// stats over all execs
let withResp = 0, withErr = 0; const codes = {};
for (const x of ex) {
  const r = x.requestExecuted && x.requestExecuted.response;
  if (r) { withResp++; const c = r.code !== undefined ? r.code : (r.status || '?'); codes[c] = (codes[c] || 0) + 1; }
  if (x.errors && x.errors.length) withErr++;
}
console.log('withResponse=' + withResp + '/' + ex.length + ' withErrors=' + withErr + ' codes=' + JSON.stringify(codes));

console.log('');
console.log('== sandbox_mtls 400-exec detail ==');
const p2 = 'D:/dida/SupplierV2/.trellis/tasks/11-17-engine-omni-mapping/research/.tmp_engine_sandbox_mtls.json';
const n = JSON.parse(fs.readFileSync(p2, 'utf8'));
const bad = n.run.executions.find(x => x.response && x.response.code === 400);
if (bad) {
  console.log('item.name=' + bad.item.name + ' code=' + bad.response.code + ' status=' + bad.response.status);
  console.log('response keys:', Object.keys(bad.response).join(','));
  console.log('body head:', Buffer.from(bad.response.stream.data).toString('utf8').slice(0, 220));
  for (const a of bad.assertions) console.log('assertion head: ' + String(a.assertion).slice(0, 400) + ' | error=' + JSON.stringify(a.error || null).slice(0, 200));
}
const bad5 = n.run.executions.find(x => x.response && x.response.code === 500);
if (bad5) {
  console.log('500-exec: ' + bad5.item.name + ' assertions errors: ' + bad5.assertions.map(a => a.error ? 'ERR' : 'ok').join(','));
  console.log('500 body head: ' + Buffer.from(bad5.response.stream.data).toString('utf8').slice(0, 150));
}
// any assertion with error != null across the file?
let errCount = 0;
for (const x of n.run.executions) for (const a of x.assertions || []) if (a.error) errCount++;
console.log('assertions with error!=null in sandbox_mtls: ' + errCount);
