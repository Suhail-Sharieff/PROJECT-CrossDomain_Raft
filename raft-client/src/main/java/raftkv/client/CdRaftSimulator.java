package raftkv.client;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Feasibility Simulator comparing Standard Raft vs CD-Raft latency.
 * Emulates parallel cross-domain network replication with artificial delays.
 */
public class CdRaftSimulator {

    // Latency parameters in milliseconds
    private static final int CROSS_DOMAIN_ONE_WAY_MS = 50;  // 100ms RTT
    private static final int INTRA_DOMAIN_ONE_WAY_MS = 2;   // 4ms RTT
    private static final int BENCHMARK_OPERATIONS = 10;

    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(8);

    public static void main(String[] args) throws Exception {
        System.out.println("=========================================================");
        System.out.println("          CD-RAFT LATENCY SIMULATION BENCHMARK           ");
        System.out.println("=========================================================");
        System.out.println("Topology: 4 Nodes across 2 Domains");
        System.out.println("  - Domain A: Node 1 (GL), Node 2 (Follower)");
        System.out.println("  - Domain B: Node 3 (DL), Node 4 (Follower)");
        System.out.println("  - Client Location: Domain B");
        System.out.println("  - Latency settings: Cross-Domain RTT = 100ms, Local RTT = 4ms");
        System.out.println("=========================================================");

        // 1. Run Standard Raft Benchmark
        System.out.println("\nRunning Standard Raft write benchmark (10 operations)...");
        long stdStart = System.currentTimeMillis();
        for (int i = 0; i < BENCHMARK_OPERATIONS; i++) {
            runStandardRaftWrite().get();
            System.out.print(".");
        }
        long stdDuration = System.currentTimeMillis() - stdStart;
        double stdAvg = (double) stdDuration / BENCHMARK_OPERATIONS;
        System.out.printf("\nStandard Raft average write latency: %.2f ms\n", stdAvg);

        // 2. Run CD-Raft Benchmark
        System.out.println("\nRunning CD-Raft (Fast Return) write benchmark (10 operations)...");
        long cdStart = System.currentTimeMillis();
        for (int i = 0; i < BENCHMARK_OPERATIONS; i++) {
            runCdRaftWrite().get();
            System.out.print(".");
        }
        long cdDuration = System.currentTimeMillis() - cdStart;
        double cdAvg = (double) cdDuration / BENCHMARK_OPERATIONS;
        System.out.printf("\nCD-Raft average write latency: %.2f ms\n", cdAvg);

        // 3. Print Results Summary
        System.out.println("\n=========================================================");
        System.out.println("                    BENCHMARK SUMMARY                    ");
        System.out.println("=========================================================");
        System.out.printf("Standard Raft Latency: %.2f ms (approx. 2.0 RTTs)\n", stdAvg);
        System.out.printf("CD-Raft Latency:       %.2f ms (approx. 1.0 RTTs)\n", cdAvg);
        double reduction = (1.0 - (cdAvg / stdAvg)) * 100;
        System.out.printf("LATENCY REDUCTION:     %.2f%%\n", reduction);
        System.out.println("=========================================================");

        scheduler.shutdown();
    }

