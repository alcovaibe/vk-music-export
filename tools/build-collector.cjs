const fs=require('node:fs');
const {minify}=require('terser');
const core=require('./collector-core.cjs');
const extractor=fs.readFileSync('app/src/main/assets/vk-extractor.js','utf8').replace('})(true);','})(false);').trim().replace(/;$/,'');
const runner=fs.readFileSync('tools/collector-runner.js','utf8').replace('/* CORE */','('+core.toString()+')()').replace('/* EXTRACTOR */',extractor);
const output='app/src/main/assets/vk-chrome-bookmarklet.js';
(async()=>{
 const result=await minify(runner,{ecma:2020,compress:{passes:2},mangle:true,format:{comments:false,ascii_only:true,beautify:false}});
 const code=result.code+';void"VKM_END_051";';
 if(!code || /[\r\n\t\u0080-\uffff]/.test(code))throw Error('Bookmark must be a single ASCII line');
 if(process.argv.includes('--check')) {
  if(fs.readFileSync(output,'utf8')!==code)throw Error('Run npm ci --prefix tools && node tools/build-collector.cjs');
 }else fs.writeFileSync(output,code);
})().catch(e=>{console.error(e);process.exitCode=1});
