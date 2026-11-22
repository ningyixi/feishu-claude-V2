// Read-only deep dump: Schema A (newman export) & Schema B (postman CLI export) details. Masks long values.
const fs = require('fs');
const base = 'D:/dida/SupplierV2/.trellis/tasks/11-17-engine-omni-mapping/research/';
const mask = (s, n) => { s = String(s); return s.length > n ? s.slice(0, n) + '...[len=' + s.length + ']' : s; };

function schemaB(f) {
  const j = JSON.parse(fs.readFileSync(base + f, 'utf8'));
  const run = j.run;
  console.log('=== ' + f + ' (Schema B: postman CLI) ===');
  console.log('run.meta:', JSON.stringify(run.meta));
  console.log('run.summary:', JSON.stringify(run.summary, null, 1).slice(0, 1500));
  console.log('run.runError:', JSON.stringify(run.runError).slice(0, 400));
  const ex = run.executions;
  console.log('executions=' + ex.length + ' keys=' + Object.keys(ex[0]).join(','));
  const e0 = ex[0];
  console.log('exec[0].requestExecuted keys:', Object.keys(e0.requestExecuted).join(','));
  const re = e0.requestExecuted;
  console.log('  re.url:', JSON.stringify(re.url).slice(0, 400));
  console.log('  re.method:', re.method);
  console.log('  re.headers?:', re.headers ? 'headers[' + re.headers.length + '] first=' + JSON.stringify(re.headers[0]) : 'absent', '| re.header?:', !!re.header);
  console.log('  re.response keys:', re.response ? Object.keys(re.response).join(',') : String(re.response));
  if (re.response) {
    console.log('  re.response sample:', JSON.stringify(re.response, (k, v) => (k === 'stream' || k === 'body') ? mask(JSON.stringify(v), 200) : v).slice(0, 700));
  }
  console.log('  re.body:', re.body ? JSON.stringify(re.body).slice(0, 200) : 'absent');
  console.log('exec[0].tests:', JSON.stringify(e0.tests).slice(0, 500));
  console.log('exec[0].errors:', JSON.stringify(e0.errors).slice(0, 500));
  const withResp = ex.filter(x => x.requestExecuted && x.requestExecuted.response);
  console.log('execs with response: ' + withResp.length + '/' + ex.length);
  if (withResp.length) {
    const r = withResp[0].requestExecuted.response;
    console.log('first response keys:', Object.keys(r).join(','));
    console.log('first response (masked):', JSON.stringify(r, (k, v) => (k === 'stream' || k === 'body' || k === 'text') ? mask(typeof v === 'string' ? v : JSON.stringify(v), 200) : v).slice(0, 900));
  }
  const errOnly = ex.filter(x => x.errors && x.errors.length);
  console.log('execs with errors[]: ' + errOnly.length + (errOnly.length ? ' first errors=' + JSON.stringify(errOnly[0].errors).slice(0, 400) : ''));
}

function schemaA(f) {
  const j = JSON.parse(fs.readFileSync(base + f, 'utf8'));
  console.log('=== ' + f + ' (Schema A: newman) ===');
  console.log('top keys:', Object.keys(j).join(','));
  console.log('collection keys:', Object.keys(j.collection || {}).join(','), '| info.name=' + (j.collection && j.collection.info && j.collection.info.name));
  console.log('environment:', j.environment ? JSON.stringify({ id: j.environment.id, name: j.environment.name, values: (j.environment.values || []).map(v => v.key) }) : 'absent');
  console.log('globals:', j.globals ? (Array.isArray(j.globals) ? 'array len=' + j.globals.length : JSON.stringify(Object.keys(j.globals))) : 'absent');
  const run = j.run;
  console.log('run.stats:', JSON.stringify(run.stats));
  console.log('run.timings keys:', Object.keys(run.timings || {}).join(','), '| started=' + (run.timings && run.timings.started) + ' completed=' + (run.timings && run.timings.completed) + ' responseAverage=' + (run.timings && run.timings.responseAverage));
  console.log('run.transfers:', JSON.stringify(run.transfers));
  console.log('run.error:', JSON.stringify(run.error));
  console.log('run.failures count=' + (run.failures || []).length);
  if (run.failures && run.failures.length) console.log('failure[0]:', JSON.stringify(run.failures[0], (k, v) => (k === 'stream' || k === 'body') ? mask(JSON.stringify(v), 150) : v).slice(0, 900));
  const e = run.executions[0];
  console.log('exec[0].cursor:', JSON.stringify(e.cursor));
  console.log('exec[0].item keys:', Object.keys(e.item).join(','), '| name=' + e.item.name);
  console.log('exec[0].request.url FULL:', JSON.stringify(e.request.url).slice(0, 600));
  console.log('exec[0].request.certificate:', JSON.stringify(e.request.certificate).slice(0, 200));
  console.log('exec[0].request.auth:', e.request.auth ? JSON.stringify(e.request.auth).slice(0, 150) : 'absent');
  console.log('exec[0].request.description:', e.request.description ? JSON.stringify(e.request.description).slice(0, 120) : 'absent');
  console.log('exec[0].response FULL keys+types:');
  const r = e.response;
  for (const k of Object.keys(r)) {
    const v = r[k];
    console.log('   .' + k + ' : ' + (v === null ? 'null' : Array.isArray(v) ? 'array[' + v.length + ']' : typeof v) + ' = ' + mask(typeof v === 'object' ? JSON.stringify(v) : v, 160));
  }
  console.log('stream decode head:', mask(Buffer.from(r.stream.data).toString('utf8'), 200));
  console.log('exec[0].assertions ALL:');
  for (const a of e.assertions) console.log('   assertion=' + mask(a.assertion, 600) + ' | error=' + JSON.stringify(a.error || null).slice(0, 120));
  // response body presence across execs
  console.log('body-field check: ' + run.executions.map(x => x.response && x.response.body !== undefined ? 'B' : '-').join('') + ' stream check: ' + run.executions.map(x => x.response && x.response.stream ? 'S' : '-').join(''));
  console.log('codes: ' + run.executions.map(x => x.response && x.response.code).join(','));
  console.log('per-exec assertions failed: ' + run.executions.map(x => (x.assertions || []).filter(a => a.error).length).join(','));
}

const which = process.argv[2];
if (which === 'B') { schemaB(process.argv[3]); } else { schemaA(process.argv[3]); }
