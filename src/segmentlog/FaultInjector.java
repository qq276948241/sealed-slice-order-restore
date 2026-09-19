package segmentlog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 演示用：在已经封口的片文件里，把指定序号记录的负载翻转几个字节，
 * 模拟"中途有一条校验坏了"。改完 CRC 必然对不上。
 * 只用标准库，按帧格式原地定位。
 */
public final class FaultInjector {

    private static final Pattern SEALED_NAME = Pattern.compile("seg-\\d{6}-\\d+\\.seg");

    private FaultInjector() {
    }

    public static void corruptPayload(Path dir, long targetSeq) throws IOException {
        List<Path> files;
        try (Stream<Path> paths = Files.list(dir)) {
            files = paths
                    .filter(p -> SEALED_NAME.matcher(p.getFileName().toString()).matches())
                    .sorted()
                    .toList();
        }

        for (Path file : files) {
            byte[] data = Files.readAllBytes(file);
            int pos = 0;
            while (pos + Record.HEADER_BYTES <= data.length) {
                int payloadLen = readInt(data, pos);
                int frameLen = Record.HEADER_BYTES + payloadLen;
                if (payloadLen < 0 || pos + frameLen > data.length) {
                    break;
                }
                long seq = readLong(data, pos + 4);
                if (seq == targetSeq) {
                    int payloadStart = pos + Record.HEADER_BYTES;
                    data[payloadStart] ^= 0xFF;
                    if (payloadLen > 1) {
                        data[payloadStart + payloadLen / 2] ^= 0x5A;
                    }
                    Files.write(file, data);
                    System.out.printf("[注入] 在 %s 中损坏序号 %d 的负载%n",
                            file.getFileName(), targetSeq);
                    return;
                }
                pos += frameLen;
            }
        }
        throw new IOException("没找到序号 " + targetSeq + " 的记录，无法注入故障");
    }

    private static int readInt(byte[] data, int pos) {
        return ((data[pos] & 0xFF) << 24)
                | ((data[pos + 1] & 0xFF) << 16)
                | ((data[pos + 2] & 0xFF) << 8)
                | (data[pos + 3] & 0xFF);
    }

    private static long readLong(byte[] data, int pos) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (data[pos + i] & 0xFFL);
        }
        return value;
    }
}
