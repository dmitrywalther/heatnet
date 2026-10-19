const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const script = path.join(__dirname, '../../main/resources/static/ui/longitudinal-profile.js');

function element(tag) {
  const node = {tag, attributes: {}, children: [], handlers: {}, hidden: false, ownText: '',
    setAttribute(name, value) { this.attributes[name] = String(value); },
    getAttribute(name) { return this.attributes[name]; },
    removeAttribute(name) { delete this.attributes[name]; },
    appendChild(child) { this.children.push(child); return child; },
    replaceChildren(...children) { this.children = children; this.ownText = ''; },
    addEventListener(name, callback) { this.handlers[name] = callback; },
    set textContent(value) { this.ownText = String(value); this.children = []; },
    get textContent() { return this.ownText + this.children.map(c => c.textContent).join(' '); },
    set innerHTML(_) { throw new Error('Profile must not interpolate HTML'); },
    getBoundingClientRect() { return {left: 0, top: 0, width: 1000, height: 260}; },
    emit(name, data = {}) { this.handlers[name]?.({preventDefault() {}, ...data}); }
  };
  node.classList = {
    toggle(name, yes) { const s = new Set((node.attributes.class || '').split(' ').filter(Boolean));
      if (yes) s.add(name); else s.delete(name); node.attributes.class = [...s].join(' '); },
    add(name) { this.toggle(name, true); }, remove(name) { this.toggle(name, false); }
  };
  return node;
}
function find(root, predicate) { return [root, ...root.children.flatMap(c => find(c, () => true))].filter(predicate); }
const attr = (root, name, value) => find(root, n => n.attributes[name] === value);
function harness() {
  assert.ok(fs.existsSync(script), 'longitudinal profile widget must exist');
  const container = element('section'), hover = [], positions = [], closed = [];
  const context = vm.createContext({document: {createElement: element, createElementNS: (_, tag) => element(tag)},
    window: {}, Intl, console});
  vm.runInContext(fs.readFileSync(script, 'utf8'), context);
  const widget = context.window.HeatnetProfile.create({container,
    onSegmentHover: id => hover.push(id), onPosition: p => positions.push(p), onClose: () => closed.push(true)});
  return {container, widget, hover, positions, closed};
}
function profile() {
  return {variant_id: 'd1', oks: {key: '2', label: 'ОКС 2', coordinates: [37.2, 55.2]},
    source: {id: 'tap', label: 'Врезка tap', coordinates: [37, 55]}, total_length: 100,
    segments: [
      {id: 'd1_net_1', from: 0, to: 40, depth_start: 3, depth_end: 4, slope: 0.025,
        diameter: 200, height: 0.7, coordinates: [[37, 55], [37.1, 55.1]]},
      {id: 'd1_net_2', from: 40, to: 100, depth_start: 4, depth_end: 2, slope: -1 / 30,
        diameter: 100, height: 0.5, coordinates: [[37.1, 55.1], [37.2, 55.2]]}
    ], obstacles: [], notes: []};
}

test('profile shows selected building, stationing, top depths and real envelope thickness', () => {
  const {container, widget} = harness(); widget.setData(profile());
  assert.match(container.textContent, /ОКС 2/);
  assert.match(container.textContent, /100/);
  assert.match(container.textContent, /верх.*габарит/);
  assert.match(container.textContent, /условн.*поверхност/);
  const svg = find(container, n => n.tag === 'svg')[0];
  assert.ok(svg.attributes.viewBox);
  const segments = attr(container, 'data-segment-id', 'd1_net_1');
  assert.equal(segments.length, 1);
  const band = find(segments[0], n => n.tag === 'polygon')[0];
  assert.ok(band, 'real pipe height has an envelope band');
  assert.equal(segments[0].attributes.tabindex, '0');
  segments[0].emit('focus');
  assert.match(container.textContent, /2[,.]50\s*%/);
  assert.match(container.textContent, /ДУ 200/);
});

