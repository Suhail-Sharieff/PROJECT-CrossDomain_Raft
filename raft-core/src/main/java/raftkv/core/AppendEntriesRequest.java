package raftkv.core;

import java.util.List;

public record AppendEntriesRequest(
    long term,
    int leaderId,
    long prevLogIndex,
    long prevLogTerm,
    List<LogEntry> entries,
    long leaderCommit
) {
}
