const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ui = path.join(__dirname, '../../main/resources/static/ui');
const html = fs.readFileSync(path.join(ui, 'index.html'), 'utf8');
const source = html.slice(html.indexOf('  // ---------- варианты ----------'),
  html.indexOf('  // ---------- конец продольного профиля ----------'));
const flush = async () => { for (let i = 0; i < 8; i++) await new Promise(setImmediate); };
function element(tag = 'div') {
  const n = {tag, children: [], handlers: {}, attributes: {}, style: {}, ownText: '', markup: '', hidden: false,
    appendChild(child) { this.children.push(child); return child; },
    replaceChildren(...children) { this.children = children; this.ownText = ''; this.markup = ''; },
    setAttribute(k, v) { this.attributes[k] = String(v); },
    removeAttribute(k) { delete this.attributes[k]; },
    addEventListener(k, v) { this.handlers[k] = v; },
    set textContent(v) { this.ownText = String(v); this.children = []; this.markup = ''; },
    get textContent() { return this.ownText + this.markup.replace(/<[^>]+>/g, '') + this.children.map(c => c.textContent).join(' '); },
    set innerHTML(v) { this.markup = v; this.children = []; this.ownText = ''; },
    get innerHTML() { return this.markup; },
    getBoundingClientRect() { return {left: 0, top: 0, width: 1000, height: 260}; },
    remove() {}, scrollIntoView() {}};
  n.classList = {add() {}, toggle(k, yes) { n.attributes[k] = yes; }};
  return n;
}
const summary = (id, rank) => ({type: 'Feature', geometry: null, properties: {
  object_type: 'variant_summary', variant_id: id, rank, score: rank, calculated_cost: 1000,
  construction_cost: 1000, new_network_length: 10}});
const fc = features => ({type: 'FeatureCollection', features});
const freeze = value => {
  if (value && typeof value === 'object') { Object.values(value).forEach(freeze); Object.freeze(value); }
  return value;
};
function harness(features, mode = '2d') {
  const nodes = new Map(), requests = [], downloads = [], mapLines = [];
  const $ = id => { if (!nodes.has(id)) nodes.set(id, element()); return nodes.get(id); };
  const data = freeze(fc(features)), before = JSON.stringify(data);
  const context = vm.createContext({$, mode, modeData: {[mode]: data}, jobId: 'saved-job', currentVariant: null,
    Map, Set, Number, Intl, Promise, encodeURIComponent, console,
    escapeHtml: s => String(s).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;'),
    fmtRub: n => String(n) + ' ₽', fmtM: n => String(n) + ' м',
    window: {open: url => downloads.push(url)}, document: {
      createElement: element, createElementNS: (_, tag) => element(tag),
      querySelectorAll: selector => selector === '.variant' ? $('variants').children : []},
    svg: element('svg'), proj: {ready: false}, toXY: (...x) => x, linePts: x => x,
    el: (tag, attrs) => { const n = element(tag); Object.assign(n.attributes, attrs); mapLines.push(n); return n; },
    drawAll() {}, renderBreakdown() {},
    fetch: async url => {
      requests.push(url);
      const id = new URL(url, 'http://localhost').searchParams.get('variant_id');
      const oks = {key: 'n:2', label: '2', connected: true, flow_tph: 12};
      const body = !url.includes('oks_key=') ? {oks: [oks]} : url.includes('/profile?') ? {
        variant_id: id, oks, source: {label: '201'}, total_length: 10, obstacles: [], notes: [],
        segments: [{id: id + '_net_1', from: 0, to: 10, depth_start: 3, depth_end: 3,
          diameter: 80, height: 0.5, coordinates: [[37, 55], [37.001, 55]]}]
      } : {variant_id: id, oks, status: 'connected', segments: [], route_summary: {}, notes: []};
      return {ok: true, json: async () => freeze(body)};
    }
  });
  for (const file of ['connection-card.js', 'longitudinal-profile.js']) {
    vm.runInContext(fs.readFileSync(path.join(ui, file), 'utf8'), context);
  }
  context.HeatnetConnectionCard = context.window.HeatnetConnectionCard;
  context.HeatnetProfile = context.window.HeatnetProfile;
  vm.runInContext(source, context);
  return {$, context, requests, downloads, mapLines, data, before, eval: code => vm.runInContext(code, context)};
}

for (const [mode, prefix] of [['2d', 'v'], ['depth', 'd']]) {
  test(`${mode}: visible numbers follow reversed rank, selection and exports retain technical IDs`, async () => {
    const h = harness([summary(prefix + '1', 2), summary(prefix + '3', 1)], mode);
    h.eval('renderVariantList()'); await flush();
    const buttons = h.$('variants').children;
    assert.match(buttons[0].textContent, new RegExp('Вариант №1.*ID: ' + prefix + '3'));
    assert.match(buttons[1].textContent, new RegExp('Вариант №2.*ID: ' + prefix + '1'));
    assert.equal(h.context.currentVariant, prefix + '3');
    assert.match(h.$('connection-card').textContent, new RegExp('Вариант №1 · ID: ' + prefix + '3'));
    if (mode === 'depth') assert.match(h.$('profile-panel').textContent, /Вариант №1 · ID: d3/);
    h.$('connection-download').handlers.click();
    assert.match(h.downloads[0], new RegExp('connection\\.pdf\\?variant_id=' + prefix + '3&oks_key=n%3A2'));
    buttons[1].handlers.click(); await flush();
    assert.equal(h.context.currentVariant, prefix + '1');
    assert.ok(h.requests.some(url => url.includes('variant_id=' + prefix + '1&oks_key=n%3A2')));
    assert.match(h.$('connection-card').textContent, new RegExp('Вариант №2 · ID: ' + prefix + '1'));
    assert.equal(JSON.stringify(h.data), h.before, 'Rendering and selection must not rewrite saved data');
  });
}

