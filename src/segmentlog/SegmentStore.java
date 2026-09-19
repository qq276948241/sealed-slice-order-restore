package segmentlog;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

public final class SegmentStore implements AutoCloseable {
    private final Path dir;
    private final int maxRecordsPerSegment;
    private final Validator validator;

    private SegmentWriter active;
    private int nextSegmentIndex;
    private long nextSeq;

    private SegmentStore(Path dir, int maxRecordsPerSegment, Validator validator) {
        this.dir = dir;
        this.maxRecordsPerSegment = maxRecordsPerSegment;
        this.validator = validator;
    }

    public static SegmentStore open(Path dir, int maxRecordsPerSegment, Validator validator) throws IOException {
        Files.createDirectories(dir);
        SegmentStore store = new SegmentStore(dir, maxRecordsPerSegment, validator);
        store.recover();
        return store;
    }

    private void recover() throws IOException {
        int maxIndex = -1;
        long maxSeq = -1;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (name.endsWith(".active")) {
                    Files.move(path, sealedPath(SegmentReader.segmentIndex(name)),
                            StandardCopyOption.ATOMIC_MOVE);
                    name = name.substring(0, name.lastIndexOf('.')) + ".sealed";
                    path = sealedPath(SegmentReader.segmentIndex(name));
                }
                if (name.endsWith(".sealed")) {
                    maxIndex = Math.max(maxIndex, SegmentReader.segmentIndex(name));
                    for (Record record : SegmentReader.readSegment(path)) {
                        maxSeq = Math.max(maxSeq, record.seq());
                    }
                }
            }
        }
        nextSegmentIndex = maxIndex + 1;
        nextSeq = maxSeq + 1;
    }

    public synchronized long append(String payload) throws IOException {
        if (!validator.isValid(payload)) {
            return -1;
        }
        if (active == null) {
            active = SegmentWriter.create(activePath(nextSegmentIndex));
        }
        long seq = nextSeq++;
        active.write(new Record(seq, payload));
        if (active.count() >= maxRecordsPerSegment) {
            seal();
        }
        return seq;
    }

    public synchronized void seal() throws IOException {
        if (active == null) {
            return;
        }
        active.close();
        Files.move(active.path(), sealedPath(nextSegmentIndex), StandardCopyOption.ATOMIC_MOVE);
        active = null;
        nextSegmentIndex++;
    }

    public List<Record> readAll() throws IOException {
        return SegmentReader.readAll(dir);
    }

    private Path activePath(int index) {
        return dir.resolve(fileName(index, ".active"));
    }

    private Path sealedPath(int index) {
        return dir.resolve(fileName(index, ".sealed"));
    }

    private static String fileName(int index, String suffix) {
        return String.format("segment-%06d%s", index, suffix);
    }

    @Override
    public synchronized void close() throws IOException {
        seal();
    }
}
