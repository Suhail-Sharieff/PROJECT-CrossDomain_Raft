package raftkv.core;

import java.util.List;

public interface RaftNodeListener {
    /**
     * Send a RequestVote RPC to a peer.
     */
    void sendRequestVote(int peerId, long term, int candidateId, long lastLogIndex, long lastLogTerm);

    /**
     * Send an AppendEntries RPC to a peer.
     */
    void sendAppendEntries(int peerId, long term, int leaderId, long prevLogIndex, long prevLogTerm, List<LogEntry> entries, long leaderCommit);

    /**
     * Triggered when a log entry is committed.
     */
    void onCommit(long logIndex, LogEntry entry);

    /**
     * Triggered when the node state changes (e.g. Follower -> Candidate).
     */
    void onStateChange(NodeState oldState, NodeState newState, long term);
}
