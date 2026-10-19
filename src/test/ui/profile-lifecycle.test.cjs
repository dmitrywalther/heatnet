const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const html = fs.readFileSync(path.join(__dirname, '../../main/resources/static/ui/index.html'), 'utf8');
const begin = '  // ---------- продольный профиль ----------';
const end = '  // ---------- конец продольного профиля ----------';
const flush = async () => { for (let i = 0; i < 6; i++) await new Promise(setImmediate); };
const defer = () => { let resolve; const promise = new Promise(r => resolve = r); return {promise, resolve}; };
const ok = body => ({ok:true, json:async () => body});
const list = {oks:[{key:'n:2',label:'2',connected:true,coordinates:[37,55]},
  {key:'s:2',label:'2 (строка)',connected:false,coordinates:[37.01,55]}]};
const profile = {variant_id:'d1',oks:list.oks[0],segments:[{id:'d1_net_1',coordinates:[[37,55],[37.01,55]]}]};
function element() {
  return {hidden:false,disabled:false,value:'',children:[],style:{},handlers:{},
    classList:{toggle(){},add(){},remove(){}},
    appendChild(x){this.children.push(x);return x;},
    replaceChildren(...xs){this.children=xs;},remove(){},
    addEventListener(k,v){this.handlers[k]=v;},setAttribute(){},
    set textContent(x){this.text=x;this.children=[];},get textContent(){return this.text||'';}};
}
function setup(fetcher) {
  const nodes = new Map(), views=[],calls=[];
  let widgetOptions;
  const $ = id => {if(!nodes.has(id))nodes.set(id,element());return nodes.get(id);};
  const context=vm.createContext({$,jobId:'job-a',mode:'depth',currentVariant:'d1',modeData:{depth:{}},
    requestVersion:1,Promise,encodeURIComponent,Number,Map,Set,
    document:{createElement:element},
    HeatnetProfile:{create:opts=>{widgetOptions=opts;return {setData:x=>views.push(x),setMessage:x=>views.push(x),clear(){},highlightSegment(){}};}},
    drawProfileOverlay(){}, svg:element(),el:()=>element(),toXY:(...x)=>x,linePts:x=>x,
    fetch:async url=>{calls.push(url);return fetcher(url);}
  });
  assert.ok(html.includes(begin),'UI must wire the longitudinal profile lifecycle');
  vm.runInContext(html.slice(html.indexOf(begin),html.indexOf(end)),context);
  return {$,views,calls,context,widgetOptions,eval:s=>vm.runInContext(s,context)};
}
test('depth selection loads typed OKS choices and selected profile without rerunning calculation',async()=>{
  const h=setup(url=>ok(url.includes('oks_key=')?profile:list));
  h.eval('syncProfile()');await flush();
  assert.equal(h.$('profile-oks').children.length,2);
  assert.equal(h.$('profile-oks').children[1].disabled,true);
  assert.equal(h.views.at(-1).oks.key,'n:2');
  assert.ok(h.calls.every(u=>u.includes('/profile?')));
});
test('late profile cannot reappear after switching to base mode',async()=>{
  const pending=defer();
  const h=setup(url=>url.includes('oks_key=')?pending.promise:ok(list));
  h.eval('syncProfile()');await flush();
  h.context.mode='2d';h.context.currentVariant='v1';h.eval('syncProfile()');
  pending.resolve(ok(profile));await flush();
  assert.equal(h.$('profile-panel').hidden,true);
  assert.equal(h.views.some(v=>v?.oks),false);
});
test('late list cannot overwrite replacement job',async()=>{
  const pending=defer();const h=setup(()=>pending.promise);
  h.eval('syncProfile()');h.eval('resetProfile()');h.context.jobId='job-b';
  pending.resolve(ok(list));await flush();
  assert.equal(h.calls.length,1);assert.equal(h.$('profile-panel').hidden,true);
});
test('late previous variant cannot replace current profile',async()=>{
  const pending=defer();
  const h=setup(url=>url.includes('variant_id=d1')&&url.includes('oks_key=')?pending.promise:
    ok(url.includes('oks_key=')?{...profile,variant_id:'d3'}:list));
  h.eval('syncProfile()');await flush();
  h.context.currentVariant='d3';h.eval('syncProfile()');await flush();
  pending.resolve(ok(profile));await flush();
  assert.equal(h.views.filter(v=>v?.oks).at(-1).variant_id,'d3');
});
test('unavailable profile shows a useful message and clears previous path',async()=>{
  const h=setup(url=>url.includes('oks_key=')?{ok:false,json:async()=>({message:'Нет допустимого профиля'})}:ok(list));
  h.eval('syncProfile()');await flush();
  assert.match(h.views.at(-1),/Нет допустимого профиля/);
  assert.equal(h.eval('activeProfile'),null);
});
test('reopening a closed panel restores the chart without another request',async()=>{
  const h=setup(url=>ok(url.includes('oks_key=')?profile:list));
  h.eval('syncProfile()');await flush();
  const before=h.views.filter(v=>v?.oks).length, requests=h.calls.length;
  h.$('profile-panel').hidden=true;
  h.$('profile-open').handlers.click();
  assert.equal(h.$('profile-panel').hidden,false);
  assert.equal(h.views.filter(v=>v?.oks).length,before+1);
  assert.equal(h.calls.length,requests);
});
test('closing while a profile loads prevents it reopening and allows retry',async()=>{
  const pending=defer();let first=true;
  const h=setup(url=>url.includes('oks_key=')?(first?(first=false,pending.promise):ok(profile)):ok(list));
  h.eval('syncProfile()');await flush();h.widgetOptions.onClose();
  pending.resolve(ok(profile));await flush();
  assert.equal(h.$('profile-panel').hidden,true);
  assert.equal(h.views.some(v=>v?.oks),false);
  h.$('profile-open').handlers.click();await flush();
  assert.equal(h.views.at(-1).oks.key,'n:2');
});
test('leaving the profile removes the corresponding map position marker',()=>{
  const h=setup(()=>ok(list));
  h.eval('profileMarker=[37,55]');h.widgetOptions.onSegmentHover(null);
  assert.equal(h.eval('profileMarker'),null);
});

