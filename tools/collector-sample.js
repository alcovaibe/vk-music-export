/* Passive metadata sample. The caller has validated the page origin. */
function sample() {
  const q=(s,r=document)=>r.querySelector(s);
  const all=(s,r)=>[...r.querySelectorAll(s)];
  const clean=s=>{s=String(s||'').replace(/\s+/g,' ').trim();if(s.length>500)throw 13;return s};
  const text=(r,s)=>clean(q(s,r)?.textContent);
  const id=s=>{const m=decodeURIComponent(s).match(/(?:audio_playlist|\/music\/playlist\/)(-?\d+)_(\d+)/);return m?'playlist:'+m[1]+':'+m[2]:null};
  if(q('input[type=password],#captcha,.Captcha,[data-testid=captcha]'))throw 5;
  let root=q('.audio_layer,.audio_playlist_layer,.audio_page__audio_rows,.audio_page__audios,#audio_list');
  const modern='[class*=vkitAudioRow__root]';
  const headers=all('[data-testid=MusicPlaylistTracks_Header]',document);
  if(!root&&headers.length===1) {
    for(let p=headers[0].parentElement;p&&p!==document.body;p=p.parentElement)if(q(modern,p)){root=p;break;}
  }
  const catalog=q('.audio_page__playlists,#audio_playlists');
  if(!root&&!catalog)throw 6;
  let bad=0;
  const tracks=root?all('.audio_row,.audio_item,'+modern,root).flatMap((r,i)=>{
    const title=text(r,'.audio_row__title_inner')||text(r,'.audio_row__title,.ai_title,[data-testid=MusicTrackRow_Title],[class*=vkitAudioRowInfo__header]');
    const artist=text(r,'[class*=audio_row__performer],.ai_artist,span[class*=vkitAudioRowInfo__text]');
    if(!title||!artist){bad++;return []}
    const p=r.dataset.index??r.dataset.position;
    const raw=r.dataset.fullId||'';
    return [[/^\d{1,9}$/.test(p??'')?Number(p):null,title,artist,/^-?\d+_\d+$/.test(raw)?raw:null]];
  }):[];
  if(tracks.some(t=>t[0]===null))tracks.forEach(t=>t[0]=null);
  const playlists=[];
  if(catalog)for(const a of all('a[href]',catalog))try{
    const u=new URL(a.href),key=id(u.href);
    if(u.origin===location.origin&&key)playlists.push([key,clean(a.title||a.textContent)||key]);
  }catch(e){if(e===13)throw e}
  const count=root?text(root,'.audio_page__count,.audio_playlist__count,[data-testid=MusicPlaylistTracks_Header]'):'';
  return [root,tracks,playlists,bad,count,
    text(document,'.audio_playlist__title,h1'),id(location.href)||'my-music'];
}
