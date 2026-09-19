package segmentlog;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * 一条记录在磁盘上的二进制帧（大端字节序）：
 *
 *   int  payloadLen   负载字节数
 *   long seq          写入时分配的序号，跨片连续、永不重排
 *   long timestamp    写入时间（毫秒）
 *   long crc32        对 seq + timestamp + payload 算出的校验值
 *   byte payload      负载原文
 *
 * 长度前缀让坏帧可以被整帧跳过：校验失败时当前帧已经读完，
 * 流的位置正好落在下一帧开头，后续好记录的序号完全不受影响。
 */
public final class Record {

    /** 固定头部字节数：payloadLen(4) + seq(8) + timestamp(8) + crc(8)。 */
    public static final int HEADER_BYTES = 4 + 8 + 8 + 8;

    /** 防御性上限：长度字段被撞坏时不允许按它申请巨型数组。 */
    public static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;

    private final long seq;
    private final long timestamp;
    private final byte[] payload;

    public Record(long seq, long timestamp, byte[] payload) {
        this.seq = seq;
        this.timestamp = timestamp;
        this.payload = payload.clone();
    }

    public static Record of(long seq, String text) {
        return new Record(seq, System.currentTimeMillis(), text.getBytes(StandardCharsets.UTF_8));
    }

    public long seq() {
        return seq;
    }

    public long timestamp() {
        return timestamp;
    }

    public byte[] payloadBytes() {
        return payload.clone();
    }

    public String text() {
        return new String(payload, StandardCharsets.UTF_8);
    }

    public int payloadLength() {
        return payload.length;
    }

    /** 整条帧落盘后的字节数。 */
    public int frameBytes() {
        return HEADER_BYTES + payload.length;
    }

    public void writeTo(DataOutput out) throws IOException {
        out.writeInt(payload.length);
        out.writeLong(seq);
        out.writeLong(timestamp);
        out.writeLong(checksum(seq, timestamp, payload));
        out.write(payload);
    }

    /**
     * 从流首读取一整条帧。长度字段由调用方先读出并传入，
     * 本方法保证返回或抛异常时负载也已全部读完，帧边界不丢。
     */
    public static Record readBody(DataInput in, int payloadLen) throws IOException {
        if (payloadLen < 0 || payloadLen > MAX_PAYLOAD_BYTES) {
            throw new CorruptRecordException(
                    "长度字段非法（" + payloadLen + "），无法定位下一帧", -1);
        }
        long frameSeq = in.readLong();
        long frameTimestamp = in.readLong();
        long expectedCrc = in.readLong();
        byte[] framePayload = new byte[payloadLen];
        in.readFully(framePayload);

        long actualCrc = checksum(frameSeq, frameTimestamp, framePayload);
        if (actualCrc != expectedCrc) {
            throw new CorruptRecordException(
                    "CRC 校验失败，序号 " + frameSeq + " 已跳过", frameSeq);
        }
        return new Record(frameSeq, frameTimestamp, framePayload);
    }

    static long checksum(long seq, long timestamp, byte[] payload) {
        CRC32 crc = new CRC32();
        crc.update(longBytes(seq), 0, 8);
        crc.update(longBytes(timestamp), 0, 8);
        crc.update(payload);
        return crc.getValue();
    }

    private static byte[] longBytes(long value) {
        return new byte[] {
                (byte) (value >>> 56),
                (byte) (value >>> 48),
                (byte) (value >>> 40),
                (byte) (value >>> 32),
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value
        };
    }
}
