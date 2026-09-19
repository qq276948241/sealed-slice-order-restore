package segmentlog;

import java.util.List;

/** 一次全量找回的结果：按写入顺序排列的好记录，以及被跳过的坏记录序号。 */
public final class ReadResult {

    private final List<Record> records;
    private final List<Long> skippedSeqs;

    public ReadResult(List<Record> records, List<Long> skippedSeqs) {
        this.records = List.copyOf(records);
        this.skippedSeqs = List.copyOf(skippedSeqs);
    }

    public List<Record> records() {
        return records;
    }
    public List<Long> skippedSeqs() {
        return skippedSeqs;
    }
}
