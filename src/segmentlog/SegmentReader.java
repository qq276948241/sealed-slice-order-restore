package segmentlog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class SegmentReader {
    private SegmentReader() {
    }

    public static List<Path> sealedSegments(Path dir) throws IOException {
        List<Path> sealed = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.sealed")) {
            for (Path path : stream) {
                sealed.add(path);
            }
        }
        sealed.sort(Comparator.comparingInt(path -> segmentIndex(path.getFileName().toString())));
        return sealed;
    }

    public static List<Record> readAll(Path dir) throws IOException {
        List<Record> records = new ArrayList<>();
        for (Path segment : sealedSegments(dir)) {
            records.addAll(readSegment(segment));
        }
        return records;
    }

    public static List<Record> readSegment(Path segment) throws IOException {
        List<Record> records = new ArrayList<>();
        for (String line : Files.readAllLines(segment, StandardCharsets.UTF_8)) {
            if (!line.isEmpty()) {
                records.add(Record.decode(line));
            }
        }
        return records;
    }

    static int segmentIndex(String fileName) {
        int dash = fileName.indexOf('-');
        int dot = fileName.lastIndexOf('.');
        return Integer.parseInt(fileName.substring(dash + 1, dot));
    }
}
