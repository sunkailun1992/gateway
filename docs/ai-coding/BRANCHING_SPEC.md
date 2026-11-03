# 分支管理规范

本项目使用 `main`、`test`、`preprod` 三条受保护长期分支与短生命周期功能分支。Git 分支表示代码集成和候选状态，不自动绑定 Spring profile、Nacos namespace 或部署环境。

## 长期分支职责

| 分支 | 职责 | 保护要求 |
|---|---|---|
| `main` | 正式主干与已完成交付的确定代码 | 可构建、可测试；禁止 force push 和删除 |
| `test` | 完整功能的集成测试，可包含尚未正式发布的需求 | 保留历史；禁止 force push 和删除 |
| `preprod` | 已选定完整功能及配套组成的发布候选 | 固定候选 commit 与验证证据；禁止 force push 和删除 |

- 三条长期分支缺失时，先核对远端；按用户授权从本次已完成并核实的 `main` 创建缺失分支。已有分支保留各自历史，不 reset、不强推对齐。
- 不新建额外长期 `dev/staging/prod` 分支，不删除其他既有受保护、环境或私有化长期分支。
- 创建或推送 Git 分支不代表测试、预发或生产环境已部署。是否存在 CI、自动化或运行环境，必须单独取证。

## 新任务起点与在途保护

1. 核对真实仓库、当前分支、HEAD、dirty、远端和其他会话工作，再执行 `git fetch origin`。
2. 新功能、修复、测试、文档或构建任务默认从最新 `origin/main` 的确定 SHA 创建独立短分支；不从混合 `test` 或 `preprod` 随意拉新功能。
3. 继续同一功能可复用已核对任务归属与基线的源分支；只读查询无需建分支。用户已明确指定基线或续作范围时按该次授权执行，不重复切分支。
4. 保留无关 dirty、未推送提交和其他会话文件。不得用 stash/reset/clean、强推或删除 worktree 掩盖冲突；有无法区分的改动时先报告具体文件。
5. 开工、提交前与推送前都 fetch。远端前进后先同步并重做风险匹配验证，不覆盖远端新历史。

## 短分支命名与范围

使用 `feature/*`、`fix/*`、`hotfix/*`、`docs/*`、`chore/*`、`refactor/*`、`test/*`、`release/*`；Codex 可按用户或工具约定使用 `codex/*`。一个分支只承载一个完整目标，包含必要代码、测试、文档、migration、公共契约和依赖，不混入其他需求。

`test/<scope>` 是短测试分支，与受保护长期分支 `test` 区分。`release/<version>` 仅用于冻结候选，不成为第四条长期分支。

## 完整功能集成与候选验证

1. 在功能源分支完成风险匹配的本地编译、测试、安全专项检查和必要独立验证。代码 Review 是补充证据，不额外增设人工审批；已有仓库检查与用户授权边界仍须遵守。
2. 经授权将完整功能 merge/快进合入 `test` 并验证确定 commit 的集成结果。仅进入 `test` 时必须保留源分支；后续修复仍在该源分支完成，不能混回 `test` 的其他需求。
3. 发布候选以最新 `main` 加本批完整功能分支及全部必需跨仓配套组成，经授权进入 `preprod` 并验证。混合 `test` 不默认整条合入 `preprod` 或 `main`；只有本批候选范围已明确时才整体交付。
4. 验证通过后将同一完整候选整体 merge/快进合入 `main`。常规交付不逐提交 cherry-pick 拼装；main、候选或必要配套前进时重新核对完整差异并验证。
5. 推送后读回远端 refs 与包含关系，记录实际 CI 状态。Git 已提交、本地测试通过、远端已更新、CI 成功、制品已发布和环境已部署分别报告，不相互替代。

跨仓候选必须记录各仓固定 HEAD、精确接口/制品版本、配置与 migration。缺少必要配套不能称为正式就绪；不要复制其他仓库源码替代依赖。公共库先做 Maven Local 与消费者验证，远程 Maven 发布、tag 和部署遵守各仓版本及授权规则。

