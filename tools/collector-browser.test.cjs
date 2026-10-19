const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs');
const {chromium}=require('playwright-core');
const urlCode=fs.readFileSync('app/src/main/assets/vk-chrome-bookmarklet.url','utf8');
const reasons='BOTTOM_STABLE USER_STOPPED SAMPLE_LIMIT ENTRY_LIMIT PAGE_CHANGED LOGIN_OR_CAPTCHA UNSUPPORTED_LAYOUT NO_SUPPORTED_ROWS LIST_CHANGED POSITION_MODE_CHANGED VIRTUALIZED_WITHOUT_POSITIONS SCRIPT_ERROR SCROLL_STALLED METADATA_LIMIT CATALOG_LIMIT'.split(' ');
function decoded(raw){
 const p=JSON.parse(raw);assert.equal(p[0],2);assert.equal(p.length,7);
 return {source:{id:p[1],title:p[2],tracks:p[4].map((t,i)=>({key:t[0]===null?'capture:'+i:'pos:'+t[0],title:t[1],artist:t[2],vkId:t[3]}))},playlists:p[5].map(t=>({id:t[0],title:t[1]})),capture:{reason:reasons[p[6][0]]}};
}
const row=(n)=>`<div class="audio_row" data-full-id="1_1" data-index="${n}"><b class="audio_row__title">Песня 🎵</b><span class="audio_row__performer">Артист</span></div>`;
async function setup(html,url='https://vk.ru/audio',failInit=false) {
 const browser=await chromium.launch({executablePath:process.env.CHROME_BIN||'/usr/bin/google-chrome',headless:true,args:['--no-sandbox']});
 const page=await browser.newPage({acceptDownloads:true});
 await page.route('**/*',r=>r.fulfill({status:200,contentType:'text/html',headers:{'content-security-policy':"default-src 'none'; style-src 'unsafe-inline'; script-src 'none'"},body:'<meta charset="UTF-8">'+html}));
 await page.goto(url);
 await page.evaluate(()=>{
  Object.defineProperty(document,'cookie',{get(){throw Error('Cookie read forbidden')}});
  window.fetch=()=>{throw Error('Fetch forbidden')};
  Object.defineProperty(window,'localStorage',{get(){throw Error('Storage forbidden')}});
 });
 if(failInit)await page.evaluate(()=>{
  const create=document.createElement.bind(document);
  document.createElement=(...args)=>{if(args[0]==='section')throw new TypeError('PRIVATE_INPUT_MUST_NOT_BE_DISPLAYED');return create(...args)};
 });
 // Browser-initiated JavaScript URL navigation, using the exact native encoding.
 // This still does not exercise Android's bookmark editor, keyboard or omnibox UI.
 const dialogs=[];let resolveFailure;
 const failure=new Promise(r=>resolveFailure=r);
 page.on('dialog',async d=>{dialogs.push(d.message());await d.accept();if(d.message()==='INIT')resolveFailure()});
 assert.ok(urlCode.length<=4950);
 assert.equal(await page.evaluate(u=>{const a=document.createElement('a');a.href=u;return a.href},urlCode),urlCode);
 const client=await page.context().newCDPSession(page);
 await client.send('Page.navigate',{url:urlCode,transitionType:'auto_bookmark'});
 if(failInit)await failure;else await page.locator('#vkm-export-panel').waitFor();
 return {browser,page,dialogs};
}
test('bookmark launch acknowledges version and reports initialization failure without exception contents',async()=>{
 const {browser,page,dialogs}=await setup('<h1>Fixture</h1>','https://vk.ru/audio',true);
 try {
  assert.match(dialogs[0],/VKM 0\.5\.2/);
  assert.match(dialogs[1],/^INIT$/);
  assert.equal(dialogs.join('').includes('PRIVATE_INPUT'),false);
  assert.equal(await page.locator('#vkm-export-panel').count(),0);
 }finally{await browser.close()}
});
test('Chromium collector downloads metadata only, preserves repeats and removes playlist access keys',async()=>{
 const {browser,page}=await setup(`<input type="tel" value="PRIVATE_PHONE_CODE"><div id="audio_list">${row(0)}${row(1)}<b class="audio_page__count">2</b></div><div id="audio_playlists"><a href="/music/playlist/123_5_PRIVATE_TOKEN?access_token=PRIVATE_TOKEN">Для дороги</a></div>`);
 try {
  await page.getByRole('button',{name:'Сбор',exact:true}).click();
  await page.getByRole('button',{name:'JSON',exact:true}).waitFor();
  await page.waitForFunction(()=>!document.querySelector('#vkm-export-panel button:nth-of-type(3)').disabled,{},{timeout:20000});
  const downloadPromise=page.waitForEvent('download');await page.getByRole('button',{name:'JSON',exact:true}).click();
  const download=await downloadPromise;const raw=fs.readFileSync(await download.path(),'utf8');const p=decoded(raw);
  assert.equal(p.source.tracks.length,2);assert.equal(p.source.tracks[0].title,'Песня 🎵');
  assert.deepEqual(p.source.tracks.map(t=>t.key),['pos:0','pos:1']);assert.equal(p.capture.reason,'BOTTOM_STABLE');
  assert.equal(p.playlists[0].id,'playlist:123:5');assert.equal(raw.includes('PRIVATE_'),false);assert.equal(raw.includes('https://'),false);
 }finally{await browser.close()}
});
test('Chromium virtualized rows are accumulated in position order and manual stop remains partial',async()=>{
 const {browser,page}=await setup(`<div id="audio_list">${row(0)}${row(1)}</div>`);
 try {
  await page.getByRole('button',{name:'Сбор',exact:true}).click();await page.waitForFunction(()=>document.getElementById('vkm-export-panel').textContent.endsWith('#1'));
  await page.evaluate(html=>document.getElementById('audio_list').innerHTML=html,row(1)+row(2));await page.waitForFunction(()=>document.getElementById('vkm-export-panel').textContent.endsWith('#2'));
  await page.evaluate(html=>document.getElementById('audio_list').innerHTML=html,row(2)+row(3));await page.waitForFunction(()=>document.getElementById('vkm-export-panel').textContent.endsWith('#3'));
  await page.getByRole('button',{name:'Стоп',exact:true}).click();
  const dp=page.waitForEvent('download');await page.getByRole('button',{name:'JSON',exact:true}).click();
  const p=decoded(fs.readFileSync(await (await dp).path(),'utf8'));assert.deepEqual(p.source.tracks.map(t=>t.key),['pos:0','pos:1','pos:2','pos:3']);assert.equal(p.capture.reason,'USER_STOPPED');
 }finally{await browser.close()}
});
test('unknown layout produces a clear error and no downloadable empty success',async()=>{
 const {browser,page}=await setup('<h1>Unsupported fixture</h1>');
 try {await page.getByRole('button',{name:'Сбор',exact:true}).click();await page.waitForFunction(()=>document.getElementById('vkm-export-panel').textContent.includes('E6'));assert.equal(await page.getByRole('button',{name:'JSON',exact:true}).isEnabled(),false)}finally{await browser.close()}
});