test('keyboard activation links a segment midpoint to the map in source-to-building order', () => {
  const {container, widget, hover, positions} = harness(); widget.setData(profile());
  const segment = attr(container, 'data-segment-id', 'd1_net_2')[0];
  segment.emit('focus'); segment.emit('keydown', {key: 'Enter'});
  assert.equal(hover.at(-1), 'd1_net_2');
  assert.equal(positions.at(-1).segmentId, 'd1_net_2');
  assert.equal(positions.at(-1).station, 70);
  assert.ok(Math.abs(positions.at(-1).coordinate[0] - 37.15) < 1e-10);
  assert.ok(Math.abs(positions.at(-1).coordinate[1] - 55.15) < 1e-10);
});

test('map highlighting updates profile detail without emitting a feedback callback', () => {
  const {container, widget, hover, positions} = harness(); widget.setData(profile());
  const before = hover.length;
  widget.highlightSegment('d1_net_2');
  assert.match(attr(container, 'data-segment-id', 'd1_net_2')[0].attributes.class, /active/);
  assert.match(container.textContent, /ДУ 100/);
  assert.equal(hover.length, before);
  assert.equal(positions.length, 0);
  widget.highlightSegment(null);
  assert.doesNotMatch(attr(container, 'data-segment-id', 'd1_net_2')[0].attributes.class, /active/);
});

test('obstacle physical bands and rule thresholds remain distinct, with unknown depths explicit', () => {
  const h = harness(), data = profile();
  data.obstacles = [
    {id: 'gas', type: 'gas', from: 20, to: 25, top_depth: 1, bottom_depth: 1.5, clearance: 0.5},
    {id: 'tram', type: 'tram', from: 50, to: 65, min_top_depth: 1.8},
    {id: 'unknown', type: 'custom', label: '<img src=x onerror=alert(1)>', from: 70, to: 75}
  ];
  h.widget.setData(data);
  assert.equal(attr(h.container, 'data-obstacle-band', 'gas').length, 1);
  assert.equal(attr(h.container, 'data-obstacle-band', 'tram').length, 0);
  assert.equal(attr(h.container, 'data-obstacle-threshold', 'tram').length, 1);
  const unknown = attr(h.container, 'data-obstacle-id', 'unknown')[0]; unknown.emit('focus');
  assert.match(h.container.textContent, /глубина.*не задана/i);
  assert.match(h.container.textContent, /<img src=x/);
  assert.equal(find(h.container, n => n.tag === 'img').length, 0);
});

test('loading, errors, close and clear remove stale plot state and map highlighting', () => {
  const h = harness(); h.widget.setData(profile());
  h.widget.setMessage('Загружаем профиль…');
  assert.match(h.container.textContent, /Загружаем/);
  assert.equal(find(h.container, n => n.tag === 'svg').length, 0);
  h.widget.setData(profile());
  attr(h.container, 'data-profile-action', 'close')[0].emit('click');
  assert.equal(h.closed.length, 1);
  h.widget.clear();
  assert.equal(h.container.hidden, true);
  assert.equal(h.hover.at(-1), null);
});

test('empty or invalid profiles explain unavailable data instead of drawing invented depths', () => {
  const h = harness(), data = profile(); data.segments[0].depth_start = null;
  h.widget.setData(data);
  assert.match(h.container.textContent, /недостаточн|неполны|неполные/i);
  assert.equal(find(h.container, n => n.tag === 'svg').length, 0);
  h.widget.setData({...profile(), segments: []});
  assert.match(h.container.textContent, /нет|отсутств/i);
});

test('no-intersection and rounded-slope explanations do not claim normative certification', () => {
  const h = harness(); h.widget.setData(profile());
  assert.match(h.container.textContent, /пересечени.*нет|не пересекает/i);
  assert.match(h.container.textContent, /округл/);
  assert.doesNotMatch(h.container.textContent, /все нормы соблюдены|проверка пройдена/i);
});

test('pointer uses the SVG transform, avoiding wrong map positions in letterboxed layouts', () => {
  const h = harness(); h.widget.setData(profile());
  const chart = find(h.container, n => n.tag === 'svg')[0];
  chart.getScreenCTM = () => ({inverse: () => ({})});
  chart.createSVGPoint = () => ({x: 0, y: 0, matrixTransform: () => ({x: 790, y: 100})});
  const segment = attr(h.container, 'data-segment-id', 'd1_net_2')[0];
  segment.emit('mousemove', {clientX: 850, clientY: 300});
  assert.equal(h.positions.at(-1).station, 80);
  assert.ok(Math.abs(h.positions.at(-1).coordinate[0] - (37.1 + 0.1 * 2 / 3)) < 1e-10);
});

