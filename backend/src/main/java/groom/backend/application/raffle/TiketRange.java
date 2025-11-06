package groom.backend.application.raffle;

public final class TiketRange {
    private final long start;
    private final long end;

    public TiketRange(long start, long end) {
        this.start = start;
        this.end = end;
    }

    public long getStart() { return start; }
    public long getEnd() { return end; }
    public long size() { return end - start + 1; }
}