## Release、hotfix 与 tag

- `release/*` 只接收本批阻塞发布的修复，固定候选及回滚目标，不继续开发其他功能。
- `hotfix/*` 从已确认的线上 tag 或最新 main 开始，只修改紧急修复必需内容；主干与仍需该修复的集成候选分别同步并验证。
- tag 使用与实际制品版本一致的 `v<major>.<minor>.<patch>`，指向已验证 commit；不移动、覆盖或删除已发布 tag。
- commit、push、merge、tag、制品发布和部署按用户已给出的范围执行，不把创建分支或改文件等同于新增外部操作授权。
- 纯规范文档不提升业务/公共库版本，也不重复发布已存在的 Maven 制品。

## 环境与 Nacos 边界

- `test`、`preprod` 是 Git 集成和候选分支名，不据此自动切换 `APP_ENV`、Spring profile、Nacos namespace、Dockerfile 或前端构建入口。
- 当前个人 dev 仍为 `APP_ENV=dev`、Nacos namespace `github-dev`。服务从仓库根目录的 ignored `config/application-dev.properties` 读取本机引导；base `application.yml` 不因此改写。
- test/preprod/prod 的未来运行环境映射尚需单独配置和验收。建立 Git 分支不创建 Nacos namespace、数据库或部署，不复用公司生产资源。
- 库项目不部署服务运行环境。配置 group、灰度、Dubbo tag、权重和注册元数据属于运行时治理，不由 Git 分支名推断。
- 真实地址、密码、证书和密钥只在受控私有配置中，具体入口遵守 `ENVIRONMENT_CONFIG_SPEC.md`。

## 完成交付后的功能分支清理

本次用户已要求合入 main 后清理功能分支。完成以下条件后，删除本次已合入的本地与远端短分支，不留下已结束任务的源分支：

- 完整功能及全部必需配套已进入各自 `main`；fresh fetch 后，本地 main 与远端 main 的包含关系和交付 SHA 已核实。
- 待删除的本地及远端功能分支 HEAD 均属于已交付范围，远端源分支没有其他会话的新提交。通常用 `git merge-base --is-ancestor <feature-head> origin/main` 核对。
- 若显式采用 squash/等价合并，须有完整差异与验证证据；仅部分提交等价或 `git cherry` 结果不足以证明全部交付。
- `git worktree list --porcelain` 确认没有其他 worktree 占用该分支；没有 dirty、唯一未推送提交或仍在使用的依赖。其他会话和用途不明的分支保留并说明原因。

只合入 `test` 或 `preprod`、尚未合入 `main` 的源分支不得删除。`main`、`test`、`preprod` 及其他既有受保护长期分支不属于清理对象。分支变旧只触发复核，不自动证明已废弃。用户明确要求废弃未合分支时另按具体授权处理。

已完成包含关系与 worktree 核对后的清理示例：

```bash
git fetch origin
git worktree list --porcelain
git merge-base --is-ancestor <feature-head> origin/main
git switch main
git branch -d <merged-feature-branch>
git push origin --delete <merged-feature-branch>
git fetch --prune origin
```

远端分支从未创建或已删除时，只处理实际存在的本地分支，不新建后再删除。删除后读回远端 heads 与本地 refs，记录成功或保留原因；不得用 `-D`、force push 或批量删除所有 merged 分支绕过保护条件。释放 worktree 目录与删除源分支是两件事，不能清理他人 worktree。

## 交付回执

写明当前工作分支、功能源分支固定 HEAD、test 集成、preprod 候选、main 交付 SHA、跨仓配套、版本/制品、验证命令和结果、远端 refs、实际 CI/部署状态，以及已删除或因保护条件保留的分支。没有执行或无法核实的阶段明确标注，不推测完成。
