const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs');
const {chromium}=require('playwright-core');
const code=fs.readFileSync('app/src/main/assets/vk-chrome-bookmarklet.js','utf8');
const row=(n)=>`<div class="audio_row" data-full-id="1_1" data-index="${n}"><b class="audio_row__title">Песня 🎵</b><span class="audio_row__performer">Артист</span></div>`;
async function setup(html,url='https://vk.ru/audio') {
 const browser=await chromium.launch({executablePath:process.env.CHROME_BIN||'/usr/bin/google-chrome',headless:true,args:['--no-sandbox']});
 const page=await browser.newPage({acceptDownloads:true});
 await page.route('**/*',r=>r.fulfill({status:200,contentType:'text/html',headers:{'content-security-policy':"default-src 'none'; style-src 'unsafe-inline'; script-src 'none'"},body:'<meta charset="UTF-8">'+html}));
 await page.goto(url);
 await page.evaluate(()=>{
  Object.defineProperty(document,'cookie',{get(){throw Error('Cookie read forbidden')}});
  window.fetch=()=>{throw Error('Fetch forbidden')};
  Object.defineProperty(window,'localStorage',{get(){throw Error('Storage forbidden')}});
 });
 // DevTools evaluation exercises the actual Chromium DOM; Android bookmark invocation is a separate phone check.
 await page.evaluate(code);return {browser,page};
}
test('Chromium collector downloads metadata only, preserves repeats and removes playlist access keys',async()=>{
 const {browser,page}=await setup(`<input type="tel" value="PRIVATE_PHONE_CODE"><div id="audio_list">${row(0)}${row(1)}<b class="audio_page__count">2</b></div><div id="audio_playlists"><a href="/music/playlist/123_5_PRIVATE_TOKEN?access_token=PRIVATE_TOKEN">Для дороги</a></div>`);
 try {
  await page.getByRole('button',{name:'Начать сбор',exact:true}).click();
  await page.getByRole('button',{name:'Сохранить JSON',exact:true}).waitFor();
  await page.waitForFunction(()=>!document.querySelector('#vkm-export-panel button:nth-of-type(3)').disabled,{},{timeout:20000});
  const downloadPromise=page.waitForEvent('download');await page.getByRole('button',{name:'Сохранить JSON',exact:true}).click();
  const download=await downloadPromise;const raw=fs.readFileSync(await download.path(),'utf8');const p=JSON.parse(raw);
  assert.equal(p.kind,'vk_music_chrome_export');assert.equal(p.source.tracks.length,2);assert.equal(p.source.tracks[0].title,'Песня 🎵');
  assert.deepEqual(p.source.tracks.map(t=>t.key),['pos:0','pos:1']);assert.equal(p.capture.reason,'BOTTOM_STABLE');
  assert.equal(p.playlists[0].id,'playlist:123:5');assert.equal(raw.includes('PRIVATE_'),false);assert.equal(raw.includes('https://'),false);
 }finally{await browser.close()}
});
test('Chromium virtualized rows are accumulated in position order and manual stop remains partial',async()=>{
 const {browser,page}=await setup(`<div id="audio_list">${row(0)}${row(1)}</div>`);
 try {
  await page.getByRole('button',{name:'Начать сбор',exact:true}).click();await page.waitForFunction(()=>document.getElementById('vkm-export-panel').textContent.includes('проход 1.'));
  await page.evaluate(html=>document.getElementById('audio_list').innerHTML=html,row(1)+row(2));await page.waitForFunction(()=>document.getElementById('vkm-export-panel').textContent.includes('проход 2.'));
  await page.evaluate(html=>document.getElementById('audio_list').innerHTML=html,row(2)+row(3));await page.waitForFunction(()=>document.getElementById('vkm-export-panel').textContent.includes('проход 3.'));
  await page.getByRole('button',{name:'Остановить',exact:true}).click();
  const dp=page.waitForEvent('download');await page.getByRole('button',{name:'Сохранить JSON',exact:true}).click();
  const p=JSON.parse(fs.readFileSync(await (await dp).path(),'utf8'));assert.deepEqual(p.source.tracks.map(t=>t.key),['pos:0','pos:1','pos:2','pos:3']);assert.equal(p.capture.reason,'USER_STOPPED');
 }finally{await browser.close()}
});
test('unknown layout produces a clear error and no downloadable empty success',async()=>{
 const {browser,page}=await setup('<h1>Unsupported fixture</h1>');
 try {await page.getByRole('button',{name:'Начать сбор',exact:true}).click();await page.waitForFunction(()=>document.getElementById('vkm-export-panel').textContent.includes('UNSUPPORTED_LAYOUT'));assert.equal(await page.getByRole('button',{name:'Сохранить JSON',exact:true}).isEnabled(),false)}finally{await browser.close()}
});
