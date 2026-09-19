package segmentlog;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class SegmentWriter implements AutoCloseable {
    private final Path path;
    private final BufferedWriter out;
    private int count;

    private SegmentWriter(Path path, BufferedWriter out) {
        this.path = path;
        this.out = out;
    }

    static SegmentWriter create(Path path) throws IOException {
        BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return new SegmentWriter(path, out);
    }

    void write(Record record) throws IOException {
        out.write(record.encode());
        out.newLine();
        count++;
    }

    int count() {
        return count;
    }

    Path path() {
        return path;
    }

    @Override
    public void close() throws IOException {
        out.flush();
        out.close();
    }
}
