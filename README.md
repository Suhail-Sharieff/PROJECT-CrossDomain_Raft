<img src="logo.png" alt="CD-Raft logo" width="160">

# Cross-Domain Raft (CD-Raft)

An educational Java prototype of **CD-Raft**, a Raft variant designed to cut consensus latency across geographically separated data centers (cross-domain sites). This repository implements a working **classic Raft** replicated key-value cluster, a **6-node Docker demo**, a **live React dashboard**, and a **latency simulator** that visualizes how CD-Raft’s Fast Return strategy reduces write latency from ~2 WAN round-trips to ~1.

The protocol ideas follow:

- Wang, Cheng, Dong, Xu — *CD-Raft: Reducing the Latency of Distributed Consensus in Cross-Domain Sites* ([IEEE](https://ieeexplore.ieee.org/document/11571599), [arXiv:2603.10555](https://arxiv.org/abs/2603.10555v1))
- Ongaro & Ousterhout — *In Search of an Understandable Consensus Algorithm* (Raft, USENIX ATC 2014)

---

## What this project does

Classic Raft elects one leader for the whole cluster. Every write must:

1. Reach the leader.
2. Be replicated until a **majority of all nodes** acknowledge it.
3. Return to the client from that same leader.

When the client and the leader sit in different regions (for example London vs Virginia), that path typically costs **two wide-area RTTs** (~200 ms if WAN RTT is 100 ms). Intra-datacenter RTT is only a few milliseconds, so WAN hops dominate.

**CD-Raft** organizes nodes into **domains** (sites / data centers) and uses two leader roles:

| Role | Scope | Job |
| --- | --- | --- |
| **Domain Leader (DL)** | One per domain | Local Raft-style majority inside the site |
| **Global Leader (GL)** | One DL chosen among DLs | Coordinates cross-domain consensus |

**Fast Return** lets the Domain Leader in the *client’s* domain reply locally once:

- a majority of the **GL’s domain** holds the new entry, **and**
- a majority of **at least one other domain** (typically the client’s domain) holds it.

The client no longer waits for the GL’s reply to travel back across the ocean. Write latency drops to about **one WAN RTT** (~100 ms in the demo model), while data is still durable in **two geographic sites**.

This repo is a **teaching and demo system**, not a production CD-Raft engine. The Java cluster runs **standard Raft** (one leader, cluster-wide majority). The dashboard and `CdRaftSimulator` **model** dual-leader topology, Fast Return timing, and domain quorums so you can explain and compare the two protocols.

---

## Why it matters

Leader-based consensus (Raft, Multi-Paxos) is the backbone of systems such as etcd, CockroachDB, TiDB, YugabyteDB, and many control planes. Those systems work well inside one datacenter. Multi-region deployments still pay WAN RTT on every strongly consistent write.

The paper reports (YCSB-style evaluation vs classic Raft):

- about **32.9%** lower average latency on mixed read/write traces
- about **49.2%** lower 99th-percentile tail latency
- about **39.8%** lower average latency on write-only workloads

The authors formally specified CD-Raft in **TLA+** / TLAPS to argue linearizability and split-brain safety. That specification is external ([CD-Raft-TLA](https://github.com/Czq0001/CD-Raft-TLA.git)); this repo does not include the TLA+ proofs.

---

## Applications and use cases

CD-Raft (and this prototype as a lab for it) is aimed at **strongly consistent state that must survive a site failure** and still stay usable from more than one region.

**Natural applications**

- Multi-region **configuration / metadata** stores (feature flags, service discovery, cluster membership)
- **Control planes** that must not split brain across continents
- **Geo-replicated KV** or session stores where linearizability matters more than eventual consistency
- **Distributed lock / lease** services spanning two or more DCs
- **AI / HPC coordination** across sites: job queues, checkpoint indexes, parameter-server metadata (the paper’s motivating load)
- Teaching **consensus, WAN latency, and disaster tolerance** without needing real multi-cloud VMs

**Demo use cases in this repo**

1. **Consensus race** — same write visualized as classic Raft (~200 ms) vs CD-Raft Fast Return (~106 ms) with a 100 ms WAN RTT model.
2. **Crash / revive** — take a node offline from the dashboard; watch elections, term changes, and quorum bars.
3. **Domain vs global quorum** — Domain A needs 2/3, Domain B needs 2/3 for the CD-Raft commit story; classic Raft needs 4/6 overall.
4. **CLI KV** — `put` / `get` against the live cluster with leader redirect.
5. **Timing benchmark** — `CdRaftSimulator` compares 10 sequential writes under the two delay models.

---

## Repository layout

Gradle multi-module project (`raft-kv-java`), Java 21, plus a Vite/React UI.

```
PROJECT-CrossDomain_Raft/
├── raft-core/            Consensus: election, log, commit, disk persistence
├── raft-rpc/             gRPC + Protobuf (RaftService, KVService)
├── raft-server/          Node process, HTTP dashboard, crash simulation
├── raft-client/          CLI client + CD-Raft latency simulator
├── raft-dashboard-ui/    React + Tailwind live cluster UI
├── docker/               Dockerfile + 6-node docker-compose cluster
├── CD-RAFT-DESIGN.md     Paper → implementation design notes
├── CD-RAFT-GUIDE.md      Academic talking points
└── run.md                Short demo script
```

| Module | Responsibility |
| --- | --- |
| **raft-core** | `RaftNode` state machine (FOLLOWER / CANDIDATE / LEADER), randomized election timeout, heartbeats, log matching, majority commit of **current-term** entries, `PersistentState` (`metadata.state` + `raft.log`) |
| **raft-rpc** | `raft.proto`: `RequestVote`, `AppendEntries`, `Put`, `Get`, `GetState` |
| **raft-server** | Loads `NODE_ID`, `PEERS`, timeouts from the environment; gRPC on `NODE_PORT`; HTTP dashboard on `NODE_PORT + 1000`; in-memory KV applied from committed log; simulated crash (`offline`) |
| **raft-client** | Blocking gRPC CLI with leader redirect; `CdRaftSimulator` delay model |
| **raft-dashboard-ui** | Polls `/api/state`, animates packet traces, crash buttons, domain quorum health |

---

## System design

### 1. Classic Raft path (what the Java nodes actually run)

```
Client  --Put-->  Leader
                      |  append to log, persist
                      |  AppendEntries to all peers
                      v
              majority matchIndex  -->  commitIndex
                      |
                      v
              apply to KV map  -->  reply to client
```

Safety properties implemented in `RaftNode`:

- Reject votes / appends with a stale term; step down on a higher term.
- Vote at most once per term, and only if the candidate’s log is at least as up to date.
- Follower rejects appends if `prevLogIndex` / `prevLogTerm` do not match; leader backs off `nextIndex`.
- Leader advances `commitIndex` only for entries from the **current term** once a majority has them (Raft’s Figure 8 rule).
- Term and `votedFor` are written through a temp-file + atomic rename; log entries are appended (Base64 commands) and rewritten on truncate.

Writes are linearized through the leader. `Get` currently reads the **local** map on whichever node you hit (see Limitations).

### 2. CD-Raft path (protocol this project teaches)

```
Client (Domain B)  --½ WAN RTT-->  Global Leader (Domain A)
                                        |  local majority in A (LAN)
                                        |  forward to Domain Leader B (WAN)
                                        v
                               Domain B local majority (LAN)
                                        |
                          Fast Return --LAN--> Client
                          ACK (async) ----WAN----> GL (commitIndex)
```

**Commit rule (paper):** an entry is committed when a majority of the GL domain **and** a majority of at least one other domain have it. That is geographic disaster tolerance: one site can burn down and the other still holds a majority copy.

**GL election (paper):** with `N` domains, at least `N-1` Domain Leaders participate, and the new GL’s log must be newer than at least `N-2` other DLs. That intersection is meant to prevent split brain under partitions. **In-domain** DL election stays ordinary majority.

**Optimal Global Leader Position (paper, not implemented here):** periodically place the GL in the domain that minimizes total cross-domain latency given request mix `W_i`, `R_i` and pairwise latencies `l_ij`.

### 3. Demo topology

Docker Compose starts **6 nodes**, presented as two sites:

| Domain | Nodes | Demo location |
| --- | --- | --- |
| A | 1, 2, 3 | Virginia |
| B | 4, 5, 6 | London |

Node 1 uses a **shorter election timeout** so it usually becomes leader first (useful for a stable demo). Other nodes use 400–750 ms timeouts. Heartbeats are 50 ms.

Each container:

- gRPC: port `8001` inside the network (host `8001`–`8006`)
- HTTP dashboard: `9001` inside (`NODE_PORT + 1000`); host `9001`–`9006`

Open **http://localhost:9001** for the UI served by node 1.

### 4. Dashboard and “consensus race”

The UI is a control plane over the **real** cluster, plus a **canned WAN animation**:

- `GET /api/state` — aggregates local + peer `GetState` (role, term, leader, log, KV, online flag).
- `POST /api/simulate-write?type=cd` — submits a **real** `Put` through Raft, then returns a **fixed 106 ms Fast Return trace**.
- `POST /api/simulate-write?type=standard` — does **not** write again (avoids two racing puts); succeeds if a live leader exists, returns a **fixed 200 ms classic-Raft trace**.
- `POST /api/toggle-node?id=` — crash/revive via gRPC interceptor (`UNAVAILABLE`) so the node drops out of quorum.

Animation uses a 16× slowdown so 100 ms WAN hops are visible. Domain Leader labels in the CD-Raft SVG are **derived in the UI** from node ids and the live Raft leader; the engine itself has no DL/GL types.

### 5. Latency model used in the simulator

`raft-client` `CdRaftSimulator` (and the dashboard traces) assume:

| Hop | One-way | RTT |
| --- | --- | --- |
| Intra-domain LAN | 2 ms | 4 ms |
| Cross-domain WAN | 50 ms | 100 ms |

Approximate client-visible write times (client in Domain B, leader/GL in Domain A):

- **Classic Raft:** client→leader (50) + wait for a remote ACK (100) + leader→client (50) ≈ **200 ms** (2 WAN RTTs).
- **CD-Raft Fast Return:** client→GL (50) + GL→DL-B (50) + local reply (~2–6 ms) ≈ **106 ms** (~1 WAN RTT).

These numbers are **injected delays**, not measured transatlantic pings. Compose nodes all run on one machine with no WAN interceptor on gRPC.

### 6. Request / process architecture

```
                 ┌─────────────────────────────────────┐
   Browser  HTTP │ DashboardServer  (port +1000)        │
                 │  /api/state  /api/put  /simulate...  │
                 └──────────────┬──────────────────────┘
                                │
                 ┌──────────────▼──────────────────────┐
   CLI / peers   │ gRPC  KVService + RaftService        │
                 └──────────────┬──────────────────────┘
                                │
                         RaftNode (core)
                                │
                    PersistentState + in-memory KV
```

---

## How to run

**Requirements:** Docker (recommended), or JDK 21 + Gradle wrapper. For UI hot reload: Node.js.

### Option A — Docker Compose (demo)

From the repo root:

```bash
docker compose -f docker/docker-compose.yml up --build
```

Then open http://localhost:9001

Suggested walkthrough:

1. Point out Domain A (Virginia) vs Domain B (London) and WAN RTT 100 ms vs LAN ~4 ms.
2. Identify the live leader (usually Node 1) as GL in the CD-Raft view, and a Domain B node as DL.
3. Click **Run 6-Node Consensus Race** — CD-Raft animation finishes ~106 ms; classic Raft ~200 ms. The CD path is the real cluster write.
4. **Simulate Crash** on the leader; wait for a new term/leader; retry a write.

### Option B — React UI with hot reload

Start the Java cluster (Compose or Gradle). In another terminal:

```bash
cd raft-dashboard-ui
npm install
npm run dev
```

Open http://localhost:3000 (Vite proxies `/api` to `http://localhost:9001`).

### Option C — Gradle (local processes)

Set env vars per node (`NODE_ID`, `NODE_PORT`, `PEERS`, timeouts, `DATA_DIR`) and run `:raft-server`. Example shape for node 1:

```text
NODE_ID=1
NODE_PORT=8001
PEERS=2=localhost:8002,3=localhost:8003,...
```

CLI:

```bash
./gradlew :raft-client:run --args="1=localhost:8001,2=localhost:8002,3=localhost:8003 put mykey myvalue"
./gradlew :raft-client:run --args="1=localhost:8001,2=localhost:8002,3=localhost:8003 get mykey"
```

Latency toy benchmark (no cluster required):

```bash
./gradlew :raft-client:runSimulator
```

Embedded UI: build `raft-dashboard-ui` (`npm run build`) and copy `dist/` into `raft-server/src/main/resources/dist` so the Java HTTP server can serve it. A prebuilt `index.html` is already checked in under resources.

---

## What is implemented vs what is visualized

| Capability | In this repo |
| --- | --- |
| Classic Raft election, log replication, majority commit | **Yes** (`raft-core`) |
| Durable term / vote / log | **Yes** (file-backed `PersistentState`) |
| Replicated KV `Put` applied on commit | **Yes** |
| gRPC cluster + leader redirect CLI | **Yes** |
| 6-node Docker demo + crash toggle | **Yes** |
| Live dashboard (roles, logs, KV, quorum bars) | **Yes** |
| Packet animation + 106 ms vs 200 ms race | **Yes** (scripted traces + real CD-path write) |
| Timing simulator for Fast Return | **Yes** (`CdRaftSimulator`) |
| Real Domain Leader / Global Leader state machines | **No** — UI labels only |
| Fast Return on the gRPC write path | **No** — client still waits for the Raft leader |
| Two-domain commit rule in `RaftNode` | **No** — majority of **all 6** nodes |
| WAN delay gRPC interceptor | **No** |
| Optimal GL placement | **No** |
| Linearizable `Get` (read index / lease) | **No** — local map read |
| TLA+ model / YCSB | **Not in this repo** (paper / upstream) |

Be explicit about that split in a defense or write-up: the **cluster proves you can build Raft**; the **simulator and dashboard teach CD-Raft’s latency argument**.

---

## Limitations

**Protocol**

- One flat cluster, not a hierarchy of per-domain Raft groups plus a global group.
- Commit does not require “majority in two domains”; a 4/6 classic majority can theoretically sit entirely in one domain in a different layout (here 3+3 makes that harder in practice, but the code does not enforce it).
- No `QuorumReached` RPC, no client-domain proxy reply.
- Reads are not linearizable. A follower `Get` can return stale or uncommitted-applied inconsistency depending on who you ask.
- Heartbeats and elections share the same `AppendEntries` path with **no simulated WAN**, so live write latency on Docker is LAN-like, not 100 ms.

**Engineering**

- KV is an in-memory `ConcurrentHashMap`. The log and term/`votedFor` survive restart, but **`commitIndex` is not persisted**, so committed entries are not replayed into the map until a leader commits again. Restart is not a complete recovery story.
- Log file append is not `fsync`’d; crash safety is weaker than production Raft.
- `Put` blocks the gRPC handler thread on a `CompletableFuture.get` (5 s timeout).
- Dashboard JSON is hand-stringified (escaping is basic).
- Tests are thin (`RaftNodeTest` covers single-node election).
- No TLS, auth, membership change (joint consensus), log compaction, or snapshots.
- Node 1’s shorter timeout **biases** leadership — good for demos, not a fair election.

**Evaluation**

- Race timings are **constants** (106 vs 200), not measured RPC times.
- Simulator uses 10 sequential ops and scheduled sleeps; it does not run two protocol implementations.
- No YCSB, no multi-region cloud experiment.

---

## What could be improved

Roughly in order of impact for a stronger CD-Raft prototype:

1. **Domain membership in config** — `nodeId=domain:host:port` and pass domain id into the node.
2. **Two-level consensus** — intra-domain Raft elects DLs; DLs run a second Raft (or the paper’s GL election rules) for the global log.
3. **Fast Return RPCs** — GL signals local-domain quorum; client-domain DL completes the client future when both conditions hold; async ACK to GL.
4. **Enforce the two-domain commit rule** in the GL so disaster tolerance is real, not only drawn on the dashboard.
5. **gRPC interceptors** injecting 2 ms vs 50 ms delay from domain pairs, then measure real `Put` latency instead of canned traces.
6. **Linearizable reads** — Raft read index or leader lease; optional follower reads only after apply catches commit.
7. **Optimal GL placement** — count `W_i`/`R_i`, measure `l_ij`, migrate GL as in the paper’s latency model.
8. **Persistence** — `fsync`, snapshots, log compaction; rebuild KV from snapshot + tail on start.
9. **Fault-injection tests** — partitions, message loss matching the paper’s Figure 6/7 cases; more than one unit test.
10. **Unbiased elections** and configurable WAN RTT in the UI.
11. **Membership changes**, TLS, and a proper metrics/CSV exporter for the race.

---

## Safety and fault-tolerance story (paper)

Useful when explaining CD-Raft even though the engine is still classic Raft:

- **Node faults:** service can continue if the GL domain still has a local majority **and** another domain has a majority; if the GL domain itself loses majority, a new GL is elected among remaining DLs (stricter than vanilla Raft).
- **Message loss:** lost client request → timeout/retry; lost GL→DL batch → treat like a dead domain and use another domain if possible; lost Fast Return confirmation → fall back to GL answering the client (classic 2-RTT path); lost DL→GL ACK → client may already have succeeded via Fast Return, but GL delays `commitIndex` until another domain ACKs (reads wait on that commit).

---

## Stack

- Java 21, Gradle, SLF4J / Logback, JUnit 5
- gRPC 1.62 + Protobuf 3.25
- React 18, Vite 5, Tailwind 3
- Docker multi-stage: `gradle:8.5-jdk21` build, `eclipse-temurin:21-jre` runtime

---

## References

1. Y. Wang, Z. Cheng, Y. Dong, Z. Xu, *CD-Raft: Reducing the Latency of Distributed Consensus in Cross-Domain Sites*, 2026. [arXiv](https://arxiv.org/abs/2603.10555v1)
2. D. Ongaro, J. Ousterhout, *In Search of an Understandable Consensus Algorithm*, USENIX ATC 2014.
3. L. Lamport, *Paxos Made Simple*; *The Part-Time Parliament*.
4. H. Howard, D. Malkhi, A. Spiegelman, *Flexible Paxos: Quorum Intersection Revisited*.
5. Cooper et al., *Benchmarking Cloud Serving Systems with YCSB*, SoCC 2010.

Related geo-consensus work (for “what else exists”): Mencius, WPaxos, GeoLM, PigPaxos, EPaxos.

---

## License / status

Academic prototype. Not production-ready. Treat WAN numbers, Fast Return, and dual-leader labels as **explanatory models** unless you implement the items in *What could be improved*.
