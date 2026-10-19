const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

// Exercise the actual polling/loading UI against controlled HTTP responses.
// Map drawing is replaced because these checks concern availability and diagnostics.
const html = fs.readFileSync(path.join(__dirname, '../../main/resources/static/ui/index.html'), 'utf8');
const source = html.slice(html.indexOf('  // ---------- запуск расчёта'),
  html.indexOf('  // ---------- скачивание'));
const fc = id => ({type: 'FeatureCollection', features: [{type: 'Feature', geometry: null,
  properties: {object_type: 'variant_summary', variant_id: id}}]});

function element() {
  return {hidden: true, disabled: false, className: '', title: '', children: [], ownText: '',
    classList: {toggle() {}, add() {}, remove() {}},
    set textContent(value) { this.ownText = value; this.children = []; },
    get textContent() { return this.ownText + this.children.map(c => c.textContent).join(''); },
    set innerHTML(_) { throw new Error('Diagnostics must use textContent, not HTML'); },
    appendChild(child) { this.children.push(child); return child; },
    replaceChildren(...children) { this.ownText = ''; this.children = children; },
    setAttribute() {}, addEventListener() {}};
}

async function run(job, depth = fc('d1'), depthHttpOk = true) {
  const elements = new Map();
  const $ = id => { if (!elements.has(id)) elements.set(id, element()); return elements.get(id); };
  const requests = [];
  const context = vm.createContext({$, jobId: 'test', modeData: {}, mode: '2d', Date, Promise,
    inputFile: null, setTimeout() {}, renderVariantList() {}, drawAll() {},
    groupVariants: value => value?.features?.filter(f => f.properties?.object_type === 'variant_summary') || [],
    document: {createElement: element, querySelector: selector => $(selector),
      querySelectorAll: selector => selector === '[data-dl]' ? [] : [$(selector)]},
    fetch: async url => {
      requests.push(url);
      if (url.endsWith('/result-depth')) return {ok: depthHttpOk, json: async () => depth};
      if (url.endsWith('/result')) return {ok: true, json: async () => fc('v1')};
      return {ok: true, json: async () => job};
    }
  });
  vm.runInContext(source + '\npoll(Date.now());', context);
  await new Promise(resolve => setImmediate(resolve));
  await new Promise(resolve => setImmediate(resolve));
  return {$, requests, context};
}

const issue = {variant_id: 'd3', mode: 'depth', code: 'DEPTH_SLOPE_EXCEEDED',
  message: 'Не хватает длины для допустимого уклона. <img src=x onerror=alert(1)>',
  segment_ids: ['d3_net_1']};

test('base remains visible when depth is unavailable, with readable safe diagnostics', async () => {
  const {$, requests} = await run({status: 'DONE', diagnostics: [issue], depth_available: false}, null, false);
  assert.equal($('results').hidden, false);
  assert.equal($('mode-depth').disabled, true);
  assert.equal($('[data-dl="result-depth"]').disabled, true);
  assert.equal($('diagnostics').hidden, false);
  assert.match($('diagnostics').textContent, /d3/);
  assert.match($('diagnostics').textContent, /Не хватает длины/);
  assert.match($('status').textContent, /замечан/);
  assert.equal(requests.some(url => url.endsWith('/result-depth')), false);
});

test('partial depth rejection keeps another depth variant available', async () => {
  const {$, context} = await run({status: 'DONE', diagnostics: [issue], depth_available: true});
  assert.equal($('mode-depth').disabled, false);
  assert.equal($('[data-dl="result-depth"]').disabled, false);
  assert.equal(context.modeData.depth.features[0].properties.variant_id, 'd1');
  assert.match($('diagnostics').textContent, /d3/);
});

test('failed admissibility shows reasons and never requests result downloads', async () => {
  const {$, requests} = await run({status: 'ERROR', error: 'Рассчитанные варианты не прошли проверку.',
    diagnostics: [{...issue, variant_id: 'v1', mode: 'base'}], result_available: false, depth_available: false});
  assert.equal($('results').hidden, true);
  assert.match($('status').textContent, /не прошли проверку/);
  assert.match($('diagnostics').textContent, /v1/);
  assert.equal(requests.length, 1);
});

test('ordinary successful result has no diagnostic banner', async () => {
  const {$} = await run({status: 'DONE', diagnostics: [], depth_available: true});
  assert.equal($('results').hidden, false);
  assert.equal($('diagnostics').hidden, true);
  assert.equal($('mode-depth').disabled, false);
  assert.equal($('status').className, 'ok');
});

test('legacy empty depth FeatureCollection does not enable an empty mode', async () => {
  const {$} = await run({status: 'DONE'}, {type: 'FeatureCollection', features: []});
  assert.equal($('mode-depth').disabled, true);
  assert.equal($('[data-dl="result-depth"]').disabled, true);
});
