const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, '../../main/resources/static/ui/index.html'), 'utf8');
const fileSource = html.slice(html.indexOf('  function setFile('), html.indexOf('  // ---------- сводка входного набора'));
const rulesSource = html.slice(html.indexOf('  let rulesModel ='), html.indexOf('  // ---------- запуск расчёта'));
const jobSource = html.slice(html.indexOf('  // ---------- запуск расчёта'), html.indexOf('  // ---------- скачивание'));
const fc = id => ({type: 'FeatureCollection', features: [{type: 'Feature', geometry: null,
  properties: {object_type: 'variant_summary', variant_id: id}}]});
const flush = async () => { await new Promise(setImmediate); await new Promise(setImmediate); };
const deferred = () => { let resolve; const promise = new Promise(r => resolve = r); return {promise, resolve}; };
const reply = body => ({ok: true, json: async () => body});

function element() {
  return {hidden: false, disabled: false, value: '', files: [], style: {}, children: [], handlers: {},
    classList: {add() {}, remove() {}, toggle() {}},
    appendChild(child) { this.children.push(child); return child; },
    replaceChildren(...children) { this.children = children; },
    addEventListener(event, handler) { this.handlers[event] = handler; },
    click() { if (!this.disabled) this.handlers.click?.({}); },
    setAttribute() {}, reportValidity() {}, scrollIntoView() {},
    set textContent(value) { this.text = value; this.children = []; },
    get textContent() { return (this.text || '') + this.children.map(c => c.textContent).join(''); },
    set innerHTML(value) { this.html = value; this.children = []; }, get innerHTML() { return this.html || ''; }};
}

async function harness(handler = async () => reply({status: 'RUNNING'})) {
  const elements = new Map(), calls = [], timers = [];
  const $ = id => { if (!elements.has(id)) elements.set(id, element()); return elements.get(id); };
  const builtin = {restrictions: {road: {mode: 'special', k_spec: 1.25, min_distance: 5}}};
  class Form { constructor() { this.parts = []; } append(...part) { this.parts.push(part); } }
  const context = vm.createContext({$, Date, Promise, Blob, FormData: Form,
    inputFile: {name: 'old.geojson', size: 10}, inputText: null, addedInputFeatures: [],
    baseData: null, inputModified: false, jobId: null, modeData: {}, mode: '2d', currentVariant: null,
    compareVariant: null, drop: $('drop'), svg: $('map'), proj: {ready: false}, tileCache: new Map(),
    setDrawMode() {}, cancelFly() {}, renderInputStats() {}, renderVariantList() {}, drawAll() {}, resetProfile() {},
    setTimeout(fn) { timers.push(fn); }, clearTimeout() {},
    groupVariants: value => value?.features?.filter(f => f.properties?.object_type === 'variant_summary') || [],
    document: {createElement: element, querySelector: selector => $(selector), querySelectorAll: () => []},
    fetch: async (url, options) => {
      calls.push({url, options});
      if (url === '/api/v1/rules/json') return context.rulesLoader ? context.rulesLoader() : reply(builtin);
      return handler(url, options);
    }
  });
  vm.runInContext(fileSource + rulesSource + jobSource, context);
  await flush();
  return {$, context, calls, timers, eval: code => vm.runInContext(code, context)};
}

test('YAML file is sent unchanged and disables the visual editor until explicitly cleared', async () => {
  assert.match(html, /id="rules-file"[^>]*type="file"|type="file"[^>]*id="rules-file"/);
  const h = await harness();
  const raw = new Blob(['# keep precision and fields\nrestrictions:\n  metro:\n    min_distance: 8.0000000001\n']);
  raw.name = 'custom.yml';
  h.$('rules-file').files = [raw];
  h.$('rules-file').handlers.change();
  assert.equal(h.$('rules-controls').disabled, true);
  assert.match(h.$('rules-file-status').textContent, /custom.yml/);
  h.$('run').click();
  const upload = h.calls.find(c => c.options?.method === 'POST');
  assert.equal(upload.options.body.parts.find(p => p[0] === 'rules')[1], raw);
  h.$('rules-use-editor').click();
  assert.equal(h.$('rules-controls').disabled, false);
  assert.equal(h.$('rules-file').value, '');
  h.$('run').click();
  const last = h.calls.filter(c => c.options?.method === 'POST').at(-1);
  assert.notEqual(last.options.body.parts.find(p => p[0] === 'rules')[1], raw);
});

