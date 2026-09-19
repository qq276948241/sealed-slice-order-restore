package segmentlog;

public final class Record {
    private final long seq;
    private final String payload;

    public Record(long seq, String payload) {
        this.seq = seq;
        this.payload = payload;
    }

    public long seq() {
        return seq;
    }

    public String payload() {
        return payload;
    }

    public String encode() {
        return seq + "|" + payload;
    }

    public static Record decode(String line) {
        int bar = line.indexOf('|');
        if (bar <= 0) {
            throw new IllegalArgumentException("corrupt line: " + line);
        }
        long seq = Long.parseLong(line.substring(0, bar));
        return new Record(seq, line.substring(bar + 1));
    }

    @Override
    public String toString() {
        return "Record{seq=" + seq + ", payload=" + payload + "}";
    }
}
