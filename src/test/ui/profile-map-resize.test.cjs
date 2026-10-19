const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const html=fs.readFileSync(path.join(__dirname,'../../main/resources/static/ui/index.html'),'utf8');
test('opening and closing profile keeps map scale and center with a matching viewport aspect',()=>{
  const marker='  // ---------- размер карты при открытии профиля ----------';
  assert.ok(html.includes(marker),'Map must handle the resized viewport when the profile opens');
  let callback;
  const context=vm.createContext({svg:{clientWidth:1000,clientHeight:800},
    vb:{x:20,y:30,w:1000,h:800},cancelFly(){},applyVb(){},
    ResizeObserver:class{constructor(fn){callback=fn;}observe(){}}});
  vm.runInContext(html.slice(html.indexOf(marker),html.indexOf('  // ---------- рисование объекта-ограничения')),context);
  context.svg.clientHeight=400;callback();
  assert.equal(context.vb.w/context.vb.h,2.5);
  assert.equal(context.vb.x+context.vb.w/2,520);
  assert.equal(context.vb.y+context.vb.h/2,430);
  context.svg.clientHeight=800;callback();
  assert.equal(context.vb.x,20);assert.equal(context.vb.y,30);
  assert.equal(context.vb.w,1000);assert.equal(context.vb.h,800);
});
