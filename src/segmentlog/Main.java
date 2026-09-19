package segmentlog;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Main {
    public static void main(String[] args) throws Exception {
        Path dir = Paths.get("data");
        Files.createDirectories(dir);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path path : stream) {
                Files.delete(path);
            }
        }

        Validator validator = payload ->
                payload != null && !payload.isEmpty() && !payload.startsWith("BAD");

        List<String> input = Arrays.asList(
                "alpha", "bravo", "charlie",
                "BAD-corrupt",
                "delta", "echo", "foxtrot",
                "golf");

        System.out.println("== write phase (segment capacity = 3) ==");
        try (SegmentStore store = SegmentStore.open(dir, 3, validator)) {
            for (String payload : input) {
                long seq = store.append(payload);
                System.out.println("write  " + payload
                        + (seq >= 0 ? "  -> seq " + seq : "  -> SKIPPED (validation failed)"));
            }
            store.seal();
        }

        System.out.println("\n== sealed segment files ==");
        for (Path segment : SegmentReader.sealedSegments(dir)) {
            System.out.println("  " + segment.getFileName());
        }

        System.out.println("\n== reopen and read back ==");
        List<Record> records;
        try (SegmentStore store = SegmentStore.open(dir, 3, validator)) {
            records = store.readAll();
        }
        for (Record record : records) {
            System.out.println("  " + record);
        }

        List<String> expected = new ArrayList<>();
        for (String payload : input) {
            if (validator.isValid(payload)) {
                expected.add(payload);
            }
        }

        boolean orderOk = records.size() == expected.size();
        boolean seqOk = true;
        for (int i = 0; i < records.size(); i++) {
            Record record = records.get(i);
            if (!record.payload().equals(expected.get(i))) {
                orderOk = false;
            }
            if (record.seq() != i) {
                seqOk = false;
            }
        }

        System.out.println("\nwrite order preserved after reopen : " + (orderOk ? "PASS" : "FAIL"));
        System.out.println("sequence numbers consecutive       : " + (seqOk ? "PASS" : "FAIL"));
        if (!orderOk || !seqOk) {
            System.exit(1);
        }
    }
}
