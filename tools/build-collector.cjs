const fs=require('node:fs');
const core=require('./collector-core.cjs');
const extractor=fs.readFileSync('app/src/main/assets/vk-extractor.js','utf8').replace('})(true);','})(false);').trim().replace(/;$/,'');
const runner=fs.readFileSync('tools/collector-runner.js','utf8').replace('/* CORE */','('+core.toString()+')()').replace('/* EXTRACTOR */',extractor);
const output='app/src/main/assets/vk-chrome-bookmarklet.js';
if(process.argv.includes('--check')) {
 if(fs.readFileSync(output,'utf8')!==runner)throw Error('Run node tools/build-collector.cjs');
}else fs.writeFileSync(output,runner);
