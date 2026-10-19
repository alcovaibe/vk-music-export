/* Track tuples: [position|null, title, artist, vkId|null]. Numeric errors map in ChromeImport. */
module.exports = function collectorCore() {
  const identity=t=>JSON.stringify(t.slice(1));
  function merge(a,b) {
    if(!b.length)return a;
    const positioned=b.every(t=>t[0]!==null);
    if(a.length&&positioned!==a.every(t=>t[0]!==null))throw 9;
    if(positioned) {
      const m=new Map(a.map(t=>[t[0],t]));
      for(const t of b){if(m.has(t[0])&&identity(m.get(t[0]))!==identity(t))throw 8;m.set(t[0],t)}
      return [...m.values()].sort((a,b)=>a[0]-b[0]);
    }
    if(!a.length)return b.slice();
    const x=a.map(identity),y=b.map(identity);
    if(x.every((s,i)=>s===y[i]))return b.slice();
    if(y.every((s,i)=>s===x[i]))return a;
    // Linear KMP suffix/prefix overlap. Repeated tracks stay separate entries.
    const z=[...y,null,...x.slice(-y.length)],p=[];
    for(let i=0;i<z.length;i++){
      let j=i?p[i-1]:0;
      while(j&&z[i]!==z[j])j=p[j-1];
      if(i&&z[i]===z[j])j++;
      p[i]=j;
    }
    const overlap=p.pop();if(!overlap)throw 10;
    return a.concat(b.slice(overlap));
  }
  return {merge};
};
