#!/usr/bin/env python3
"""Autonomous M7 APK delivery helper.
Pushes the local Gradle-wrapper commit, waits for the CI build, downloads the
APK artifact, and uploads it to QClaw. The GitHub token is read from the git
remote URL at runtime (never hardcoded). Safe to re-run (push is idempotent).
"""
import os, re, sys, time, json, zipfile, subprocess, shutil, urllib.request, urllib.error

REPO = "hit-droid/maiden-dungeon-apk"
WORKDIR = os.path.dirname(os.path.abspath(__file__))
CLOUD = "/app/skills/cloud-upload-backup/scripts/unix/cloud_backup.sh"

def token():
    url = subprocess.check_output(["git", "remote", "get-url", "origin"], text=True).strip()
    m = re.search(r"https://([^@]+)@", url)
    return m.group(1) if m else None

def api(path, tok, method="GET", data=None):
    req = urllib.request.Request("https://api.github.com" + path, method=method,
                                 data=json.dumps(data).encode() if data else None,
                                 headers={"Authorization": f"token {tok}",
                                          "Accept": "application/vnd.github+json",
                                          "User-Agent": "deliver-m7"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)

def run(cmd, **kw):
    print("+", " ".join(cmd[:3]), "...")
    return subprocess.run(cmd, cwd=WORKDIR, capture_output=True, text=True, **kw)

def main():
    tok = token()
    if not tok:
        print("FAIL: no token in git remote URL"); sys.exit(1)

    # 1) push (idempotent; no-op if b419225 already on remote)
    p = run(["git", "push", "origin", "master"])
    print(p.stdout[-500:], p.stderr[-500:])
    if p.returncode != 0 and "up-to-date" not in p.stdout and "up-to-date" not in p.stderr:
        print("FAIL: git push failed (network still down?)"); sys.exit(2)

    # 2) find the workflow run triggered by THIS push (match head sha, not a stale run)
    head_sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=WORKDIR, text=True).strip()
    runs = api(f"/repos/{REPO}/actions/runs?head_sha={head_sha}&per_page=5", tok)
    if not runs.get("workflow_runs"):
        print("FAIL: no workflow run for", head_sha); sys.exit(3)
    run_id = runs["workflow_runs"][0]["id"]
    print("Watching run", run_id, "head", head_sha)

    # 3) poll until completed
    deadline = time.time() + 900
    status = conclusion = None
    while time.time() < deadline:
        r = api(f"/repos/{REPO}/actions/runs/{run_id}", tok)
        status, conclusion = r.get("status"), r.get("conclusion")
        if status == "completed":
            break
        time.sleep(20)
    if status != "completed" or conclusion != "success":
        print(f"FAIL: build {status}/{conclusion}"); sys.exit(4)

    # 4) download artifact
    arts = api(f"/repos/{REPO}/actions/runs/{run_id}/artifacts", tok)
    if not arts.get("artifacts"):
        print("FAIL: no artifacts"); sys.exit(5)
    dl = arts["artifacts"][0]["archive_download_url"]
    zip_path = os.path.join(WORKDIR, "apk_artifact.zip")
    # GitHub returns a 302 to Azure blob storage (SAS in URL). Do NOT forward the
    # GitHub token to Azure or it 403s -> strip Authorization on the redirect.
    class NoAuthRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            nr = super().redirect_request(req, fp, code, msg, headers, newurl)
            if nr and "Authorization" in nr.headers:
                del nr.headers["Authorization"]
            return nr
    opener = urllib.request.build_opener(NoAuthRedirect())
    req = urllib.request.Request(dl, headers={"Authorization": f"token {tok}", "User-Agent": "deliver-m7"})
    with opener.open(req, timeout=120) as resp, open(zip_path, "wb") as f:
        shutil.copyfileobj(resp, f)

    # 5) unzip and find apk
    apk = None
    with zipfile.ZipFile(zip_path) as z:
        z.extractall(WORKDIR)
        for n in z.namelist():
            if n.endswith(".apk"):
                apk = os.path.join(WORKDIR, n)
    if not apk or not os.path.exists(apk):
        print("FAIL: apk not found in artifact"); sys.exit(6)

    # 6) upload to QClaw
    up = run(["bash", CLOUD, "upload", "--local-path", apk, "--conflict-strategy", "rename"])
    print(up.stdout)
    print("RESULT:", "delivered" if up.returncode == 0 else "upload-failed")
    sys.exit(0 if up.returncode == 0 else 7)

if __name__ == "__main__":
    main()