test('replacing GeoJSON removes stale results and their job download target', async () => {
  const h = await harness();
  h.context.jobId = 'old-job'; h.context.modeData = {'2d': fc('v1'), depth: fc('d1')};
  h.$('results').hidden = false;
  h.context.nextFile = {name: 'new.geojson', size: 100_000_000};
  h.eval('setFile(nextFile)');
  assert.equal(h.$('results').hidden, true);
  assert.equal(h.context.jobId, null);
  assert.equal(h.context.modeData['2d'], null);
  assert.equal(h.$('mode-depth').disabled, true);
});

test('reset removes selected YAML and reloads the active server rules', async () => {
  const h = await harness();
  h.$('rules-file').files = [{name: 'override.yaml'}];
  h.$('rules-file').handlers.change();
  const before = h.calls.filter(c => c.url === '/api/v1/rules/json').length;
  h.$('rules-reset').click();
  await flush();
  assert.equal(h.$('rules-controls').disabled, false);
  assert.equal(h.$('rules-use-editor').hidden, true);
  assert.equal(h.$('rules-file').value, '');
  assert.equal(h.calls.filter(c => c.url === '/api/v1/rules/json').length, before + 1);
  h.$('run').click();
  const upload = h.calls.find(c => c.options?.method === 'POST');
  const yaml = await upload.options.body.parts.find(p => p[0] === 'rules')[1].text();
  assert.match(yaml, /road:/);
  assert.doesNotMatch(yaml, /override/);
});

test('repeated run events do not submit duplicate jobs for the same input', async () => {
  const pending = deferred();
  const h = await harness(() => pending.promise);
  h.$('run').handlers.click();
  h.$('run').handlers.click();
  assert.equal(h.calls.filter(c => c.options?.method === 'POST').length, 1);
});

test('late status from a replaced input cannot load or display its old result', async () => {
  const pending = deferred();
  const h = await harness(url => url.endsWith('/old-job') ? pending.promise : reply(fc('v1')));
  h.context.jobId = 'old-job';
  h.eval('poll(Date.now())');
  h.context.nextFile = {name: 'new.geojson', size: 100_000_000};
  h.eval('setFile(nextFile)');
  pending.resolve(reply({status: 'DONE', depth_available: false}));
  await flush();
  assert.equal(h.calls.some(c => c.url.endsWith('/result')), false);
  assert.equal(h.$('results').hidden, true);
});

test('late result download from a replaced input cannot overwrite the new map', async () => {
  const pending = deferred();
  const h = await harness(() => pending.promise);
  h.context.jobId = 'old-job';
  h.eval('loadResults({depth_available: false})');
  h.context.nextFile = {name: 'new.geojson', size: 100_000_000};
  h.eval('setFile(nextFile)');
  pending.resolve(reply(fc('old-v1')));
  await flush();
  assert.equal(h.context.modeData['2d'], null);
  assert.equal(h.$('results').hidden, true);
});

test('late POST response cannot replace a newer job or start polling it', async () => {
  const first = deferred(), second = deferred(); let posts = 0;
  const h = await harness((url, options) => options?.method === 'POST'
    ? (++posts === 1 ? first.promise : second.promise) : reply({status: 'RUNNING'}));
  h.$('run').click();
  h.context.nextFile = {name: 'new.geojson', size: 100_000_000}; h.eval('setFile(nextFile)');
  h.$('run').click();
  second.resolve(reply({job_id: 'new-job'})); await flush();
  first.resolve(reply({job_id: 'old-job'})); await flush();
  assert.equal(h.context.jobId, 'new-job');
  assert.equal(h.calls.some(c => c.url.endsWith('/old-job')), false);
});

