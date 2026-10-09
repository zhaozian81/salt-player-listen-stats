import com.xuncorp.spw.workshop.api.Channel;
import com.xuncorp.spw.workshop.api.PlaybackExtensionPoint;
import com.xuncorp.spw.workshop.api.PluginContext;
import com.xuncorp.spw.workshop.api.WorkshopApi;
import com.xuncorp.spw.workshop.api.WorkshopPluginManager;
import com.xuncorp.spw.workshop.api.config.ConfigHelper;
import com.xuncorp.spw.workshop.api.config.ConfigManager;
import org.pf4j.CompoundPluginDescriptorFinder;
import org.pf4j.DefaultPluginFactory;
import org.pf4j.ManifestPluginDescriptorFinder;
import org.pf4j.Plugin;
import org.pf4j.PluginDescriptorFinder;
import org.pf4j.PluginFactory;
import org.pf4j.PluginState;
import org.pf4j.PluginStatusProvider;
import org.pf4j.PluginWrapper;
import org.pf4j.PropertiesPluginDescriptorFinder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 用真实 PF4J + 宿主同款加载流程验证 .zip 分发包：
 *
 *  1. 未写 enabled.txt 时插件应被识别但保持 DISABLED（扩展点不激活）
 *  2. 把插件 ID 写进 enabled.txt 后应 STARTED 并暴露 1 个 PlaybackExtensionPoint
 *  3. 驱动 onBeforeLoadLyrics / onPositionUpdated，验证 30 秒门槛与落盘
 *  4. 单曲循环（onSeekTo(0)）后应再计一次
 *
 * 用法：java PluginLoadHarness <workDir> <plugin.zip>
 */
public final class PluginLoadHarness {

    private static final String PLUGIN_ID = "com.spwmods.listenstats";
    private static final String DB_NAME = "listen-stats.db.properties";

