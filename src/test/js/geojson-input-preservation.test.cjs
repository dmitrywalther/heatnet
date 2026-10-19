const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const uiDir = path.resolve(__dirname, '../../main/resources/static/ui');
const html = fs.readFileSync(path.join(uiDir, 'index.html'), 'utf8');
const helperPath = path.join(uiDir, 'geojson-input.js');
const helper = fs.existsSync(helperPath) ? require(helperPath) : undefined;

// Exercise the expressions actually used by the UI upload and drawing handlers.
const upload = html.match(/new Blob\(\[([\s\S]*?)\],\s*\{\s*type:\s*'application\/geo\+json'\s*\}\)/)[1];
const nextId = html.match(/properties:\s*\{\s*id:\s*([^,]+),\s*object_type:\s*'restriction'/)[1];
async function modifiedUpload(raw, additions) {
  const baseData = JSON.parse(raw);
  baseData.features.push(...additions);
  const content = new Function('baseData', 'inputText', 'addedInputFeatures', 'HeatnetInput',
    'return ' + upload)(baseData, raw, additions, helper);
  return new Blob([content]).text();
}
function restrictionId(features) {
  const maxId = features.reduce((m, f) => typeof f.properties.id === 'number'
    ? Math.max(m, f.properties.id) : m, 0);
  return new Function('baseData', 'maxId', 'HeatnetInput', 'return ' + nextId)(
    { features }, maxId, helper);
}
const extra = { type: 'Feature', properties: { id: 'ui_restriction_1', object_type: 'restriction' },
  geometry: { type: 'Polygon', coordinates: [[[0, 0], [1, 0], [0, 1], [0, 0]]] } };

test('drawing preserves original numeric IDs, loads and exact lexical values', async () => {
  const raw = '{"type":"FeatureCollection", "features":[\n'
    + '{"properties":{"id":9223372036854775808,"flow_tph":0.1000000000000000001}},\n'
    + '{"properties":{"id":9223372036854775809,"flow_tph":0.1}},\n'
    + '{"properties":{"id":0.1000000000000000001}},\n'
    + '{"properties":{"id":0.1}}\n],"tail":{"unchanged":1.2300e+05}}';
  const close = raw.indexOf('],"tail"');
  const expected = raw.slice(0, close) + ',' + JSON.stringify(extra) + raw.slice(close);
  assert.equal(await modifiedUpload(raw, [extra]), expected);
});

test('scanner selects root features and respects nested arrays, strings and escaped keys', async () => {
  const raw = '{"metadata":{"features":[1,2]},"fea\\u0074ures":[ '
    + '{"properties":{"id":"a\\\" ] } [ \\\\ b","text":"features"},'
    + '"geometry":{"coordinates":[[[1,2],[3,4]]]}} ],"tail":[9]}';
  const close = raw.indexOf('],"tail"');
  assert.equal(await modifiedUpload(raw, [extra]),
    raw.slice(0, close) + ',' + JSON.stringify(extra) + raw.slice(close));
});

test('empty root features accepts several additions without a leading comma', async () => {
  const raw = '{ "features" : [ \n ], "type": "FeatureCollection" }';
  const second = { ...extra, properties: { ...extra.properties, id: 'ui_restriction_2' } };
  const close = raw.indexOf(']');
  assert.equal(await modifiedUpload(raw, [extra, second]),
    raw.slice(0, close) + JSON.stringify(extra) + ',' + JSON.stringify(second) + raw.slice(close));
});

test('zero additions preserve the original document verbatim', async () => {
  const raw = '{ "features" : [], "exact": 1.230000e+04 }\n';
  assert.equal(await modifiedUpload(raw, []), raw);
});

test('new drawn restriction uses a free string ID, never rounded numeric max plus one', () => {
  const features = [9223372036854775808, 'ui_restriction_1', 'ui_restriction_2', 1, '1']
    .map(id => ({ properties: { id } }));
  const id = restrictionId(features);
  assert.equal(typeof id, 'string');
  assert.equal(id, 'ui_restriction_3');
  assert.ok(features.every(f => f.properties.id !== id));
});

test('ordinary unmodified uploads still send the original File directly', () => {
  assert.match(html, /else\s*\{\s*form\.append\('file', inputFile\);/);
});