    /**
     * Standard Raft write flow:
     * 1. Client (Domain B) sends PUT to Leader (Node 1, Domain A) -> 50ms.
     * 2. Leader appends locally, sends replication to Node 2 (local, 2ms) and Node 3 & 4 (cross-domain, 50ms).
     * 3. Leader waits for quorum (majority = 3 out of 4 nodes).
     *    - Node 2 replies in 2ms.
     *    - Node 3 replies in 50ms.
     * 4. Quorum achieved once Node 3's reply arrives (taking 50ms write + 50ms response = 100ms total replication time).
     * 5. Leader commits and replies to Client (Domain B) -> 50ms.
     */
    private static CompletableFuture<Void> runStandardRaftWrite() {
        CompletableFuture<Void> clientFuture = new CompletableFuture<>();

        // Step 1: Client -> Leader (Cross-Domain)
        scheduler.schedule(() -> {
            // Leader received request, starts local log append and broadcasts AppendEntries in parallel
            AtomicInteger acks = new AtomicInteger(1); // Leader counts itself
            CompletableFuture<Void> quorumFuture = new CompletableFuture<>();

            // Step 2: Replicate to Node 2 (Local Domain A)
            scheduler.schedule(() -> {
                if (acks.incrementAndGet() >= 3) {
                    quorumFuture.complete(null);
                }
            }, INTRA_DOMAIN_ONE_WAY_MS * 2, TimeUnit.MILLISECONDS); // RTT inside Domain A

            // Step 3: Replicate to Node 3 (Cross-Domain to Domain B)
            scheduler.schedule(() -> {
                if (acks.incrementAndGet() >= 3) {
                    quorumFuture.complete(null);
                }
            }, CROSS_DOMAIN_ONE_WAY_MS * 2, TimeUnit.MILLISECONDS); // Cross-Domain RTT

            // Step 4: Replicate to Node 4 (Cross-Domain to Domain B)
            scheduler.schedule(() -> {
                if (acks.incrementAndGet() >= 3) {
                    quorumFuture.complete(null);
                }
            }, CROSS_DOMAIN_ONE_WAY_MS * 2, TimeUnit.MILLISECONDS);

            // Step 5: Quorum met -> Leader replies to client (Cross-Domain)
            quorumFuture.thenRun(() -> {
                scheduler.schedule(() -> {
                    clientFuture.complete(null);
                }, CROSS_DOMAIN_ONE_WAY_MS, TimeUnit.MILLISECONDS);
            });

        }, CROSS_DOMAIN_ONE_WAY_MS, TimeUnit.MILLISECONDS);

        return clientFuture;
    }

    /**
     * CD-Raft write flow (Fast Return):
     * 1. Client (Domain B) sends PUT to Global Leader (Node 1, Domain A) -> 50ms.
     * 2. GL broadcasts write to Domain Leaders (Node 3, Domain B) -> 50ms.
     * 3. Concurrent replication:
     *    - GL replicates to Node 2 (local, 2ms). Local quorum achieved in 4ms RTT.
     *    - DL Node 3 receives request and replicates to Node 4 (local, 2ms). Local quorum achieved in 4ms RTT.
     * 4. Confirmation: Once GL has local quorum (which occurs at 50ms Client-to-GL + 4ms replication = 54ms),
     *    it sends a "QuorumReached" confirmation to Node 3 (cross-domain, 50ms).
     * 5. Fast Return: Node 3 receives the confirmation from GL (takes 54ms + 50ms = 104ms).
     *    Since Node 3 already has local quorum in Domain B (achieved at 50ms + 50ms dispatch + 4ms local = 104ms),
     *    Node 3 directly replies to the client (local, 2ms).
     */
    private static CompletableFuture<Void> runCdRaftWrite() {
        CompletableFuture<Void> clientFuture = new CompletableFuture<>();

        // Step 1: Client -> GL Node 1 (Cross-Domain, 50ms)
        scheduler.schedule(() -> {
            
            // GL sends write to DL Node 3 in Domain B (Cross-Domain, 50ms)
            // Meanwhile, GL replicates to Node 2 (Local, 2ms)
            long glReceivedTime = System.currentTimeMillis();
            
            // GL Local Replicaton (2ms one way, 4ms RTT)
            scheduler.schedule(() -> {
                // GL local quorum met. Send confirmation to DL Node 3 (Cross-Domain, 50ms)
                scheduler.schedule(() -> {
                    // DL Node 3 receives confirmation from GL.
                    // Now check if Node 3 has achieved its local quorum.
                    // Node 3 local quorum takes 4ms RTT from the time Node 3 received the log.
                    // Client -> GL (50ms) + GL -> DL (50ms) = 100ms when DL gets it.
                    // Local replication RTT is 4ms, so DL has local quorum at 104ms.
                    // The GL confirmation arrives at DL at 50ms + 4ms + 50ms = 104ms.
                    // So both conditions are met at 104ms!
                    // DL Node 3 directly responds to Client (Local, 2ms)
                    scheduler.schedule(() -> {
                        clientFuture.complete(null);
                    }, INTRA_DOMAIN_ONE_WAY_MS, TimeUnit.MILLISECONDS);
                    
                }, CROSS_DOMAIN_ONE_WAY_MS, TimeUnit.MILLISECONDS);
                
            }, INTRA_DOMAIN_ONE_WAY_MS * 2, TimeUnit.MILLISECONDS);

        }, CROSS_DOMAIN_ONE_WAY_MS, TimeUnit.MILLISECONDS);

        return clientFuture;
    }
}
