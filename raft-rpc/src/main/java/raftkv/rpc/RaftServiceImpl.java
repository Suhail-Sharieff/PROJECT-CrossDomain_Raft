package raftkv.rpc;

import io.grpc.stub.StreamObserver;
import raftkv.core.LogEntry;
import raftkv.core.RaftNode;
import raftkv.rpc.proto.*;

import java.util.ArrayList;
import java.util.List;

public class RaftServiceImpl extends RaftServiceGrpc.RaftServiceImplBase {
    private final RaftNode raftNode;

    public RaftServiceImpl(RaftNode raftNode) {
        this.raftNode = raftNode;
    }

    @Override
    public void requestVote(VoteRequest request, StreamObserver<VoteResponse> responseObserver) {
        raftkv.core.VoteRequest coreReq = new raftkv.core.VoteRequest(
                request.getTerm(),
                request.getCandidateId(),
                request.getLastLogIndex(),
                request.getLastLogTerm()
        );
        
        raftkv.core.VoteResponse coreResp = raftNode.handleRequestVote(coreReq);
        
        VoteResponse protoResp = VoteResponse.newBuilder()
                .setTerm(coreResp.term())
                .setVoteGranted(coreResp.voteGranted())
                .build();
                
        responseObserver.onNext(protoResp);
        responseObserver.onCompleted();
    }

    @Override
    public void appendEntries(AppendEntriesRequest request, StreamObserver<AppendEntriesResponse> responseObserver) {
        List<LogEntry> coreEntries = new ArrayList<>();
        for (LogEntryProto entryProto : request.getEntriesList()) {
            coreEntries.add(new LogEntry(
                    entryProto.getTerm(),
                    entryProto.getIndex(),
                    entryProto.getCommand()
            ));
        }
        
        raftkv.core.AppendEntriesRequest coreReq = new raftkv.core.AppendEntriesRequest(
                request.getTerm(),
                request.getLeaderId(),
                request.getPrevLogIndex(),
                request.getPrevLogTerm(),
                coreEntries,
                request.getLeaderCommit()
        );
        
        raftkv.core.AppendEntriesResponse coreResp = raftNode.handleAppendEntries(coreReq);
        
        AppendEntriesResponse protoResp = AppendEntriesResponse.newBuilder()
                .setTerm(coreResp.term())
                .setSuccess(coreResp.success())
                .setMatchIndex(coreResp.matchIndex())
                .build();
                
        responseObserver.onNext(protoResp);
        responseObserver.onCompleted();
    }
}
