'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const storage=require('../data/mobile-storage.js');
const encode=object=>Object.entries(object).map(([key,value])=>[key,key==='rg2.theme'?value:JSON.stringify(value)]);
const solved=Array.from({length:54},(_,index)=>Math.floor(index/9));
const maxim=JSON.parse(fs.readFileSync(path.join(__dirname,'../data/trainer-data.json'),'utf8')).azbuka.alphabets.maxim;

test('complete current desktop settings are accepted byte-for-byte without mutation',()=>{
 const entries=encode({
  'rg2.theme':'graphite','rg2.favorites':['PLL-17','BEGIN-0'],'rg2.readerSize':'large',
  'rg2.focusedAlgorithms':{'item:PLL-17':{id:'alg-0-hash',formula:"R U R' U'"}},
  'rg2.lastRoute':{type:'item',id:'PLL-17'},'rg2.model.image.v1':{contrast:100,saturation:100,brightness:100,gamma:1,sharpness:0,glow:40},
  'rg2.model.neon':false,'rg2.mobile.keepAwake':true,
  'rg2.desktop.timer.v1':{sessions:[{id:'session-1',name:'Моя сессия',createdAt:1000,results:[{id:'result-1',ms:12345,at:2000,penalty:'+2',puzzle:3,scramble:"R U R'"}]}],activeId:'session-1',size:3,inspection:true},
  'rg2.desktop.alphabet.v1':{scheme:'custom',type:'edges',limit:15,custom:maxim},
  'rg2.cfop.input':solved,'rg2.cfop.settings':{crossColor:'auto',autoCrossColor:5,rankMode:'execution',pauseForChoice:true,prepareColl:true,speed:.25}
 });
 const before=JSON.stringify(entries);assert.equal(storage.validateEntries(entries),entries);assert.equal(JSON.stringify(entries),before);
});
test('a source-generated current scramble record is accepted',async()=>{
 const {createScramble}=await import('../data/solver/scramble.mjs');
 const record=createScramble(solved,{rng:()=>.25});
 assert.equal(storage.validateEntries(encode({'rg2.cfop.scramble.v1':record})).length,1);
});
test('incomplete painted drafts and source empty/default stores remain valid',()=>{
 for(const unknown of [255,-1])assert.doesNotThrow(()=>storage.validateEntries(encode({'rg2.cfop.input':solved.map((c,i)=>i%9===4?c:unknown)})));
 assert.doesNotThrow(()=>storage.validateEntries(encode({'rg2.desktop.timer.v1':{},'rg2.desktop.alphabet.v1':{},'rg2.model.image.v1':{},'rg2.cfop.settings':{},'rg2.focusedAlgorithms':{}})));
});
test('favorites that would crash new Set at startup are rejected',()=>{
 for(const value of [{},42,true,'PLL-17',[{}],[null]])assert.throws(()=>storage.validateEntries(encode({'rg2.favorites':value})),/избранное/);
});
test('each known schema rejects incompatible values before restoration',()=>{
 const bad={
  'rg2.readerSize':'huge','rg2.focusedAlgorithms':{'item:PLL-17':{id:42,formula:{}}},'rg2.lastRoute':{type:4},
  'rg2.model.image.v1':{gamma:99},'rg2.model.neon':'false','rg2.mobile.keepAwake':1,
  'rg2.desktop.timer.v1':{sessions:[{id:'s',results:[{id:'r',ms:-1}]}]},
  'rg2.desktop.alphabet.v1':{custom:['А']},'rg2.cfop.input':solved.slice(1),'rg2.cfop.settings':{speed:0},
  'rg2.cfop.scramble.v1':{version:1,moves:['R'],centers:[0,1,2,3,4,5]}
 };
 for(const [key,value]of Object.entries(bad))assert.throws(()=>storage.validateEntries(encode({[key]:value})),undefined,key);
 for(const theme of ['dark','"graphite"','null'])assert.throws(()=>storage.validateEntries([['rg2.theme',theme]]),/тема/);
});
test('timer penalties, result shape and alphabet choices are checked',()=>{
 for(const result of [{id:'r',ms:1,penalty:'skip'},{id:'r',ms:1,puzzle:9},{ms:1},{id:'r',ms:'123'}])assert.throws(()=>storage.validateEntries(encode({'rg2.desktop.timer.v1':{sessions:[{id:'s',results:[result]}]}})),/таймер/);
 assert.throws(()=>storage.validateEntries(encode({'rg2.desktop.alphabet.v1':{scheme:'other'}})),/азбука/);
 assert.throws(()=>storage.validateEntries(encode({'rg2.cfop.input':solved.map(()=>7)})),/раскраска/);
});
test('future safe keys and route names are preserved without normalization',()=>{
 const entries=[['rg2.future.v2',' { "newFeature": true } '],['rg2.future.raw','future-value'],['rg2.lastRoute','{"type":"future-route","id":"future-id"}']];
 const before=JSON.stringify(entries);assert.equal(storage.validateEntries(entries),entries);assert.equal(JSON.stringify(entries),before);
});
test('unsafe keys, duplicate entries and nested prototype pollution are rejected',()=>{
 for(const key of ['other.secret','rg2.__proto__','rg2.constructor.x','rg2.x.prototype','rg2..bad'])assert.throws(()=>storage.validateEntries([[key,'{}']]));
 assert.throws(()=>storage.validateEntries([['rg2.theme','system'],['rg2.theme','graphite']]));
 assert.throws(()=>storage.validateEntries([['rg2.future','{"x":{"__proto__":{"poison":true}}}']]),/дополнительные/);
 assert.throws(()=>storage.validateEntries([['rg2.focusedAlgorithms','{"constructor":{}}']]),/алгоритмы/);
 assert.equal(Object.prototype.poison,undefined);
});
test('size, nesting and malformed JSON are bounded',()=>{
 assert.throws(()=>storage.validateEntries([['rg2.future','x'.repeat(storage.MAX_VALUE_CHARS+1)]]));
 assert.throws(()=>storage.validateEntries(Array.from({length:301},(_,i)=>['rg2.future'+i,''])));
 assert.throws(()=>storage.validateEntries([['rg2.favorites','{broken']]));
 const deep='['.repeat(70)+'0'+']'.repeat(70);assert.throws(()=>storage.validateEntries([['rg2.future',deep]]));
 assert.throws(()=>storage.validateEntries('not entries'));
});
test('UMD also exposes the same browser API',()=>{
 const vm=require('node:vm'),context={};vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../data/mobile-storage.js'),'utf8'),context);
 assert.equal(typeof context.RG2Storage.validateEntries,'function');assert.equal(context.RG2Storage.validateEntries([]).length,0);
});
