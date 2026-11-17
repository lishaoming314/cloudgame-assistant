#!/usr/bin/env python3
"""Publish this project to GitHub using only the REST API.

Why: `git push` to github.com is often reset on some networks, while
api.github.com keeps working. This script uploads the tracked files as one
commit, creates the tag, the Release and uploads the APK asset.

Usage:
    python3 publish.py                 # prompts for token (hidden input)
    GH_TOKEN=ghp_xxx python3 publish.py
    python3 publish.py --dry-run       # show what would be published

Requirements: a GitHub Personal Access Token (classic) with the `repo` scope.
"""

import argparse
import base64
import getpass
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.github.com"
UPLOAD = "https://uploads.github.com"
UA = "screenwatch-publisher/1.0"
DEFAULT_REPO = "cloudgame-assistant"
DEFAULT_TAG = "v2.1.1"
SKIP_DIRS = {".git", "build", "tools", "keystore", ".idea", ".gradle"}
SKIP_EXT = {".apk", ".zip", ".keystore"}

RELEASE_BODY = """## 云游戏助手 2.1.1

### 本次更新
- **主题色球**：应用内拖动 HSV 色球选色，悬浮球、主按钮、通知颜色实时跟随，可一键恢复默认
- 版本文案更新为 2.1.1（图标保持渐变云朵）

### 2.1 已有功能
- 像素变化 + 离线 OCR 文字变化，两个独立开关，可单开或同开
- 同开时可选「需同时满足」（默认，误报更少）或任一满足
- 倒计时悬浮球，点击展开截图 / 暂停 / 停止 / 收起
- 截图间隔 5 秒 – 10 分钟，提醒后自动停止监测
- 完全离线，无网络权限

### 安装
下载 `CloudGameAssistant-2.1.1.apk`，可覆盖升级 2.1。

> 每次开始都需要系统录屏授权（Android 10 限制）。

作者：理烧明 · https://space.bilibili.com/1416380260
"""


class ApiError(Exception):
    def __init__(self, status, body):
        self.status = status
        self.body = body
        super().__init__(f"HTTP {status}: {body[:300]}")


def api(method, url, token, payload=None, raw=None, content_type=None,
        timeout=60, retries=3):
    """One API call with retries for transient network errors."""
    data = None
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "User-Agent": UA,
    }
    if raw is not None:
        data = raw
        headers["Content-Type"] = content_type or "application/octet-stream"
    elif payload is not None:
        data = json.dumps(payload).encode()
        headers["Content-Type"] = "application/json"

    last = None
    for attempt in range(retries):
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                body = resp.read().decode("utf-8", "replace")
                return json.loads(body) if body.strip() else {}
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", "replace")
            if e.code >= 500 or e.code == 429:
                last = ApiError(e.code, body)
                time.sleep(2 + attempt * 2)
                continue
            raise ApiError(e.code, body)
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            last = e
            time.sleep(2 + attempt * 2)
    raise last if isinstance(last, ApiError) else RuntimeError(f"网络错误: {last}")


def list_files(root):
    """Tracked files if this is a git repo, otherwise a sane directory walk."""
    try:
        out = subprocess.check_output(
            ["git", "-C", root, "ls-files", "-z"], stderr=subprocess.DEVNULL)
        files = sorted(p.decode() for p in out.split(b"\0") if p)
        if files:
            return files
    except Exception:
        pass
    found = []
    for base, dirs, names in os.walk(root):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for n in names:
            if os.path.splitext(n)[1].lower() in SKIP_EXT:
                continue
            p = os.path.relpath(os.path.join(base, n), root)
            found.append(p.replace(os.sep, "/"))
    return sorted(found)


def find_apk(root):
    build = os.path.join(root, "build")
    if os.path.isdir(build):
        apks = sorted((os.path.join(build, n) for n in os.listdir(build)
                       if n.endswith(".apk")), key=os.path.getmtime, reverse=True)
        if apks:
            return apks[0]
    for base, dirs, names in os.walk(root):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for n in names:
            if n.endswith(".apk"):
                return os.path.join(base, n)
    return None


