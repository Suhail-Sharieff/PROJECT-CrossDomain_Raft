package raftkv.server;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import raftkv.core.LogEntry;
import raftkv.core.NodeState;
import raftkv.core.RaftNode;
import raftkv.core.RaftNodeListener;
import raftkv.core.PersistentState;
import raftkv.rpc.KVServiceImpl;
import raftkv.rpc.RaftServiceImpl;
import raftkv.rpc.proto.VoteRequest;
import raftkv.rpc.proto.VoteResponse;
import raftkv.rpc.proto.AppendEntriesRequest;
import raftkv.rpc.proto.AppendEntriesResponse;
import raftkv.rpc.proto.LogEntryProto;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    private final ClusterConfig config;
    private final ScheduledExecutorService scheduler;
    private final Map<String, String> kvStore = new ConcurrentHashMap<>();
    private final Map<Integer, PeerClient> peers = new ConcurrentHashMap<>();

    private RaftNode raftNode;
    private KVServiceImpl kvService;
    private Server grpcServer;

    public Main(ClusterConfig config) {
        this.config = config;
        this.scheduler = Executors.newScheduledThreadPool(4);
    }

    public void start() throws IOException {
        MDC.put("nodeId", String.valueOf(config.getNodeId()));

        logger.info("Initializing node with configuration: {}", config);

        PersistentState persistentState = new PersistentState(config.getDataDir());

        for (Map.Entry<Integer, String> entry : config.getPeers().entrySet()) {
            int peerId = entry.getKey();
            String address = entry.getValue();
            logger.info("Adding peer ID {} at address {}", peerId, address);
            peers.put(peerId, new PeerClient(peerId, address));
        }

        List<Integer> peerIds = new ArrayList<>(peers.keySet());

        RaftNodeListener listener = new RaftNodeListener() {
            @Override
            public void sendRequestVote(int peerId, long term, int candidateId, long lastLogIndex, long lastLogTerm) {
                PeerClient peer = peers.get(peerId);
                if (peer == null) return;

                VoteRequest request = VoteRequest.newBuilder()
                        .setTerm(term)
                        .setCandidateId(candidateId)
                        .setLastLogIndex(lastLogIndex)
                        .setLastLogTerm(lastLogTerm)
                        .build();

                peer.sendRequestVote(request, new StreamObserver<VoteResponse>() {
                    @Override
                    public void onNext(VoteResponse response) {
                        raftNode.handleRequestVoteResponse(peerId, response.getTerm(), response.getVoteGranted());
                    }

                    @Override
                    public void onError(Throwable t) {
                        logger.debug("RequestVote call to peer {} failed: {}", peerId, t.getMessage());
                    }

                    @Override
                    public void onCompleted() {}
                });
            }

            @Override
            public void sendAppendEntries(int peerId, long term, int leaderId, long prevLogIndex, long prevLogTerm, List<LogEntry> entries, long leaderCommit) {
                PeerClient peer = peers.get(peerId);
                if (peer == null) return;

                AppendEntriesRequest.Builder builder = AppendEntriesRequest.newBuilder()
                        .setTerm(term)
                        .setLeaderId(leaderId)
                        .setPrevLogIndex(prevLogIndex)
                        .setPrevLogTerm(prevLogTerm)
                        .setLeaderCommit(leaderCommit);

                for (LogEntry entry : entries) {
                    builder.addEntries(LogEntryProto.newBuilder()
                            .setTerm(entry.term())
                            .setIndex(entry.index())
                            .setCommand(entry.command())
                            .build());
                }

                AppendEntriesRequest request = builder.build();

                peer.sendAppendEntries(request, new StreamObserver<AppendEntriesResponse>() {
                    @Override
                    public void onNext(AppendEntriesResponse response) {
                        raftNode.handleAppendEntriesResponse(peerId, response.getTerm(), response.getSuccess(), response.getMatchIndex());
                    }

                    @Override
                    public void onError(Throwable t) {
                        logger.debug("AppendEntries call to peer {} failed: {}", peerId, t.getMessage());
                    }

                    @Override
                    public void onCompleted() {}
                });
            }

            @Override
            public void onCommit(long logIndex, LogEntry entry) {
                String command = entry.command();
                logger.info("Committed entry at index {}: '{}'", logIndex, command);
                if (command.startsWith("PUT ")) {
                    String[] parts = command.split(" ", 3);
                    if (parts.length == 3) {
                        String key = parts[1];
                        String value = parts[2];
                        kvStore.put(key, value);
                        logger.info("Applied PUT command to state machine: {} -> {}", key, value);
                    }
                }
                if (kvService != null) {
                    kvService.notifyCommit(logIndex, true);
                }
            }

            @Override
            public void onStateChange(NodeState oldState, NodeState newState, long term) {
                logger.info("State transition: {} -> {} in term {}", oldState, newState, term);
                if (newState != NodeState.LEADER && kvService != null) {
                    kvService.abortAllPendingWrites();
                }
            }
        };

        raftNode = new RaftNode(
                config.getNodeId(),
                peerIds,
                scheduler,
                listener,
                config.getElectionTimeoutMinMs(),
                config.getElectionTimeoutMaxMs(),
                config.getHeartbeatIntervalMs(),
                persistentState
        );

        kvService = new KVServiceImpl(raftNode, kvStore);
        RaftServiceImpl raftService = new RaftServiceImpl(raftNode);

        grpcServer = ServerBuilder.forPort(config.getPort())
                .addService(kvService)
                .addService(raftService)
                .build()
                .start();

        logger.info("gRPC server started on port {}", config.getPort());

        raftNode.start();
    }

    public void stop() {
        logger.info("Shutting down Raft server...");
        if (raftNode != null) {
            raftNode.stop();
        }
        if (grpcServer != null) {
            grpcServer.shutdown();
            try {
                grpcServer.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                logger.warn("gRPC server termination interrupted");
            }
        }
        for (PeerClient peer : peers.values()) {
            peer.shutdown();
        }
        scheduler.shutdownNow();
        logger.info("Shutdown completed.");
    }

    public static void main(String[] args) {
        ClusterConfig config = ClusterConfig.loadFromEnv();
        Main server = new Main(config);

        try {
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
            Thread.currentThread().join();
        } catch (Exception e) {
            logger.error("Error starting Raft server", e);
            System.exit(1);
        }
    }
}