test('changing YAML clears the old SVG even when a large GeoJSON has no local preview', async () => {
  const h = await harness();
  h.context.baseData = null;
  h.context.jobId = 'old-job'; h.context.modeData = {'2d': fc('v1'), depth: null};
  h.context.svg.innerHTML = '<g>old network</g>';
  h.context.tileCache.set('old-tile', {});
  h.$('legend').hidden = false;
  h.$('hint').style.display = 'none';
  const drawSource = html.slice(html.indexOf('  function drawAll()'), html.indexOf('  // ---------- анимация вида'));
  h.eval(drawSource);
  h.$('rules-file').files = [{name: 'new.yml'}];
  h.$('rules-file').handlers.change();
  assert.equal(h.context.svg.innerHTML, '');
  assert.equal(h.context.tileCache.size, 0);
  assert.equal(h.$('legend').hidden, true);
  assert.equal(h.$('hint').style.display, '');
  assert.equal(h.context.jobId, null);
});

test('reset blocks Run until current server rules arrive and submits those rules', async () => {
  const h = await harness();
  h.eval('rulesModel.restrictions.road.k_spec = 7');
  const pending = deferred();
  h.context.rulesLoader = () => pending.promise;
  h.$('rules-reset').click();
  assert.equal(h.$('run').disabled, true);
  h.$('run').handlers.click();
  assert.equal(h.calls.some(c => c.options?.method === 'POST'), false);
  pending.resolve(reply({restrictions: {road: {mode: 'special', k_spec: 1.75}}}));
  await flush();
  assert.equal(h.$('run').disabled, false);
  assert.doesNotMatch(h.$('status').textContent, /Загрузка действующего справочника/);
  h.$('run').click();
  const upload = h.calls.find(c => c.options?.method === 'POST');
  const yaml = await upload.options.body.parts.find(p => p[0] === 'rules')[1].text();
  assert.match(yaml, /k_spec: 1.75/);
  assert.doesNotMatch(yaml, /k_spec: 7/);
});

test('a late previous rules reset cannot overwrite the newest server rules', async () => {
  const h = await harness();
  const first = deferred(), second = deferred(); let loads = 0;
  h.context.rulesLoader = () => ++loads === 1 ? first.promise : second.promise;
  h.$('rules-reset').click();
  h.$('rules-reset').click();
  second.resolve(reply({restrictions: {road: {mode: 'special', k_spec: 2}}}));
  await flush();
  first.resolve(reply({restrictions: {road: {mode: 'special', k_spec: 7}}}));
  await flush();
  assert.equal(h.eval('rulesModel.restrictions.road.k_spec'), 2);
});

test('failed rules reset blocks editor submission but allows an explicit YAML file', async () => {
  const h = await harness();
  h.context.rulesLoader = async () => ({ok: false, status: 503});
  h.$('rules-reset').click();
  await flush();
  assert.equal(h.$('run').disabled, true);
  assert.match(h.$('status').textContent, /Не удалось загрузить справочник/);
  h.$('run').handlers.click();
  assert.equal(h.calls.some(c => c.options?.method === 'POST'), false);
  h.$('rules-file').files = [{name: 'usable.yml'}];
  h.$('rules-file').handlers.change();
  assert.equal(h.$('run').disabled, false);
  h.$('run').click();
  assert.equal(h.calls.some(c => c.options?.method === 'POST'), true);
});

test('a pending editor reset cannot override a newer selected YAML file or running job', async () => {
  const h = await harness();
  const pending = deferred();
  h.context.rulesLoader = () => pending.promise;
  h.$('rules-reset').click();
  const selected = {name: 'chosen.yml'};
  h.$('rules-file').files = [selected];
  h.$('rules-file').handlers.change();
  h.$('run').click();
  pending.resolve(reply({restrictions: {road: {mode: 'special', k_spec: 2}}}));
  await flush();
  assert.equal(h.eval('rulesFile'), selected);
  assert.equal(h.$('rules-controls').disabled, true);
  assert.equal(h.$('run').disabled, true);
  const upload = h.calls.find(c => c.options?.method === 'POST');
  assert.equal(upload.options.body.parts.find(p => p[0] === 'rules')[1], selected);
});
