# 上游同步与自有仓库维护

## 两份目录，各司其职

- `upstream` 目录：原始 `try2love/codex-mobile-bridge` 的干净参考副本，禁止推送。
- `702165405` 目录：你的正式维护副本。`origin` 是 `702165405/codex-mobile-bridge`，`upstream` 只用于拉取原作者更新。
- 根目录旧版和 `.tmp/latest-source-*` 是历史工作副本，不再作为发布来源。历史修改保留，不直接删除。

## 每次同步必须经过检查

在自己的仓库目录执行：

```powershell
python scripts/prepare-upstream-sync.py
```

脚本先检查工作区干净、远程地址正确，再拉取双方主分支，创建独立 `sync/upstream-*` 审查分支并执行未提交的合并。冲突会保留供处理；不会自动提交、推送、强制重置或改写 `main`。差异摘要写入忽略的 `.tmp/upstream-review-*.md`。

1. 检查上游提交及 `git diff --cached`，重点审查认证、HTTP/IPC 边界、路径校验、依赖、构建/更新签名及 Actions 权限；保留本分支推送限制和其他自定义修复。
2. 运行全部测试：

```powershell
python -B -m unittest discover -s tests -v
npm run test:desktop
npm run test:updater
```

3. 在 PR 中记录检查结论、测试结果和未覆盖的平台检查；通过后再合并到 **702165405/main**。保留 merge commit，避免 squash 丢失上游合并祖先。
4. 以自己的代码构建、安装并验证，不直接用上游安装包覆盖自定义修复。

有冲突或测试失败时停止合并；不要用 `git reset --hard`、强制推送或关闭安全校验绕过。

## 发布和本机配置

本分支桌面更新源指向 `702165405/codex-mobile-bridge`。目前没有为本仓库创建正式签名发布；发布前需要生成自己的更新签名密钥、公钥并配置仓库的 `UPDATE_SIGNING_KEY` Secret。不能使用原作者私钥，也不能移除验签。

为了保留已有 Windows 安装、凭据和数据目录，本次不修改 appId 或产品名。

当前电脑的 GitHub 联网使用已有本机代理，Git 配置只保存在这两份仓库的本地 `.git/config`，不写入仓库文件。其他电脑按自己的网络情况配置；代理不可用时应修正或移除自己的本地 `http.proxy`，不要全局改写。

本流程按需执行，没有创建定时自动合并任务。即使日后加入定时拉取，也必须保留审查和测试这一步。
