# 兼容性与预览工具

这些脚本都不需要启动播放器，也不会连网（依赖从 Gradle 缓存或 `-LibDir` 取）。

| 脚本 | 作用 |
| --- | --- |
| `extract-host-api.ps1` | 从本机已安装的 SPW 里抽出 Workshop API 类，做成编译桩 |
| `run-verify.ps1` | 用真实 PF4J + 旧宿主（1.18.5）同款加载流程验证分发包，20 项检查 |
| `preview-report.ps1` | 用真实统计数据生成报表预览 HTML |

## 用法

```powershell
cd tools\compat-check

# 1) 抽取旧宿主 API（默认自动寻找 Steam 安装目录，也可 -SpwDir 指定）
powershell -ExecutionPolicy Bypass -File .\extract-host-api.ps1

# 2) 验证刚打出来的分发包
powershell -ExecutionPolicy Bypass -File .\run-verify.ps1 -PluginZip ..\..\build\libs\plugin-com.spwmods.listenstats-1.1.4.zip

# 3) （可选）用真实听歌数据生成报表预览
powershell -ExecutionPolicy Bypass -File .\preview-report.ps1 `
    -PluginZip ..\..\build\libs\plugin-com.spwmods.listenstats-1.1.4.zip `
    -DbFile "$env:APPDATA\Salt Player for Windows\workshop\data\com.spwmods.listenstats\listen-stats.db.properties"
```

## `run-verify.ps1` 检查了什么

1. 分发包被识别、Manifest 里的 ID / 版本 / 名称正确（Manifest 在 `classes/META-INF/`）
2. 未写 `enabled.txt` 时插件保持 `DISABLED`、`PlaybackExtensionPoint` 不激活（旧宿主是白名单）
3. 写入插件 ID 后插件 `STARTED`，`start()` 被调用，发现 1 个扩展点
4. 驱动播放回调：听满 30 秒计 1 次、单曲循环再计 1 次、收听时长落盘

退出码 0 表示 20 项全部通过。

## 说明

- `PluginLoadHarness.java` 复刻了宿主加载器的关键组件：`WorkshopPluginManager` 子类、
  `enabled.txt` 白名单状态提供者、`classes/META-INF/MANIFEST.MF` 描述文件查找、
  `PluginContext` 单参构造的插件工厂——全部按 `androidx.compose.ui.ht/hq/hr/hu/hs` 的行为实现。
- 编译桩（`build/host-api/spw-workshop-api-legacy.jar`）是**本机宿主**的代码，只用于本地验证，
  已被 `.gitignore` 忽略，请不要提交到仓库。
- 需要 JDK 21+（脚本会依次尝试 `JAVA_HOME`、PATH 里的 `javac`）。
