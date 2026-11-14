/* Validate backup values before a single localStorage entry is replaced.
   Values remain byte-for-byte unchanged; unknown safe RG2 keys are preserved. */
(function(root,factory){
  if(typeof module==='object'&&module.exports)module.exports=factory();
  else root.RG2Storage=factory();
})(typeof globalThis!=='undefined'?globalThis:this,function(){
  'use strict';
  const own=(value,key)=>Object.prototype.hasOwnProperty.call(value,key);
  const isObject=value=>value!==null&&typeof value==='object'&&!Array.isArray(value);
  const finite=value=>typeof value==='number'&&Number.isFinite(value);
  const numberIn=(value,min,max)=>finite(value)&&value>=min&&value<=max;
  const integerIn=(value,min,max)=>Number.isInteger(value)&&value>=min&&value<=max;
  const text=(value,max=4096,empty=true)=>typeof value==='string'&&value.length<=max&&(empty||value.length>0)&&!/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/.test(value);
  const id=value=>text(value,512,false);
  const color=value=>integerIn(value,0,5);
  const optional=(object,key,check)=>!own(object,key)||check(object[key]);
  const allOptional=(object,rules)=>isObject(object)&&Object.entries(rules).every(([key,check])=>optional(object,key,check));
  const enums=(...values)=>value=>values.includes(value);
  const forbidden=new Set(['__proto__','prototype','constructor']);
  const MAX_ENTRIES=300,MAX_VALUE_CHARS=2*1024*1024,MAX_TOTAL_CHARS=4*1024*1024;
  function fail(label){throw new Error('В копии некорректные данные: '+label+'.');}
  function safeTree(value,label,depth=0,budget={nodes:0}){
    if(depth>64||++budget.nodes>1000000)fail(label);
    if(value&&typeof value==='object')for(const key of Object.keys(value)){
      if(forbidden.has(key))fail(label);safeTree(value[key],label,depth+1,budget);
    }
    return value;
  }
  function json(raw,label){try{return safeTree(JSON.parse(raw),label);}catch(error){if(error.message?.startsWith('В копии'))throw error;fail(label);}}
  const arrayOf=(check,max=100000)=>value=>Array.isArray(value)&&value.length<=max&&value.every(check);
  const draftCube=value=>Array.isArray(value)&&value.length===54&&value.every(color=>color===-1||color===255||integerIn(color,0,5));
  const completeCube=value=>Array.isArray(value)&&value.length===54&&value.every(color);
  const imageRules={contrast:value=>numberIn(value,70,150),saturation:value=>numberIn(value,0,180),brightness:value=>numberIn(value,70,130),gamma:value=>numberIn(value,.7,1.3),sharpness:value=>numberIn(value,0,100),glow:value=>numberIn(value,0,100)};
  function timer(value){
    return allOptional(value,{
      size:value=>integerIn(value,2,7),inspection:value=>typeof value==='boolean',activeId:id,
      sessions:arrayOf(session=>isObject(session)&&id(session.id)&&Array.isArray(session.results)&&
        optional(session,'name',value=>text(value,4096))&&optional(session,'createdAt',value=>numberIn(value,0,Number.MAX_SAFE_INTEGER))&&
        arrayOf(result=>isObject(result)&&id(result.id)&&numberIn(result.ms,0,Number.MAX_SAFE_INTEGER)&&
          optional(result,'penalty',enums('','+2','DNF'))&&optional(result,'at',value=>numberIn(value,0,Number.MAX_SAFE_INTEGER))&&
          optional(result,'puzzle',value=>integerIn(value,2,7))&&optional(result,'scramble',value=>text(value,65536)))(session.results),10000)
    });
  }
  function alphabet(value){
    return allOptional(value,{scheme:enums('maxim','anton','custom'),type:enums('both','edges','corners'),limit:enums(0,5,10,15,30),custom:value=>Array.isArray(value)&&value.length===54&&value.every(letter=>text(letter,16))});
  }
  function focused(value){
    return isObject(value)&&Object.keys(value).length<=10000&&Object.entries(value).every(([key,entry])=>id(key)&&
      isObject(entry)&&id(entry.id)&&text(entry.formula,65536));
  }
  function route(value){
    // Future route names remain safe: the app normalizes unknown types to home.
    return isObject(value)&&id(value.type)&&optional(value,'id',id)&&optional(value,'q',value=>text(value,65536))&&optional(value,'scramble',value=>text(value,65536));
  }
  function solverSettings(value){
    return allOptional(value,{crossColor:value=>value==='auto'||color(value),autoCrossColor:color,rankMode:enums('execution','htm'),pauseForChoice:value=>typeof value==='boolean',prepareColl:value=>typeof value==='boolean',speed:value=>numberIn(value,.25,4)});
  }
  function scramble(value){
    if(!isObject(value)||value.version!==1||!Array.isArray(value.moves)||value.moves.length!==20||
      !value.moves.every(move=>typeof move==='string'&&/^[URFDLB](?:2|')?$/.test(move))||
      !Array.isArray(value.centers)||value.centers.length!==6||!value.centers.every(color)||new Set(value.centers).size!==6)return false;
    const hash=value=>typeof value==='string'&&/^[A-F]{54}$/.test(value);
    return optional(value,'state',completeCube)&&optional(value,'inputHash',hash)&&optional(value,'hash',hash)&&
      optional(value,'orientation',value=>allOptional(value,{U:color,R:color,F:color,D:color,L:color,B:color}));
  }
  const validators=new Map([
    ['rg2.favorites',{label:'избранное',check:arrayOf(id)}],
    ['rg2.readerSize',{label:'размер текста',check:enums('small','normal','large')}],
    ['rg2.focusedAlgorithms',{label:'выбранные алгоритмы',check:focused}],
    ['rg2.lastRoute',{label:'последний раздел',check:route}],
    ['rg2.model.image.v1',{label:'изображение куба',check:value=>allOptional(value,imageRules)}],
    ['rg2.model.neon',{label:'свечение куба',check:value=>typeof value==='boolean'}],
    ['rg2.mobile.keepAwake',{label:'настройка экрана',check:value=>typeof value==='boolean'}],
    ['rg2.desktop.timer.v1',{label:'таймер',check:timer}],
    ['rg2.desktop.alphabet.v1',{label:'азбука для слепой сборки',check:alphabet}],
    ['rg2.cfop.input',{label:'раскраска куба',check:draftCube}],
    ['rg2.cfop.settings',{label:'настройки решателя',check:solverSettings}],
    ['rg2.cfop.scramble.v1',{label:'скрамбл решателя',check:scramble}]
  ]);
  function validateEntries(entries){
    if(!Array.isArray(entries)||entries.length>MAX_ENTRIES)fail('содержимое копии');
    const seen=new Set();let total=0;
    for(const entry of entries){
      if(!Array.isArray(entry)||entry.length!==2)fail('содержимое копии');
      const [key,raw]=entry;
      if(typeof key!=='string'||!/^rg2\.[A-Za-z0-9][A-Za-z0-9._-]{0,119}$/.test(key)||key.split('.').some(part=>!part||forbidden.has(part))||seen.has(key))fail('ключи RG2');
      if(typeof raw!=='string'||raw.length>MAX_VALUE_CHARS||(total+=key.length+raw.length)>MAX_TOTAL_CHARS)fail('размер копии');
      seen.add(key);
      if(key==='rg2.theme') {if(!['system','atelier','graphite'].includes(raw))fail('тема оформления');continue;}
      const validator=validators.get(key);
      if(validator){if(!validator.check(json(raw,validator.label)))fail(validator.label);}
      else if(/^[\s]*[\[{]/.test(raw)){
        // Unknown keys may use raw strings, but a JSON object must not hide
        // prototype pollution or an excessively nested structure for future code.
        try{safeTree(JSON.parse(raw),'дополнительные данные RG2');}catch(error){if(error.message?.startsWith('В копии'))throw error;}
      }
    }
    return entries;
  }
  return Object.freeze({validateEntries,knownKeys:Object.freeze(['rg2.theme',...validators.keys()]),MAX_ENTRIES,MAX_VALUE_CHARS,MAX_TOTAL_CHARS});
});
