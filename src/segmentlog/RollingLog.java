package segmentlog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 把写入、封口、找回三截串起来的日志门面：
 *
 *  - append：写进当前打开的一片；这片满了就先封口，再开新片继续写。
 *  - close：把当前片封口后落盘，之后进程可以完全退出。
 *  - recover：重新打开目录，按每片文件名里的封口时间排序，
 *    逐片顺序读出，取出的全局顺序与写入时一致。
 *
 * 序号在这里分配，跨片连续。某条坏记录被跳过时，
 * 其它记录的序号保持写入时的值，不补齐、不重排。
 */
public final class RollingLog implements AutoCloseable {

    private static final Pattern SEALED_NAME =
            Pattern.compile("seg-(\\d{6})-(\\d+)\\.seg");

    private final Path dir;
    private final int maxRecordsPerSegment;
    private final long maxBytesPerSegment;

    private long nextId;
    private long nextSeq;
    private SegmentWriter active;

    public RollingLog(Path dir, int maxRecordsPerSegment, long maxBytesPerSegment)
            throws IOException {
        this.dir = dir;
        this.maxRecordsPerSegment = maxRecordsPerSegment;
        this.maxBytesPerSegment = maxBytesPerSegment;
        Files.createDirectories(dir);

        // 重新打开：扫一遍已经存在的片，恢复片号和序号的起点。
        long maxId = -1;
        long maxSeq = -1;
        for (SealedSegment seg : listSealed()) {
            maxId = Math.max(maxId, seg.id);
            maxSeq = Math.max(maxSeq, scanMaxSeq(seg.file));
        }
        this.nextId = maxId + 1;
        this.nextSeq = maxSeq + 1;
        this.active = new SegmentWriter(dir, nextId++, maxRecordsPerSegment, maxBytesPerSegment);
    }

    /** 追加一条文本记录，序号自动分配。 */
    public Record append(String text) throws IOException {
        Record record = Record.of(nextSeq, text);
        if (active.isFullFor(record)) {
            roll();
        }
        active.append(record);
        nextSeq++;
        return record;
    }

    /** 当前片写满：封口，再开一片新的。 */
    private void roll() throws IOException {
        active.seal(System.currentTimeMillis());
        active = new SegmentWriter(dir, nextId++, maxRecordsPerSegment, maxBytesPerSegment);
    }

    /** 收尾：把最后一片也封上口，磁盘上不留半成品 open 文件。 */
    @Override
    public void close() throws IOException {
        if (active == null || active.isSealed()) {
            return;
        }
        if (active.recordCount() == 0) {
            active.discardIfEmpty();
        } else {
            active.seal(System.currentTimeMillis());
        }
    }

    /**
     * 按封口时间从早到晚逐片找回全部好记录。
     * 同一毫秒封口的两片用单调片号兜底，顺序仍然确定。
     */
    public ReadResult recover() throws IOException {
        List<Record> records = new ArrayList<>();
        List<Long> skipped = new ArrayList<>();

        List<SealedSegment> segments = listSealed();
        segments.sort(Comparator.comparingLong(SealedSegment::sealedAt)
                .thenComparingLong(SealedSegment::id));

        for (SealedSegment seg : segments) {
            SegmentReader reader = new SegmentReader(seg.file) {
                @Override
                protected void onCorrupt(CorruptRecordException e) {
                    skipped.add(e.recordSeq());
                }
            };
            try (reader) {
                Record record;
                while ((record = reader.nextGood()) != null) {
                    records.add(record);
                }
            }
        }
        return new ReadResult(records, skipped);
    }

    private List<SealedSegment> listSealed() throws IOException {
        List<SealedSegment> result = new ArrayList<>();
        try (Stream<Path> paths = Files.list(dir)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Matcher m = SEALED_NAME.matcher(path.getFileName().toString());
                if (m.matches()) {
                    result.add(new SealedSegment(
                            path, Long.parseLong(m.group(1)), Long.parseLong(m.group(2))));
                }
            }
        }
        return result;
    }

    /** 只扫序号，跳过坏帧，用来在重新打开时恢复序号水位。 */
    private long scanMaxSeq(Path file) throws IOException {
        long max = -1;
        SegmentReader reader = new SegmentReader(file);
        try (reader) {
            Record record;
            while ((record = reader.nextGood()) != null) {
                max = Math.max(max, record.seq());
            }
        } catch (CorruptRecordException ignored) {
            // 恢复水位时遇到整片不可对齐的坏片，跳过它继续看后面的片。
        }
        return max;
    }

    private static final class SealedSegment {
        final Path file;
        final long id;
        final long sealedAt;

        SealedSegment(Path file, long id, long sealedAt) {
            this.file = file;
            this.id = id;
            this.sealedAt = sealedAt;
        }

        long id() {
            return id;
        }

        long sealedAt() {
            return sealedAt;
        }
    }
}
