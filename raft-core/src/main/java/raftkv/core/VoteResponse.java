package raftkv.core;

public record VoteResponse(long term, boolean voteGranted) {
}
