package segmentlog;

import java.io.BufferedInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 顺序读一片已经封口的记录片。
 *
 * 关键点：以帧为单位读取。某条记录 CRC 坏了，它已经被整帧消费掉，
 * 通过 {@link CorruptRecordException#recordSeq()} 报告后直接读下一帧；
 * 后续好记录仍带着写入时的原始序号，不会因为前面跳过一条而错位。
 *
 * 文件在帧中间截断（长度字段都不完整）才算整片损坏，直接抛异常；
 * 在帧边界上干净结束是正常读完。
 */
public class SegmentReader implements AutoCloseable {

    private final Path file;
    private final InputStream fileIn;
    private final BufferedInputStream in;
    private final DataInput data;
    private boolean done;

    public SegmentReader(Path file) throws IOException {
        this.file = file;
        this.fileIn = Files.newInputStream(file);
        this.in = new BufferedInputStream(fileIn);
        this.data = new DataInputStream(in);
    }

    /**
     * 读下一条好记录；遇到坏帧会跳过并继续往后找。
     *
     * @return 下一条校验通过的记录；整片读完返回 null
     */
    public Record nextGood() throws IOException {
        while (true) {
            int firstByte = in.read();
            if (firstByte == -1) {
                done = true;
                return null;
            }
            int payloadLen;
            try {
                payloadLen = (firstByte << 24)
                        | (readByteOrThrow() << 16)
                        | (readByteOrThrow() << 8)
                        | readByteOrThrow();
            } catch (EOFException e) {
                throw new IOException("片在帧头部处截断: " + file, e);
            }

            if (payloadLen < 0 || payloadLen > Record.MAX_PAYLOAD_BYTES) {
                // 长度字段本身被撞坏，无法知道坏帧到哪里结束，整片不能再对齐。
                throw new CorruptRecordException(
                        "长度字段非法（" + payloadLen + "），放弃整片: " + file, -1);
            }

            try {
                // readBody 内部保证把 payloadLen 个负载字节全部读完，
                // 即使 CRC 不符，流也正好停在下一帧开头。
                return Record.readBody(data, payloadLen);
            } catch (EOFException e) {
                throw new IOException("片在帧体处截断: " + file, e);
            } catch (CorruptRecordException bad) {
                // 坏帧已跳过，序号不变，继续读下一条。
                onCorrupt(bad);
            }
        }
    }

    protected void onCorrupt(CorruptRecordException e) throws IOException {
        // 默认什么都不做，由上层 RollingLog 收集统计。
    }

    private int readByteOrThrow() throws IOException {
        int value = in.read();
        if (value == -1) {
            throw new EOFException();
        }
        return value;
    }

    public void close() throws IOException {
        fileIn.close();
    }
}
