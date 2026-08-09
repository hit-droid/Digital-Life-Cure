#!/usr/bin/env python3
"""Finish M7 delivery: download the APK artifact for the already-built run,
extract the APK, and upload it to QClaw. (Push already done; run already green.)"""
import os, re, sys, json, zipfile, subprocess, shutil, urllib.request

REPO = "hit-droid/maiden-dungeon-apk"
WORKDIR = os.path.dirname(os.path.abspath(__file__))
CLOUD = "/app/skills/cloud-upload-backup/scripts/unix/cloud_backup.sh"
RUN_ID = 29689121679  # run triggered by our pushed HEAD (4f3dd9f)

def token():
    url = subprocess.check_output(["git", "remote", "get-url", "origin"], text=True).strip()
    m = re.search(r"https://([^@]+)@", url)
    return m.group(1) if m else None

def api(path, tok):
    req = urllib.request.Request("https://api.github.com" + path,
        headers={"Authorization": f"token {tok}", "Accept": "application/vnd.github+json", "User-Agent": "deliver-finish"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)

def main():
    tok = token()
    if not tok:
        print("FAIL: no token"); sys.exit(1)

    # verify run succeeded
    r = api(f"/repos/{REPO}/actions/runs/{RUN_ID}", tok)
    print("run", RUN_ID, r.get("status"), r.get("conclusion"))
    if r.get("status") != "completed" or r.get("conclusion") != "success":
        print("FAIL: build not green"); sys.exit(4)

    # list artifacts, prefer one whose name suggests an APK
    arts = api(f"/repos/{REPO}/actions/runs/{RUN_ID}/artifacts", tok)
    alist = arts.get("artifacts") or []
    if not alist:
        print("FAIL: no artifacts"); sys.exit(5)
    apk_art = next((a for a in alist if "apk" in a["name"].lower()), alist[0])
    print("artifact:", apk_art["name"], "id", apk_art["id"])

    dl = apk_art["archive_download_url"]
    zip_path = os.path.join(WORKDIR, "apk_artifact.zip")
    # GitHub 302s to Azure blob (SAS in URL). Do not forward the GitHub token.
    class NoAuthRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            nr = super().redirect_request(req, fp, code, msg, headers, newurl)
            if nr and "Authorization" in nr.headers:
                del nr.headers["Authorization"]
            return nr
    opener = urllib.request.build_opener(NoAuthRedirect())
    req = urllib.request.Request(dl, headers={"Authorization": f"token {tok}", "User-Agent": "deliver-finish"})
    with opener.open(req, timeout=120) as resp, open(zip_path, "wb") as f:
        shutil.copyfileobj(resp, f)
    print("downloaded", os.path.getsize(zip_path), "bytes")

    # extract and find apk
    apk = None
    with zipfile.ZipFile(zip_path) as z:
        z.extractall(WORKDIR)
        for n in z.namelist():
            if n.endswith(".apk"):
                apk = os.path.join(WORKDIR, n)
    if not apk or not os.path.exists(apk):
        print("FAIL: apk not found in artifact"); sys.exit(6)
    print("apk:", apk, os.path.getsize(apk), "bytes")

    # upload to QClaw
    up = subprocess.run(["bash", CLOUD, "upload", "--local-path", apk, "--conflict-strategy", "rename"],
                        cwd=WORKDIR, capture_output=True, text=True)
    sys.stdout.write(up.stdout)
    sys.stderr.write(up.stderr)
    print("RESULT:", "delivered" if up.returncode == 0 else "upload-failed")
    sys.exit(0 if up.returncode == 0 else 7)

if __name__ == "__main__":
    main()
