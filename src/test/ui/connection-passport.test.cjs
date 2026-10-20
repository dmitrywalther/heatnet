const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const html = fs.readFileSync(path.join(__dirname, '../../main/resources/static/ui/index.html'), 'utf8');
const source = html.slice(html.indexOf('  // ---------- продольный профиль ----------'),
  html.indexOf('  // ---------- конец продольного профиля ----------'));
const flush = async () => { for (let i = 0; i < 8; i++) await new Promise(setImmediate); };
const deferred = () => { let resolve; const promise = new Promise(r => resolve = r); return {promise, resolve}; };
const ok = body => ({ok: true, json: async () => body});
const choices = [
  {key: 'n:10', label: '10', connected: true, coordinates: [37, 55]},
  {key: 's:10 / &?+#', label: '10 / &?+#', connected: false, coordinates: [37.1, 55]}
];
const card = (index = 0, variant = 'v1') => ({
  variant_id: variant, status: choices[index].connected ? 'connected' : 'unconnected',
  oks: choices[index], segments: []
});
function element() {
  return {hidden: false, disabled: false, value: '', children: [], handlers: {}, style: {},
    classList: {add() {}, toggle() {}}, appendChild(x) { this.children.push(x); return x; },
    replaceChildren(...x) { this.children = x; }, remove() {}, setAttribute() {},
    addEventListener(k, v) { this.handlers[k] = v; }, set textContent(x) { this.text = x; }};
}
function harness(fetcher, mode = '2d') {
  const nodes = new Map(), opened = []; let profileOptions;
  const $ = id => { if (!nodes.has(id)) nodes.set(id, element()); return nodes.get(id); };
  const ctx = vm.createContext({$, jobId: 'job-a', mode, currentVariant: mode === 'depth' ? 'd1' : 'v1',
    modeData: {}, Promise, Map, Set, Number, encodeURIComponent,
    document: {createElement: element}, window: {open: (...args) => opened.push(args)},
    svg: element(), el: () => element(), toXY: (...x) => x, linePts: x => x, proj: {ready: false},
    HeatnetConnectionCard: {create: () => ({clear() {}, setData() {}, setMessage() {}, highlightSegment() {}})},
    HeatnetProfile: {create: o => { profileOptions = o; return {
      clear() {}, setData() {}, setMessage() {}, highlightSegment() {}}; }},
    fetch: url => fetcher(url)});
  vm.runInContext(source, ctx);
  return {$, ctx, opened, get profileOptions() { return profileOptions; },
    eval: text => vm.runInContext(text, ctx),
    click: () => $('connection-download').handlers.click?.()};
}
const immediate = url => Promise.resolve(ok(url.includes('oks_key=')
  ? card(url.includes('s%3A') ? 1 : 0, url.includes('variant_id=d1') ? 'd1' : 'v1') : {oks: choices}));

test('passport button is present and initially disabled in the connection controls', () => {
  assert.match(html, /<button\b[^>]*id="connection-download"[^>]*disabled[^>]*>Скачать паспорт ОКС \(PDF\)<\/button>/);
});
test('base passport opens the PDF endpoint with the selected numeric OKS', async () => {
  const h = harness(immediate); h.eval('syncProfile()'); await flush();
  assert.equal(h.$('connection-download').disabled, false); h.click();
  assert.deepEqual(h.opened, [[
    '/api/v1/tracing/jobs/job-a/connection.pdf?variant_id=v1&oks_key=n%3A10', '_blank', 'noopener'
  ]]);
});
test('unconnected OKS can export a status passport and preserves encoded string identity', async () => {
  const h = harness(immediate); h.eval('syncProfile()'); await flush();
  h.eval("selectProfileOks('s:10 / &?+#')"); await flush();
  assert.equal(h.$('connection-download').disabled, false); h.click();
  assert.equal(h.opened[0]?.[0],
    '/api/v1/tracing/jobs/job-a/connection.pdf?variant_id=v1&oks_key=s%3A10%20%2F%20%26%3F%2B%23');
});
test('passport remains disabled while the connection card loads or fails', async () => {
  const pending = deferred();
  const h = harness(url => url.includes('oks_key=') ? pending.promise : Promise.resolve(ok({oks: choices})));
  h.eval('syncProfile()'); await flush();
  assert.equal(h.$('connection-download').disabled, true); h.click(); assert.equal(h.opened.length, 0);
  pending.resolve({ok: false, json: async () => ({message: 'Нет данных карточки'})}); await flush();
  assert.equal(h.$('connection-download').disabled, true); h.click(); assert.equal(h.opened.length, 0);
});
test('changing OKS blocks the previous export until the selected card is ready', async () => {
  const pending = deferred();
  const h = harness(url => url.includes('s%3A') ? pending.promise : immediate(url));
  h.eval('syncProfile()'); await flush(); h.eval("selectProfileOks('s:10 / &?+#')");
  assert.equal(h.$('connection-download').disabled, true); h.click(); assert.equal(h.opened.length, 0);
  pending.resolve(ok(card(1))); await flush(); h.click();
  assert.ok(h.opened[0]?.[0].endsWith('oks_key=s%3A10%20%2F%20%26%3F%2B%23'));
});
test('late previous card does not change the export selected by the user', async () => {
  const pending = deferred();
  const h = harness(url => url.includes('n%3A') ? pending.promise : immediate(url));
  h.eval('syncProfile()'); await flush(); h.eval("selectProfileOks('s:10 / &?+#')"); await flush();
  pending.resolve(ok(card())); await flush(); h.click();
  assert.ok(h.opened[0]?.[0].endsWith('oks_key=s%3A10%20%2F%20%26%3F%2B%23'));
});
test('reset blocks export and late responses cannot enable it again', async () => {
  const pending = deferred();
  const h = harness(url => url.includes('oks_key=') ? pending.promise : immediate(url));
  h.eval('syncProfile()'); await flush(); h.eval('resetProfile()'); h.ctx.jobId = 'job-b';
  pending.resolve(ok(card())); await flush();
  assert.equal(h.$('connection-download').disabled, true); h.click(); assert.equal(h.opened.length, 0);
});
test('switching to depth exports the fresh depth variant even when its profile is closed', async () => {
  const h = harness(immediate); h.eval('syncProfile()'); await flush();
  h.ctx.mode = 'depth'; h.ctx.currentVariant = 'd1'; h.eval('syncProfile()'); await flush();
  h.profileOptions.onClose(); assert.equal(h.$('connection-download').disabled, false); h.click();
  assert.equal(h.opened[0]?.[0],
    '/api/v1/tracing/jobs/job-a/connection.pdf?variant_id=d1&oks_key=n%3A10');
});
test('job or variant mismatch never exports the previous card before UI synchronization', async () => {
  const h = harness(immediate); h.eval('syncProfile()'); await flush();
  h.ctx.currentVariant = 'v3'; h.click(); assert.equal(h.opened.length, 0);
  h.ctx.currentVariant = 'v1'; h.ctx.jobId = 'job-b'; h.click(); assert.equal(h.opened.length, 0);
});
