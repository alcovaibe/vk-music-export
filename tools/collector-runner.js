/* No requests, storage, form values, HTML or audio URLs. */
void(function(){
  'use strict';
  try {
    alert('VKM 0.5.2');
    if(window.top!==window||!/^https:\/\/(m\.|music\.)?vk\.(ru|com)\/(audio(s[0-9]+)?|music)([/?#]|$)/.test(location.href)){alert('Open VK Music');return}
    if(document.getElementById('vkm-export-panel')){alert('Reload page');return}
    const core=/* CORE */;
    /* SAMPLE */

    const original=location.href;
    const panel=document.createElement('section');panel.id='vkm-export-panel';
    panel.style.cssText='position:fixed;left:0;right:0;bottom:0;z-index:2147483647;padding:8px;background:navy;color:white';
    const status=document.createElement('p');panel.appendChild(status);
    const button=(label,click)=>{const b=document.createElement('button');b.textContent=label;b.style.padding='8px';b.onclick=click;panel.appendChild(b);return b};
    let entries=[],playlists=new Map(),p,scrollable,timer,running=false,samples=0,idle=0,last='',bad=0,ambiguous=false,top=false,reason=-1;
    const show=()=>{start.disabled=running;stop.textContent=running?'\u0421\u0442\u043e\u043f':'X';save.disabled=running||!(entries.length||playlists.size);status.textContent=entries.length+' / '+playlists.size+' | '+(running?'#'+samples:reason<0?'VKM':'E'+reason)};
    const finish=n=>{clearInterval(timer);running=false;reason=Number.isInteger(n)&&n>=0&&n<15?n:11;show()};
    function tick(){
      if(!running)return;
      try {
        if(location.href!==original||!document.contains(scrollable)){finish(4);return}
        p=sample();samples++;

        bad=Math.max(bad,p[3]);ambiguous=ambiguous||p[1].some(t=>t[0]===null);
        entries=core.merge(entries,p[1]);
        for(const a of p[2]){if(!playlists.has(a[0])&&playlists.size===1000){finish(14);return}playlists.set(a[0],a)}
        if(entries.length>10000){entries.length=10000;finish(3);return}
        const state=entries.length+':'+playlists.size+':'+scrollable.scrollTop;
        idle=state===last?idle+1:0;last=state;show();
        if(scrollable.scrollTop+scrollable.clientHeight>=scrollable.scrollHeight-8&&idle>=7){finish(entries.length||playlists.size?0:7);return}
        if(idle>=15){finish(12);return}if(samples>=600){finish(2);return}
        scrollable.scrollTop+=Math.max(240,scrollable.clientHeight*.7);
      }catch(e){finish(e)}
    }
    const start=button('Сбор',()=>{
      if(running)return;
      try {
        entries=[];playlists.clear();samples=idle=bad=0;last='';ambiguous=false;p=sample();
        scrollable=p[0];
        while(scrollable&&!(scrollable.scrollHeight>scrollable.clientHeight+3&&/auto|scroll/.test(getComputedStyle(scrollable).overflowY)))scrollable=scrollable.parentElement;
        scrollable=scrollable||document.scrollingElement;scrollable.scrollTop=0;top=scrollable.scrollTop<=2;
        running=true;show();timer=setInterval(tick,800);
      }catch(e){finish(e)}
    });
    const stop=button('X',()=>running?finish(1):panel.remove());
    const save=button('JSON',()=>{
      if(running||!p||!entries.length&&!playlists.size)return;
      const blob=new Blob([JSON.stringify([2,p[6],p[5],p[4],entries,[...playlists.values()],[reason,samples,bad,ambiguous,top]])],{type:'application/json'});
      if(blob.size>10000000){alert('JSON > 10 MB');return}
      const a=document.createElement('a'),u=URL.createObjectURL(blob);a.href=u;a.download='vk-music-'+Date.now()+'.json';panel.appendChild(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(u),60000);
    });

    show();document.documentElement.appendChild(panel);
  }catch(_){alert('INIT')}
})();
