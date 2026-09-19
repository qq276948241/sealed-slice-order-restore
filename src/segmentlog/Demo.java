package segmentlog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 端到端演示，分两个互相独立的进程阶段跑：
 *
 *   java segmentlog.Demo write   写 10 条，每片 3 条，写满自动封口，
 *                                最后一片在退出时封口；随后把序号 5 的负载弄坏。
 *   java segmentlog.Demo read    重新打开目录（上一阶段进程已退出），
 *                                按封口时间把所有片找回，校验、断言顺序。
 */
public final class Demo {

    private static final int TOTAL_RECORDS = 10;
    private static final int RECORDS_PER_SEGMENT = 3;
    private static final long BYTES_PER_SEGMENT = 4096;
    private static final long CORRUPT_SEQ = 5;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("用法: java segmentlog.Demo write|read");
            System.exit(2);
        }
        Path dir = Path.of("logdata");

        switch (args[0]) {
            case "write" -> writePhase(dir);
            case "read" -> readPhase(dir);
            default -> {
                System.err.println("未知阶段: " + args[0]);
                System.exit(2);
            }
        }
    }

    /** 第一截：写入 -> 写满封口 -> 滚动新片 -> 退出前封最后一片。 */
    private static void writePhase(Path dir) throws IOException {
        deleteRecursively(dir);

        try (RollingLog log = new RollingLog(dir, RECORDS_PER_SEGMENT, BYTES_PER_SEGMENT)) {
            for (int i = 1; i <= TOTAL_RECORDS; i++) {
                Record r = log.append("记录-" + i);
                System.out.printf("写入序号 %-2d -> 当前片已有记录%n", r.seq() + 1);
            }
        }
        System.out.println("[写入] 进程退出前所有片均已封口，文件：");
        try (Stream<Path> files = Files.list(dir)) {
            files.sorted().forEach(p -> System.out.println("        " + p.getFileName()));
        }

        // 模拟中途坏掉一条：改它的负载，CRC 必然失配。
        FaultInjector.corruptPayload(dir, CORRUPT_SEQ - 1);
    }

    /** 第三截：关掉后重新打开，按封口时间找回，跳过坏记录并断言顺序。 */
    private static void readPhase(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            throw new IOException("日志目录不存在: " + dir + "，请先跑 write 阶段");
        }

        // 不追加任何数据，纯粹"重新打开"已有封口片。
        RollingLog log = new RollingLog(dir, RECORDS_PER_SEGMENT, BYTES_PER_SEGMENT);
        log.close();

        ReadResult result = log.recover();

        System.out.println("[找回] 按封口时间逐片读出的好记录：");
        for (Record r : result.records()) {
            System.out.printf("        序号 %-2d  时间 %d  内容 %s%n",
                    r.seq() + 1, r.timestamp(), r.text());
        }
        System.out.println("[找回] 校验失败被整帧跳过的序号："
                + result.skippedSeqs().stream().map(s -> String.valueOf(s + 1)).toList());

        List<Long> actual = result.records().stream().map(Record::seq).toList();
        List<Long> expected = Stream.iterate(0L, s -> s + 1)
                .limit(TOTAL_RECORDS)
                .filter(s -> s != CORRUPT_SEQ - 1)
                .toList();

        boolean orderOk = actual.equals(expected);
        boolean skipOk = result.skippedSeqs().equals(List.of(CORRUPT_SEQ - 1));
        boolean contentOk = result.records().stream()
                .allMatch(r -> r.text().equals("记录-" + (r.seq() + 1)));

        System.out.println("断言：找回顺序与写入一致（坏序号后不错位） = " + orderOk);
        System.out.println("断言：恰好跳过坏掉的序号 " + CORRUPT_SEQ + "              = " + skipOk);
        System.out.println("断言：每条好记录内容正确                = " + contentOk);

        if (orderOk && skipOk && contentOk) {
            System.out.println("结果：PASS");
        } else {
            System.out.println("结果：FAIL");
            System.exit(1);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