test('comparison labels, selected colours and map tooltip agree about reversed ranking', async () => {
  const h = harness([summary('v1', 2), summary('v3', 1)]);
  h.eval('renderVariantList()'); await flush(); h.$('compare-toggle').handlers.click();
  const text = h.$('compare-stats').textContent;
  assert.match(text, /A · Вариант №1.*ID: v3.*розовый/);
  assert.match(text, /B · Вариант №2.*ID: v1.*синий/);
  assert.match(text, /Δ \(B − A\)/);
  assert.equal(h.$('variants').children[0].attributes.active, true);
  assert.equal(h.$('variants').children[1].attributes.compare, true);
  h.context.duWidth = () => 1;
  h.context.resultFeatures = ['v1', 'v3'].map(id => ({
    properties: {variant_id: id, object_type: 'heat_network', id: id + '_net_1', diameter: 80},
    geometry: {type: 'LineString', coordinates: [[37, 55], [37.001, 55]]}}));
  h.eval(html.slice(html.indexOf('    const drawResult ='), html.indexOf('    // точки ОКС поверх')));
  assert.deepEqual(h.mapLines.filter(n => ['#2f8fe0', '#FF0053'].includes(n.attributes.stroke))
    .map(n => [n.attributes.stroke, n.attributes['stroke-width']]), [['#2f8fe0', 4], ['#FF0053', 1]]);
  h.eval(html.slice(html.indexOf('  // ---------- tooltip ----------'), html.indexOf('  // ---------- пан/зум ----------')));
  h.context.segment = {variant_id: 'v1', id: 'v1_net_1', diameter: 80, flow_tph: 12, length: 10, cost: 1000};
  h.context.line = element();
  h.eval('showTip({clientX: 10, clientY: 10}, segment, line)');
  assert.match(h.$('tooltip').textContent, /Вариант №2 · ID: v1/);
  assert.equal(JSON.stringify(h.data), h.before);
});

test('comparison keeps complete grouped prices in three columns under separate metric labels', async () => {
  const a = summary('v3', 1), b = summary('v1', 2);
  a.properties.calculated_cost = 50421566;
  b.properties.calculated_cost = 42974655;
  const h = harness([b, a]);
  h.eval(html.match(/  const fmtRub = [^\r\n]+/)[0]);
  h.eval('renderVariantList()'); await flush(); h.$('compare-toggle').handlers.click();
  const markup = h.$('compare-stats').innerHTML;
  const cells = [...markup.matchAll(/<div class="([^"]+)">([\s\S]*?)<\/div>/g)].map(m => ({
    className: m[1], text: m[2].replace(/<[^>]+>/g, '').replace(/\u00a0/g, ' ')}));
  assert.equal(cells.length, 15, 'Three headers and three groups of a metric label plus three values');
  assert.deepEqual(cells.slice(0, 3).map(c => c.className), ['h va', 'h vb', 'h']);
  assert.deepEqual(cells.filter(c => c.className === 'h metric').map(c => c.text),
    ['стоимость', 'длина', 'score']);
  assert.deepEqual(cells.slice(4, 7).map(c => c.text), ['50 421 566 ₽', '42 974 655 ₽', '−7 446 911 ₽']);
  const style = html.match(/#compare-stats \{([^}]+)\}/)[1];
  assert.match(style, /grid-template-columns:\s*repeat\(3,/);
  assert.match(style, /overflow-x:\s*auto/);
  assert.match(html, /#compare-stats \.metric\s*\{[^}]*grid-column:\s*1\s*\/\s*-1/);
  assert.match(html, /#compare-stats \.num\s*\{[^}]*white-space:\s*nowrap/);
  assert.equal(JSON.stringify(h.data), h.before);
});

test('missing or invalid historical rank never becomes a number inferred from the ID or array position', async () => {
  for (const rank of [undefined, null, 0, -1, 1.5, '2', Number.MAX_SAFE_INTEGER + 1]) {
    const h = harness([summary('v3', rank)]);
    h.eval('renderVariantList()'); await flush();
    assert.match(h.$('variants').textContent, /Вариант.*ID: v3/);
    assert.doesNotMatch(h.$('variants').textContent, /Вариант №/);
    assert.match(h.$('connection-card').textContent, /Вариант · ID: v3/);
    assert.doesNotMatch(h.$('connection-card').textContent, /Вариант №/);
  }
});
