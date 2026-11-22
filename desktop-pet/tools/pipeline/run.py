import sys, json, time, concurrent.futures as cf, pathlib
import nb, jobs
names = sys.argv[1:]
log = pathlib.Path("costs.jsonl")
def one(name):
    prompt, refs, aspect = jobs.build(name)
    t = time.time()
    try:
        r = nb.gen(prompt, refs, aspect=aspect, size="2K", out=f"raw/{name}.jpg")
        rec = {"name": name, "cost": r["usage"].get("cost"), "sec": round(time.time()-t, 1)}
    except Exception as e:
        rec = {"name": name, "error": str(e)[:300]}
    with log.open("a") as f: f.write(json.dumps(rec) + "\n")
    return rec
with cf.ThreadPoolExecutor(4) as ex:
    for rec in ex.map(one, names): print(rec, flush=True)
