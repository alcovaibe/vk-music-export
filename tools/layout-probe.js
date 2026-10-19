/* Fixed vocabulary only: no text, HTML, arbitrary attribute values or URLs. */
void(function(){
 'use strict';
 try {
  if(window.top!==window||!/^https:\/\/(m\.|music\.)?vk\.(ru|com)\/(audio(s[0-9]+)?|music)([/?#]|$)/.test(location.href)){alert('Open VK Music');return}
  if(document.getElementById('vkm-layout-panel')){alert('Reload page');return}
  alert('VKM layout 1');
  const features={
   legacy_rows:'.audio_row',mobile_rows:'.audio_item',modern_rows:'[class*=vkitAudioRow__root]',
   legacy_root:'.audio_layer,.audio_playlist_layer,.audio_page__audio_rows,.audio_page__audios,#audio_list',
   mobile_search:'#au_search_items',mobile_snippet:'.audioPlaylistSnippet__list',mobile_playlist:'.audioPlaylist__list',
   mobile_page:'.AudioPage,.audioPage,.AudioPage__audios',
   legacy_catalog:'.audio_page__playlists,#audio_playlists',mobile_catalog:'.audioPlaylistsPage__item',
   modern_header:'[data-testid=MusicPlaylistTracks_Header]',modern_title:'[data-testid=MusicTrackRow_Title]',
   legacy_title:'.audio_row__title_inner,.audio_row__title',mobile_title:'.ai_title',
   mobile_artist:'.ai_artist',legacy_artist:'[class*=audio_row__performer]',modern_artist:'span[class*=vkitAudioRowInfo__text]',
   mobile_body:'.ai_body,.ai_info',playlist_title:'.audio_playlist__title,.audioPlaylist__title',
   audio_class:'[class*=audio],[class*=Audio]',music_class:'[class*=music],[class*=Music]',
   track_class:'[class*=track],[class*=Track]',playlist_class:'[class*=playlist],[class*=Playlist]',
   audio_testid:'[data-testid*=Audio],[data-testid*=audio]',music_testid:'[data-testid*=Music],[data-testid*=music]',
   track_testid:'[data-testid*=Track],[data-testid*=track]',playlist_testid:'[data-testid*=Playlist],[data-testid*=playlist]',
   full_id:'[data-full-id]',audio_data:'[data-audio]',index:'[data-index]',position:'[data-position]',
   list_role:'[role=list],[role=listbox]',item_role:'[role=listitem],[role=option]',main:'main,[role=main]',
   headings:'h1,h2,h3,[role=heading]',frames:'iframe',shadow_templates:'template[shadowrootmode]'
  };
  const keys=Object.keys(features),tagNames='HTML BODY MAIN SECTION ARTICLE ASIDE NAV HEADER FOOTER DIV SPAN P UL OL LI A BUTTON H1 H2 H3 H4 IMG INPUT FORM IFRAME SVG'.split(' ');
  const cap=n=>Math.min(n,10000);
  const describe=e=>({tag:tagNames.includes(e.tagName)?e.tagName:'OTHER',features:keys.filter(k=>e.matches(features[k])),children:cap(e.childElementCount)});
  const capture=()=>{
   const counts={},seeds=new Set();let limited=false;
   for(const k of keys){const nodes=document.querySelectorAll(features[k]);counts[k]=cap(nodes.length);if(nodes.length>10000)limited=true;
    // At most twelve samples; rows/titles/headers precede broad features.
    if(seeds.size<12)for(let i=0;i<Math.min(nodes.length,3)&&seeds.size<12;i++)seeds.add(nodes[i]);
   }
   const chains=[...seeds].map(n=>{const path=[];for(let i=0;n&&i<8;i++,n=n.parentElement)path.push(describe(n));return path});
   const tags={};for(const t of tagNames)tags[t]=cap(document.getElementsByTagName(t).length);
   return {kind:'vkm-layout',schema:1,probe_version:1,collector_version:'0.5.2',
    host_kind:location.hostname.startsWith('m.')?'mobile':location.hostname.startsWith('music.')?'music':'desktop',
    route_kind:/audio_playlist|\/music\/playlist\//.test(location.pathname+location.search)?'playlist':/^\/audios[0-9]+/.test(location.pathname)?'user_music':'music',
    ready_state:['loading','interactive','complete'].includes(document.readyState)?document.readyState:'other',
    counts,tags,chains,limited,sample_limit:12,ancestor_limit:8,
    scope:'Light DOM only; fixed selector counts and sampled ancestry, no page content. Counts do not establish playlist scope or completeness.'};
  };
  const panel=document.createElement('section');panel.id='vkm-layout-panel';
  panel.style.cssText='position:fixed;left:0;right:0;bottom:0;z-index:2147483647;padding:16px;background:#222;color:white';
  const button=(label,click)=>{const b=document.createElement('button');b.textContent=label;b.style.padding='12px';b.onclick=click;panel.appendChild(b)};
  button('Скачать диагностику',()=>{try{
   const blob=new Blob([JSON.stringify(capture())],{type:'application/json'});
   if(blob.size>100000){alert('LIMIT');return}
   const u=URL.createObjectURL(blob),a=document.createElement('a');a.href=u;a.download='vk-layout.json';panel.appendChild(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(u),60000);
  }catch(_){alert('PROBE_ERROR')}});
  button('X',()=>panel.remove());document.documentElement.appendChild(panel);
 }catch(_){alert('PROBE_INIT')}
})();