test('retry reloads a failed variant list despite a previously selected OKS',async()=>{
  let failed=false;
  const h=setup(url=>{
    if(url.includes('variant_id=d3')&&!url.includes('oks_key=')&&!failed){
      failed=true;return {ok:false,json:async()=>({message:'Temporary network failure'})};
    }
    return ok(url.includes('oks_key=')?{...profile,variant_id:url.includes('variant_id=d3')?'d3':'d1'}:list);
  });
  h.eval('syncProfile()');await flush();
  h.context.currentVariant='d3';h.eval('syncProfile()');await flush();
  assert.match(h.views.at(-1),/Temporary network failure/);
  h.$('profile-open').handlers.click();await flush();
  assert.equal(h.views.at(-1).variant_id,'d3');
  assert.equal(h.views.at(-1).oks.key,'n:2');
});

test('reopening after a cancelled variant list fetch retries the list',async()=>{
  const pending=defer();let first=true;
  const h=setup(url=>{
    if(url.includes('variant_id=d3')&&!url.includes('oks_key=')&&first){first=false;return pending.promise;}
    return ok(url.includes('oks_key=')?{...profile,variant_id:url.includes('variant_id=d3')?'d3':'d1'}:list);
  });
  h.eval('syncProfile()');await flush();
  h.context.currentVariant='d3';h.eval('syncProfile()');h.widgetOptions.onClose();
  pending.resolve(ok(list));await flush();
  assert.equal(h.$('profile-panel').hidden,true);
  h.$('profile-open').handlers.click();await flush();
  assert.equal(h.views.at(-1).variant_id,'d3');
  assert.equal(h.$('profile-panel').hidden,false);
});
