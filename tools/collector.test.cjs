const {test}=require('node:test');const assert=require('node:assert/strict');const {merge}=require('./collector-core.cjs')();
const song=(key,title='Song')=>[key.startsWith('pos:')?Number(key.slice(4)):null,title,'Artist','1_1'];
test('positioned virtual windows preserve repeats, order and stable re-reads',()=>{
 const a=merge([],[song('pos:2'),song('pos:1')]);const b=merge(a,[song('pos:2'),song('pos:3')]);
 assert.deepEqual(b.map(t=>'pos:'+t[0]),['pos:1','pos:2','pos:3']);assert.equal(b.length,3);
});
test('position conflict stops rather than mixing changed lists',()=>{
 assert.throws(()=>merge([song('pos:1')],[song('pos:1','Changed')]),e=>e===8);
 assert.throws(()=>merge([song('pos:1')],[song('unknown')]),e=>e===9);
});
test('unpositioned growing DOM retains duplicate occurrences',()=>{
 assert.equal(merge([song('a'),song('b')],[song('a'),song('b'),song('c','Next')]).length,3);
});
test('overlapping unpositioned windows append with multiplicity',()=>{
 const p=[song('a','A'),song('b','B'),song('c','C')];
 const n=[song('x','B'),song('y','C'),song('z','D')];
 assert.deepEqual(merge(p,n).map(t=>t[1]),['A','B','C','D']);
 assert.throws(()=>merge(p,[song('z','Unrelated')]),e=>e===10);
});
test('generated bookmarklet is current and valid JavaScript',()=>{
 require('node:child_process').execFileSync(process.execPath,['tools/build-collector.cjs','--check']);
 new (require('node:vm').Script)(require('node:fs').readFileSync('app/src/main/assets/vk-chrome-bookmarklet.js','utf8'));
});
test('clipboard URL stays below Android input limit after serialization and decoding',()=>{
 const fs=require('node:fs'),vm=require('node:vm');
 const code=fs.readFileSync('app/src/main/assets/vk-chrome-bookmarklet.js','utf8');
 const url=fs.readFileSync('app/src/main/assets/vk-chrome-bookmarklet.url','utf8');
 assert.ok(url.length<=4950);assert.match(url,/\/\*VKM_END_052\*\/$/);
 assert.equal(/[\r\n\t\u0080-\uffff]/.test(url),false);
 assert.equal(new URL(url).href,url);
 assert.equal(decodeURIComponent(url.slice(11)),code);
 new vm.Script(decodeURIComponent(url.slice(11)));
 assert.equal(url.slice(0,5000),url);
});
test('long overlapping samples retain all repeated entries in linear merge',()=>{
 const a=Array.from({length:10000},(_,i)=>song('x','Song '+(i%127)));
 const b=a.slice(9000).concat([song('x','New')]);
 const result=merge(a,b);assert.equal(result.length,10001);assert.equal(result.at(-1)[1],'New');
});
