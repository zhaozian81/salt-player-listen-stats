# 宿主加载器笔记（SPW 1.18.5 / 1.19.0）

这份笔记是开发过程中对 Salt Player for Windows（SPW）创意工坊加载器做的实际核对结果，
用来解释本插件的打包方式为什么长这样。结论都可在本机安装的 SPW 上复现，命令见文末。

## 一句话结论

| 结论 | 影响 |
| --- | --- |
| SPW 自带 PF4J 插件框架，**1.18.5 就有**加载器 | 不需要等新版本才有 Mod 支持 |
| 加载器被 R8 混淆进 `androidx.compose.ui.*` | 用字符串搜索 `SpwPlugin` / `pf4j` 找不到它，容易误判成"没有加载器" |
| 只扫描 **`.zip` / `.jar`** | `.spmod` 会被静默忽略，插件"装了但没出现" |
| `enabled.txt` 是**白名单** | 空文件 = 全部禁用；插件会被加载但保持 `DISABLED`，扩展点不激活 |
| Manifest 从 `classes/META-INF/MANIFEST.MF` 读 | 主 jar 内容必须放在分发包的 `classes/` 下 |
| 扩展点索引 `META-INF/extensions.idx` 必需 | 由 pf4j 注解处理器（kapt）生成，缺了扩展点不会被发现 |
| 展开目录存在时不会重新解压 | 更新版本必须先删 `plugins/plugin-<id>-<版本>/` |

## 宿主是怎么打包的

```
<SPW 安装目录>\
├─ Salt Player for Windows.exe     # jpackage 启动器
├─ app\
│  ├─ Salt Player for Windows.cfg  # app.classpath=$APPDIR\ffmpeg-x64.dll
│  ├─ ffmpeg-x64.dll               # ← 整个程序：从字节 0 开始就是一个完整 zip（fat jar）
│  └─ voxzen.aot                   # JDK AOT 缓存
└─ runtime\                        # 裁剪过的 JRE
```

`.cfg` 里的 `app.classpath` 指向这个伪装成 dll 的 fat jar，所以应用类、PF4J、Workshop API 全在里面。

## 加载器藏在哪（混淆后的真实位置）

| 混淆类 | 职责 | 判定依据 |
| --- | --- | --- |
| `androidx.compose.ui.ht` | 插件管理器 | `extends com.xuncorp.spw.workshop.api.WorkshopPluginManager`；描述文件用 `Compound(PropertiesPluginDescriptorFinder, hr)`；工厂是 `hs` |
| `androidx.compose.ui.hq` / `hr` | 读 Manifest | 依次找 `META-INF/MANIFEST.MF`、`classes/META-INF/MANIFEST.MF` |
| `androidx.compose.ui.hu` | 插件启用状态 | 构造时读 `pluginsRoot/enabled.txt`；`isPluginDisabled(id) = !enabled.contains(id)`（**白名单**），并实现 enable/disable 回写 |
| `androidx.compose.ui.hs` | 插件工厂 | `Class.forName(pluginClass, true, pluginClassLoader)` 后优先用 `PluginContext` 单参构造器实例化 |
| `androidx.compose.ui.hv` | 插件运行时 | 后缀判断 `.zip/.jar`（含大小写变体）、"Failed to load plugin"、`getExtensions`、"插件"、`.oldPlugin`；提供 `WorkshopApi` 的 playback/ui/manager 实现 |
| `androidx.compose.ui.hw` / `hx` | 配置读写 | `preference_config.json` 经 `ClassLoader.getResourceAsStream` 读取；配置文件在数据目录 |
| `com.xuncorp.voxzen.service.PlaybackService` | 播放回调分发 | `updateProgress` 每秒遍历 `PlaybackExtensionPoint` 调 `onPositionUpdated`；`updateLyrics` 调 `onBeforeLoadLyrics` / `onAfterLoadLyrics` |

界面侧的类名没有被混淆（含源码行号字符串），例如
`com.xuncorp.voxzen.ui.screen.workshop.ModManagementScreen`（模组管理）、
`ModConfigScreen`（插件配置页）、`EnableModDialog` / `DeleteModDialog` / `UpdateModDialog`。

## 分发包格式

上游 `com.xuncorp.spw.workshop` Gradle 插件产出的 `.spmod` 布局：

```
plugin-<插件 ID>-<版本>.spmod      # 就是一个 zip
├─ classes/                       # 主 jar 的内容（含 META-INF/MANIFEST.MF 与 extensions.idx）
│  ├─ META-INF/MANIFEST.MF        # Plugin-Class / Plugin-Id / Plugin-Version / Plugin-Name ...
│  ├─ META-INF/extensions.idx     # PF4J 扩展点索引，kapt 生成
│  └─ com/example/...class
└─ lib/                           # runtimeClasspath 里的 jar（compileOnly 不进）
```

打包关键点：

- **主 jar 展开到 `classes/`**，Manifest 才会落在 `classes/META-INF/MANIFEST.MF` 被找到
- **Kotlin 标准库不要打进去**（`compileOnly(kotlin("stdlib"))`）：宿主自带，插件类加载器会向上委托
- **`extensions.idx` 必须存在**，否则 `getExtensions()` 返回空列表
- 分发包后缀在 1.18.5 上必须是 `.zip` 或 `.jar`

## API 兼容策略

上游 JitPack 上的最新 API（`0.1.0-dev21`，对应 SPW 1.19.0）比 1.18.5 多出
`MediaItem.id/duration`、`Manager.isPermissionGranted/keyBindingManager`、`WorkshopApi.library`、
`Playback.getCurrentMediaItem` 等成员。若插件调用它们，在 1.18.5 上会直接 `NoSuchMethodError`。

本插件的做法：**对着新版 API 编译，只使用两版共有的成员**，然后用
`tools/compat-check/` 把打包产物放到"旧宿主 API + 真实 PF4J"的加载流程里完整跑一遍，
确保没有越界调用。

## 安装 / 更新要点

1. 把 `plugin-*.zip` 放进 `%APPDATA%\Salt Player for Windows\workshop\plugins\`（只放一个副本）
2. 在 `enabled.txt` 里加一行插件 ID，或在模组管理界面点「启用」
3. 完全退出 SPW 再启动
4. **更新时**：先删掉 `plugins/plugin-<id>-<版本>/` 展开目录，否则 PF4J 认为它已解压而继续跑旧代码；
   同时不要留下两个同 ID 的分发包

## 复现这些结论

```powershell
$spw = "D:\steam\st\steamapps\common\Salt Player for Windows"
$dll = "$spw\app\ffmpeg-x64.dll"
$tmp = "$env:TEMP\spw-jar"

# 1) 确认 fat jar（前 4 字节应为 50 4B = "PK"）
Format-Hex -Path $dll -Count 4

# 2) 解开（注意：Windows 文件系统大小写不敏感，混淆类里存在仅大小写不同的名字，
#    直接解压会互相覆盖；只想看内容的话优先级无所谓，逐个用 javap 读 zip 更稳妥）
tar.exe -xf $dll -C $tmp

# 3) 直接对 zip 里的类反编译（Java 的 ZipFile 区分大小写，不会踩上面的坑）
$javap = "<JDK>\bin\javap.exe"
& $javap -p -classpath $dll androidx.compose.ui.ht
& $javap -v -p -classpath $dll androidx.compose.ui.hv | Select-String "zip|jar|plugin|插件"

# 4) 找出所有引用 Workshop API 的宿主类
cd $tmp; findstr /S /M /C:"spw/workshop" *.class
```

`findstr` 在本仓库的 `tools/compat-check` 里已用于验证流程，可以直接参考。
