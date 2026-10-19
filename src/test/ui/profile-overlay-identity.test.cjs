const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const html = fs.readFileSync(path.join(__dirname, '../../main/resources/static/ui/index.html'), 'utf8');
const source = html.slice(html.indexOf('  function drawProfileOverlay() {'),
  html.indexOf('  // ---------- конец продольного профиля ----------'));

function harness() {
  let focused = null;
  function element(tag) {
    const node = {tag, parentNode: null, children: [], attributes: {}, handlers: {},
      setAttribute(name, value) { this.attributes[name] = String(value); },
      getAttribute(name) { return this.attributes[name]; },
      appendChild(child) { child.remove(); this.children.push(child); child.parentNode = this; return child; },
      replaceChildren(...children) { [...this.children].forEach(child => child.remove()); children.forEach(child => this.appendChild(child)); },
      remove() {
        if (this.parentNode) this.parentNode.children = this.parentNode.children.filter(child => child !== this);
        this.parentNode = null;
        if (focused && !focused.isConnected) focused = null;
      },
      addEventListener(name, callback) { this.handlers[name] = callback; },
      emit(name, extra = {}) { this.handlers[name]?.({stopPropagation() {}, preventDefault() {}, ...extra}); },
      focus() { focused = this; },
      get isConnected() { return this === svg || Boolean(this.parentNode?.isConnected); }
    }; return node;
  }
  const svg = element('svg'), selected = [];
  const context = vm.createContext({svg, Map, Set,
    mode: 'depth', profileMapOverlay: null, selectedOksKey: 'n:1', profileHoverId: 'd1_net_22', profileMarker: [37.05, 55],
    profileOks: [
      {key: 'n:1', label: '1', connected: true, coordinates: [37, 55]},
      {key: 'n:2', label: '2', connected: true, coordinates: [37.1, 55]},
      {key: 's:2', label: '2 (строка)', connected: false, coordinates: [37.2, 55]}
    ],
    activeProfile: {segments: [{id: 'd1_net_22', coordinates: [[37, 55], [37.1, 55]]}]},
    el(tag, attrs, parent = svg) {
      const node = element(tag); Object.entries(attrs).forEach(([k, v]) => node.setAttribute(k, v));
      return parent.appendChild(node);
    },
    toXY: (x, y) => [x * 100, y * 100], linePts: coords => coords.map(c => c.join(',')).join(' '),
    selectProfileOks: key => selected.push(key)
  });
  vm.runInContext(source, context);
  const all = root => [root, ...root.children.flatMap(all)];
  const point = key => all(svg).find(node => node.attributes['data-profile-oks'] === key);
  const draw = () => vm.runInContext('drawProfileOverlay()', context);
  return {context, svg, selected, point, draw, all, focus: () => focused};
}

test('profile blur does not detach the pressed map OKS before its click', () => {
  const h = harness(); h.draw();
  const pressed = h.point('n:2'); pressed.emit('pointerdown'); pressed.focus();
  h.context.profileHoverId = null; h.context.profileMarker = null; h.draw();
  assert.equal(pressed.isConnected, true, 'blur must not remove the pointerdown target before pointerup');
  assert.equal(h.point('n:2'), pressed);
  assert.equal(h.focus(), pressed);
  pressed.emit('click');
  assert.deepEqual(h.selected, ['n:2']);
});

test('selection and coordinate updates preserve keyboard focus and typed OKS identity', () => {
  const h = harness(); h.draw();
  const focused = h.point('n:2'), stringPoint = h.point('s:2'); focused.focus();
  h.context.selectedOksKey = 'n:2'; h.context.profileOks[1].coordinates = [37.15, 55.1]; h.draw();
  assert.equal(h.point('n:2'), focused);
  assert.equal(h.point('s:2'), stringPoint);
  assert.equal(h.focus(), focused);
  assert.equal(focused.attributes.r, '8');
  assert.equal(focused.attributes.cx, '3715');
  assert.equal(stringPoint.attributes.tabindex, '-1');
  focused.emit('keydown', {key: 'Enter'});
  assert.deepEqual(h.selected, ['n:2']);
});

test('full map redraw rebuilds a detached overlay and leaving depth removes it', () => {
  const h = harness(); h.draw(); const old = h.point('n:2');
  h.svg.replaceChildren(); h.draw();
  assert.equal(old.isConnected, false);
  assert.notEqual(h.point('n:2'), old);
  assert.equal(h.point('n:2').isConnected, true);
  assert.equal(h.all(h.svg).filter(node => node.attributes['data-profile-overlay']).length, 1);
  h.context.mode = '2d'; h.draw();
  assert.equal(h.point('n:2'), undefined);
  assert.equal(h.context.profileMapOverlay, null);
});
