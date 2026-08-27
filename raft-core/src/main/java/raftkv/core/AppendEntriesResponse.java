package raftkv.core;

public record AppendEntriesResponse(
    long term,
    boolean success,
    long matchIndex
) {
}
