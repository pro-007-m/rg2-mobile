/* Native Android integration. The library and solver stay fully local. */
(() => {
  'use strict';
  const VERSION='1.2.0', RELEASES='https://github.com/pro-007-m/twistforge-mobile/releases/latest';
  const $=selector=>document.querySelector(selector);
  let nativeInfo=null,menuObserver=null,initialized=false;
  function post(type,values={}) {
    if(typeof window.RG2NativePort?.postMessage!=='function')return false;
    window.RG2NativePort.postMessage(JSON.stringify({type,...values}));return true;
  }
  async function copy(text) {
    if(typeof text!=='string')throw new TypeError('Expected clipboard text');
    if(post('clipboard',{text}))return;
    if(typeof originalCopy==='function')return originalCopy(text);
    const input=document.createElement('textarea');input.value=text;input.style.position='fixed';input.style.opacity='0';document.body.append(input);input.select();
    const ok=document.execCommand('copy');input.remove();if(!ok)throw new Error('Clipboard unavailable');
  }
  const originalCopy=navigator.clipboard?.writeText?.bind(navigator.clipboard);
  // All existing copy buttons, including dynamic solver modules, use this adapter.
  if(typeof window.RG2NativePort?.postMessage==='function') {
    try {Object.defineProperty(navigator,'clipboard',{configurable:true,value:{writeText:copy}});} catch {}
  }
  function openExternal(value) {
    const url=new URL(value,location.href);
    if(!['https:','http:','mailto:'].includes(url.protocol))return false;
    if(post('open-external',{url:url.href}))return true;
    return false;
  }
  const platform=window.RG2Platform={version:VERSION,releases:RELEASES,copy,post,
    theme(resolved){post('theme',{resolved});},openExternal,
    mobileSettings(){return `<section class="settings-block mobile-settings"><h2>На телефоне</h2><p>Поверни куб одним пальцем, приближай двумя. Коснись наклейки, чтобы раскрасить её. Системная кнопка «Назад» закрывает меню и изображения, затем возвращает на предыдущий экран.</p><label class="settings-row" for="mobile-awake"><span><strong>Не выключать экран в тренажёрах</strong><small>Таймер и решатель останутся видимыми во время практики.</small></span><input type="checkbox" id="mobile-awake"></label><div class="mobile-data-actions"><button class="action-button" id="mobile-export">Сохранить резервную копию</button><button class="action-button" id="mobile-import">Восстановить из копии</button></div><p>Копия содержит избранное, настройки, результаты и раскраску. Она хранится там, где ты выберешь.</p><button class="action-button" id="mobile-privacy">Конфиденциальность</button></section>`;},
    updates(){return `<section class="settings-block app-updates"><h2>Обновления</h2><p id="update-current-version">TwistForge · Android ${VERSION}</p><p>Обновляй приложение в магазине, из которого оно установлено. Для версии из GitHub новый APK устанавливается поверх старого с сохранением данных.</p><a class="app-release-link" href="${RELEASES}" target="_blank" rel="noopener noreferrer">Открыть выпуски для Android</a></section>`;}
  };
  function awakeEnabled(){try{return localStorage.getItem('rg2.mobile.keepAwake')!=='false';}catch{return true;}}
  function syncAwake(){post('keep-awake',{enabled:awakeEnabled()&&document.visibilityState!=='hidden'&&window.RG2App?.state.route?.type==='tool'});}
  function syncMenu(){
    const expanded=$('#sidebar')?.classList.contains('expanded')||false;
    $('#sidebar-toggle')?.setAttribute('aria-expanded',String(expanded));
    const backdrop=$('#mobile-menu-backdrop');if(backdrop)backdrop.hidden=!expanded;
  }
  function closeMenu(){ $('#sidebar')?.classList.remove('expanded');syncMenu(); }
  function back(){
    if(window.TwistForgeReview?.handleBack?.())return true;
    const fullscreen=document.querySelector('.puz-fullscreen');
    if(fullscreen){fullscreen.classList.remove('puz-fullscreen');fullscreen.dispatchEvent(new Event('rg2:puzzle-fullscreen-change',{bubbles:true}));return true;}
    if(document.fullscreenElement){document.exitFullscreen().catch(()=>{});return true;}
    const dialog=document.querySelector('dialog[open]');if(dialog){dialog.close();return true;}
    if($('#sidebar')?.classList.contains('expanded')){closeMenu();return true;}
    if(/INPUT|TEXTAREA|SELECT/.test(document.activeElement?.tagName||'')){document.activeElement.blur();return true;}
    const app=window.RG2App;if(!app)return false;
    if(app.state.historyIndex>0){history.back();return true;}
    const route=app.state.route;
    if(route?.type==='item'){
      const item=app.items.get(route.id);app.navigate(/^ABOUT(?:-|$)/.test(item?.id||'')||item?.phaseId==='ABOUT'?{type:'settings'}:item?.phaseId==='__EXTRAS__'?{type:'extras'}:{type:'phase',id:item?.phaseId||app.catalog.defaultPhase||'MAIN3X3'},{replace:true});return true;
    }
    if(route?.type==='tool'){app.navigate({type:'tools'},{replace:true});return true;}
    const home=app.catalog.defaultPhase||'MAIN3X3';
    if(route?.type!=='phase'||route.id!==home){
      const parent=route?.type==='phase'?app.catalog.phases.find(phase=>phase.items.some(item=>item.target_phase===route.id)):null;
      app.navigate({type:'phase',id:parent?.id||home},{replace:true});return true;
    }
    return false;
  }
  function exportData(){
    const data={};for(let i=0;i<localStorage.length;i++){const key=localStorage.key(i);if(key?.startsWith('rg2.'))data[key]=localStorage.getItem(key);}
    return JSON.stringify({format:'rg2-backup',version:1,appVersion:VERSION,data},null,2);
  }
  function validateImport(text){
    if(typeof text!=='string'||text.length>4*1024*1024)throw new Error('Копия слишком большая.');
    const value=JSON.parse(text);
    if(value?.format!=='rg2-backup'||value.version!==1||!value.data||typeof value.data!=='object'||Array.isArray(value.data))throw new Error('Это не резервная копия TwistForge.');
    const entries=Object.entries(value.data);
    if(entries.length>300||!entries.every(([key,item])=>/^rg2\.[A-Za-z0-9._-]{1,120}$/.test(key)&&typeof item==='string'&&item.length<=2*1024*1024))throw new Error('В копии есть неподдерживаемые данные.');
    if(typeof window.RG2Storage?.validateEntries!=='function')throw new Error('Не удалось проверить резервную копию. Перезапусти приложение.');
    window.RG2Storage.validateEntries(entries);
    return entries;
  }
  function receiveImport(text){
    try {
      const entries=validateImport(text);
      if(!window.confirm('Заменить сохранённые данные TwistForge данными из этой копии?'))return false;
      const old=[];for(let i=0;i<localStorage.length;i++){const k=localStorage.key(i);if(k?.startsWith('rg2.'))old.push([k,localStorage.getItem(k)]);}
      try {for(const [k]of old)localStorage.removeItem(k);for(const [k,v]of entries)localStorage.setItem(k,v);}
      catch(error){for(const[k]of entries)localStorage.removeItem(k);for(const[k,v]of old)localStorage.setItem(k,v);throw error;}
      location.reload();return true;
    }catch(error){window.RG2App?.toast(error instanceof SyntaxError?'Не удалось прочитать копию.':error.message);return false;}
  }
  function privacy(){
    let d=$('#mobile-privacy-dialog');
    if(!d){d=document.createElement('dialog');d.id='mobile-privacy-dialog';d.className='mobile-dialog';d.innerHTML='<h2>Конфиденциальность</h2><p>TwistForge работает без регистрации, рекламы и аналитики. Библиотека, цвета куба, избранное, результаты и настройки обрабатываются и хранятся на этом устройстве.</p><p>Приложение не запрашивает доступ к камере, микрофону, контактам или геопозиции и само не отправляет данные на сервер. Видео, магазин и GitHub открываются отдельно в браузере. Для них действуют правила соответствующих сайтов.</p><p>Резервная копия создаётся и открывается только по твоей команде через системный выбор файла. Удаление данных приложения в настройках Android очищает локальный профиль; выбранные тобой внешние копии остаются у тебя.</p><form method="dialog"><button class="action-button">Закрыть</button></form>';document.body.append(d);}
    d.showModal();
  }
  async function receivePuzzleImport(text){
    try{
      if(typeof text!=='string'||text.length>8*1024*1024)throw new Error('Файл партии слишком большой.');
      const data=JSON.parse(text);if(data?.schema!=='rg2-puzzle-game'||data.version!==1)throw new Error('Это не файл партии TwistForge.');
      const app=window.RG2App;await app.ready;
      if(app.state.route?.type!=='tool'||app.state.route.id!=='virtual-puzzles')app.navigate({type:'tool',id:'virtual-puzzles'});
      for(let i=0;i<100;i++){const tool=document.querySelector('#tool-host')?.virtualPuzzle;if(tool){await tool.ready;return await tool.receiveImport(text);}await new Promise(resolve=>setTimeout(resolve,50));}
      throw new Error('Не удалось открыть виртуальную головоломку.');
    }catch(error){window.RG2App?.toast(error.message);return false;}
  }
  const mobile=window.RG2Mobile={back,receiveImport,receivePuzzleImport,exportData,validateImport,
    receiveNativeInfo(value){if(value&&typeof value==='object')nativeInfo=value;},get nativeInfo(){return nativeInfo;}
  };
  document.addEventListener('visibilitychange',syncAwake);
  window.addEventListener('rg2:pause',()=>{post('keep-awake',{enabled:false});window.dispatchEvent(new Event('blur'));});
  window.addEventListener('rg2:resume',syncAwake);
  document.addEventListener('rg2:navigation',()=>{closeMenu();syncAwake();const input=$('#mobile-awake');if(input)input.checked=awakeEnabled();});
  document.addEventListener('change',event=>{if(event.target.id==='mobile-awake'){localStorage.setItem('rg2.mobile.keepAwake',JSON.stringify(event.target.checked));syncAwake();}});
  document.addEventListener('click',event=>{
    const target=event.target.closest('button,a');if(!target)return;
    if(target.id==='mobile-menu-close'||target.id==='mobile-menu-backdrop')closeMenu();
    if(target.id==='mobile-export'){
      const text=exportData();if(!post('export-data',{text})){const blob=new Blob([text],{type:'application/json'}),url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download='TwistForge-backup.json';a.click();URL.revokeObjectURL(url);}
    }
    if(target.id==='mobile-import'&&!post('import-data')){
      const file=document.createElement('input');file.type='file';file.accept='application/json,.json';file.addEventListener('change',async()=>{if(file.files[0])receiveImport(await file.files[0].text());});file.click();
    }
    if(target.id==='mobile-privacy')privacy();
    if(target.tagName==='A'&&target.href){try{const url=new URL(target.href);if(url.origin!==location.origin&&openExternal(url.href))event.preventDefault();}catch{}}
  },true);
  function init(){
    if(initialized)return;initialized=true;
    document.body.classList.add('mobile-device');
    menuObserver=new MutationObserver(syncMenu);menuObserver.observe($('#sidebar'),{attributes:true,attributeFilter:['class']});syncMenu();
    post('ready');
    window.RG2App?.ready.then(()=>{syncAwake();const box=$('#mobile-awake');if(box)box.checked=awakeEnabled();});
  }
  window.addEventListener('pagehide',()=>{menuObserver?.disconnect();post('keep-awake',{enabled:false});});
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',init,{once:true});else init();
})();
