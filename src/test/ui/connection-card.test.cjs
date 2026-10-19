const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const script = path.join(__dirname, '../../main/resources/static/ui/connection-card.js');
function element(tag) {
  const n={tag,attributes:{},children:[],handlers:{},hidden:false,ownText:'',
    setAttribute(k,v){this.attributes[k]=String(v);}, appendChild(c){this.children.push(c);return c;},
    replaceChildren(...c){this.children=c;this.ownText='';}, addEventListener(k,v){this.handlers[k]=v;},
    set textContent(v){this.ownText=String(v);this.children=[];},
    get textContent(){return this.ownText+' '+this.children.map(c=>c.textContent).join(' ');},
    set innerHTML(v){throw Error('Untrusted card text must not be HTML');}};
  n.classList={add(){},toggle(k,v){n.attributes[k]=v;}};return n;
}
const all=n=>[n,...n.children.flatMap(all)];
function harness(){
  assert.ok(fs.existsSync(script),'Connection card widget must exist');
  const container=element('section'),targets=[],retries=[];
  const ctx=vm.createContext({window:{},document:{createElement:element},Intl});
  vm.runInContext(fs.readFileSync(script,'utf8'),ctx);
  const card=ctx.window.HeatnetConnectionCard.create({container,onTarget:x=>targets.push(x),onRetry:()=>retries.push(true)});
  return {container,card,targets,retries};
}
function data(){return {variant_id:'v1',status:'connected',oks:{key:'n:2',label:'2',flow_tph:0},
 source:{key:'n:10',label:'10',kind:'existing_chamber',coordinates:[37,55]},
 route_summary:{total_length:100,shared_length:60,dedicated_length:40,special_length:12,internal_length:5},
 diameters:[80,200],entry:{status:'available',wall_label:'Восточная стена',coordinates:[37.1,55],wall_coordinates:[[37.1,55],[37.1,55.1]],internal_length:5},
 segments:[{id:'v1_net_1',from:0,to:60,diameter:200,flow_tph:20,shared:true,usage_count:2}],
 costs:{path_segment_cost:1000,shared_segment_cost:600,dedicated_segment_cost:400,note:'Без камер и врезок.'},
 explanations:[{kind:'shared',text:'Общий участок обслуживает 2 ОКС.',target:{kind:'segment',segment_id:'v1_net_1'}}],notes:[]};}
test('card shows factual connected route and zero load without claiming a standalone connection price',()=>{
 const h=harness();h.card.setData(data());const text=h.container.textContent;
 assert.match(text,/ОКС 2/);assert.match(text,/0[,.]00.*т\/ч/);assert.match(text,/100[,.]00/);
 assert.match(text,/Общий участок обслуживает 2 ОКС/);assert.match(text,/не.*цен[а-я]* подключения/i);
 assert.match(text,/без камер и врезок/i);assert.doesNotMatch(text,/оптимальн|дешевле альтернатив/);
});
test('source, entry and explanation actions emit map targets',()=>{
 const h=harness();h.card.setData(data());
 for(const kind of ['source','entry','segment']){
   const button=all(h.container).find(n=>n.attributes['data-connection-target']===kind);
   assert.ok(button,kind);button.handlers.click();assert.equal(h.targets.at(-1).kind,kind);
 }
 assert.equal(h.targets.at(-1).segment_id,'v1_net_1');
});
test('unconnected replaces old route with status and no invented source or path',()=>{
 const h=harness();h.card.setData(data());h.card.setData({status:'unconnected',oks:{label:'7',flow_tph:100},message:'Нет допустимой ветви',segments:[]});
 assert.match(h.container.textContent,/Нет допустимой ветви/);assert.doesNotMatch(h.container.textContent,/Восточная|Общий участок/);
 assert.equal(all(h.container).filter(n=>n.attributes['data-connection-target']).length,0);
});
test('unsafe labels remain text and unknown numbers are never displayed as zero or NaN',()=>{
 const h=harness(),d=data();d.oks.label='<img src=x onerror=alert(1)>';d.oks.flow_tph=null;
 d.route_summary.total_length=NaN;d.explanations[0].text='<script>alert(1)</script>';h.card.setData(d);
 assert.match(h.container.textContent,/<img src=x/);assert.match(h.container.textContent,/<script>/);
 assert.doesNotMatch(h.container.textContent,/NaN|undefined/);assert.match(h.container.textContent,/—/);
});
test('ambiguous wall remains an explanation and cannot focus a fabricated wall',()=>{
 const h=harness(),d=data();d.entry={status:'ambiguous',message:'Несколько внешних стен'};h.card.setData(d);
 assert.match(h.container.textContent,/Несколько внешних стен/);
 assert.equal(all(h.container).some(n=>n.attributes['data-connection-target']==='entry'),false);
});
test('load failure offers a retry and map hover highlights the corresponding path section',()=>{
 const h=harness();h.card.setMessage('Ошибка связи',true);
 all(h.container).find(n=>n.tag==='button').handlers.click();assert.equal(h.retries.length,1);
 h.card.setData(data());h.card.highlightSegment('v1_net_1');
 assert.equal(all(h.container).find(n=>n.attributes['data-connection-segment']==='v1_net_1').attributes.active,true);
});
test('new tie-in has an explicit chamber label and unavailable connections retain honest notes',()=>{
 const h=harness(),d=data();d.source.kind='new_chamber_tie';h.card.setData(d);
 assert.match(h.container.textContent,/новая камера на существующей трубе/);
 h.card.setData({status:'unconnected',oks:{label:'8'},message:'Нет подключения',notes:['Это не доказательство невозможности подключения.']});
 assert.match(h.container.textContent,/не доказательство невозможности/);
});
