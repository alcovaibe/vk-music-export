/* User-started, metadata-only collector. No fetch, credentials, HTML, or storage access. */
void (function () {
  'use strict';
  alert('VKM Export 0.5.1: код запущен. Нажми ОК, чтобы открыть панель сбора.');
  try {
  if (window.top !== window) {alert('VKM Export: запусти закладку в основном окне страницы.');return;}
  const hosts=['vk.ru','vk.com','m.vk.ru','m.vk.com','music.vk.ru','music.vk.com'];
  if(location.protocol!=='https:' || !hosts.includes(location.hostname) || !/^\/(audio(?:s[0-9]+)?|music)(\/|$)/.test(location.pathname)) {
    alert('Открой в Chrome раздел «Моя музыка» или отдельный плейлист ВК и снова запусти закладку.');return;
  }
  if(document.getElementById('vkm-export-panel')) {alert('VKM Export: панель уже создана. Перезагрузи страницу и запусти закладку снова.');return;}
  const core=/* CORE */;
  const sample=()=>JSON.parse(/* EXTRACTOR */);
  const original=location.href, sourceId=(()=>{
    try {const m=decodeURIComponent(original).match(/(?:audio_playlist|\/music\/playlist\/)(-?\d+)_(\d+)/);return m?'playlist:'+m[1]+':'+m[2]:'my-music';}catch(_){return null;}
  })();
  if(!sourceId) {alert('VKM Export: не удалось определить открытый список (INVALID_SOURCE).');return;}
  const panel=document.createElement('section');panel.id='vkm-export-panel';
  Object.assign(panel.style,{position:'fixed',left:'8px',right:'8px',bottom:'8px',zIndex:'2147483647',padding:'14px',background:'#202337',color:'white',borderRadius:'16px',font:'14px sans-serif',boxShadow:'0 4px 24px #0008',maxHeight:'45vh',overflow:'auto'});
  const status=document.createElement('p');status.textContent='VKM Export · Открытый список. Не закрывай вкладку во время сбора.';panel.appendChild(status);
  const button=(label,fn)=>{const b=document.createElement('button');b.textContent=label;b.type='button';Object.assign(b.style,{margin:'4px',padding:'10px',borderRadius:'10px',background:'#eeeaff',color:'#202337',border:'0',font:'14px sans-serif'});b.addEventListener('click',fn);panel.appendChild(b);return b;};
  let entries=[],playlists=new Map(),expected=null,title='',running=false,timer=null,samples=0,idle=0,lastScroll=-1,unparsed=0,ambiguous=false,startedAtTop=false,reason='NOT_STARTED',lastCount=0,scrollable=null;
  const allowed=new Set(['BOTTOM_STABLE','USER_STOPPED','SAMPLE_LIMIT','ENTRY_LIMIT','PAGE_CHANGED','LOGIN_OR_CAPTCHA','NOT_MUSIC_PAGE','UNSUPPORTED_LAYOUT','NO_SUPPORTED_ROWS','LIST_CHANGED','POSITION_MODE_CHANGED','VIRTUALIZED_WITHOUT_POSITIONS','SCRIPT_ERROR','SCROLL_STALLED','METADATA_LIMIT','CATALOG_LIMIT']);
  function finish(code) {
    clearTimeout(timer);running=false;reason=allowed.has(code)?code:'SCRIPT_ERROR';start.disabled=false;stop.disabled=true;save.disabled=!(entries.length||playlists.size);
    const count=entries.length;status.textContent='VKM Export · '+count+' треков · '+playlists.size+' плейлистов. '+
      (reason==='BOTTOM_STABLE'?'Прокрутка завершена; полнота не гарантирована.':'Сбор остановлен ('+reason+'). Сохрани доступный результат.');
  }
  function resolveScroll() {
    let e=document.querySelector('.audio_layer, .audio_playlist_layer, .audio_page__audio_rows, .audio_page__audios, #audio_list, [data-testid="audio-list"]');
    while(e && !(e.scrollHeight>e.clientHeight+3 && /auto|scroll/.test(getComputedStyle(e).overflowY))) e=e.parentElement;
    return e||document.scrollingElement;
  }
  function tick() {
    if(!running)return;
    try {
      if(location.href!==original){finish('PAGE_CHANGED');return;}
      const p=sample();samples++;
      if(p.error){finish(p.error);return;}
      if(!p.scoped && !p.playlistsScoped){finish('UNSUPPORTED_LAYOUT');return;}
      if((p.sourceId||'my-music')!==sourceId){finish('PAGE_CHANGED');return;}
      if(String(p.sourceTitle||'').length>500 || (p.scoped && p.tracks.some(t=>String(t.title).length>500||String(t.artist).length>500)) || p.playlists.some(t=>String(t.title).length>500)){finish('METADATA_LIMIT');return;}
      title=String(p.sourceTitle|| (sourceId==='my-music'?'Моя музыка':sourceId));
      if(Number.isSafeInteger(p.expectedCount)&&p.expectedCount>=0&&p.expectedCount<=1000000)expected=p.expectedCount;
      if(p.scoped){unparsed=Math.max(unparsed,p.unparsedRows||0);ambiguous=ambiguous||!!p.ambiguousPositions;}
      entries=core.merge(entries,(p.scoped?p.tracks:[]).map(t=>({key:t.key,title:String(t.title),artist:String(t.artist),vkId:t.vkId||null})));
      for(const link of p.playlists) {if(!playlists.has(link.id)&&playlists.size>=1000){finish('CATALOG_LIMIT');return;}playlists.set(link.id,{id:link.id,title:String(link.title)});}
      if(entries.length>10000){entries=entries.slice(0,10000);finish('ENTRY_LIMIT');return;}
      if(!scrollable || !document.contains(scrollable)){finish('PAGE_CHANGED');return;}
      const bottom=scrollable.scrollTop+scrollable.clientHeight>=scrollable.scrollHeight-8;
      const count=entries.length+playlists.size;
      idle=count===lastCount && scrollable.scrollTop===lastScroll?idle+1:0;
      lastCount=count;lastScroll=scrollable.scrollTop;
      status.textContent='VKM Export · '+entries.length+' треков · '+playlists.size+' плейлистов · проход '+samples+'. Не закрывай вкладку.';
      if(bottom&&idle>=7){finish(entries.length||playlists.size?'BOTTOM_STABLE':'NO_SUPPORTED_ROWS');return;}
      if(idle>=15){finish('SCROLL_STALLED');return;}
      if(samples>=600){finish('SAMPLE_LIMIT');return;}
      scrollable.scrollTop+=Math.max(240,scrollable.clientHeight*0.7);
      timer=setTimeout(tick,800);
    }catch(e){finish(allowed.has(e&&e.message)?e.message:'SCRIPT_ERROR');}
  }
  const start=button('Начать сбор',()=>{
    if(running)return;
    entries=[];playlists=new Map();expected=null;samples=0;idle=0;lastCount=0;lastScroll=-1;unparsed=0;ambiguous=false;reason='RUNNING';
    scrollable=resolveScroll();if(scrollable)scrollable.scrollTop=0;startedAtTop=!!scrollable && scrollable.scrollTop<=2;
    running=true;start.disabled=true;stop.disabled=false;save.disabled=true;timer=setTimeout(tick,400);
  });
  const stop=button('Остановить',()=>finish('USER_STOPPED'));stop.disabled=true;
  const save=button('Сохранить JSON',()=>{
    if(running||(!entries.length&&!playlists.size))return;
    const payload={kind:'vk_music_chrome_export',schemaVersion:1,source:{id:sourceId,title:title||sourceId,tracks:entries.map((t,i)=>({...t,key:/^pos:\d{1,9}$/.test(t.key)?t.key:'capture:'+i})),expectedCount:expected},playlists:[...playlists.values()],capture:{reason,samples,unparsedRows:unparsed,ambiguousPositions:ambiguous,startedAtTop}};
    const blob=new Blob([JSON.stringify(payload)],{type:'application/json'});
    if(blob.size>10000000){status.textContent='Файл превышает 10 МБ. Сохрани меньший список отдельно.';return;}
    const url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download='vk-music-chrome-'+Date.now()+'.json';panel.appendChild(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),60000);
    status.textContent='JSON передан Chrome для скачивания. Вернись в VK Music Export и выбери «Импорт JSON».';
  });save.disabled=true;
  button('Закрыть',()=>{if(running&&!confirm('Остановить сбор без сохранения?'))return;clearTimeout(timer);running=false;panel.remove();});
  document.documentElement.appendChild(panel);
  } catch (error) {
    const code=['TypeError','ReferenceError','SecurityError','SyntaxError','RangeError'].includes(error&&error.name)?error.name:'OTHER';
    alert('VKM Export: панель не создана (INIT_'+code+'). Сообщи этот код разработчику.');
  }
})();
