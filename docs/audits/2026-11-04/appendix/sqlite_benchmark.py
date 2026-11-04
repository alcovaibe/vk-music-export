"""Host-side SQLite SQL/JSON microbenchmark. NOT Android/Kotlin or MediaStore measurement."""
import json, sqlite3, tempfile, statistics, time, platform, sys
from pathlib import Path

def ms(fn, repeats=7):
    values=[]
    for _ in range(repeats):
        start=time.perf_counter(); fn(); values.append((time.perf_counter()-start)*1000)
    return {'median_ms':round(statistics.median(values),3),'min_ms':round(min(values),3),'max_ms':round(max(values),3),'samples':repeats}

results={'scope':'Synthetic host-side benchmark, same tasks/files/meta DDL and relevant SQL; Python JSON decoder, no Android, no Kotlin, no MediaStore; warm page cache.',
         'python':sys.version.split()[0], 'sqlite':sqlite3.sqlite_version,'platform':platform.platform(),'runs':[]}
for n in (100,4500,20000):
    with tempfile.TemporaryDirectory() as tmp:
        db=sqlite3.connect(str(Path(tmp)/'audio-export.db'))
        db.executescript('''PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL;
CREATE TABLE tasks(ordinal INTEGER PRIMARY KEY AUTOINCREMENT,task_key TEXT UNIQUE NOT NULL,payload TEXT NOT NULL,state TEXT NOT NULL,error TEXT,prepared TEXT);
CREATE INDEX task_states ON tasks(state,ordinal);
CREATE TABLE files(task_key TEXT PRIMARY KEY,track_id TEXT NOT NULL,payload TEXT NOT NULL);
CREATE INDEX file_tracks ON files(track_id);
CREATE TABLE meta(name TEXT PRIMARY KEY,value TEXT NOT NULL);''')
        task=[]; files=[]; targets=[]
        for i in range(n):
            key=f'{i:064x}'; track={'id':f'1_{i+1}','title':f'Track {i}','artist':'Synthetic artist','duration':180,'restricted':False,'album':None,'subtitle':None,'metadataError':None,'releaseYear':None}
            payload=json.dumps({'key':key,'track':track,'folders':['My music'],'position':i+1,'source':{'account':1,'kind':'library','id':'my'},'occurrence':1},separators=(',',':'))
            task.append((key,payload,'FAILED','UNAVAILABLE'))
            uri=f'content://media/external_primary/audio/media/{i+1}'
            files.append((key,track['id'],json.dumps({'taskKey':key,'track':track,'uri':uri,'displayName':f'{i}.mp3','relativePath':'Music/VK Music Export/My music/','extension':'mp3','mime':'audio/mpeg','durationMs':180000,'bytes':7200000,'sha256':'a'*64,'source':{'account':1,'kind':'library','id':'my'},'occurrence':1},separators=(',',':'))))
            targets.append({'taskKey':key,'uri':uri,'trackId':track['id'],'name':f'{i}.mp3'})
        start=time.perf_counter()
        db.executemany('INSERT INTO tasks(task_key,payload,state,error) VALUES(?,?,?,?)',task)
        db.executemany('INSERT INTO files(task_key,track_id,payload) VALUES(?,?,?)',files);db.commit()
        insert=(time.perf_counter()-start)*1000
        def failures():
            list(db.execute('SELECT state,COUNT(*) FROM tasks GROUP BY state'))
            for (raw,error) in db.execute("SELECT payload,error FROM tasks WHERE state='FAILED' ORDER BY ordinal"):
                obj=json.loads(raw)
                db.execute('SELECT value FROM meta WHERE name=?',('failure_detail:'+obj['key'],)).fetchone()
        counter=[0]
        def trace(_):counter[0]+=1
        db.set_trace_callback(trace); failures(); db.set_trace_callback(None)
        def saved():return [json.loads(row[0]) for row in db.execute('SELECT payload FROM files ORDER BY rowid')]
        def cursor():return [json.loads(row[0]) for row in db.execute('SELECT payload FROM tasks ORDER BY ordinal')]
        run={'tracks':n,'insert_tasks_and_files_one_transaction_ms':round(insert,3),'failed_snapshot_subset':ms(failures),'failed_snapshot_subset_sql_statements':counter[0],
             'savedFiles_read_and_python_decode':ms(saved),'taskRows_read_and_python_decode':ms(cursor),
             'failure_query_plan':list(db.execute("EXPLAIN QUERY PLAN SELECT payload,error FROM tasks WHERE state='FAILED' ORDER BY ordinal")),
             'lookup_query_plan':list(db.execute('EXPLAIN QUERY PLAN SELECT payload FROM files WHERE track_id=?',('1_1',)))}
        run['metadata_run_serialized_bytes']=len(json.dumps({'owner':1,'targets':targets,'next':0},separators=(',',':')).encode())
        run['metadata_run_serialized_bytes_times_items_estimate']=run['metadata_run_serialized_bytes']*n
        # Receipt JSON has 64-character key + codec and 64-character digest; exact prefix sums of this synthetic representation.
        receipt_item=json.dumps('a'*64)+':'+json.dumps({'codec':'mp3','sha256':'b'*64},separators=(',',':'))
        run['receipt_entry_bytes']=len(receipt_item)
        run['receipt_entries_cumulative_bytes_estimate']=len(receipt_item)*n*(n+1)//2+n*(n-1)//2
        results['runs'].append(run);db.close()
Path(__file__).with_name('sqlite-benchmark.json').write_text(json.dumps(results,indent=2)+'\n')
print(json.dumps(results,indent=2))
