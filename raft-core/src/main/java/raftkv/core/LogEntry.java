package raftkv.core;

public record LogEntry(long term, long index, String command) {
}
