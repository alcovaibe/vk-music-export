"""Reproducible lexical inventory, not a Kotlin compiler call graph."""
import csv, hashlib, json, re, subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[1]/'repo'; out=Path(__file__).resolve().parent
paths=subprocess.check_output(['git','ls-files'],cwd=root,text=True).splitlines()
inventory=[]; symbols=[]; functions=[]; tests=[];texts={}
for name in paths:
    p=root/name; raw=p.read_bytes()
    try:t=raw.decode('utf-8')
    except UnicodeDecodeError:t=None
    if t is not None:texts[name]=t
    inventory.append({'path':name,'bytes':len(raw),'lines':t.count('\n') if t is not None else None,'sha256':hashlib.sha256(raw).hexdigest(),'kind':'Kotlin' if p.suffix=='.kt' else 'Markdown' if p.suffix=='.md' else p.suffix or 'text','text_scanned':t is not None})
    if p.suffix=='.kt':
        for m in re.finditer(r'\b(class|interface|object)\s+(\w+)',t):
            if m[2] in ('class','interface','only','and','is','from','with','has','or','of','can','remains','to','identity','and','first'):continue
            # Anchoring avoids most mentions in prose; companion/data/enum/sealed accepted.
            line=t[t.rfind('\n',0,m.start())+1:t.find('\n',m.end())]
            if line.lstrip().startswith(('/','*')):continue
            symbols.append({'symbol':m[2],'kind':m[1],'file':name,'line':t.count('\n',0,m.start())+1,'declaration':line.strip()})
        for i,line in enumerate(t.splitlines(),1):
            if re.search(r'\bfun\s+(?:<[^>]+>\s*)?[A-Za-z_]',line) and not line.lstrip().startswith(('*','/')):
                functions.append({'file':name,'line':i,'signature_line':line.strip()})
        if '/test/' in name or '/androidTest/' in name:
            tests.append({'file':name,'tests':re.findall(r'@Test\s+fun\s+(\w+)',t),'conditions':[x.strip() for x in t.splitlines() if re.search(r'assumeTrue|@Ignore|@SdkSuppress|System.getenv',x)],'assertions':len(re.findall(r'\b(?:assert\w+|fail)\s*\(',t))})
main_symbols=[s for s in symbols if '/main/' in s['file']]
all_names={s['symbol'] for s in main_symbols}
for symbol in main_symbols:
    t=texts[symbol['file']]
    symbol['file_level_symbol_references']=sorted(set(re.findall(r'\b[A-Z]\w*\b',t))&all_names-{symbol['symbol']})
    symbol['imports']=re.findall(r'^import (.+)',t,re.M)
for name,data in [('source-inventory.json',inventory),('architecture-lexical.json',main_symbols),('function-signatures.json',functions),('test-inventory.json',tests)]:
    (out/name).write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
with (out/'architecture-lexical.tsv').open('w') as f:
    w=csv.writer(f,delimiter='\t');w.writerow(['symbol','file','line','declaration','file-level references (not verified dependencies)'])
    for s in main_symbols:w.writerow([s['symbol'],s['file'],s['line'],s['declaration'],', '.join(s['file_level_symbol_references'])])
print(json.dumps({'tracked_files':len(paths),'Kotlin_files':sum(x['kind']=='Kotlin' for x in inventory),'main_Kotlin':sum('/main/' in x['path'] and x['kind']=='Kotlin' for x in inventory),'unit_Kotlin':sum('/test/' in x['path'] and x['kind']=='Kotlin' for x in inventory),'device_Kotlin':sum('/androidTest/' in x['path'] and x['kind']=='Kotlin' for x in inventory),'Markdown_files':sum(x['kind']=='Markdown' for x in inventory),'main_symbols':len(main_symbols),'unit_test_methods':sum(len(t['tests']) for t in tests if '/test/' in t['file']),'device_test_methods':sum(len(t['tests']) for t in tests if '/androidTest/' in t['file'])},indent=2))
