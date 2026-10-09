# local-repo —— 自带一份上游 API

## 为什么需要它

本插件的构建需要上游的 `com.github.Moriafly:spw-workshop-api:0.1.0-dev21`。
它在 JitPack 上的 **API jar 是坏的**：

```
$ curl -sI https://jitpack.io/com/github/Moriafly/spw-workshop-api/0.1.0-dev21/spw-workshop-api-0.1.0-dev21.jar
content-length: 308          # ← 只有 308 字节，里面仅一个空 MANIFEST，没有任何类

$ curl -sI https://jitpack.io/com/github/Moriafly/spw-workshop-api/0.1.0-dev20/spw-workshop-api-0.1.0-dev20.jar
content-length: 23738        # ← dev20 是正常的
```

用那个空 jar 编译会直接失败（找不到 `com.xuncorp.spw.workshop.api.*` 的任何类），
所以在 JitPack 修好之前，本仓库自带一份**真正的 jar**，通过 `settings.gradle.kts` 里
最高优先级的 `local-repo` 仓库解析同名同版本坐标。

**文件来源**

| 项目 | 值 |
| --- | --- |
| 上游仓库 | <https://github.com/Moriafly/spw-workshop-api> |
| 版本 | `0.1.0-dev21`（tag `0.1.0-dev21`，commit `65e5eee`） |
| jar 构建方式 | 在 tag `0.1.0-dev21` 的源码上执行上游自带的 `:api:jar` 任务 |
| 校验 | 该 tag 的 `api/src` 源码与当前 main 分支逐文件哈希一致，因此与本仓库开发时使用的 API 完全相同 |
| 大小 | 33 KB（对比 JitPack 那个 308 字节的空包） |
| 许可 | Apache-2.0（与上游一致，见仓库根目录的 `LICENSE`） |

`spw-workshop-api-0.1.0-dev21.pom` 是手写的最小 POM：
**故意不写**上游声明的 `pf4j` / `compose-ui` / `compose-foundation` / `salt-ui` 依赖，
因为本插件自己声明 pf4j，也不需要 Compose 和 salt-ui——这样 CI 不必为了编译 API 再拉几百 MB。

## 上游修好之后怎么办

1. 确认 JitPack 上的 jar 恢复正常：

   ```bash
   curl -sI https://jitpack.io/com/github/Moriafly/spw-workshop-api/0.1.0-dev21/spw-workshop-api-0.1.0-dev21.jar | grep -i content-length
   ```

2. 删除本目录里的 `com/github/Moriafly/spw-workshop-api/`，并删除 `settings.gradle.kts` 里的
   `local-repo` 仓库声明即可——依赖坐标不用改。

## 也可以选择用 dev20

`0.1.0-dev20` 在 JitPack 上是完整可用的。改 `gradle/libs.versions.toml` 里的
`spw-workshop-api = "0.1.0-dev20"` 并删掉本目录即可，但那样测试里的假宿主需要去掉
dev21 新增的成员（`WorkshopApi.library`、`Manager.keyBindingManager`、`Playback.getCurrentMediaItem`）。
