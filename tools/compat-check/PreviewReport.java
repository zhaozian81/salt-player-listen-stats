import com.spwmods.listenstats.StatsReport;
import com.xuncorp.spw.workshop.api.WorkshopApi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 用真实的统计文件生成报表预览（不启动播放器）。
 *
 * 用法：java PreviewReport &lt;listen-stats.db.properties&gt; &lt;输出目录&gt;
 *
 * 原理：把真实统计文件复制到临时目录，挂一个只负责提供数据目录的假宿主，
 * 然后直接调用插件自己的 StatsReport.exportHtml()，因此看到的样式与插件里生成的完全一致。
 */
public final class PreviewReport {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("用法: PreviewReport <listen-stats.db.properties> <outDir>");
            System.exit(2);
        }

        Path db = Paths.get(args[0]).toAbsolutePath();
        Path outDir = Paths.get(args[1]).toAbsolutePath();
        if (!Files.isRegularFile(db)) {
            System.out.println("[FAIL] 找不到统计文件: " + db);
            System.exit(1);
        }

        Files.createDirectories(outDir);
        Files.copy(db, outDir.resolve("listen-stats.db.properties"), StandardCopyOption.REPLACE_EXISTING);

        // 假宿主只提供"配置目录"这一件事，数据目录就是 outDir
        WorkshopApi.Companion.setInstance(new PluginLoadHarness.FakeApi(outDir));

        StatsReport.exportHtml();

        Path report = outDir.resolve("listen-stats-report.html");
        if (Files.isRegularFile(report)) {
            System.out.println("已生成预览: " + report);
            System.exit(0);
        }
        System.out.println("[FAIL] 没有生成报表文件");
        System.exit(1);
    }
}
