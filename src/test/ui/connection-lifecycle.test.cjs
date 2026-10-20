const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const html=fs.readFileSync(path.join(__dirname,'../../main/resources/static/ui/index.html'),'utf8');
const flush=async()=>{for(let i=0;i<8;i++)await new Promise(setImmediate);};
const deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return {promise,resolve};};
const ok=x=>({ok:true,json:async()=>x});
const list={oks:[{key:'n:2',label:'2',connected:true,coordinates:[37,55]},{key:'s:2',label:'2 (строка)',connected:false,coordinates:[37.1,55]}]};
const card={variant_id:'v1',status:'connected',oks:list.oks[0],segments:[]};
function element(){return {hidden:false,disabled:false,children:[],handlers:{},style:{},classList:{add(){},toggle(){}},
 appendChild(x){this.children.push(x);return x;},replaceChildren(...x){this.children=x;},remove(){},setAttribute(){},
 scrollCalls:[],scrollIntoView(options){this.scrollCalls.push({block:options.block,hidden:this.hidden});},
 addEventListener(k,v){this.handlers[k]=v;},set textContent(x){this.text=x;},get textContent(){return this.text||'';}};}
function harness(fetcher,mode='2d'){
 const nodes=new Map(),cards=[],profiles=[],calls=[];let profileOptions,connectionOptions;
 const $=id=>{if(!nodes.has(id))nodes.set(id,element());return nodes.get(id);};
 const ctx=vm.createContext({$,jobId:'job-a',mode,currentVariant:mode==='2d'?'v1':'d1',modeData:{},Promise,Map,Set,Number,encodeURIComponent,
 document:{createElement:element},svg:element(),el:()=>element(),toXY:(...x)=>x,linePts:x=>x,proj:{ready:false},
 HeatnetConnectionCard:{create:o=>{connectionOptions=o;return{setData:x=>cards.push(x),setMessage:x=>cards.push(x),clear(){},highlightSegment(){}};}},
 HeatnetProfile:{create:o=>{profileOptions=o;return{setData:x=>profiles.push(x),setMessage:x=>profiles.push(x),clear(){},highlightSegment(){}};}},
 fetch:async url=>{calls.push(url);return fetcher(url);}});
 vm.runInContext(html.slice(html.indexOf('  // ---------- продольный профиль ----------'),html.indexOf('  // ---------- конец продольного профиля ----------')),ctx);
 return {$,ctx,cards,profiles,calls,get profileOptions(){return profileOptions;},get connectionOptions(){return connectionOptions;},eval:x=>vm.runInContext(x,ctx)};
}
test('base mode has a connection card and selectable unconnected typed OKS without requesting depth',async()=>{
 const h=harness(url=>ok(url.includes('oks_key=')?{...card,status:url.includes('s%3A2')?'unconnected':'connected'}:list));
 h.eval('syncProfile()');await flush();assert.equal(h.cards.at(-1)?.status,'connected');
 assert.equal(h.$('profile-oks').children[1].disabled,false);
 assert.ok(h.calls.every(u=>u.includes('/connection?')));
 h.eval("selectProfileOks('s:2')");await flush();assert.equal(h.cards.at(-1).status,'unconnected');
 assert.equal(h.$('profile-panel').hidden,true);
});
test('late previous card cannot overwrite a newer OKS selection',async()=>{
 const pending=deferred();
 const h=harness(url=>!url.includes('oks_key=')?ok(list):url.includes('n%3A2')?pending.promise:ok({...card,status:'unconnected',oks:list.oks[1]}));
 h.eval('syncProfile()');await flush();h.eval("selectProfileOks('s:2')");await flush();pending.resolve(ok(card));await flush();
 assert.equal(h.cards.filter(x=>x?.oks).at(-1).oks.key,'s:2');
});
test('closing a pending depth profile does not cancel the connection card',async()=>{
 const pending=deferred(),p=deferred();const h=harness(url=>!url.includes('oks_key=')?ok(list):url.includes('/profile?')?p.promise:pending.promise,'depth');
 h.eval('syncProfile()');await flush();h.profileOptions.onClose();pending.resolve(ok(card));p.resolve(ok({...card,variant_id:'d1'}));await flush();
 assert.equal(h.cards.at(-1)?.status,'connected');assert.equal(h.$('profile-panel').hidden,true);
 assert.equal(h.profiles.some(x=>x?.oks),false);
});
test('new input suppresses a pending connection list',async()=>{
 const pending=deferred(),h=harness(()=>pending.promise);h.eval('syncProfile()');h.eval('resetProfile()');h.ctx.jobId='job-b';pending.resolve(ok(list));await flush();
 assert.equal(h.cards.some(x=>x?.oks),false);assert.equal(h.$('profile-controls').hidden,true);
});
test('base to depth preserves the typed selected key but requests a fresh variant',async()=>{
 const h=harness(url=>ok(url.includes('oks_key=')?{...card,variant_id:url.includes('d1')?'d1':'v1'}:list));
 h.eval('syncProfile()');await flush();h.ctx.mode='depth';h.ctx.currentVariant='d1';h.eval('syncProfile()');await flush();
 assert.equal(h.eval('selectedOksKey'),'n:2');assert.equal(h.cards.filter(x=>x?.oks).at(-1)?.variant_id,'d1');
 assert.ok(h.calls.some(u=>u.includes('/profile?variant_id=d1&oks_key=n%3A2')));
});
test('segment action focuses the halfway position along an unequal multi-vertex path',async()=>{
 const d={...card,segments:[{id:'edge',coordinates:[[37,55],[37.01,55],[37.1,55]]}]};
 const h=harness(url=>ok(url.includes('oks_key=')?d:list));h.eval('syncProfile()');await flush();
 h.eval("focusConnectionTarget({kind:'segment',segment_id:'edge'})");
 assert.ok(Math.abs(h.eval('profileMarker[0]')-37.05)<1e-9,'Marker should be at half of path length, not its middle vertex');
});
test('retrying the card respects a profile that the user closed',async()=>{
 const h=harness(url=>ok(url.includes('oks_key=')?{...card,variant_id:'d1'}:list),'depth');
 h.eval('syncProfile()');await flush();h.profileOptions.onClose();
 const before=h.calls.filter(u=>u.includes('/profile?')).length;
 h.connectionOptions.onRetry();await flush();
 assert.equal(h.$('profile-panel').hidden,true);
 assert.equal(h.calls.filter(u=>u.includes('/profile?')).length,before);
});
test('explicitly reopening a cached profile reveals it without scrolling on automatic selection',async()=>{
 const h=harness(url=>ok(url.includes('oks_key=')?{...card,variant_id:'d1'}:list),'depth');
 h.eval('syncProfile()');await flush();
 const panel=h.$('profile-panel');
 assert.deepEqual(panel.scrollCalls,[]);
 h.profileOptions.onClose();
 const requests=h.calls.length;
 h.$('profile-open').handlers.click();
 assert.equal(panel.hidden,false);
 assert.deepEqual(panel.scrollCalls,[{block:'nearest',hidden:false}]);
 assert.equal(h.calls.length,requests,'A cached profile must not be requested again');
});
test('explicitly opening a pending profile reveals its loading state before the response',async()=>{
 const pending=deferred();
 const h=harness(url=>url.includes('/profile?')?pending.promise:
   ok(url.includes('oks_key=')?{...card,variant_id:'d1'}:list),'depth');
 h.eval('syncProfile()');await flush();
 const panel=h.$('profile-panel');
 assert.deepEqual(panel.scrollCalls,[]);
 h.profileOptions.onClose();
 h.$('profile-open').handlers.click();
 assert.equal(panel.hidden,false);
 assert.deepEqual(panel.scrollCalls,[{block:'nearest',hidden:false}]);
 pending.resolve(ok({...card,variant_id:'d1'}));await flush();
 assert.equal(panel.scrollCalls.length,1,'Completing the response must not move the viewport again');
});
test('opening a profile in base mode does not scroll or request a depth profile',async()=>{
 const h=harness(url=>ok(url.includes('oks_key=')?card:list));
 h.eval('syncProfile()');await flush();
 const requests=h.calls.length;
 h.$('profile-open').handlers.click();
 assert.equal(h.$('profile-panel').hidden,true);
 assert.deepEqual(h.$('profile-panel').scrollCalls,[]);
 assert.equal(h.calls.length,requests);
});
