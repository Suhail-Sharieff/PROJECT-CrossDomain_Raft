package raftkv.server;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import raftkv.rpc.proto.*;

import java.util.concurrent.TimeUnit;

public class PeerClient {
    private static final Logger logger = LoggerFactory.getLogger(PeerClient.class);

    private final int peerId;
    private final String address;
    private final ManagedChannel channel;
    private final RaftServiceGrpc.RaftServiceStub asyncStub;

    public PeerClient(int peerId, String address) {
        this.peerId = peerId;
        this.address = address;
        String[] parts = address.split(":");
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);
        this.channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        this.asyncStub = RaftServiceGrpc.newStub(channel);
    }

    public void shutdown() {
        try {
            channel.shutdown().awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            logger.warn("Thread interrupted while shutting down channel to peer {}", peerId);
            Thread.currentThread().interrupt();
        }
    }

    public int getPeerId() {
        return peerId;
    }

    public String getAddress() {
        return address;
    }

    public void sendRequestVote(VoteRequest request, StreamObserver<VoteResponse> responseObserver) {
        asyncStub.withDeadlineAfter(500, TimeUnit.MILLISECONDS).requestVote(request, responseObserver);
    }

    public void sendAppendEntries(AppendEntriesRequest request, StreamObserver<AppendEntriesResponse> responseObserver) {
        asyncStub.withDeadlineAfter(500, TimeUnit.MILLISECONDS).appendEntries(request, responseObserver);
    }
}
