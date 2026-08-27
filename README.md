 .\gradlew :raft-client:runSimulator
Starting a Gradle Daemon (subsequent builds will be faster)

> Task :raft-client:runSimulator
=========================================================
          CD-RAFT LATENCY SIMULATION BENCHMARK
=========================================================
Topology: 4 Nodes across 2 Domains
  - Domain A: Node 1 (GL), Node 2 (Follower)
  - Domain B: Node 3 (DL), Node 4 (Follower)
  - Client Location: Domain B
  - Latency settings: Cross-Domain RTT = 100ms, Local RTT = 4ms        
=========================================================

Running Standard Raft write benchmark (10 operations)...
..........
Standard Raft average write latency: 234.40 ms

Running CD-Raft (Fast Return) write benchmark (10 operations)...
..........===-> 93% EXECUTING [10s]
CD-Raft average write latency: 155.30 ms

=========================================================
                    BENCHMARK SUMMARY
=========================================================
Standard Raft Latency: 234.40 ms (approx. 2.0 RTTs)
CD-Raft Latency:       155.30 ms (approx. 1.0 RTTs)
LATENCY REDUCTION:     33.75%
CD-Raft Latency:       155.30 ms (approx. 1.0 RTTs)
LATENCY REDUCTION:     33.75%
=========================================================

BUILD SUCCESSFUL in 16s
10 actionable tasks: 1 executed, 9 up-to-date      
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.0.0/userguide/configuration_cache_enabling.html