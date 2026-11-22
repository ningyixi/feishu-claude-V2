// Read-only structure dump of Postman CLI run-result JSON samples.
const fs = require('fs');
const base = 'D:/dida/SupplierV2/.trellis/tasks/11-17-engine-omni-mapping/research/';
const files = process.argv.slice(2);
for (const f of files) {
  try {
    const j = JSON.parse(fs.readFileSync(base + f, 'utf8'));
    console.log('=== ' + f + ' (' + fs.statSync(base + f).size + ' B) ===');
    console.log('top keys:', Object.keys(j).join(','));
    if (j.info) {
      console.log('[COLLECTION DEF] info.name=' + j.info.name + ' schema=' + j.info.schema);
      console.log('  item count=' + (j.item || []).length);
      if (j.variable) console.log('  variable names=' + j.variable.map(v => v.key).join(','));
      if (j.auth) console.log('  auth=' + JSON.stringify(j.auth).slice(0, 200));
      continue;
    }
    const run = j.run;
    if (!run) { console.log('  (no run key)'); continue; }
    console.log('run keys:', Object.keys(run).join(','));
    if (run.meta) console.log('run.meta:', JSON.stringify(run.meta).slice(0, 1200));
    if (run.summary) {
      console.log('run.summary keys:', Object.keys(run.summary).join(','));
      console.log('run.summary.stats:', JSON.stringify(run.summary.stats));
      if (run.summary.collection) console.log('summary.collection.name=' + run.summary.collection.name);
      if (run.summary.run) console.log('summary.run:', JSON.stringify(run.summary.run).slice(0, 400));
    }
    const ex = run.executions || [];
    console.log('executions: ' + ex.length);
    if (ex.length) {
      const e = ex[0];
      console.log('exec[0] keys:', Object.keys(e).join(','));
      console.log('exec[0].item.name=' + (e.item && e.item.name));
      if (e.request) {
        console.log('  request keys:', Object.keys(e.request).join(','));
        console.log('  request.method=' + e.request.method);
        console.log('  request.url type=' + typeof e.request.url + ' isObj=' + (e.request.url && typeof e.request.url === 'object'));
        if (e.request.url && typeof e.request.url === 'object') console.log('  request.url keys:', Object.keys(e.request.url).join(','), '| raw=' + String(e.request.url.raw).slice(0, 150));
        console.log('  request.header sample:', JSON.stringify((e.request.header || []).slice(0, 4)).slice(0, 400));
        if (e.request.body) console.log('  request.body keys:', Object.keys(e.request.body).join(','), '| mode=' + e.request.body.mode, '| raw len=' + ((e.request.body.raw || '').length));
        if (e.request.auth) console.log('  request.auth type=' + (e.request.auth.type || JSON.stringify(Object.keys(e.request.auth))));
      }
      if (e.response) {
        console.log('  response keys:', Object.keys(e.response).join(','));
        console.log('  response.code=' + e.response.code + ' status=' + JSON.stringify(e.response.status));
        console.log('  response.responseSize=' + e.response.responseSize + ' responseTime=' + e.response.responseTime);
        const hs = e.response.header || e.response.headers;
        console.log('  response headers field present: header=' + !!e.response.header + ' headers=' + !!e.response.headers + ' sample=' + JSON.stringify((hs || []).slice(0, 2)).slice(0, 300));
        if (e.response.stream) console.log('  response.stream: type=' + (e.response.stream.type || 'n/a') + ' len=' + (e.response.stream.data ? e.response.stream.data.length : (Buffer.isBuffer(e.response.stream) ? e.response.stream.length : '?')));
        console.log('  response.body typeof=' + typeof e.response.body + ' len=' + (typeof e.response.body === 'string' ? e.response.body.length : 'n/a') + ' head=' + JSON.stringify(String(e.response.body).slice(0, 120)));
      } else { console.log('  response: ' + e.response); }
      if (e.assertions) console.log('  assertions count=' + e.assertions.length + ' first=' + JSON.stringify(e.assertions[0]).slice(0, 300));
      if (e.id) console.log('  exec.id=' + e.id);
      if (e._postman_id) console.log('  exec._postman_id present');
      // scan all executions for missing/odd fields
      const missing = { noReq: 0, noResp: 0, respNull: 0, strUrl: 0, errField: 0, non2xx: 0 };
      const codes = {};
      for (const x of ex) {
        if (!x.request) missing.noReq++;
        if (x.response === undefined) missing.noResp++;
        if (x.response === null) missing.respNull++;
        if (x.request && typeof x.request.url === 'string') missing.strUrl++;
        if (x.error || x.errors) missing.errField++;
        if (x.response) { codes[x.response.code] = (codes[x.response.code] || 0) + 1; if (x.response.code < 200 || x.response.code >= 300) missing.non2xx++; }
      }
      console.log('  ALL-EXEC scan: ' + JSON.stringify(missing) + ' code histogram=' + JSON.stringify(codes));
      const errEx = ex.find(x => x.error || x.errors || !x.response);
      if (errEx) console.log('  FIRST-PROBLEM-EXEC: ' + JSON.stringify(errEx).slice(0, 800));
    }
  } catch (e) { console.log(f + ' ERR ' + e.message.slice(0, 200)); }
}
