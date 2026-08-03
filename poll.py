import json, time, os, urllib.request, subprocess, sys

TOK = "***REMOVED-LEAKED-PAT***".strip()
REPO = "hit-droid/maiden-dungeon-apk"
OUT = "/home/node/.openclaw/workspace-agent-2cd3f804/少女地牢-apk"

def api(path, raw=False):
    req = urllib.request.Request("https://api.github.com/repos/%s/%s" % (REPO, path))
    req.add_header("Authorization", "token " + TOK)
    with urllib.request.urlopen(req) as r:
        data = r.read()
    return data if raw else json.loads(data)

for i in range(160):
    time.sleep(12)
    try:
        runs = api("actions/runs?per_page=1")["workflow_runs"]
    except Exception as e:
        print("poll err", e); continue
    if not runs:
        print("[%d] no runs" % i); continue
    run = runs[0]
    st, cc, rid = run["status"], run["conclusion"], run["id"]
    print("[%d] %s %s run=%s" % (i, st, cc, rid), flush=True)
    if st == "completed":
        if cc == "success":
            arts = api("actions/artifacts?per_page=5")["artifacts"]
            if not arts:
                print("SUCCESS but no artifact"); break
            dl = arts[0]["archive_download_url"]
            req = urllib.request.Request(dl); req.add_header("Authorization", "token " + TOK)
            with urllib.request.urlopen(req) as r, open(OUT + "/apk.zip", "wb") as f:
                f.write(r.read())
            subprocess.run(["python3", "-c",
                "import zipfile;zipfile.ZipFile('%s/apk.zip').extractall('%s/out')" % (OUT, OUT)])
            apk = None
            for root, _, files in os.walk(OUT + "/out"):
                for fn in files:
                    if fn.endswith(".apk"): apk = os.path.join(root, fn)
            if apk:
                dst = OUT + "/少女地牢.apk"
                subprocess.run(["cp", apk, dst])
                print("APK_READY " + dst, flush=True)
                open(OUT + "/done.flag", "w").write(dst)
            else:
                print("no apk in artifact")
        else:
            print("BUILD_FAILED " + str(cc), flush=True)
            logs = api("actions/runs/%s/jobs" % rid)
            for j in logs.get("jobs", []):
                for s in j.get("steps", []):
                    if s.get("conclusion") == "failure":
                        print("FAILED_STEP:", s.get("name"), flush=True)
            open(OUT + "/fail.flag", "w").write(str(cc))
        break
print("poller exit", flush=True)
