package segmentlog;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 当前正在写入的一片。背后是一个 {@code open-*.seg} 文件。
 * 一片写满后调用 {@link #seal}，文件原子改名成
 * {@code seg-NNNNNN-<封口时间>.seg}，这片就封死不再写入。
 */
public final class SegmentWriter {

    private final Path dir;
    private final long id;
    private final int maxRecords;
    private final long maxBytes;

    private Path openFile;
    private Path rawFile;
    private DataOutputStream out;
    private int recordCount;
    private long byteCount;
    private boolean sealed;

    public SegmentWriter(Path dir, long id, int maxRecords, long maxBytes) throws IOException {
        this.dir = dir;
        this.id = id;
        this.maxRecords = maxRecords;
        this.maxBytes = maxBytes;
        this.openFile = dir.resolve(String.format("open-%06d.seg", id));
        this.rawFile = openFile;
        Files.createDirectories(dir);
        this.out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(openFile)));
    }

    public long id() {
        return id;
    }

    public int recordCount() {
        return recordCount;
    }

    /** 追加一条记录并立即落盘，进程被打断也不会留在内存里丢失。 */
    public void append(Record record) throws IOException {
        if (sealed) {
            throw new IOException("该片已经封口，不能再写入: " + openFile);
        }
        record.writeTo(out);
        out.flush();
        recordCount++;
        byteCount += record.frameBytes();
    }

    /** 这条记录放进去之后该片是否就满了。 */
    public boolean isFullFor(Record record) {
        return recordCount >= maxRecords
                || byteCount + record.frameBytes() > maxBytes;
    }

    /**
     * 封口：刷盘、关闭，再原子改名。
     * 文件名里同时带上单调片号和封口时刻，后者用于按时间找回。
     */
    public Path seal(long sealedAtMillis) throws IOException {
        if (sealed) {
            throw new IOException("该片已经封口: " + openFile);
        }
        out.flush();
        out.close();
        sealed = true;

        Path sealedFile = dir.resolve(String.format(
                "seg-%06d-%d.seg", id, sealedAtMillis));
        Files.move(openFile, sealedFile);
        openFile = sealedFile;
        return sealedFile;
    }

    /** 一片一条记录都没写：不参与封口，直接删掉临时文件。 */
    public void discardIfEmpty() throws IOException {
        if (sealed) {
            return;
        }
        out.close();
        sealed = true;
        Files.deleteIfExists(rawFile);
    }

    public boolean isSealed() {
        return sealed;
    }
}
