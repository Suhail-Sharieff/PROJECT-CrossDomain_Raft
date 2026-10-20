package raftkv.server;

import io.grpc.*;
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
    private DashboardServer dashboardServer;
    private volatile boolean offline = false;

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
                if (offline) return;
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
                if (offline) return;
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
                            .setIndex(entry.index())
                            .setTerm(entry.term())
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
            public void onStateChange(NodeState oldState, NodeState newState, long term) {
                logger.info("State transition: {} -> {} (term {})", oldState, newState, term);
                if (newState != NodeState.LEADER && kvService != null) {
                    kvService.abortAllPendingWrites();
                }
            }

            @Override
            public void onCommit(long index, LogEntry entry) {
                logger.info("Applying committed entry at index {}: {}", index, entry.command());
                String cmd = entry.command();
                if (cmd != null && cmd.startsWith("PUT ")) {
                    String[] parts = cmd.split(" ", 3);
                    if (parts.length >= 3) {
                        kvStore.put(parts[1], parts[2]);
                        logger.info("Applied to state machine: {} = {}", parts[1], parts[2]);
                    }
                }
                if (kvService != null) {
                    kvService.notifyCommit(index, true);
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
                .intercept(new ServerInterceptor() {
                    @Override
                    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                            ServerCall<ReqT, RespT> call,
                            Metadata headers,
                            ServerCallHandler<ReqT, RespT> next) {
                        if (offline) {
                            call.close(Status.UNAVAILABLE.withDescription("simulated crash"), new Metadata());
                            return new ServerCall.Listener<>() {};
                        }
                        return next.startCall(call, headers);
                    }
                })
                .addService(kvService)
                .addService(raftService)
                .build()
                .start();

        logger.info("gRPC server started on port {}", config.getPort());

        dashboardServer = new DashboardServer(this, config.getPort());
        dashboardServer.start();

        raftNode.start();
    }

    public void stop() {
        logger.info("Shutting down Raft server...");
        if (dashboardServer != null) {
            dashboardServer.stop();
        }
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

    public ClusterConfig getConfig() {
        return config;
    }

    public RaftNode getRaftNode() {
        return raftNode;
    }

    public Map<String, String> getKvStore() {
        return kvStore;
    }

    public Map<Integer, PeerClient> getPeers() {
        return peers;
    }

    public KVServiceImpl getKvService() {
        return kvService;
    }

    public boolean isOffline() {
        return offline;
    }

    public void setOffline(boolean offline) {
        this.offline = offline;
        if (raftNode != null) {
            raftNode.setOffline(offline);
        }
        logger.info("Node {} simulated {}", config.getNodeId(), offline ? "CRASH" : "REVIVE");
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