    private static int checks = 0;
    private static int failures = 0;
    private static FakeUi lastUi;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("用法: PluginLoadHarness <workDir> <plugin.zip>");
            System.exit(2);
        }
        Path workDir = Paths.get(args[0]).toAbsolutePath();
        Path pluginZip = Paths.get(args[1]).toAbsolutePath();
        if (!Files.isRegularFile(pluginZip)) {
            System.out.println("[FAIL] 找不到待测分发包: " + pluginZip);
            System.exit(1);
        }

        Path pluginsRoot = workDir.resolve("plugins");
        Path dataDir = workDir.resolve("data").resolve(PLUGIN_ID);

        System.out.println("== 阶段 1：未启用（enabled.txt 里没有插件 ID） ==");
        resetPluginsRoot(workDir, pluginsRoot, pluginZip);
        runPhase(pluginsRoot, dataDir, false);

        System.out.println();
        System.out.println("== 阶段 2：已启用（enabled.txt 写入插件 ID） ==");
        resetPluginsRoot(workDir, pluginsRoot, pluginZip);
        runPhase(pluginsRoot, dataDir, true);

        System.out.println();
        System.out.printf("== 结果：%d 项检查，失败 %d 项 ==%n", checks, failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ 场景

    private static void runPhase(Path pluginsRoot, Path dataDir, boolean enabled) throws Exception {
        if (enabled) {
            Files.write(pluginsRoot.resolve("enabled.txt"),
                    (PLUGIN_ID + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        } else {
            Files.write(pluginsRoot.resolve("enabled.txt"), new byte[0]);
        }

        // 假宿主：只提供插件实际会用到的最小能力，配置目录指向本工作目录
        FakeApi api = new FakeApi(dataDir);
        lastUi = api.ui;
        WorkshopApi.Companion.setInstance(api);

        TestPluginManager manager = new TestPluginManager(pluginsRoot);
        try {
            manager.loadPlugins();

            List<PluginWrapper> wrappers = manager.getPlugins();
            check(wrappers.size() == 1, "插件文件被识别（loadPlugins 得到 1 个插件）");
            if (wrappers.isEmpty()) {
                return;
            }
            PluginWrapper wrapper = wrappers.get(0);
            check(PLUGIN_ID.equals(wrapper.getPluginId()), "插件 ID = " + PLUGIN_ID + "（实际 " + wrapper.getPluginId() + "）");
            // 版本号不写死：以 Manifest 为准，避免每发一版都要改验证程序
            String manifestVersion = readManifestValue(manager, wrapper, "Plugin-Version");
            check(manifestVersion != null && !manifestVersion.isBlank()
                            && manifestVersion.equals(wrapper.getDescriptor().getVersion()),
                    "插件版本与 Manifest 一致：" + wrapper.getDescriptor().getVersion());
            String manifestName = readManifestValue(manager, wrapper, "Plugin-Name");
            check("听歌统计".equals(manifestName),
                    "Manifest 里的插件名 = 听歌统计（实际 " + manifestName + "）");

            if (!enabled) {
                check(wrapper.getPluginState() == PluginState.DISABLED,
                        "未启用时状态是 DISABLED（实际 " + wrapper.getPluginState() + "）");
                manager.startPlugins();
                check(manager.getExtensions(PlaybackExtensionPoint.class).isEmpty(),
                        "未启用时 PlaybackExtensionPoint 不激活");
                return;
            }

            manager.startPlugins();
            check(wrapper.getPluginState() == PluginState.STARTED,
                    "启用后状态是 STARTED（实际 " + wrapper.getPluginState() + "）");
            check(lastUi != null && lastUi.messages.stream().anyMatch(m -> m.contains("听歌统计")),
                    "插件 start() 被调用（收到吐司）");

            List<PlaybackExtensionPoint> extensions = manager.getExtensions(PlaybackExtensionPoint.class);
            check(extensions.size() == 1, "发现 1 个 PlaybackExtensionPoint 扩展（实际 " + extensions.size() + "）");
            if (extensions.isEmpty()) {
                return;
            }

            driveCounting(extensions.get(0), dataDir);
            manager.stopPlugins();
        } finally {
            try {
                manager.unloadPlugins();
            } catch (Throwable ignored) {
                // 卸载失败不影响结论
            }
        }
    }

    private static String readManifestValue(TestPluginManager manager, PluginWrapper wrapper, String key) {
        try {
            return manager.readManifest(wrapper).getMainAttributes().getValue(key);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void driveCounting(PlaybackExtensionPoint extension, Path dataDir) throws Exception {
        PlaybackExtensionPoint.MediaItem item = new PlaybackExtensionPoint.MediaItem(
                "测试歌曲", "测试歌手", "测试专辑", "测试歌手", "C:\\Music\\harness.mp3");

        extension.onBeforeLoadLyrics(item);
        extension.onIsPlayingChanged(true);
        for (int second = 1; second <= 31; second++) {
            extension.onPositionUpdated(second * 1000L);
        }

        Properties props = loadProps(dataDir);
        List<String> countKeys = props.stringPropertyNames().stream()
                .filter(name -> name.startsWith("t.") && name.endsWith(".count"))
                .collect(Collectors.toList());
        check(countKeys.size() == 1, "只生成一条曲目记录（实际 " + countKeys.size() + " 条）");
        check(countKeys.size() == 1 && "1".equals(props.getProperty(countKeys.get(0))),
                "听满 30 秒计 1 次");
        check("1".equals(props.getProperty("total.plays")), "total.plays = 1");
        check(props.stringPropertyNames().stream()
                        .anyMatch(name -> name.endsWith(".title") && "测试歌曲".equals(props.getProperty(name))),
                "记录了歌曲元数据（title）");

        // 单曲循环：拖回开头后应重新累计，并再计一次
        extension.onSeekTo(0L);
        for (int second = 1; second <= 31; second++) {
            extension.onPositionUpdated(second * 1000L);
        }
        Properties after = loadProps(dataDir);
        check(countKeys.size() == 1 && "2".equals(after.getProperty(countKeys.get(0))),
                "单曲循环后计数变成 2（实际 "
                        + (countKeys.size() == 1 ? after.getProperty(countKeys.get(0)) : "无记录") + "）");

        // 播放结束：结算这一遍的收听时长
        extension.onStateChanged(PlaybackExtensionPoint.State.Ended);
        Properties closed = loadProps(dataDir);
        long totalMs = Long.parseLong(closed.getProperty("total.ms", "0"));
        check(totalMs >= 60_000L,
                "两遍的累计收听时长已落盘且 ≥ 60 秒（实际 " + totalMs + " ms）");
        check(countKeys.size() == 1
                        && Long.parseLong(closed.getProperty(countKeys.get(0).replace(".count", ".ms"), "0")) >= 60_000L,
                "曲目级累计收听时长 ≥ 60 秒");
    }

    // ------------------------------------------------------------------ 辅助

    private static void resetPluginsRoot(Path workDir, Path pluginsRoot, Path pluginZip) throws IOException {
        // 只清理运行期目录，避免把验证程序自己的 class 文件一起删掉
        deleteRecursively(pluginsRoot);
        deleteRecursively(workDir.resolve("data"));
        Files.createDirectories(pluginsRoot);
        Path target = pluginsRoot.resolve(pluginZip.getFileName().toString());
        Files.copy(pluginZip, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static Properties loadProps(Path dataDir) throws IOException {
        Properties props = new Properties();
        Path file = dataDir.resolve(DB_NAME);
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            }
        }
        return props;
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + message);
    }

    // ------------------------------------------------------------------ 宿主同款组件

    /** 与宿主 androidx.compose.ui.ht 等价的插件管理器 */
    static final class TestPluginManager extends WorkshopPluginManager {
        TestPluginManager(Path pluginsRoot) {
            super(pluginsRoot);
        }

        @Override
        protected PluginStatusProvider createPluginStatusProvider() {
            return new WhitelistStatusProvider(getPluginsRoots().get(0));
        }

        @Override
        protected PluginDescriptorFinder createPluginDescriptorFinder() {
            return new CompoundPluginDescriptorFinder()
                    .add(new PropertiesPluginDescriptorFinder())
                    .add(new ClassesManifestFinder());
        }

        @Override
        protected PluginFactory createPluginFactory() {
            return new ContextPluginFactory();
        }

        /** 与宿主 androidx.compose.ui.hq 一致：读 classes/META-INF/MANIFEST.MF */
        Manifest readManifest(PluginWrapper wrapper) {
            try {
                return new ClassesManifestFinder().findManifest(wrapper.getPluginPath());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** 与宿主 androidx.compose.ui.hu 一致：enabled.txt 是白名单 */
    static final class WhitelistStatusProvider implements PluginStatusProvider {
        private final Path enabledFile;
        private final List<String> enabled;

        WhitelistStatusProvider(Path pluginsRoot) {
            enabledFile = pluginsRoot.resolve("enabled.txt");
            List<String> ids = new ArrayList<>();
            if (Files.isRegularFile(enabledFile)) {
                try {
                    for (String line : Files.readAllLines(enabledFile, StandardCharsets.UTF_8)) {
                        String trimmed = line.trim();
                        if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                            ids.add(trimmed);
                        }
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("读取 enabled.txt 失败", e);
                }
            }
            enabled = ids;
            System.out.println("   (宿主等价状态提供者) pluginsRoot = " + pluginsRoot + "，已启用 = " + ids);
        }

        @Override
        public boolean isPluginDisabled(String pluginId) {
            return !enabled.contains(pluginId);
        }

        @Override
        public void disablePlugin(String pluginId) {
            enabled.remove(pluginId);
            write();
        }

        @Override
        public void enablePlugin(String pluginId) {
            if (!enabled.contains(pluginId)) {
                enabled.add(pluginId);
                write();
            }
        }

        private void write() {
            try {
                Files.write(enabledFile, enabled, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** 与宿主 androidx.compose.ui.hr 等价：支持 classes/ 布局的 Manifest 描述文件查找 */
    static final class ClassesManifestFinder extends ManifestPluginDescriptorFinder {
        @Override
        protected Manifest readManifestFromDirectory(Path pluginPath) {
            try {
                Manifest manifest = readIfExists(pluginPath.resolve("classes").resolve("META-INF").resolve("MANIFEST.MF"));
                if (manifest == null) {
                    manifest = readIfExists(pluginPath.resolve("META-INF").resolve("MANIFEST.MF"));
                }
                return manifest;
            } catch (IOException e) {
                return null;
            }
        }

        Manifest findManifest(Path pluginPath) throws IOException {
            if (Files.isDirectory(pluginPath)) {
                return readManifestFromDirectory(pluginPath);
            }
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(pluginPath.toFile())) {
                for (String candidate : new String[]{"classes/META-INF/MANIFEST.MF", "META-INF/MANIFEST.MF"}) {
                    java.util.zip.ZipEntry entry = zip.getEntry(candidate);
                    if (entry != null) {
                        try (InputStream in = zip.getInputStream(entry)) {
                            return new Manifest(in);
                        }
                    }
                }
            }
            return null;
        }

        private static Manifest readIfExists(Path path) throws IOException {
            if (!Files.isRegularFile(path)) {
                return null;
            }
            try (InputStream in = Files.newInputStream(path)) {
                return new Manifest(in);
            }
        }
    }

    /** 与宿主 androidx.compose.ui.hs 等价：优先用 PluginContext 构造插件主类 */
    static final class ContextPluginFactory implements PluginFactory {
        @Override
        public Plugin create(PluginWrapper wrapper) {
            try {
                Class<?> clazz = Class.forName(wrapper.getDescriptor().getPluginClass(), true,
                        wrapper.getPluginClassLoader());
                PluginContext context = new PluginContext(
                        wrapper.getPluginId(),
                        wrapper.getDescriptor().getVersion(),
                        wrapper.getPluginPath().toString(),
                        "1.18.5",
                        Channel.Steam);
                return (Plugin) clazz.getConstructor(PluginContext.class).newInstance(context);
            } catch (NoSuchMethodException e) {
                return new DefaultPluginFactory().create(wrapper);
            } catch (Exception e) {
                throw new IllegalStateException("创建插件实例失败: " + wrapper.getPluginId(), e);
            }
        }
    }

    // ------------------------------------------------------------------ 假宿主

    static final class FakeUi implements WorkshopApi.Ui {
        final List<String> messages = new ArrayList<>();

        @Override
        public void toast(String text, ToastType type) {
            messages.add(text);
        }
    }

    static final class FakeApi implements WorkshopApi {
        final FakeUi ui = new FakeUi();
        private final Path dataDir;
        private final WorkshopApi.Playback playback = new WorkshopApi.Playback() {
            @Override public void changeExclusive(boolean exclusive) { }
            @Override public void pause() { }
            @Override public void play() { }
            @Override public void previous() { }
            @Override public void next() { }
            @Override public void seekTo(long position) { }
        };
        private final WorkshopApi.Manager manager;

        FakeApi(Path dataDir) {
            this.dataDir = dataDir;
            this.manager = new WorkshopApi.Manager() {
                @Override public ConfigManager createConfigManager(String pluginId) { return configManager(); }
                @Override public ConfigManager createConfigManager() { return configManager(); }
            };
        }

        private ConfigManager configManager() {
            ConfigHelper helper = new ConfigHelper() {
                @Override public <T> T get(String key, T defaultValue) { return defaultValue; }
                @Override public void set(String key, Object value) { }
                @Override public boolean save() { return true; }
                @Override public boolean reload() { return true; }
                @Override public Path getConfigPath() { return dataDir.resolve("config.json"); }
            };
            return new ConfigManager() {
                @Override public ConfigHelper getConfig() { return helper; }
                @Override public ConfigHelper getConfig(String fileName) { return helper; }
                @Override public void addConfigChangeListener(Consumer<ConfigHelper> listener) { }
                @Override public void addConfigChangeListener(String fileName, Consumer<ConfigHelper> listener) { }
                @Override public void removeConfigChangeListener(Consumer<ConfigHelper> listener) { }
            };
        }

        @Override public WorkshopApi.Playback getPlayback() { return playback; }
        @Override public WorkshopApi.Ui getUi() { return ui; }
        @Override public WorkshopApi.Manager getManager() { return manager; }
    }
}
