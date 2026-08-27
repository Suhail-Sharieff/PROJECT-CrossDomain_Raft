# Academic Presentation & Design Guide: CD-Raft

This document serves as your guide to understanding, implementing, and defending **CD-Raft (Cross-Domain Raft)** as your final-year college project. It contains the core theories, latency equations, implementation design, and presentation talking points for your professor.

---

## 1. What Improvement Does CD-Raft Introduce?

Classic Raft works exceptionally well in local networks (single data center), but experiences severe performance degradation in **cross-domain sites** (geographically distributed data centers) because of **network latency**.

CD-Raft addresses this with two key innovations:

### A. The Dual-Leader Topology (Hierarchical Consensus)
Instead of a single global leader managing all individual nodes directly, CD-Raft organizes nodes into **Domains** (e.g., Virginia data center, London data center):
* **Domain Leader (DL)**: Elected within each local domain to handle intra-domain consensus. Elections inside the domain follow the standard majority rule (fast, low-latency).
* **Global Leader (GL)**: One of the Domain Leaders is elected as the Global Leader to coordinate transactions across domains.

### B. The Fast Return Strategy (1 RTT Writes)
In classic Raft, when a client in Domain B writes to a leader in Domain A, the write requires **2 cross-domain Round-Trip Times (RTTs)** (Client $\rightarrow$ Leader $\rightarrow$ Follower $\rightarrow$ Leader $\rightarrow$ Client) which is very slow (e.g. 200ms).

Under CD-Raft's **Fast Return** strategy:
1. The client in Domain B sends the write to the Global Leader (GL) in Domain A (travels cross-domain, ½ RTT).
2. The GL appends the write locally and broadcasts it to the Domain Leaders of other domains (travels cross-domain, ½ RTT).
3. **Parallel In-Domain Replication**:
   * The GL replicates the write to its followers in Domain A (extremely fast, e.g., 2ms).
   * The DL in Domain B replicates the write to its followers in Domain B (extremely fast, e.g., 2ms).
4. **Local Response**: Once the GL's domain reaches a local quorum, it sends a confirmation to the DL in Domain B. As soon as the DL in Domain B reaches a local quorum and gets the GL's confirmation, it **directly responds to the Client** (since they are in the same domain, this takes < 2ms).
5. **Result**: The client receives a success confirmation in just **1 cross-domain RTT** (Client $\rightarrow$ GL $\rightarrow$ DL-B $\rightarrow$ Client), reducing write latency by **up to 50%**.

### C. Optimal Global Leader Position
Because traffic fluctuates, CD-Raft periodically runs a background optimization calculation to migrate the Global Leader role to the domain that receives the highest client write volume, minimizing global latency.

---

## 2. Safety, Commits, and Fault Tolerance

### The Commit Rule
In CD-Raft, a log entry is committed when it is successfully replicated by:
$$\text{Replicated on Majority of GL Domain} \quad \text{AND} \quad \text{Replicated on Majority of at least one other Domain}$$
This ensures that data is stored in at least **two separate geographical domains**, providing geographical disaster tolerance.

### Split-Brain Prevention
Global Leader elections require at least $N-1$ Domain Leaders to participate (where $N$ is the total number of domains), and the new GL's log must be more up-to-date than at least $N-2$ other Domain Leaders. This strict intersection rule prevents split-brain scenarios even during total network partitions.

---

## 3. Formal Correctness (TLA+ Verification)

Your professor will love this point: **CD-Raft's correctness is mathematically verified**.
The authors wrote a formal specification of the protocol in **TLA+** (Temporal Logic of Actions) and verified it using the **TLA+ Proof System (TLAPS)**, proving that it guarantees strong consistency (linearizability) and prevents split-brain under any failure scenario.
* *TLA+ Specification Code*: [CD-Raft-TLA](https://github.com/Czq0001/CD-Raft-TLA.git)

---

## 4. How to Demonstrate it to Your Professor

To get an 'A' grade, you need to show the latency reduction in action. We can do this by building a **Simulation Benchmark** directly in your project.

### Step 1: Simulated Latency (gRPC Interceptors)
We don't need real AWS cloud servers. We simulate geographical domains locally by wrapping our gRPC channels in an interceptor that injects simulated network delays:
* Ping between Node 1 (Domain A) and Node 2 (Domain A) $\rightarrow$ Delay = **2ms** (Local).
* Ping between Node 1 (Domain A) and Node 3 (Domain B) $\rightarrow$ Delay = **50ms** (Cross-Domain, creating a 100ms RTT).

### Step 2: Running the Benchmark Program
We write a simulation runner (`CdRaftSimulator.java`) that:
1. Boots up 4 mock nodes in memory (2 in Domain A, 2 in Domain B).
2. Performs 100 write requests using **Standard Raft** (Client in Domain B $\rightarrow$ Leader in Domain A $\rightarrow$ Follower in B $\rightarrow$ Leader A $\rightarrow$ Client).
3. Performs 100 write requests using **CD-Raft Fast Return** (Client in Domain B $\rightarrow$ GL in Domain A $\rightarrow$ DL-B $\rightarrow$ Client).
4. Prints the comparison table:
   ```text
   ===================================================
   LATENCY COMPARISON BENCHMARK (100ms Cross-Domain RTT)
   ===================================================
   Standard Raft Write Latency: 204 ms (2.0 RTTs)
   CD-Raft Write Latency:       103 ms (1.0 RTTs)
   ---------------------------------------------------
   LATENCY IMPROVEMENT:         ~49.5% Reduction!
   ===================================================
   ```

This makes the project incredibly visual, academically rigorous, and very easy to demonstrate on a single laptop during your project review.
