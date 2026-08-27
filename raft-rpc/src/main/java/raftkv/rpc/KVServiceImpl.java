package raftkv.rpc;

import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import raftkv.core.NodeState;
import raftkv.core.RaftNode;
import raftkv.rpc.proto.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class KVServiceImpl extends KVServiceGrpc.KVServiceImplBase {
    private static final Logger logger = LoggerFactory.getLogger(KVServiceImpl.class);

    private final RaftNode raftNode;
    private final Map<String, String> kvStore;
    private final Map<Long, CompletableFuture<Boolean>> pendingWrites = new ConcurrentHashMap<>();

    public KVServiceImpl(RaftNode raftNode, Map<String, String> kvStore) {
        this.raftNode = raftNode;
        this.kvStore = kvStore;
    }

    @Override
    public void put(PutRequest request, StreamObserver<PutResponse> responseObserver) {
        if (raftNode.getRole() != NodeState.LEADER) {
            responseObserver.onNext(PutResponse.newBuilder()
                    .setSuccess(false)
                    .setLeaderId(raftNode.getLeaderId())
                    .build());
            responseObserver.onCompleted();
            return;
        }

        String command = "PUT " + request.getKey() + " " + request.getValue();
        long index = raftNode.clientWrite(command);

        if (index == -1) {
            responseObserver.onNext(PutResponse.newBuilder()
                    .setSuccess(false)
                    .setLeaderId(raftNode.getLeaderId())
                    .build());
            responseObserver.onCompleted();
            return;
        }

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pendingWrites.put(index, future);

        try {
            Boolean success = future.get(5, TimeUnit.SECONDS);
            responseObserver.onNext(PutResponse.newBuilder()
                    .setSuccess(success != null && success)
                    .setLeaderId(raftNode.getLeaderId())
                    .build());
        } catch (Exception e) {
            logger.warn("Put request for key '{}' at log index {} timed out or was aborted", request.getKey(), index);
            responseObserver.onNext(PutResponse.newBuilder()
                    .setSuccess(false)
                    .setLeaderId(raftNode.getLeaderId())
                    .build());
        } finally {
            pendingWrites.remove(index);
            responseObserver.onCompleted();
        }
    }

    @Override
    public void get(GetRequest request, StreamObserver<GetResponse> responseObserver) {
        String value = kvStore.getOrDefault(request.getKey(), "");
        responseObserver.onNext(GetResponse.newBuilder()
                .setSuccess(true)
                .setValue(value)
                .setLeaderId(raftNode.getLeaderId())
                .build());
        responseObserver.onCompleted();
    }

    public void notifyCommit(long index, boolean success) {
        CompletableFuture<Boolean> future = pendingWrites.remove(index);
        if (future != null) {
            future.complete(success);
        }
    }

    public void abortAllPendingWrites() {
        for (CompletableFuture<Boolean> future : pendingWrites.values()) {
            future.complete(false);
        }
        pendingWrites.clear();
    }
}
