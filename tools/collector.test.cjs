const {test}=require('node:test');const assert=require('node:assert/strict');const {merge}=require('./collector-core.cjs')();
const song=(key,title='Song')=>({key,title,artist:'Artist',vkId:'1_1'});
test('positioned virtual windows preserve repeats, order and stable re-reads',()=>{
 const a=merge([],[song('pos:2'),song('pos:1')]);const b=merge(a,[song('pos:2'),song('pos:3')]);
 assert.deepEqual(b.map(t=>t.key),['pos:1','pos:2','pos:3']);assert.equal(b.length,3);
});
test('position conflict stops rather than mixing changed lists',()=>{
 assert.throws(()=>merge([song('pos:1')],[song('pos:1','Changed')]),/LIST_CHANGED/);
 assert.throws(()=>merge([song('pos:1')],[song('unknown')]),/POSITION_MODE_CHANGED/);
});
test('unpositioned growing DOM retains duplicate occurrences',()=>{
 assert.equal(merge([song('a'),song('b')],[song('a'),song('b'),song('c','Next')]).length,3);
});
test('overlapping unpositioned windows append with multiplicity',()=>{
 const p=[song('a','A'),song('b','B'),song('c','C')];
 const n=[song('x','B'),song('y','C'),song('z','D')];
 assert.deepEqual(merge(p,n).map(t=>t.title),['A','B','C','D']);
 assert.throws(()=>merge(p,[song('z','Unrelated')]),/VIRTUALIZED_WITHOUT_POSITIONS/);
});
test('generated bookmarklet is current and valid JavaScript',()=>{
 require('node:child_process').execFileSync(process.execPath,['tools/build-collector.cjs','--check']);
 new (require('node:vm').Script)(require('node:fs').readFileSync('app/src/main/assets/vk-chrome-bookmarklet.js','utf8'));
});
test('clipboard bookmark survives URL newline stripping and one percent decode',()=>{
 const code=require('node:fs').readFileSync('app/src/main/assets/vk-chrome-bookmarklet.js','utf8');
 assert.match(code,/;void"VKM_END_051";$/);
 assert.equal(/[\r\n\t\u0080-\uffff]/.test(code),false);
 const url='javascript:'+code.replaceAll('%','%25').replaceAll('#','%23');
 assert.ok(url.length<14000,'Clipboard script size regression');
 assert.equal(decodeURIComponent(new URL(url).href.slice(11)),code);
 new (require('node:vm').Script)(decodeURIComponent(new URL(url).href.slice(11)));
});
