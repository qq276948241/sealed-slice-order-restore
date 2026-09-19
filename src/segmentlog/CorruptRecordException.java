package segmentlog;

import java.io.IOException;

/**
 * 读到一条校验不过的记录时抛出。
 * 抛出点已经把整条坏帧从流里读完，调用方可以直接继续读下一帧。
 */
public class CorruptRecordException extends IOException {

    private final long recordSeq;

    public CorruptRecordException(String message, long recordSeq) {
        super(message);
        this.recordSeq = recordSeq;
    }

    /** 坏帧里尽力读到的序号；连头部都读不出来时为 -1。 */
    public long recordSeq() {
        return recordSeq;
    }
}
