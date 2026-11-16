# 发布到 GitHub（国内网络可用）

这台手机上 `git push` 到 github.com 经常被重置，但 `api.github.com` 是通的。
`publish.py` 用 GitHub REST API 完成发布：建公开仓库 → 上传全部源码（一个提交）→ 打 `v1.0.0` 标签 → 建 Release → 上传 APK → 设置 topics。

## 1. 生成一个临时 Token

打开（已登录 GitHub 的前提下）：

<https://github.com/settings/tokens/new?scopes=repo&description=screenwatch>

- **Expiration** 选 7 days
- 确认勾选了 **repo**（整个大项）
- 拉到底点 **Generate token**
- 复制 `ghp_` 开头的字符串（只显示一次）

## 2. 运行发布脚本

两种方式任选：

**A. 在本机终端里自己跑**

```bash
cd /root/workspace/screenwatch
python3 publish.py
# 按提示粘贴 token（输入不回显，不会留在命令历史里）
```

**B. 把 token 发给 DSH，由 DSH 代跑**

```
GH_TOKEN=ghp_xxx python3 publish.py
```

## 3. 结果

脚本最后会打印：

```
✅ 发布完成
仓库   : https://github.com/<你的用户名>/screenwatch-android
Release: https://github.com/<你的用户名>/screenwatch-android/releases/tag/v1.0.0
```

## 4. 收尾

- 去 <https://github.com/settings/tokens> 删除刚才的 token。
- 本次对话里出现过的账号密码请立即修改，并开启两步验证（2FA）。

## 可选参数

```bash
python3 publish.py --repo my-repo --tag v1.0.0 --title "ScreenWatch 1.0"
python3 publish.py --dry-run          # 只看会上传哪些文件，不联网
```

## 常见问题

**422 Bad credentials / 401**
token 复制不完整，或没有 `repo` 权限；重新生成。

**Release 已存在**
脚本会复用已存在的 Release；再次运行不会报错。

**APK 上传失败**
脚本会提示 APK 的本地路径，到网页版 Release 页面手动拖入即可。