test('obstacle names are visible and selectable without hovering a tiny intersection', () => {
  const h = harness(), data = profile();
  data.obstacles = [{id: 'g1', type: 'gas', label: 'Газопровод g1', from: 20, to: 21, top_depth: 1, bottom_depth: 1.4}];
  h.widget.setData(data);
  const button = attr(h.container, 'data-obstacle-button', 'g1')[0];
  assert.ok(button);
  assert.match(button.textContent, /Газопровод g1/);
  button.emit('click');
  assert.match(h.container.textContent, /верх препятствия 1[,.]00/);
  assert.equal(h.positions.at(-1).station, 20.5);
});

test('profile inspection leaves supplied calculation data unchanged', () => {
  const h = harness(), data = profile(), before = JSON.stringify(data);
  function freeze(value) { if (value && typeof value === 'object') { Object.values(value).forEach(freeze); Object.freeze(value); } }
  freeze(data); h.widget.setData(data); h.widget.highlightSegment('d1_net_1'); h.widget.clear();
  assert.equal(JSON.stringify(data), before);
});

test('actual backend labels and obstacle types have unambiguous Russian names', () => {
  const h = harness(), data = profile(); data.oks.label = '2';
  data.obstacles = [
    {id: 't1', type: 'tram_tracks', from: 1, to: 2},
    {id: 'g1', type: 'gas_pipeline', from: 3, to: 4},
    {id: 'c1', type: 'power_cable', from: 5, to: 6},
    {id: 'h1', type: 'heat_network', from: 7, to: 8},
    {id: 'w1', type: 'water', from: 9, to: 10}
  ]; h.widget.setData(data);
  assert.match(h.container.textContent, /Продольный профиль · ОКС 2/);
  assert.match(h.container.textContent, /Трамвай t1/);
  assert.match(h.container.textContent, /Газопровод g1/);
  assert.match(h.container.textContent, /Кабель c1/);
  assert.match(h.container.textContent, /Теплосеть h1/);
  assert.match(h.container.textContent, /Водоём w1/);
  assert.doesNotMatch(h.container.textContent, /Водопровод/);
});

test('existing network at the source is labelled as a tie-in, without crossing-clearance requirement', () => {
  const h = harness(), data = profile(); data.source.label = '202';
  data.obstacles = [{id: 'old', label: 'Существующая теплосеть · old', type: 'heat_network',
    from: 0, to: 0.5, top_depth: 3, bottom_depth: 3.7, clearance: 0.5, role: 'tie_in'}];
  h.widget.setData(data);
  assert.match(h.container.textContent, /Врезка 202 → ОКС 2/);
  attr(h.container, 'data-obstacle-button', 'old')[0].emit('click');
  const detail = find(h.container, n => n.attributes.class === 'hp-detail')[0];
  assert.match(detail.textContent, /врезк/i);
  assert.doesNotMatch(detail.textContent, /требуемый просвет/);
});

test('depth range and maximum approximate slope are visible before any hover', () => {
  const h = harness(); h.widget.setData(profile());
  const stats = find(h.container, n => n.attributes.class === 'hp-stats')[0];
  assert.ok(stats, 'profile needs an always-visible engineering summary');
  assert.match(stats.textContent, /Глубина верха: 2[,.]00–4[,.]00 м/);
  assert.match(stats.textContent, /Макс\. уклон ≈ 3[,.]33%/);
  assert.match(stats.textContent, /округл/);
});

test('long explanatory notes remain available in a collapsed help block', () => {
  const h = harness(), data = profile();
  data.notes = ['Глубина — до верха расчётного габарита.', 'Особое пояснение сохранённого результата.'];
  h.widget.setData(data);
  const help = find(h.container, n => n.tag === 'details')[0];
  assert.ok(help, 'long notes must not consume the default chart height');
  assert.equal(help.attributes.open, undefined);
  assert.match(help.textContent, /Как читать профиль/);
  assert.match(help.textContent, /Особое пояснение/);
});
