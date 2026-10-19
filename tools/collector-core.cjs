module.exports = function collectorCore() {
  const identity = t => JSON.stringify([t.vkId || null,t.artist,t.title]);
  function merge(previous, rows) {
    if (!rows.length) return previous;
    const positioned = rows.every(t => /^pos:\d{1,9}$/.test(t.key));
    if (previous.length && positioned !== previous.every(t => /^pos:\d{1,9}$/.test(t.key))) throw Error('POSITION_MODE_CHANGED');
    if (positioned) {
      const map = new Map(previous.map(t => [t.key,t]));
      for (const t of rows) {
        if (map.has(t.key) && identity(map.get(t.key)) !== identity(t)) throw Error('LIST_CHANGED');
        map.set(t.key,t);
      }
      return [...map.values()].sort((a,b) => Number(a.key.slice(4))-Number(b.key.slice(4)));
    }
    if (!previous.length) return rows.slice();
    if (rows.length >= previous.length && previous.every((t,i) => identity(t)===identity(rows[i]))) return rows.slice();
    if (previous.length >= rows.length && rows.every((t,i) => identity(t)===identity(previous[i]))) return previous;
    // KMP: longest suffix/prefix overlap; never collapse duplicates within the sample.
    const pattern=rows.map(identity), text=previous.slice(-rows.length).map(identity), values=[...pattern,null,...text], pi=[];
    for(let i=0;i<values.length;i++) {
      let j=i?pi[i-1]:0;
      while(j>0 && values[i]!==values[j]) j=pi[j-1];
      if(i && values[i]===values[j]) j++;
      pi[i]=j;
    }
    const overlap=pi[pi.length-1] || 0;
    if(!overlap) throw Error('VIRTUALIZED_WITHOUT_POSITIONS');
    return previous.concat(rows.slice(overlap));
  }
  return {merge};
};
