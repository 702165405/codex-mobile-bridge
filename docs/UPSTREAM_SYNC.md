# 自有 main 与上游同步

本项目以 **702165405/codex-mobile-bridge 的 `main`** 为主，本地开发和定制功能最终合入此分支。安卓功能已由 `codex/android-native` 整合到 `main`，原分支保留历史。

| 远程 | 地址 | 用途 |
| --- | --- | --- |
| `origin` | `https://github.com/702165405/codex-mobile-bridge.git` | 自有仓库，拉取和推送 |
| `upstream` | `https://github.com/try2love/codex-mobile-bridge.git` | 原作者仓库，只拉取 `main` |

当前目录就是正式维护副本，无需另建源仓库目录。此电脑已设置 `main` 跟踪 `origin/main`、默认推送到 `origin`，并禁用 `upstream` 推送。其他电脑克隆后需单独配置这些本地 Git 设置；它们不随提交传播。

## 每次更新

先提交或妥善保存本地修改，将需要保留的功能分支合入本地 `main`。在干净的 `main` 执行：

```sh
python3 scripts/prepare-upstream-sync.py
```

脚本依次拉取 `upstream/main` 和 `origin/main`，任一失败就停止。没有待合入的提交时保持当前分支；有更新时，从**本地 `main`** 建立 `codex/sync-upstream-*` 审查分支，先合并自有远程 `origin/main`，再准备上游的未提交合并。本地尚未推送的提交会保留，不会被远程分支替代。

自有远程与本地分叉时，脚本可能在审查分支生成一次 merge commit。任一步发生冲突都会停止并保留现场；解决自有远程冲突并提交后，继续执行 `git merge --no-ff --no-commit upstream/main`。脚本不会推送或改动 `main`。成功准备后，差异摘要写入被忽略的 `.tmp/upstream-review-*.md`；同时检查 `git diff main`，覆盖远程和上游的全部变化。

1. 审查新增功能和安全敏感变化：认证、CSRF、HTTP/IPC、文件路径、依赖、构建/更新签名、Actions 权限。保留本仓库的安卓功能、模型目录兼容和通知修复。冲突以自有分支的预期行为为主，同时保留适用的上游安全修复，不盲目使用 `-X ours`。
2. 执行回归：

```sh
python3 -B -m unittest discover -s tests -v
npm run test:desktop
npm run test:updater
```

`tests/markdown.test.js` 和 `tests/math.test.js` 需要在加载网关资源的浏览器页面运行，不能直接用 Node 执行。安卓有变化时，使用现有 JDK/SDK 执行：

```sh
export GRADLE_USER_HOME="$PWD/.tmp/android-gradle-cache"
android/gradlew -p android :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

3. 记录安全审查、新功能、测试结果和未覆盖的平台/设备检查。检查通过后提交待完成的 merge，合入自有 `main` 并推送 `origin main`；也可通过 PR 合并。保留 merge 历史，不 squash 上游同步。推送前再次拉取 `origin/main`，如有新提交，合并后重新检查受影响部分。

测试失败时先定位问题，不用强制重置、强制推送或关闭安全校验绕过。开发目录中的凭据、签名密钥、`.local`、`.tmp` 和构建产物始终不入库。

## 更新来源与发布

安卓和桌面应用内更新均使用自有仓库的 Release。不要直接用上游安装包覆盖自有功能。桌面正式发布前需单独配置自己的更新签名密钥和公钥，并保留验签；安卓覆盖升级使用已有本地签名，详见 [安卓说明](../android/README.md)。

此流程按需执行；没有创建定时拉取、定时合并或自动发布任务。
