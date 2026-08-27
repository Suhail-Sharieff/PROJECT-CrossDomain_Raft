package raftkv.core;

public record VoteRequest(long term, int candidateId, long lastLogIndex, long lastLogTerm) {
}
