# CD-Raft (Cross-Domain Raft) — Analysis & Implementation Design
#  https://ieeexplore.ieee.org/document/11571599
# https://arxiv.org/abs/2603.10555v1
This document details the architecture of **CD-Raft**, its latency improvements over classic Raft, a roadmap for implementing a working prototype in Java, and a strategy for demonstrating the results to your professor.

---

## 1. The Core Problem: Cross-Domain RTT Bottleneck

In a geographically distributed (cross-domain) cluster, nodes are located in different data centers or domains (e.g., Domain A in Virginia, Domain B in London). The network round-trip time (RTT) between domains is high (e.g., 100ms), while the RTT within a domain is extremely low (e.g., 2ms).

### Classic Raft Write Flow (2 Cross-Domain RTTs)
If a client in Domain B sends a write to the Leader in Domain A:
1. **Client $\rightarrow$ Leader (Domain A)**: Request travels cross-domain. (½ RTT)
2. **Leader $\rightarrow$ Followers in Domain B & C**: Leader replicates log entries to peers to achieve a quorum. (1 RTT)
3. **Leader $\rightarrow$ Client**: Leader commits the write and responds. (½ RTT)
* **Total Latency**: **2 Cross-Domain RTTs** (approx. 200ms).

---

## 2. The CD-Raft Solution

CD-Raft reduces write operations to **1 Cross-Domain RTT** while maintaining strong consistency and disaster tolerance across at least two domains.

### Key Concepts
1. **Two-Tier Leadership**:
   * **Domain Leader (DL)**: Each domain elects its own local leader using standard Raft rules.
   * **Global Leader (GL)**: One of the Domain Leaders is elected as the Global Leader to coordinate cross-domain consensus.
2. **Fast Return Strategy**:
   * When a client in Domain B writes to the GL in Domain A, the GL forwards the write to the DL in Domain B.
   * Both domains replicate the write locally in parallel (very fast, in-domain).
   * Once the GL's domain reaches a local quorum, it sends a confirmation to the DL in Domain B.
   * As soon as the DL in Domain B achieves local quorum AND receives the GL's confirmation, it **directly responds to the Client** (since they are in the same domain, this last step takes < 2ms).
   * The DL in Domain B then asynchronously sends an acknowledgment back to the GL.
   * **Total Latency**: **1 Cross-Domain RTT** (Client $\rightarrow$ GL $\rightarrow$ DL-B $\rightarrow$ Client).

---

## 3. Implementation Plan: Can We Build It?

Yes, it is highly implementable by extending your existing Java Raft codebase. Here is how we structure the prototype:

### Step 1: Define Domain Topologies
We assign each node to a specific domain in the configuration:
```properties
# cluster.properties
# Format: node_id=domain_id:host:port
1=A:localhost:8001
2=A:localhost:8002
3=B:localhost:8003
4=B:localhost:8004
```

### Step 2: Refactor the gRPC Service & Core State Machine
We introduce two state machines or role hierarchies:
* **Local Consensus**: Nodes 1 & 2 run local Raft to elect the Domain Leader for Domain A. Nodes 3 & 4 do the same for Domain B.
* **Global Consensus**: Only the elected DLs (e.g. Node 1 and Node 3) run the global consensus algorithm to elect the Global Leader.

### Step 3: Fast Return Logic
We modify [`KVServiceImpl.java`](file:///C:/Users/suhai/Desktop/cd-raft/raft-rpc/src/main/java/raftkv/rpc/KVServiceImpl.java) and the routing logic:
1. When a client writes to the GL, the GL appends it to its log and sends an `AppendEntries` RPC to all Domain Leaders.
2. The GL waits for its local domain followers to write the log (local quorum).
3. The GL sends a `QuorumReached` signal to the target Domain Leader (where the client request originated).
4. The target Domain Leader replicates the log inside its domain. Once its local domain reaches a quorum AND it receives the `QuorumReached` signal, it completes the client's `CompletableFuture` directly, sending the response.

---

## 4. How to Demonstrate It to Your Professor

To make this a stellar final-year project, you must prove the latency reduction. You can do this by running a **simulation benchmark** on your machine:

### 1. The Latency Injection Interceptor (The Magic Ingredient)
Instead of using physical cloud machines, we can write a gRPC client/server **Interceptor** (a middleware) that injects artificial network delay based on the domain IDs:
* If Node 1 (Domain A) talks to Node 2 (Domain A) $\rightarrow$ Delay = **0ms** (same domain).
* If Node 1 (Domain A) talks to Node 3 (Domain B) $\rightarrow$ Delay = **50ms** (cross-domain, representing a 100ms RTT).

### 2. The Benchmark Runner
Create a test script that runs a series of writes:
1. **Benchmark 1 (Standard Raft)**: Client in Domain B sends 100 writes to Node 1 (Leader, Domain A). The client measures the average response latency. (Expected: ~200ms).
2. **Benchmark 2 (CD-Raft)**: Same client sends 100 writes to Node 1 (Global Leader, Domain A) with Fast Return enabled. The client measures the average response latency. (Expected: ~100ms).

### 3. Visualizing the Results
Have your Java program output a simple CSV file with the latencies and generate a bar chart or print the results cleanly in the console:
```text
===================================================
BENCHMARK RESULTS (100 Writes, 100ms Cross-Domain RTT)
===================================================
Standard Raft Write Latency: 204.3 ms (2.04 RTTs)
CD-Raft Write Latency:       102.7 ms (1.02 RTTs)
---------------------------------------------------
LATENCY REDUCTION:          49.7%
===================================================
```

Showing this live run and proof to your professor turns an ordinary Raft project into a publishable research prototype!
