import json, time, os, urllib.request, subprocess, sys

TOK = "***REMOVED-LEAKED-PAT***".strip()
REPO = "hit-droid/maiden-dungeon-apk"
OUT = "/home/node/.openclaw/workspace-agent-2cd3f804/少女地牢-apk"

def get_run():
    req = urllib.request.Request("https://api.github.com/repos/%s/actions/runs?per_page=1" % REPO)
    req.add_header("Authorization", "token " + TOK)
    return json.loads(urllib.request.urlopen(req).read())["workflow_runs"][0]

# 等待构建完成
for i in range(160):
    time.sleep(12)
    try:
        r = get_run()
    except Exception as e:
        print("poll_err", e, flush=True); continue
    print(i, r["status"], r["conclusion"], flush=True)
    if r["status"] == "completed":
        if r["conclusion"] != "success":
            open(OUT + "/fail.flag", "w").write(str(r["conclusion"]))
            print("BUILD_FAILED", flush=True); break
        # 下载 artifact（curl 避开 Azure 403）
        art = json.loads(urllib.request.urlopen(
            urllib.request.Request("https://api.github.com/repos/%s/actions/artifacts?per_page=5" % REPO,
                                   headers={"Authorization": "token " + TOK})).read())["artifacts"]
        if not art:
            print("NO_ARTIFACT", flush=True); break
        dl = art[0]["archive_download_url"]
        subprocess.run(["curl", "-sL", "-H", "Authorization: token " + TOK, "-o", OUT + "/apk.zip", dl])
        subprocess.run(["rm", "-rf", OUT + "/out"])
        os.makedirs(OUT + "/out")
        subprocess.run(["python3", "-c",
            "import zipfile;zipfile.ZipFile('%s/apk.zip').extractall('%s/out')" % (OUT, OUT)])
        apk = None
        for root, _, files in os.walk(OUT + "/out"):
            for fn in files:
                if fn.endswith(".apk"): apk = os.path.join(root, fn)
        if apk:
            subprocess.run(["cp", apk, OUT + "/少女地牢.apk"])
            print("APK_READY " + OUT + "/少女地牢.apk", flush=True)
            open(OUT + "/done.flag", "w").write(OUT + "/少女地牢.apk")
        else:
            print("NO_APK", flush=True)
        break
print("poller_done", flush=True)
