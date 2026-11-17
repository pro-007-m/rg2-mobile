'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {JSDOM}=require('jsdom');
const read=file=>fs.readFileSync(path.join(__dirname,'../data',file),'utf8');
async function app(){
 const dom=new JSDOM(read('index.html'),{url:'https://appassets.androidplatform.net/assets/www/index.html',runScripts:'outside-only',pretendToBeVisual:true});
 const w=dom.window,messages=[];
 w.matchMedia=()=>({matches:false,addEventListener(){},removeEventListener(){}});
 w.HTMLElement.prototype.scrollIntoView=function(){};
 w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};
 w.HTMLDialogElement.prototype.close=function(){this.open=false;};
 w.RG2NativePort={postMessage(value){messages.push(JSON.parse(value));}};
 w.fetch=async name=>({ok:true,json:async()=>JSON.parse(read(String(name).split('/').pop()))});
 w.eval(read('mobile-storage.js'));w.eval(read('mobile.js'));w.eval(read('algorithms.js'));w.eval(read('app.js'));
 assert.equal(await w.RG2App.ready,true);
 w.document.dispatchEvent(new w.Event('DOMContentLoaded'));
 return{w,dom,messages};
}
test('Android keeps all library data, exposes mobile backup and has no desktop updater',async()=>{
 const a=await app();try{
  assert.equal(a.w.RG2App.phases.size,60);assert.equal(a.w.RG2App.items.size,560);
  a.w.RG2App.navigate({type:'settings'});
  const d=a.w.document;
  assert.equal(d.querySelectorAll('input[name=theme]').length,3);
  assert.ok(d.querySelector('#mobile-export'));assert.ok(d.querySelector('#mobile-import'));assert.ok(d.querySelector('#mobile-awake').checked);
  assert.ok(d.querySelector('#app-version').textContent.includes('Android 1.1.0'));
  assert.equal(d.querySelector('#update-install'),null);
  assert.ok(!a.messages.some(m=>m.type.startsWith('update-')));
 }finally{a.w.dispatchEvent(new a.w.Event('pagehide'));a.dom.window.close();}
});
test('copy uses native clipboard without losing Unicode notation; external links cannot send executable schemes',async()=>{
 const a=await app();try{
  await a.w.navigator.clipboard.writeText("R U R' U' · F2L");
  assert.deepEqual(a.messages.find(m=>m.type==='clipboard'),{type:'clipboard',text:"R U R' U' · F2L"});
  const count=a.messages.length;
  assert.equal(a.w.RG2Platform.openExternal('javascript:alert(1)'),false);
  assert.equal(a.w.RG2Platform.openExternal('file:///storage/foo'),false);
  assert.equal(a.messages.length,count);
  assert.equal(a.w.RG2Platform.openExternal('https://example.org/video'),true);
  assert.deepEqual(a.messages.at(-1),{type:'open-external',url:'https://example.org/video'});
 }finally{a.w.dispatchEvent(new a.w.Event('pagehide'));a.dom.window.close();}
});
test('native back closes overlays before changing routes and returns false only at the root',async()=>{
 const a=await app();try{
  const side=a.w.document.querySelector('#sidebar'),d=a.w.document.querySelector('#image-dialog');
  side.classList.add('expanded');d.showModal();
  assert.equal(a.w.RG2Mobile.back(),true);assert.equal(d.open,false);assert.ok(side.classList.contains('expanded'));
  assert.equal(a.w.RG2Mobile.back(),true);assert.ok(!side.classList.contains('expanded'));
  assert.equal(a.w.RG2Mobile.back(),false);
  a.w.RG2App.navigate({type:'tool',id:'timer'});assert.equal(a.w.RG2Mobile.back(),true);
 }finally{a.w.dispatchEvent(new a.w.Event('pagehide'));a.dom.window.close();}
});
test('backup contains only RG2 storage and validates before any write; cancellation keeps all data',async()=>{
 const a=await app();try{
  a.w.localStorage.setItem('unrelated.secret','must not export');
  a.w.localStorage.setItem('rg2.favorites','["TEST-0"]');
  const json=a.w.RG2Mobile.exportData(),backup=JSON.parse(json);
  assert.equal(backup.format,'rg2-backup');assert.equal(backup.data['rg2.favorites'],'["TEST-0"]');assert.equal(backup.data['unrelated.secret'],undefined);
  const count=a.w.localStorage.length;
  assert.throws(()=>a.w.RG2Mobile.validateImport(JSON.stringify({format:'rg2-backup',version:1,data:{'other.secret':'bad'}})));
  assert.throws(()=>a.w.RG2Mobile.validateImport(JSON.stringify({format:'rg2-backup',version:1,data:{'rg2.x':42}})));
  assert.equal(a.w.localStorage.length,count);
  a.w.confirm=()=>false;assert.equal(a.w.RG2Mobile.receiveImport(json),false);
  assert.equal(a.w.localStorage.getItem('rg2.favorites'),'["TEST-0"]');
 }finally{a.w.dispatchEvent(new a.w.Event('pagehide'));a.dom.window.close();}
});

test('Android back exits the virtual game viewport before changing the route',async()=>{
 const a=await app();try{
  const root=a.w.document.createElement('section');root.className='puz puz-fullscreen';a.w.document.body.append(root);
  const before=a.w.RG2App.state.route;let changed=0;root.addEventListener('rg2:puzzle-fullscreen-change',()=>changed++);
  assert.equal(a.w.RG2Mobile.back(),true);assert.equal(root.classList.contains('puz-fullscreen'),false);
  assert.equal(changed,1);assert.equal(a.w.RG2App.state.route,before);
 }finally{a.w.dispatchEvent(new a.w.Event('pagehide'));a.dom.window.close();}
});
test('native system theme and lifecycle preserve explicit preference and release wake lock',async()=>{
 const a=await app();try{
  a.w.dispatchEvent(new a.w.CustomEvent('rg2:system-theme',{detail:{dark:true}}));
  assert.equal(a.w.RG2App.state.effectiveTheme,'graphite');
  a.w.RG2App.setTheme('atelier');a.w.dispatchEvent(new a.w.CustomEvent('rg2:system-theme',{detail:{dark:true}}));
  assert.equal(a.w.RG2App.state.effectiveTheme,'atelier');
  a.w.RG2App.navigate({type:'tool',id:'timer'});
  assert.equal(a.messages.filter(m=>m.type==='keep-awake').at(-1).enabled,true);
  a.w.dispatchEvent(new a.w.Event('rg2:pause'));
  assert.equal(a.messages.filter(m=>m.type==='keep-awake').at(-1).enabled,false);
 }finally{a.w.dispatchEvent(new a.w.Event('pagehide'));a.dom.window.close();}
});
