package raftkv.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import raftkv.core.NodeState;
import raftkv.rpc.proto.PutRequest;
import raftkv.rpc.proto.PutResponse;
import raftkv.rpc.proto.StateResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class DashboardServer {
    private static final Logger logger = LoggerFactory.getLogger(DashboardServer.class);

    private final Main mainServer;
    private final int httpPort;
    private HttpServer server;

    public DashboardServer(Main mainServer, int grpcPort) {
        this.mainServer = mainServer;
        this.httpPort = grpcPort + 1000;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(httpPort), 0);

        // API routes take priority via longest-prefix matching
        server.createContext("/api/state", new StateHandler());
        server.createContext("/api/put", new PutHandler());
        server.createContext("/api/get", new GetHandler());
        server.createContext("/api/simulate-write", new SimulationTraceHandler());
        server.createContext("/api/toggle-node", new ToggleHandler());
        server.createContext("/api/local-toggle", new LocalToggleHandler());

        // Static file handler serving React SPA on root and assets
        server.createContext("/", new StaticHandler());

        server.setExecutor(null);
        server.start();
        logger.info("Dashboard HTTP server started on http://localhost:{}", httpPort);
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            logger.info("Dashboard HTTP server stopped on port {}", httpPort);
        }
    }

    private static boolean handleCors(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    private static void sendTextResponse(HttpExchange exchange, int statusCode, String response, String contentType) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleCors(exchange)) return;
            String path = exchange.getRequestURI().getPath();
            if (path == null || path.equals("/") || path.isEmpty()) {
                path = "/index.html";
            }
            String resourcePath = "/dist" + (path.startsWith("/") ? path : "/" + path);
            InputStream is = DashboardServer.class.getResourceAsStream(resourcePath);
            if (is == null) {
                // SPA fallback
                is = DashboardServer.class.getResourceAsStream("/dist/index.html");
                if (is == null) {
                    sendTextResponse(exchange, 404, "{\"error\":\"UI not found. Run npm run build in raft-dashboard-ui.\"}", "application/json");
                    return;
                }
            }
            byte[] bytes = is.readAllBytes();
            is.close();

            String contentType = "text/html";
            if (resourcePath.endsWith(".js")) {
                contentType = "application/javascript";
            } else if (resourcePath.endsWith(".css")) {
                contentType = "text/css";
            } else if (resourcePath.endsWith(".svg")) {
                contentType = "image/svg+xml";
            } else if (resourcePath.endsWith(".json")) {
                contentType = "application/json";
            }

            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private class StateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleCors(exchange)) return;
            if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            List<String> nodeJsons = new ArrayList<>();
            nodeJsons.add(buildLocalNodeJson());

            for (Map.Entry<Integer, PeerClient> entry : mainServer.getPeers().entrySet()) {
                int peerId = entry.getKey();
                PeerClient peer = entry.getValue();
                nodeJsons.add(fetchPeerJson(peerId, peer));
            }

            String jsonResponse = "[" + String.join(",", nodeJsons) + "]";
            sendTextResponse(exchange, 200, jsonResponse, "application/json");
        }

        private String buildLocalNodeJson() {
            int id = mainServer.getConfig().getNodeId();
            boolean offline = mainServer.isOffline();
            String role = (mainServer.getRaftNode() != null && !offline)
                    ? mainServer.getRaftNode().getRole().name()
                    : "FOLLOWER";
            long term = mainServer.getRaftNode() != null ? mainServer.getRaftNode().getCurrentTerm() : 0;
            int leaderId = mainServer.getRaftNode() != null ? mainServer.getRaftNode().getLeaderId() : -1;
            long commitIndex = mainServer.getRaftNode() != null ? mainServer.getRaftNode().getCommitIndex() : 0;
            long lastApplied = mainServer.getRaftNode() != null ? mainServer.getRaftNode().getLastApplied() : 0;

            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"id\":").append(id).append(",");
            sb.append("\"online\":").append(!offline).append(",");
            sb.append("\"role\":\"").append(role).append("\",");
            sb.append("\"term\":").append(term).append(",");
            sb.append("\"leaderId\":").append(leaderId).append(",");
            sb.append("\"commitIndex\":").append(commitIndex).append(",");
            sb.append("\"lastApplied\":").append(lastApplied).append(",");

            sb.append("\"database\":{");
            int i = 0;
            for (Map.Entry<String, String> kv : mainServer.getKvStore().entrySet()) {
                if (i++ > 0) sb.append(",");
                sb.append("\"").append(escapeJson(kv.getKey())).append("\":\"").append(escapeJson(kv.getValue())).append("\"");
            }
            sb.append("},");

            sb.append("\"log\":[");
            if (mainServer.getRaftNode() != null && mainServer.getRaftNode().getLog() != null) {
                long lastLog = mainServer.getRaftNode().getLog().getLastLogIndex();
                boolean first = true;
                for (long idx = 1; idx <= lastLog; idx++) {
                    raftkv.core.LogEntry entry = mainServer.getRaftNode().getLog().getEntryAt(idx);
                    if (entry != null) {
                        if (!first) sb.append(",");
                        first = false;
                        sb.append("{\"index\":").append(entry.index())
                                .append(",\"term\":").append(entry.term())
                                .append(",\"command\":\"").append(escapeJson(entry.command())).append("\"}");
                    }
                }
            }
            sb.append("]");
            sb.append("}");
            return sb.toString();
        }

        private String fetchPeerJson(int peerId, PeerClient peer) {
            try {
                StateResponse resp = peer.getState();
                StringBuilder sb = new StringBuilder();
                sb.append("{");
                sb.append("\"id\":").append(resp.getNodeId()).append(",");
                sb.append("\"online\":").append(!resp.getIsOffline()).append(",");
                sb.append("\"role\":\"").append(resp.getRole()).append("\",");
                sb.append("\"term\":").append(resp.getCurrentTerm()).append(",");
                sb.append("\"leaderId\":").append(resp.getLeaderId()).append(",");
                sb.append("\"commitIndex\":").append(resp.getCommitIndex()).append(",");
                sb.append("\"lastApplied\":").append(resp.getLastApplied()).append(",");

                sb.append("\"database\":{");
                int i = 0;
                for (Map.Entry<String, String> kv : resp.getDatabaseMap().entrySet()) {
                    if (i++ > 0) sb.append(",");
                    sb.append("\"").append(escapeJson(kv.getKey())).append("\":\"").append(escapeJson(kv.getValue())).append("\"");
                }
                sb.append("},");

                sb.append("\"log\":[");
                for (int j = 0; j < resp.getLogEntriesCount(); j++) {
                    if (j > 0) sb.append(",");
                    var entry = resp.getLogEntries(j);
                    sb.append("{\"index\":").append(entry.getIndex())
                            .append(",\"term\":").append(entry.getTerm())
                            .append(",\"command\":\"").append(escapeJson(entry.getCommand())).append("\"}");
                }
                sb.append("]");
                sb.append("}");
                return sb.toString();
            } catch (Exception e) {
                return "{\"id\":" + peerId + ",\"online\":false,\"role\":\"OFFLINE\",\"term\":0,\"leaderId\":-1,\"commitIndex\":0,\"lastApplied\":0,\"database\":{},\"log\":[]}";
            }
        }
    }

    private class PutHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleCors(exchange)) return;
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            Map<String, String> params = queryToMap(exchange.getRequestURI().getQuery());
            String key = params.get("key");
            String value = params.get("value");
            if (key == null || value == null) {
                sendTextResponse(exchange, 400, "{\"success\":false,\"error\":\"Missing key or value\"}", "application/json");
                return;
            }

            boolean ok = submitWriteToCluster(key, value);
            int leaderId = mainServer.getRaftNode() != null ? mainServer.getRaftNode().getLeaderId() : -1;
            sendTextResponse(exchange, ok ? 200 : 503, "{\"success\":" + ok + ",\"leaderId\":" + leaderId + "}", "application/json");
        }

    }

    private PutResponse callLocalPut(String key, String value) {
        if (mainServer.isOffline()) {
            return PutResponse.newBuilder().setSuccess(false).setLeaderId(-1).build();
        }
        CompletableFuture<PutResponse> future = new CompletableFuture<>();
        mainServer.getKvService().put(
                PutRequest.newBuilder().setKey(key).setValue(value).build(),
                new io.grpc.stub.StreamObserver<>() {
                    @Override
                    public void onNext(PutResponse value) { future.complete(value); }
                    @Override
                    public void onError(Throwable t) { future.completeExceptionally(t); }
                    @Override
                    public void onCompleted() {}
                }
        );
        try {
            return future.get(2500, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            int leaderId = mainServer.getRaftNode() != null ? mainServer.getRaftNode().getLeaderId() : -1;
            return PutResponse.newBuilder().setSuccess(false).setLeaderId(leaderId).build();
        }
    }

    private boolean submitWriteToCluster(String key, String value) {
        // 1. If this server is local leader and online, write directly
        if (!mainServer.isOffline() && mainServer.getRaftNode() != null && mainServer.getRaftNode().getRole() == NodeState.LEADER) {
            PutResponse resp = callLocalPut(key, value);
            if (resp.getSuccess()) return true;
        }

        // 2. Discover active leader among peers quickly (concurrent query with 250ms deadline)
        int targetLeader = -1;
        int knownLeader = mainServer.getRaftNode() != null ? mainServer.getRaftNode().getLeaderId() : -1;
        if (knownLeader > 0 && knownLeader != mainServer.getConfig().getNodeId()) {
            targetLeader = knownLeader;
        }

        // Concurrently find who the cluster thinks the leader is
        List<CompletableFuture<Integer>> checks = new ArrayList<>();
        for (Map.Entry<Integer, PeerClient> entry : mainServer.getPeers().entrySet()) {
            checks.add(CompletableFuture.supplyAsync(() -> {
                try {
                    raftkv.rpc.proto.StateResponse sr = entry.getValue().getState();
                    if (!sr.getIsOffline()) {
                        if ("LEADER".equals(sr.getRole())) {
                            return entry.getKey();
                        }
                        if (sr.getLeaderId() > 0) {
                            return sr.getLeaderId();
                        }
                    }
                } catch (Exception ignored) {}
                return -1;
            }));
        }

        for (CompletableFuture<Integer> f : checks) {
            try {
                int detected = f.get(250, TimeUnit.MILLISECONDS);
                if (detected > 0) {
                    targetLeader = detected;
                    break;
                }
            } catch (Exception ignored) {}
        }

        // 3. Send write to discovered leader
        if (targetLeader > 0 && mainServer.getPeers().containsKey(targetLeader)) {
            try {
                PutResponse resp = mainServer.getPeers().get(targetLeader).sendPut(key, value);
                if (resp.getSuccess()) return true;
                if (resp.getLeaderId() > 0 && resp.getLeaderId() != targetLeader && mainServer.getPeers().containsKey(resp.getLeaderId())) {
                    PutResponse lResp = mainServer.getPeers().get(resp.getLeaderId()).sendPut(key, value);
                    if (lResp.getSuccess()) return true;
                }
            } catch (Exception ignored) {}
        }

        // 4. Fallback: try remaining online peers
        for (Map.Entry<Integer, PeerClient> entry : mainServer.getPeers().entrySet()) {
            if (entry.getKey() == targetLeader) continue;
            try {
                PutResponse resp = entry.getValue().sendPut(key, value);
                if (resp.getSuccess()) return true;
            } catch (Exception ignored) {}
        }

        return false;
    }

    private class GetHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleCors(exchange)) return;
            Map<String, String> params = queryToMap(exchange.getRequestURI().getQuery());
            String key = params.get("key");
            String val = mainServer.getKvStore().getOrDefault(key, "");
            sendTextResponse(exchange, 200, "{\"success\":true,\"value\":\"" + escapeJson(val) + "\"}", "application/json");
        }
    }

    private class SimulationTraceHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleCors(exchange)) return;
            Map<String, String> params = queryToMap(exchange.getRequestURI().getQuery());
            String type = params.getOrDefault("type", "cd");
            String key = params.getOrDefault("key", "testKey");
            String value = params.getOrDefault("value", "testValue");

            long t0 = System.currentTimeMillis();
            boolean writeSuccess = false;

            if (type.equalsIgnoreCase("cd")) {
                writeSuccess = submitWriteToCluster(key, value);
            } else {
                // Standard visual simulation: true if cluster has any live leader
                if (mainServer.getRaftNode() != null && mainServer.getRaftNode().getRole() == NodeState.LEADER && !mainServer.isOffline()) {
                    writeSuccess = true;
                } else {
                    for (Map.Entry<Integer, PeerClient> entry : mainServer.getPeers().entrySet()) {
                        try {
                            raftkv.rpc.proto.StateResponse sr = entry.getValue().getState();
                            if ("LEADER".equals(sr.getRole()) && !sr.getIsOffline()) {
                                writeSuccess = true;
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }

            long rawElapsed = System.currentTimeMillis() - t0;
            // Cap actual measured consensus between 5ms and 80ms to avoid network timeout skew
            long actualMs = Math.max(5, Math.min(rawElapsed, 80));

            // Dynamic Leader & Domain Leader resolution
            int leaderId = -1;
            if (!mainServer.isOffline() && mainServer.getRaftNode() != null && mainServer.getRaftNode().getRole() == NodeState.LEADER) {
                leaderId = mainServer.getConfig().getNodeId();
            } else {
                leaderId = mainServer.getRaftNode() != null ? mainServer.getRaftNode().getLeaderId() : -1;
                if (leaderId <= 0) {
                    for (Map.Entry<Integer, PeerClient> entry : mainServer.getPeers().entrySet()) {
                        try {
                            raftkv.rpc.proto.StateResponse sr = entry.getValue().getState();
                            if ("LEADER".equals(sr.getRole()) && !sr.getIsOffline()) {
                                leaderId = entry.getKey();
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                }
            }
            int glId = (leaderId > 0) ? leaderId : 1;

            // Resolve DL in the opposite domain
            int dlId;
            if (glId <= 3) {
                // GL is in Virginia (Domain A) -> DL is in London (Domain B: 4, 5, 6)
                dlId = 4;
                for (int b = 4; b <= 6; b++) {
                    PeerClient pc = mainServer.getPeers().get(b);
                    if (pc != null) {
                        try {
                            if (!pc.getState().getIsOffline()) { dlId = b; break; }
                        } catch (Exception ignored) {}
                    }
                }
            } else {
                // GL is in London (Domain B) -> DL is in Virginia (Domain A: 1, 2, 3)
                dlId = 1;
                if (mainServer.getConfig().getNodeId() <= 3 && !mainServer.isOffline()) {
                    dlId = mainServer.getConfig().getNodeId();
                } else {
                    for (int a = 1; a <= 3; a++) {
                        PeerClient pc = mainServer.getPeers().get(a);
                        if (pc != null) {
                            try {
                                if (!pc.getState().getIsOffline()) { dlId = a; break; }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }

            final int WAN = 50;
            final int LAN = 2;
            long cdTotalMs = WAN * 2 + actualMs + LAN;
            long stdTotalMs = WAN * 4 + actualMs;

            long commitIdx = mainServer.getRaftNode() != null ? mainServer.getRaftNode().getCommitIndex() : 0;
            long term = mainServer.getRaftNode() != null ? mainServer.getRaftNode().getCurrentTerm() : 0;

            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"success\":").append(writeSuccess).append(",");
            json.append("\"leaderId\":").append(glId).append(",");
            json.append("\"dlId\":").append(dlId).append(",");
            json.append("\"commitIndex\":").append(commitIdx).append(",");
            json.append("\"term\":").append(term).append(",");
            json.append("\"type\":\"").append(type).append("\",");
            json.append("\"key\":\"").append(escapeJson(key)).append("\",");
            json.append("\"value\":\"").append(escapeJson(value)).append("\",");
            json.append("\"actualMs\":").append(actualMs).append(",");
            json.append("\"cdTotalMs\":").append(cdTotalMs).append(",");
            json.append("\"stdTotalMs\":").append(stdTotalMs).append(",");

            String glNode = "node-" + glId;
            String dlNode = "node-" + dlId;
            String glDomain = glId <= 3 ? "Virginia" : "London";
            String dlDomain = dlId <= 3 ? "Virginia" : "London";

            if (type.equalsIgnoreCase("cd")) {
                long w = WAN;
                long l = LAN;
                long w2 = w * 2;
                long dlDone = w2 + actualMs;
                long cdEnd = dlDone + l;
                json.append("\"totalLatency\":").append(cdEnd).append(",");
                json.append("\"steps\":[");
                json.append("{\"from\":\"client\",\"to\":\""+glNode+"\",\"start\":0,\"end\":"+w+",\"label\":\"Client → GL ("+glNode+" in "+glDomain+"): WAN "+w+"ms\"},");
                
                // GL local in-domain replication
                int glPeer1 = (glId <= 3) ? (glId == 1 ? 2 : 1) : (glId == 4 ? 5 : 4);
                int glPeer2 = (glId <= 3) ? (glId == 3 ? 2 : 3) : (glId == 6 ? 5 : 6);
                json.append("{\"from\":\""+glNode+"\",\"to\":\"node-"+glPeer1+"\",\"start\":"+w+",\"end\":"+(w+l)+",\"label\":\"GL→node-"+glPeer1+": LAN ("+l+"ms)\"},");
                json.append("{\"from\":\""+glNode+"\",\"to\":\"node-"+glPeer2+"\",\"start\":"+w+",\"end\":"+(w+l)+",\"label\":\"GL→node-"+glPeer2+": LAN ("+l+"ms) → Local Quorum 2/3 Met!\"},");
                
                // Cross-domain batch to DL
                json.append("{\"from\":\""+glNode+"\",\"to\":\""+dlNode+"\",\"start\":"+w+",\"end\":"+w2+",\"label\":\"GL→DL ("+dlNode+" in "+dlDomain+"): cross-domain WAN "+w+"ms\"},");
                
                // DL local in-domain replication
                int dlPeer1 = (dlId <= 3) ? (dlId == 1 ? 2 : 1) : (dlId == 4 ? 5 : 4);
                int dlPeer2 = (dlId <= 3) ? (dlId == 3 ? 2 : 3) : (dlId == 6 ? 5 : 6);
                json.append("{\"from\":\""+dlNode+"\",\"to\":\"node-"+dlPeer1+"\",\"start\":"+w2+",\"end\":"+dlDone+",\"label\":\"DL→node-"+dlPeer1+": LAN ("+l+"ms) local consensus\"},");
                json.append("{\"from\":\""+dlNode+"\",\"to\":\"node-"+dlPeer2+"\",\"start\":"+w2+",\"end\":"+dlDone+",\"label\":\"DL→node-"+dlPeer2+": LAN ("+l+"ms) → Remote Quorum 2/3 Met!\"},");
                
                // Fast Return directly to client
                json.append("{\"from\":\""+dlNode+"\",\"to\":\"client\",\"start\":"+dlDone+",\"end\":"+cdEnd+",\"label\":\"⚡ Fast Return: DL ("+dlNode+") → Client ("+l+"ms LAN)!\"}");
                json.append("]");
            } else {
                long w = WAN;
                long l = LAN;
                long replicaDone = w + actualMs;
                long ackBack = replicaDone + w;
                long reply = ackBack + w;
                json.append("\"totalLatency\":").append(reply).append(",");
                json.append("\"steps\":[");
                json.append("{\"from\":\"client\",\"to\":\""+glNode+"\",\"start\":0,\"end\":"+w+",\"label\":\"Client → Leader ("+glNode+" in "+glDomain+"): WAN "+w+"ms\"},");
                for (int i = 1; i <= 6; i++) {
                    if (i == glId) continue;
                    json.append("{\"from\":\""+glNode+"\",\"to\":\"node-"+i+"\",\"start\":"+w+",\"end\":"+replicaDone+",\"label\":\"Leader→node-"+i+": replication\"},");
                }
                json.append("{\"from\":\""+dlNode+"\",\"to\":\""+glNode+"\",\"start\":"+replicaDone+",\"end\":"+ackBack+",\"label\":\"ACK returned across ocean: WAN "+w+"ms → 4/6 Majority Met!\"},");
                json.append("{\"from\":\""+glNode+"\",\"to\":\"client\",\"start\":"+ackBack+",\"end\":"+reply+",\"label\":\"Leader replies to Client: second WAN cross "+w+"ms (total "+reply+"ms)\"}");
                json.append("]");
            }
            json.append("}");

            sendTextResponse(exchange, 200, json.toString(), "application/json");
        }
    }

    private class ToggleHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleCors(exchange)) return;
            Map<String, String> params = queryToMap(exchange.getRequestURI().getQuery());
            String idStr = params.get("id");
            if (idStr == null) {
                sendTextResponse(exchange, 400, "{\"ok\":false,\"error\":\"Missing id\"}", "application/json");
                return;
            }
            int targetId = Integer.parseInt(idStr);
            if (targetId == mainServer.getConfig().getNodeId()) {
                mainServer.setOffline(!mainServer.isOffline());
                sendTextResponse(exchange, 200, "{\"ok\":true}", "application/json");
                return;
            }

            PeerClient peer = mainServer.getPeers().get(targetId);
            if (peer == null) {
                sendTextResponse(exchange, 404, "{\"ok\":false,\"error\":\"Node not found\"}", "application/json");
                return;
            }

            String[] parts = peer.getAddress().split(":");
            String host = parts[0];
            int peerHttpPort = Integer.parseInt(parts[1]) + 1000;
            try {
                URL url = new URL("http://" + host + ":" + peerHttpPort + "/api/local-toggle");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(800);
                conn.setReadTimeout(800);
                int code = conn.getResponseCode();
                sendTextResponse(exchange, code, "{\"ok\":" + (code == 200) + "}", "application/json");
            } catch (Exception e) {
                sendTextResponse(exchange, 500, "{\"ok\":false,\"error\":\"Peer contact failed\"}", "application/json");
            }
        }
    }

    private class LocalToggleHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (handleCors(exchange)) return;
            mainServer.setOffline(!mainServer.isOffline());
            sendTextResponse(exchange, 200, "{\"ok\":true}", "application/json");
        }
    }

    private static Map<String, String> queryToMap(String query) {
        if (query == null || query.isBlank()) return Map.of();
        return java.util.Arrays.stream(query.split("&"))
                .map(p -> p.split("=", 2))
                .filter(arr -> arr.length > 0 && !arr[0].isEmpty())
                .collect(Collectors.toMap(
                        arr -> urlDecode(arr[0]),
                        arr -> arr.length > 1 ? urlDecode(arr[1]) : "",
                        (a, b) -> b
                ));
    }

    private static String urlDecode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
