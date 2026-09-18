#!/usr/bin/env python3
"""Push local files to a GitHub repo via the Contents API.

Use when `git push` cannot reach github.com:443 but api.github.com is
reachable (some sandboxed networks allow the API host only).

Usage:
  python gh_push_via_api.py <owner>/<repo> <branch> <file> [<file> ...]

Requires the `gh` CLI to be authenticated (`gh auth status`).
"""
import base64
import json
import os
import subprocess
import sys


def gh(*args, check=True):
    r = subprocess.run(["gh", *args], capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    if check and r.returncode != 0:
        raise RuntimeError(f"gh {' '.join(args)} failed: {r.stderr.strip()}")
    return r.stdout


def remote_sha(repo, path, branch):
    r = subprocess.run(
        ["gh", "api", f"/repos/{repo}/contents/{path}?ref={branch}",
         "--jq", ".sha"], capture_output=True, text=True,
        encoding="utf-8", errors="replace")
    return r.stdout.strip() if r.returncode == 0 else ""


def push_file(repo, branch, path, message):
    with open(path, "rb") as f:
        content = base64.b64encode(f.read()).decode()
    args = ["api", "-X", "PUT", f"/repos/{repo}/contents/{path}",
            "-f", f"message={message}", "-f", f"content={content}",
            "-f", f"branch={branch}"]
    sha = remote_sha(repo, path, branch)
    if sha:
        args += ["-f", f"sha={sha}"]
    r = subprocess.run(["gh", *args], capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    if r.returncode != 0:
        print(f"FAIL {path}: {r.stderr.strip()[:300]}")
        return False
    d = json.loads(r.stdout)
    print(f"OK   {path} -> {d['commit']['sha'][:7]}")
    return True


def main():
    repo, branch = sys.argv[1], sys.argv[2]
    files = sys.argv[3:]
    msg = "Rename project to alien-booster-auto-claim; document 外星仔 quota"
    ok = all(push_file(repo, branch, p, msg) for p in files)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