def main():
    ap = argparse.ArgumentParser(description="Publish ScreenWatch to GitHub via REST API")
    ap.add_argument("--repo", default=os.environ.get("GH_REPO", DEFAULT_REPO))
    ap.add_argument("--tag", default=DEFAULT_TAG)
    ap.add_argument("--title", default="ScreenWatch 1.0")
    ap.add_argument("--description", default=(
        "Android 10 悬浮窗定时截图对比，画面变化超过阈值播放本地音乐 3 分钟 | "
        "Floating window + per-minute screen-diff alarm for Android 10"))
    ap.add_argument("--apk", default=None, help="要上传到 Release 的 APK 路径")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    root = os.path.dirname(os.path.abspath(__file__))
    files = list_files(root)
    apk = args.apk or find_apk(root)

    if args.dry_run:
        print("仓库 :", args.repo, "(public)")
        print("标签 :", args.tag)
        print("APK  :", apk or "(未找到，将跳过上传)")
        print(f"文件 : {len(files)} 个")
        for f in files:
            print("  -", f)
        return 0

    token = os.environ.get("GH_TOKEN", "").strip()
    if not token:
        token = getpass.getpass("GitHub Personal Access Token (repo 权限): ").strip()
    if not token:
        print("没有 token，已退出。", file=sys.stderr)
        return 2

    try:
        me = api("GET", f"{API}/user", token)
    except ApiError as e:
        print(f"Token 无效或权限不足：{e}", file=sys.stderr)
        return 1
    owner = me["login"]
    print(f"登录为 @{owner}")

    # ---- repository -------------------------------------------------------
    repo_url = f"{API}/repos/{owner}/{args.repo}"
    exists = True
    try:
        repo = api("GET", repo_url, token)
    except ApiError as e:
        if e.status != 404:
            raise
        exists = False
        print(f"创建公开仓库 {owner}/{args.repo} …")
        repo = api("POST", f"{API}/user/repos", token, {
            "name": args.repo,
            "description": args.description,
            "private": False,
            "has_issues": True,
            "has_wiki": False,
            "has_projects": False,
            "auto_init": False,
        })
    branch = repo.get("default_branch") or "main"

    # ---- head / init empty repo ------------------------------------------
    head_sha = None
    try:
        ref = api("GET", f"{repo_url}/git/ref/heads/{branch}", token)
        head_sha = ref["object"]["sha"]
    except ApiError as e:
        if e.status not in (404, 409):  # 409 = empty repository
            raise
    if head_sha is None:
        # The Git Data API refuses to create blobs in a brand-new empty repo
        # (409 "Git Repository is empty"), so seed it via the Contents API.
        print("初始化空仓库 …")
        seed = api("PUT", f"{repo_url}/contents/README.md", token, {
            "message": "chore: init repository",
            "content": base64.b64encode(b"# ScreenWatch\n").decode(),
        })
        head_sha = seed["commit"]["sha"]

    # ---- one commit with all tracked files --------------------------------
    print(f"上传 {len(files)} 个文件 …")
    entries = []
    for rel in files:
        with open(os.path.join(root, rel), "rb") as fh:
            content = base64.b64encode(fh.read()).decode()
        blob = api("POST", f"{repo_url}/git/blobs", token,
                   {"content": content, "encoding": "base64"})
        entries.append({"path": rel, "mode": "100644", "type": "blob", "sha": blob["sha"]})

    tree = api("POST", f"{repo_url}/git/trees", token, {"tree": entries})

    commit = api("POST", f"{repo_url}/git/commits", token, {
        "message": "feat: ScreenWatch 1.0 — Android 10 floating window + per-minute screen diff alarm",
        "tree": tree["sha"],
        "parents": [head_sha],
    })

    api("PATCH", f"{repo_url}/git/refs/heads/{branch}", token,
        {"sha": commit["sha"], "force": False})

    # ---- tag --------------------------------------------------------------
    try:
        api("POST", f"{repo_url}/git/refs", token,
            {"ref": f"refs/tags/{args.tag}", "sha": commit["sha"]})
    except ApiError as e:
        if e.status != 422:  # 422 = tag already exists
            raise

    # ---- release ----------------------------------------------------------
    print("创建 Release …")
    release = None
    try:
        release = api("POST", f"{repo_url}/releases", token, {
            "tag_name": args.tag,
            "name": args.title,
            "body": RELEASE_BODY,
            "draft": False,
            "prerelease": False,
        })
    except ApiError as e:
        if e.status != 422:
            raise
        release = api("GET", f"{repo_url}/releases/tags/{args.tag}", token)

    # ---- APK asset --------------------------------------------------------
    if apk and os.path.isfile(apk):
        name = os.path.basename(apk)
        print(f"上传 {name} 到 Release …")
        try:
            with open(apk, "rb") as fh:
                data = fh.read()
            upload_url = (f"{UPLOAD}/repos/{owner}/{args.repo}/releases/"
                          f"{release['id']}/assets?name={urllib.parse.quote(name)}")
            api("POST", upload_url, token, raw=data,
                content_type="application/vnd.android.package-archive",
                timeout=180, retries=2)
        except Exception as e:
            print(f"⚠️  APK 上传失败（{e}）。")
            print("   可稍后在网页版 Release 页面手动拖入 APK：")
            print("   ", os.path.abspath(apk))

    # ---- topics -----------------------------------------------------------
    try:
        api("PUT", f"{repo_url}/topics", token, {
            "names": ["android", "android-10", "mediaprojection", "screen-capture",
                      "overlay-window", "automation", "java"],
        })
    except Exception as e:
        print(f"（设置 topics 失败，可忽略：{e}）")

    print()
    print("✅ 发布完成")
    print("仓库   :", repo["html_url"])
    print("Release:", f"{repo['html_url']}/releases/tag/{args.tag}")
    print()
    print("记得去 GitHub → Settings → Applications 撤销临时 token。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
