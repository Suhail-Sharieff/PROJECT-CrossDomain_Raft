# Raft Distributed Systems

This guide maps theoretical distributed systems concepts to the implementation details of this repository (`raft-kv-java`), preparing you to discuss and defend your design in system design or backend engineering interviews.

---

## 1. Codebase Class & Method Mapping

When explaining the system to an interviewer, use this mapping to point to your exact implementation:

| Concept | Class / Interface | Key Method |
|---|---|---|
| **Consensus Engine** | [`RaftNode.java`](file:///C:/Users/suhai/Desktop/kv-claude/raft-core/src/main/java/raftkv/core/RaftNode.java) | Main orchestrator with state lock (`ReentrantLock`) |
| **State Machine Log** | [`RaftLog.java`](file:///C:/Users/suhai/Desktop/kv-claude/raft-core/src/main/java/raftkv/core/RaftLog.java) | `append()`, `truncate()`, `hasEntryAt()` |
| **Durability Layer** | [`PersistentState.java`](file:///C:/Users/suhai/Desktop/kv-claude/raft-core/src/main/java/raftkv/core/PersistentState.java) | `saveMetadata()`, `appendEntry()`, `truncateLog()` |
| **Election Timer** | [`ElectionTimer.java`](file:///C:/Users/suhai/Desktop/kv-claude/raft-core/src/main/java/raftkv/core/ElectionTimer.java) | Randomized scheduling via `ScheduledExecutorService` |
| **gRPC Server Wiring** | [`Main.java`](file:///C:/Users/suhai/Desktop/kv-claude/raft-server/src/main/java/raftkv/server/Main.java) | Binds gRPC servers and maps core listeners to stubs |
| **Client Services** | [`KVServiceImpl.java`](file:///C:/Users/suhai/Desktop/kv-claude/raft-rpc/src/main/java/raftkv/rpc/KVServiceImpl.java) | `put()` (blocks on future), `get()` (direct local read) |

---

## 2. Core Distributed Systems Q&A

### Q1: Why does Raft split consensus into Leader Election, Log Replication, and Safety?
**Answer:** Consensus is a hard problem. Raft decomposes it into three subproblems to make it understandable and implementable:
1. **Leader Election**: Ensures exactly one leader is active per term. If the leader fails, a new election is triggered.
2. **Log Replication**: The leader accepts commands from clients, appends them to its log, and replicates them to followers, forcing followers to match its own log.
3. **Safety**: Guarantees that if any node has applied a log entry at a specific index to its state machine, no other node will ever apply a different log entry for that index.

---

### Q2: What is the difference between a "committed" and an "applied" log entry?
**Answer:**
- **Committed**: A log entry is committed once it is successfully replicated to a **quorum (majority)** of nodes in the cluster *by the leader of the current term*. Commitment is a consensus guarantee; once committed, the entry cannot be overwritten or lost.
- **Applied**: An entry is applied when the local state machine (in our case, the `ConcurrentHashMap` in `Main.java`) actually executes the command (e.g. `PUT key value`). A node only applies an entry after it learns that the entry has been committed.
- **Why the distinction?** It allows the consensus layer to agree on the order and content of log entries (committed) before letting individual nodes modify their local database state (applied).

---

### Q3: Why are election timeouts randomized (e.g. 300–600ms)?
**Answer:**
If all nodes used a fixed election timeout, they would timeout simultaneously, transition to Candidate, vote for themselves, and split the votes (no candidate gets a majority). The election would loop indefinitely with no leader elected.
Randomization breaks this symmetry. The node with the shortest timeout wakes up first, increments the term, votes for itself, and requests votes from peers before they timeout. This ensures a leader is elected quickly (usually in one round).
- *Implemented in:* [`ElectionTimer.java`](file:///C:/Users/suhai/Desktop/kv-claude/raft-core/src/main/java/raftkv/core/ElectionTimer.java#L23-L33)

---

### Q4: Explain the classic Raft "Figure 8" safety issue and how you solved it.
**Answer:**
In Raft, a leader cannot commit log entries from a *previous* term by counting replicas (even if they are on a majority of nodes). Doing so violates safety if a candidate with an older log term but higher index is subsequently elected and overwrites those entries.
**Solution:**
A leader can only commit an entry by counting replicas if the entry was created in the leader's **current term**. Once a current-term entry is committed by majority replication, all prior entries in the log are indirectly committed by the Log Matching Property.
- *Implemented in:* [`RaftNode.java`](file:///C:/Users/suhai/Desktop/kv-claude/raft-core/src/main/java/raftkv/core/RaftNode.java#L330-L346) where the leader checks `log.getTermAt(N) == currentTerm` before advancing `commitIndex`.

---

### Q5: What happens during a network partition (Split-Brain avoidance)?
**Answer:**
Suppose a 5-node cluster (Nodes 1, 2, 3, 4, 5) is partitioned into two parts: `{1, 2}` and `{3, 4, 5}`. Node 1 was the leader.
1. **On the minority side `{1, 2}`**: Node 1 remains leader initially but cannot commit any writes because a quorum is 3. Any client writes to Node 1 will timeout or fail.
2. **On the majority side `{3, 4, 5}`**: The nodes will timeout, start an election, and successfully elect a new leader (e.g., Node 3) because they can form a quorum of 3.
3. **When the partition heals**: Node 1 sends a heartbeat or receives one from Node 3. Node 1 sees that Node 3 is in a higher term, immediately steps down to a Follower, and syncs its log to Node 3's log, discarding any uncommitted writes.
Raft prevents split-brain because a quorum is required to elect a leader and commit logs, and a quorum can only exist on one side of a partition.

---

### Q6: Why does persistence to disk matter for correctness, not just durability?
**Answer:**
If a node crashes and restarts, it must remember its `currentTerm` and `votedFor` state.
Suppose Node A votes for Node B in term 2, then crashes. If Node A recovers in-memory-only, it resets `votedFor = -1` in term 2. If Candidate C requests a vote in term 2, Node A might grant it. C and B could both achieve majority and become leader in the same term, violating the **Election Safety** guarantee (at most one leader per term).
Similarly, committed logs must survive restarts so they are not lost, violating the **Leader Completeness** guarantee.
- *Implemented in:* [`PersistentState.java`](file:///C:/Users/suhai/Desktop/kv-claude/raft-core/src/main/java/raftkv/core/PersistentState.java) and called in [`RaftNode.java`'s stepDown, startElection, handleRequestVote, handleAppendEntries, and clientWrite](file:///C:/Users/suhai/Desktop/kv-claude/raft-core/src/main/java/raftkv/core/RaftNode.java).

---

### Q7: Can reads be stale in Raft? How do we solve it?
**Answer:**
Yes, in our default implementation, reads are served from the local state machine immediately without a consensus round. If a node is partitioned from the leader but hasn't detected it yet, it might serve stale data (reads that don't reflect writes committed on the other side of the partition).
**How to achieve Linearizable Reads (Stretch Goal):**
1. **Read Index**: When a read arrives, the leader records its current `commitIndex` as the `readIndex`. Before returning the value, it sends a heartbeat round to a majority of nodes to confirm it is still the leader. Once confirmed, it waits until its state machine has applied at least up to `readIndex`, and then returns the value.
2. **Lease Reads**: The leader holds a lease for a short period (shorter than the election timeout) during which it is guaranteed no other leader can be elected. Reads can be served immediately from the leader's local state machine without network roundtrips during the lease, guaranteeing linearizability.
