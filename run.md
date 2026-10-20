### How to Run and Demonstrate

You have two easy ways to run the project:

#### Option A: Run via Docker Compose (Recommended for Demo)

1. In your project root terminal, run:
   docker compose -f docker/docker-compose.yml up --build

2. Open your browser at:
   http://localhost:9001
   (The compiled React dashboard will load immediately directly from the Java server).
   ──────
#### Option B: Run Standalone React Frontend (With Hot-Reloading)

If you want to modify the React UI live:

1. Start the Java cluster (or run docker compose up).
2. In a separate terminal, run:
   cd raft-dashboard-ui
   npm run dev

3. Open:
   http://localhost:3000
   (All API calls are automatically proxied to http://localhost:9001).
   ──────
### How to Showcase this to Your Professor

1. Explain the Motivation:
   • Show the two domains (Virginia vs London). Explain that in multi-datacenter deployments, WAN round-trips take ~100ms whereas local LAN
   round-trips take ~4ms.
2. Point to the Dual-Leader Topology:
   • Highlight GL 1 (Global Leader) in Virginia and DL 3 (Domain Leader) in London.
3. Execute the Race:
   • Click Run Consensus Race.
   • Show your professor the live packet racing: CD-Raft completes early in 106ms because DL 3 returns early via Fast Return, while Classic
   Raft is still waiting for replication across the ocean (200ms).
4. Demonstrate Disaster Tolerance:
   • Click Simulate Crash on GL 1. Show the term incrementing and a new election taking place automatically in the cluster cards below.

