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
    body = {"message": message, "content": content, "branch": branch}
    sha = remote_sha(repo, path, branch)
    if sha:
        body["sha"] = sha
    # Pass the payload via a temp file: Windows argv is capped at ~32k chars,
    # which a few-hundred-KB APK blows past instantly.
    import tempfile
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as tf:
        json.dump(body, tf)
        payload = tf.name
    try:
        r = subprocess.run(["gh", "api", "-X", "PUT",
                            f"/repos/{repo}/contents/{path}",
                            "--input", payload],
                           capture_output=True, text=True,
                           encoding="utf-8", errors="replace")
    finally:
        os.unlink(payload)
    if r.returncode != 0:
        print(f"FAIL {path}: {r.stderr.strip()[:300]}")
        return False
    d = json.loads(r.stdout)
    print(f"OK   {path} -> {d['commit']['sha'][:7]}")
    return True


def main():
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("repo"); ap.add_argument("branch")
    ap.add_argument("files", nargs="+", help="repo-relative paths; "
                    "each must exist under --strip if given")
    ap.add_argument("-m", "--message", default="Update files via Contents API")
    ap.add_argument("--strip", default="",
                    help="local dir prefix mapped to repo root")
    a = ap.parse_args()
    if a.strip:
        pairs = [(os.path.join(a.strip, f), f) for f in a.files]
    else:
        pairs = [(f, f) for f in a.files]
    ok = all(push_file(a.repo, a.branch, path, a.message)
             for local, path in pairs)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