test('catalog-only page does not import recommendation rows outside a recognized music container',async()=>{
 const {browser,page}=await setup(`${row(0)}<div id="audio_playlists"><a href="/music/playlist/123_5">My list</a></div>`);
 try {
  await page.getByRole('button',{name:'Сбор',exact:true}).click();
  await page.waitForFunction(()=>!document.querySelector('#vkm-export-panel button:nth-of-type(3)').disabled,{},{timeout:20000});
  const dp=page.waitForEvent('download');await page.getByRole('button',{name:'JSON',exact:true}).click();
  const p=decoded(fs.readFileSync(await (await dp).path(),'utf8'));assert.equal(p.source.tracks.length,0);assert.equal(p.playlists.length,1);
 }finally{await browser.close()}
});

test('modern VK header scopes its rows and excludes a recommendation section',async()=>{
 const modern=n=>`<div class="vkitAudioRow__root" data-index="${n}"><b data-testid="MusicTrackRow_Title">Новая песня</b><span class="vkitAudioRowInfo__text">Новый артист</span></div>`;
 const {browser,page}=await setup(`<section><h2 data-testid="MusicPlaylistTracks_Header">2 трека</h2><div>${modern(0)}${modern(1)}</div></section><aside>${modern(99)}</aside>`,'https://vk.ru/music/playlist/123_456_PRIVATE');
 try {
  await page.getByRole('button',{name:'Сбор',exact:true}).click();
  await page.waitForFunction(()=>!document.querySelector('#vkm-export-panel button:nth-of-type(3)').disabled,{},{timeout:20000});
  const dp=page.waitForEvent('download');await page.getByRole('button',{name:'JSON',exact:true}).click();
  const raw=fs.readFileSync(await(await dp).path(),'utf8');const p=decoded(raw);
  assert.equal(p.source.id,'playlist:123:456');assert.equal(p.source.tracks.length,2);
  assert.deepEqual(p.source.tracks.map(t=>t.key),['pos:0','pos:1']);assert.equal(p.source.tracks[0].title,'Новая песня');
  assert.equal(raw.includes('PRIVATE'),false);
 }finally{await browser.close()}
});
test('ambiguous modern headers do not enable a whole-page capture',async()=>{
 const {browser,page}=await setup('<section><h2 data-testid="MusicPlaylistTracks_Header">1</h2><div class="vkitAudioRow__root"></div></section><h2 data-testid="MusicPlaylistTracks_Header">2</h2>');
 try {await page.getByRole('button',{name:'Сбор',exact:true}).click();await page.waitForFunction(()=>document.getElementById('vkm-export-panel').textContent.includes('E6'));assert.equal(await page.getByRole('button',{name:'JSON',exact:true}).isEnabled(),false)}finally{await browser.close()}
});
