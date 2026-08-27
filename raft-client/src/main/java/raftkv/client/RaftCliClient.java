package raftkv.client;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import raftkv.rpc.proto.*;// The line option java_package = "raftkv.rpc.proto" in raft-rpc/src/main/proto/raft.proto tells the compiler: "Any Java code generated from this file must belong to the raftkv.rpc.proto package."

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RaftCliClient {
    private final Map<Integer, String> servers = new HashMap<>();
    private int currentServerId = -1;
    private ManagedChannel channel;
    /* In gRPC, a ManagedChannel represents a connection to a specific remote server (at a given host and port). You can think of it as the network
  pipeline through which all of your client's gRPC requests travel.

  Here is a detailed breakdown of why it is used and what makes it special:
  ──────
  ### 1. What does the "Managed" part mean?

  Instead of being a simple TCP socket, a ManagedChannel is a high-level manager that automatically handles complex networking details for you:

  • Connection Lifecycle: It automatically connects to the server when needed, keeps the connection alive, and reconnects if the connection drops.
  • Thread Management: It runs background threads to read and write bytes from the socket so your main application code doesn't have to manage
  raw threads.
  • Resource Management: When you call channel.shutdown(), it cleanly closes all sockets, thread pools, and connections to prevent resource leaks.
  ──────
  ### 2. How is it used in your code?

  First, you build a channel by providing the address of a Raft node:

    channel = ManagedChannelBuilder.forAddress("localhost", 8001)
            .usePlaintext() // Disables TLS/SSL encryption (fine for local testing)
            .build();

  Then, you bind your Stub to the channel. The stub acts as the interface, and the channel acts as the pipe:

    // We pass the channel to the stub so the stub knows where to send requests
    blockingStub = KVServiceGrpc.newBlockingStub(channel);

  Finally, when you are done, you shut it down to release the system memory:

    channel.shutdown().awaitTermination(1, TimeUnit.SECONDS);

*/
    private KVServiceGrpc.KVServiceBlockingStub blockingStub;
    /*In gRPC (Google Remote Procedure Call), a Stub is a helper class that acts as a local proxy for a remote server. It makes a network call to
  another machine look and feel exactly like calling a local method on a Java class.
  Here is a detailed breakdown of private KVServiceGrpc.KVServiceBlockingStub blockingStub;:
  ──────
  ### 1. Where does KVServiceGrpc come from?

  This class is automatically generated from your raft.proto file, where you defined the client service contract:
    service KVService {
      rpc Put (PutRequest) returns (PutResponse);
      rpc Get (GetRequest) returns (GetResponse);
    }
    ──────
  ### 2. What is a "Blocking Stub"?

  A Blocking Stub is a specific type of gRPC stub where network requests are synchronous.

  When the client thread executes a remote call on a blocking stub:

    PutResponse response = blockingStub.put(request);

  The thread will stop and wait (block) on that line of code. It will not move to the next line until the remote server finishes executing the
  request, sends a response back over the network, and the client receives it.
  ──────
  ### 3. How does it work under the hood?

  Normally, sending data over a network requires:

  1. Opening a raw network socket connection to an IP and Port.
  2. Serializing your Java objects into a stream of binary bytes.
  3. Sending the bytes over TCP.
  4. Parsing the incoming response bytes.
  5. Deserializing those bytes back into a Java object.

  The Stub does all of this boilerplate work for you. You just pass it a channel (which contains the IP and port of a Raft node):

    // Initialize the stub
    blockingStub = KVServiceGrpc.newBlockingStub(channel);

    // Use it like a local method call
    PutResponse response = blockingStub.put(request);

  Under the hood, the stub serializes the request into binary, transmits it to the server, blocks until the server replies, and returns the
  deserialized response object.*/
    public RaftCliClient(Map<Integer, String> servers) {
        this.servers.putAll(servers);
        if (!servers.isEmpty()) {
            this.currentServerId = servers.keySet().iterator().next();
        }
    }

    private void connect(int serverId) {
        if (channel != null) {
            try {
                channel.shutdown().awaitTermination(1, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        }
        String address = servers.get(serverId);
        if (address == null) {
            throw new IllegalArgumentException("Unknown server ID: " + serverId);
        }
        String[] parts = address.split(":");
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);
        channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        blockingStub = KVServiceGrpc.newBlockingStub(channel);
        currentServerId = serverId;
    }

    public void shutdown() {
        if (channel != null) {
            channel.shutdown();
        }
    }

    public void put(String key, String value) {
        if (currentServerId == -1) {
            System.err.println("No servers configured.");
            return;
        }

        int retries = 0;
        int maxRetries = servers.size();
        while (retries < maxRetries) {
            if (blockingStub == null) {
                connect(currentServerId);
            }
            try {
                System.out.println("Sending PUT " + key + "=" + value + " to Node " + currentServerId + "...");
                PutResponse response = blockingStub.withDeadlineAfter(3, TimeUnit.SECONDS)
                        .put(PutRequest.newBuilder().setKey(key).setValue(value).build());
                if (response.getSuccess()) {
                    System.out.println("SUCCESS: Put completed successfully.");
                    return;
                } else {
                    int leaderId = response.getLeaderId();
                    if (leaderId != -1 && servers.containsKey(leaderId)) {
                        System.out.println("REDIRECT: Node " + currentServerId + " is not the leader. Redirecting to Leader Node " + leaderId + "...");
                        connect(leaderId);
                    } else {
                        System.out.println("ERROR: Request failed, leader unknown. Trying next server...");
                        tryNextServer();
                    }
                }
            } catch (Exception e) {
                System.err.println("Communication error with Node " + currentServerId + ": " + e.getMessage());
                tryNextServer();
            }
            retries++;
        }
        System.err.println("FAILED: Could not complete PUT operation after trying all configured servers.");
    }

    public void get(String key) {
        if (currentServerId == -1) {
            System.err.println("No servers configured.");
            return;
        }

        int retries = 0;
        int maxRetries = servers.size();
        while (retries < maxRetries) {
            if (blockingStub == null) {
                connect(currentServerId);
            }
            try {
                System.out.println("Sending GET " + key + " to Node " + currentServerId + "...");
                GetResponse response = blockingStub.withDeadlineAfter(3, TimeUnit.SECONDS)
                        .get(GetRequest.newBuilder().setKey(key).build());
                if (response.getSuccess()) {
                    System.out.println("SUCCESS: Value for '" + key + "' is '" + response.getValue() + "'");
                    return;
                } else {
                    int leaderId = response.getLeaderId();
                    if (leaderId != -1 && servers.containsKey(leaderId)) {
                        System.out.println("REDIRECT: Node " + currentServerId + " redirected to Leader Node " + leaderId + "...");
                        connect(leaderId);
                    } else {
                        System.out.println("ERROR: Request failed. Trying next server...");
                        tryNextServer();
                    }
                }
            } catch (Exception e) {
                System.err.println("Communication error with Node " + currentServerId + ": " + e.getMessage());
                tryNextServer();
            }
            retries++;
        }
        System.err.println("FAILED: Could not complete GET operation after trying all configured servers.");
    }

    private void tryNextServer() {
        for (int serverId : servers.keySet()) {
            if (serverId != currentServerId) {
                connect(serverId);
                return;
            }
        }
    }

    public static void main(String[] args) {
        if (args.length < 3) {
            printUsage();
            System.exit(1);
        }
        //1> build server map using the user defined arguments
        //user requests like this:./gradlew :raft-client:run --args="1=localhost:8001,2=localhost:8002,3=localhost:8003 put mykey myvalue"
        String serversArg = args[0];
        String operation = args[1].toLowerCase();
        String key = args[2];
        String value = operation.equals("put") && args.length > 3 ? args[3] : "";

        Map<Integer, String> serverMap = new HashMap<>();
        try {
            String[] parts = serversArg.split(",");
            for (String part : parts) {
                String[] nodeAndAddr = part.split("=");
                serverMap.put(Integer.parseInt(nodeAndAddr[0].trim()), nodeAndAddr[1].trim());
            }
        } catch (Exception e) {
            System.err.println("Invalid servers argument: " + serversArg);
            printUsage();
            System.exit(1);
        }

        RaftCliClient client = new RaftCliClient(serverMap);
        try {
            if (operation.equals("put")) {
                client.put(key, value);
            } else if (operation.equals("get")) {
                client.get(key);
            } else {
                System.err.println("Unknown operation: " + operation);
                printUsage();
                System.exit(1);
            }
        } finally {
            client.shutdown();
        }
    }

    private static void printUsage() {
        System.out.println("Usage: java -cp ... raftkv.client.RaftCliClient <servers> <operation> <key> [value]");
        System.out.println("  servers: comma-separated list of nodeId=host:port (e.g. 1=localhost:8001,2=localhost:8002)");
        System.out.println("  operation: put or get");
        System.out.println("  key: key to read or write");
        System.out.println("  value: value to write (required for put)");
    }
}
