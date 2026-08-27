package raftkv.server;

import java.util.HashMap;
import java.util.Map;

public class ClusterConfig {
    private final int nodeId;
    private final int port;
    private final Map<Integer, String> peers = new HashMap<>();
    private final int electionTimeoutMinMs;
    private final int electionTimeoutMaxMs;
    private final int heartbeatIntervalMs;
    private final String dataDir;

    public ClusterConfig(
            int nodeId,
            int port,
            Map<Integer, String> peers,
            int electionTimeoutMinMs,
            int electionTimeoutMaxMs,
            int heartbeatIntervalMs,
            String dataDir
    ) {
        this.nodeId = nodeId;
        this.port = port;
        this.peers.putAll(peers);
        this.electionTimeoutMinMs = electionTimeoutMinMs;
        this.electionTimeoutMaxMs = electionTimeoutMaxMs;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.dataDir = dataDir;
    }

    public static ClusterConfig loadFromEnv() {
        int nodeId = Integer.parseInt(getEnvOrDefault("NODE_ID", "1"));
        int port = Integer.parseInt(getEnvOrDefault("NODE_PORT", "8001"));

        String peersStr = getEnvOrDefault("PEERS", "");
        Map<Integer, String> peers = new HashMap<>();
        if (!peersStr.trim().isEmpty()) {
            String[] parts = peersStr.split(",");
            for (String part : parts) {
                String[] nodeAndAddr = part.split("=");
                if (nodeAndAddr.length == 2) {
                    peers.put(Integer.parseInt(nodeAndAddr[0].trim()), nodeAndAddr[1].trim());
                }
            }
        }

        int minTimeout = Integer.parseInt(getEnvOrDefault("ELECTION_TIMEOUT_MIN_MS", "300"));
        int maxTimeout = Integer.parseInt(getEnvOrDefault("ELECTION_TIMEOUT_MAX_MS", "600"));
        int heartbeat = Integer.parseInt(getEnvOrDefault("HEARTBEAT_INTERVAL_MS", "50"));
        String dataDir = getEnvOrDefault("DATA_DIR", "data/node" + nodeId);

        return new ClusterConfig(nodeId, port, peers, minTimeout, maxTimeout, heartbeat, dataDir);
    }

    private static String getEnvOrDefault(String key, String defaultValue) {
        String val = System.getenv(key);
        return val != null ? val : defaultValue;
    }

    public int getNodeId() {
        return nodeId;
    }

    public int getPort() {
        return port;
    }

    public Map<Integer, String> getPeers() {
        return peers;
    }

    public int getElectionTimeoutMinMs() {
        return electionTimeoutMinMs;
    }

    public int getElectionTimeoutMaxMs() {
        return electionTimeoutMaxMs;
    }

    public int getHeartbeatIntervalMs() {
        return heartbeatIntervalMs;
    }

    public String getDataDir() {
        return dataDir;
    }

    @Override
    public String toString() {
        return "ClusterConfig{" +
                "nodeId=" + nodeId +
                ", port=" + port +
                ", peers=" + peers +
                ", electionTimeoutMinMs=" + electionTimeoutMinMs +
                ", electionTimeoutMaxMs=" + electionTimeoutMaxMs +
                ", heartbeatIntervalMs=" + heartbeatIntervalMs +
                ", dataDir='" + dataDir + '\'' +
                '}';
    }
}
