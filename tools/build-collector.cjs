const fs=require('node:fs');
const {minify}=require('terser');
const core=require('./collector-core.cjs');
const sample=fs.readFileSync('tools/collector-sample.js','utf8');
const runner=fs.readFileSync('tools/collector-runner.js','utf8').replace('/* CORE */','('+core.toString()+')()').replace('/* SAMPLE */',sample);
const output='app/src/main/assets/vk-chrome-bookmarklet.js';
(async()=>{
 const result=await minify(runner,{ecma:2020,compress:{passes:3},mangle:true,format:{comments:false,ascii_only:true,beautify:false}});
 const code=result.code+';void 0/*VKM_END_052*/';
 if(!code || /[\r\n\t\u0080-\uffff]/.test(code))throw Error('Bookmark must be a single ASCII line');
 // Escape reserved query/fragment delimiters before URL serialization, so Chrome
 // does not reinterpret the remainder as a URL query and expand its quotes/spaces.
 const url=new URL('javascript:'+code.replaceAll('%','%25').replaceAll('#','%23').replaceAll('?','%3F')).href;
 if(url.length>4950)throw Error('Android single-line field limit: '+url.length+' > 4950');
 if(process.argv.includes('--check')) {
  if(fs.readFileSync(output,'utf8')!==code||fs.readFileSync(output.replace('.js','.url'),'utf8')!==url)throw Error('Run npm ci --prefix tools && node tools/build-collector.cjs');
 }else {fs.writeFileSync(output,code);fs.writeFileSync(output.replace('.js','.url'),url)}
 console.log('Bookmark URL: '+url.length+' / 4950 characters');
 const probe=await minify(fs.readFileSync('tools/layout-probe.js','utf8'),{ecma:2020,compress:{passes:3},mangle:true,format:{comments:false,ascii_only:true}});
 const probeCode=probe.code+';void 0/*VKM_LAYOUT_1*/';
 const probeUrl=new URL('javascript:'+probeCode.replaceAll('%','%25').replaceAll('#','%23').replaceAll('?','%3F')).href;
 if(probeUrl.length>4950||/[^\x20-\x7e]/.test(probeUrl))throw Error('Layout bookmark exceeds safe input format');
 const probePath='app/src/main/assets/vk-layout-bookmarklet.url';
 if(process.argv.includes('--check')) {if(fs.readFileSync(probePath,'utf8')!==probeUrl)throw Error('Regenerate layout bookmark');}
 else fs.writeFileSync(probePath,probeUrl);
 console.log('Layout URL: '+probeUrl.length+' / 4950 characters');
})().catch(e=>{console.error(e);process.exitCode=1});
