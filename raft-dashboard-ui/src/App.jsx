import React, { useState, useEffect } from 'react';
import { Server, Zap, Database, Network, Activity, Wifi, WifiOff, RotateCcw, Play, CheckCircle2, Clock, Globe } from 'lucide-react';

// Coordinates for Standard Raft SVG (width=360, height=250)
const STD_COORDS = {
  "client": { x: 320, y: 125 },
  "node-1": { x: 70, y: 55 },
  "node-2": { x: 70, y: 125 },
  "node-3": { x: 70, y: 195 },
  "node-4": { x: 170, y: 55 },
  "node-5": { x: 170, y: 125 },
  "node-6": { x: 170, y: 195 }
};

// Coordinates for CD-Raft SVG (width=360, height=250)
const CD_COORDS = {
  "client": { x: 325, y: 125 },
  "node-1": { x: 70, y: 55 },
  "node-2": { x: 70, y: 125 },
  "node-3": { x: 70, y: 195 },
  "node-4": { x: 235, y: 55 },
  "node-5": { x: 235, y: 125 },
  "node-6": { x: 235, y: 195 }
};

export default function App() {
  const [nodes, setNodes] = useState([]);
  const [putKey, setPutKey] = useState("region");
  const [putValue, setPutValue] = useState("cross_domain_6node");
  const [running, setRunning] = useState(false);

  // Latency and progress tracking
  const [stdProgress, setStdProgress] = useState(0);
  const [cdProgress, setCdProgress] = useState(0);
  const [stdPacket, setStdPacket] = useState(null);
  const [cdPacket, setCdPacket] = useState(null);
  const [stdActiveLinks, setStdActiveLinks] = useState([]);
  const [cdActiveLinks, setCdActiveLinks] = useState([]);
  const [stdLabel, setStdLabel] = useState("Classic Raft (6 nodes): Ready.");
  const [cdLabel, setCdLabel] = useState("CD-Raft (3+3 nodes): Ready.");

  // Fetch real cluster status from backend
  const fetchState = () => {
    fetch("/api/state")
      .then(res => res.json())
      .then(data => {
        data.sort((a, b) => a.id - b.id);
        setNodes(data);
      })
      .catch(err => console.debug("Cluster fetch waiting...", err));
  };

  useEffect(() => {
    fetchState();
    const interval = setInterval(fetchState, 800);
    return () => clearInterval(interval);
  }, []);

  const handleRace = (e) => {
    e.preventDefault();
    if (running) return;
    setRunning(true);
    setStdProgress(0);
    setCdProgress(0);
    setStdLabel("Client in London dispatching write to Leader (Virginia)...");
    setCdLabel("Client in London dispatching write to Global Leader (Virginia)...");

    Promise.all([
      fetch(`/api/simulate-write?type=standard&key=${encodeURIComponent(putKey)}&value=${encodeURIComponent(putValue)}`, { method: "POST" }).then(r => r.json()),
      fetch(`/api/simulate-write?type=cd&key=${encodeURIComponent(putKey)}&value=${encodeURIComponent(putValue)}`, { method: "POST" }).then(r => r.json())
    ])
    .then(([stdData, cdData]) => {
      startConsensusRace(stdData.steps, cdData.steps);
    })
    .catch(err => {
      alert("Simulation failed. Make sure the 6-node cluster is running.");
      setRunning(false);
    });
  };

  const startConsensusRace = (stdSteps, cdSteps) => {
    const startWallTime = performance.now();
    const SPEED_FACTOR = 16; // 16x slowdown for observable packet physics

    const animate = (time) => {
      let elapsed = (time - startWallTime) / SPEED_FACTOR;

      // 1. Classic Raft (Total: 200ms)
      if (elapsed <= 200) {
        setStdProgress(elapsed);
        let active = [];
        let label = "Classic Raft executing...";
        let activePacket = null;

        stdSteps.forEach(step => {
          if (elapsed >= step.start && elapsed <= step.end) {
            active.push(step);
            label = step.label;
            let from = STD_COORDS[step.from];
            let to = STD_COORDS[step.to];
            if (from && to) {
              let ratio = (elapsed - step.start) / Math.max(1, (step.end - step.start));
              activePacket = {
                x: from.x + ratio * (to.x - from.x),
                y: from.y + ratio * (to.y - from.y)
              };
            }
          }
        });
        setStdPacket(activePacket);
        setStdActiveLinks(active.map(s => `${s.from}->${s.to}`));
        setStdLabel(`[${Math.round(elapsed)}ms] ${label}`);
      } else {
        setStdPacket(null);
        setStdActiveLinks([]);
        setStdProgress(200);
        setStdLabel("Classic Raft committed in 200ms (2.0 Cross-Domain RTTs).");
      }

      // 2. CD-Raft (Total: 106ms)
      if (elapsed <= 106) {
        setCdProgress(elapsed);
        let active = [];
        let label = "CD-Raft executing...";
        let activePacket = null;

        cdSteps.forEach(step => {
          if (elapsed >= step.start && elapsed <= step.end) {
            active.push(step);
            label = step.label;
            let from = CD_COORDS[step.from];
            let to = CD_COORDS[step.to];
            if (from && to) {
              let ratio = (elapsed - step.start) / Math.max(1, (step.end - step.start));
              activePacket = {
                x: from.x + ratio * (to.x - from.x),
                y: from.y + ratio * (to.y - from.y)
              };
            }
          }
        });
        setCdPacket(activePacket);
        setCdActiveLinks(active.map(s => `${s.from}->${s.to}`));
        setCdLabel(`[${Math.round(elapsed)}ms] ${label}`);
      } else {
        setCdPacket(null);
        setCdActiveLinks([]);
        setCdProgress(106);
        setCdLabel("CD-Raft committed in 106ms (1.0 Cross-Domain RTT Fast Return)!");
      }

      if (elapsed < 200) {
        requestAnimationFrame(animate);
      } else {
        setRunning(false);
        fetchState();
      }
    };

    requestAnimationFrame(animate);
  };

  const toggleNode = (nodeId) => {
    fetch(`/api/toggle-node?id=${nodeId}`, { method: "POST" })
      .then(() => fetchState())
      .catch(err => console.error(err));
  };

  const domainANodes = nodes.filter(n => n.id <= 3);
  const domainBNodes = nodes.filter(n => n.id > 3);

  return (
    <div className="min-h-screen p-6 max-w-7xl mx-auto flex flex-col justify-between">
      {/* Header */}
      <header className="flex justify-between items-center mb-6 border-b border-gray-900 pb-4">
        <div>
          <span className="text-yellow-500 text-xs font-semibold uppercase tracking-wider">
            6-Node Two-Tier Architecture Demonstration • 3 Virginia + 3 London
          </span>
          <h1 className="text-2xl font-extrabold tracking-tight text-white mt-0.5 flex items-center gap-2">
            🌐 CD-Raft vs Classic Raft: 6-Node Cross-Domain Race
          </h1>
        </div>
        <div className="flex items-center gap-4 bg-gray-950 border border-gray-900 px-4 py-2 rounded-xl text-xs">
          <div className="flex items-center gap-1.5">
            <span className="h-2 w-2 rounded-full bg-emerald-500 animate-pulse"></span>
            <span className="text-gray-400">6-Node Topology Online</span>
          </div>
          <span className="text-gray-600">|</span>
          <span className="text-red-400 font-mono">WAN RTT: 100ms</span>
          <span className="text-gray-600">|</span>
          <span className="text-emerald-400 font-mono">LAN RTT: 4ms</span>
        </div>
      </header>

      {/* Control Panel & Performance Metrics */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6 mb-6">
        {/* Write Command Dispatcher */}
        <div className="bg-gray-950 border border-gray-900 rounded-2xl p-5 lg:col-span-2 flex flex-col justify-between shadow-xl">
          <div>
            <h2 className="text-xs font-bold text-gray-400 uppercase tracking-widest mb-3.5 flex items-center gap-2">
              <Zap className="h-4 w-4 text-yellow-500" /> Dispatch Concurrent Consensus Write
            </h2>
            <form onSubmit={handleRace} className="grid grid-cols-1 sm:grid-cols-4 gap-4 items-end">
              <div>
                <label className="block text-[9px] text-gray-500 font-bold mb-1.5 uppercase">Key</label>
                <input
                  type="text"
                  value={putKey}
                  onChange={e => setPutKey(e.target.value)}
                  className="w-full bg-gray-900 border border-gray-800 rounded-lg px-3 py-1.5 text-xs text-white focus:outline-none focus:border-yellow-500/50"
                  required
                />
              </div>
              <div>
                <label className="block text-[9px] text-gray-500 font-bold mb-1.5 uppercase">Value</label>
                <input
                  type="text"
                  value={putValue}
                  onChange={e => setPutValue(e.target.value)}
                  className="w-full bg-gray-900 border border-gray-800 rounded-lg px-3 py-1.5 text-xs text-white focus:outline-none focus:border-yellow-500/50"
                  required
                />
              </div>
              <div className="sm:col-span-2">
                <button
                  type="submit"
                  disabled={running}
                  className="w-full bg-yellow-500 hover:bg-yellow-600 disabled:opacity-40 text-slate-950 font-bold text-xs py-2 rounded-lg transition h-[32px] uppercase tracking-wider flex items-center justify-center gap-1.5 shadow-lg shadow-yellow-500/10"
                >
                  <Play className="h-3.5 w-3.5 fill-current" />
                  {running ? "Simulating Network Race..." : "Run 6-Node Consensus Race"}
                </button>
              </div>
            </form>
          </div>
          <p className="text-[11px] text-gray-500 mt-3 font-medium">
            <strong>Key Thesis Proof:</strong> In a 6-node cluster (3 in Domain A, 3 in Domain B), Classic Raft requires <strong>4/6 global votes</strong> (forcing a 2nd WAN RTT). CD-Raft requires only <strong>2/3 local quorum in A</strong> + <strong>2/3 local quorum in B</strong>, allowing DL4 to Fast Return in 1 RTT!
          </p>
        </div>

        {/* Latency Comparison Card */}
        <div className="bg-gray-950 border border-gray-900 rounded-2xl p-5 flex flex-col justify-between shadow-xl">
          <h2 className="text-xs font-bold text-gray-400 uppercase tracking-widest mb-2 flex items-center gap-1.5">
            <Activity className="h-4 w-4 text-yellow-500" /> Latency Benchmark (6 Nodes)
          </h2>
          <div className="space-y-3.5 my-auto">
            <div>
              <div className="flex justify-between text-xs text-gray-400 mb-1 font-mono">
                <span>Classic Raft (4/6 Quorum)</span>
                <span className="font-bold text-red-400">200 ms (2.0 RTTs)</span>
              </div>
              <div className="h-2 w-full bg-gray-900 rounded-full overflow-hidden border border-gray-800">
                <div className="bg-red-500 h-full" style={{ width: "100%" }}></div>
              </div>
            </div>
            <div>
              <div className="flex justify-between text-xs text-gray-400 mb-1 font-mono">
                <span>CD-Raft (2/3 + 2/3 Fast Return)</span>
                <span className="font-bold text-yellow-400">106 ms (1.0 RTT)</span>
              </div>
              <div className="h-2 w-full bg-gray-900 rounded-full overflow-hidden border border-gray-800">
                <div className="bg-yellow-500 h-full animate-pulse" style={{ width: "53%" }}></div>
              </div>
            </div>
          </div>
          <span className="text-[10.5px] text-gray-400 text-center font-medium">
            CD-Raft cuts cross-domain write latency by <strong className="text-yellow-400 font-bold">~47%</strong>.
          </span>
        </div>
      </div>

      {/* Side-by-Side Visual Race Grid (6 Nodes each) */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-6 mb-6">
        {/* Left: Classic Raft 6-node */}
        <div className="bg-gray-950 border border-gray-900 rounded-3xl p-5 flex flex-col justify-between shadow-2xl">
          <div>
            <div className="flex justify-between items-center mb-1">
              <h3 className="text-sm font-extrabold text-white flex items-center gap-1.5">
                🔴 Classic Raft Write Path (Flat 6-Node)
              </h3>
              <span className="text-[10px] bg-red-950/30 text-red-400 border border-red-900/40 px-2 py-0.5 rounded font-mono font-bold">
                2.0 RTTs (200ms) • Quorum: 4/6
              </span>
            </div>
            <p className="text-[11px] text-gray-500 mb-3">
              Virginia Leader N1 has only 3 local nodes (N1, N2, N3). It CANNOT commit locally! It must wait for ACKs from London before replying.
            </p>
          </div>

          {/* SVG Map Classic Raft */}
          <div className="flex justify-center bg-gray-950 rounded-2xl border border-gray-900 p-2 shadow-inner mb-3">
            <svg width="350" height="230" className="bg-slate-950 rounded-xl">
              <rect x="5" y="5" width="340" height="220" fill="none" stroke="#1f2937" strokeWidth="1" strokeDasharray="3 3" />
              <text x="15" y="20" fill="#4b5563" fontSize="8" fontWeight="bold">FLAT 6-NODE TOPOLOGY (NO LOCAL QUORUM)</text>

              {/* Links from N1 to all other 5 nodes */}
              {/* N1 -> Client */}
              <line x1={STD_COORDS.client.x} y1={STD_COORDS.client.y} x2={STD_COORDS["node-1"].x} y2={STD_COORDS["node-1"].y}
                    stroke={stdActiveLinks.includes("client->node-1") || stdActiveLinks.includes("node-1->client") ? "#ef4444" : "#111827"}
                    strokeWidth={stdActiveLinks.includes("client->node-1") || stdActiveLinks.includes("node-1->client") ? "3" : "1.5"} />
              
              {/* N1 -> N2, N3 (In Domain) */}
              <line x1={STD_COORDS["node-1"].x} y1={STD_COORDS["node-1"].y} x2={STD_COORDS["node-2"].x} y2={STD_COORDS["node-2"].y}
                    stroke={stdActiveLinks.includes("node-1->node-2") || stdActiveLinks.includes("node-2->node-1") ? "#34d399" : "#111827"}
                    strokeWidth={stdActiveLinks.includes("node-1->node-2") || stdActiveLinks.includes("node-2->node-1") ? "2" : "1.2"} />
              <line x1={STD_COORDS["node-1"].x} y1={STD_COORDS["node-1"].y} x2={STD_COORDS["node-3"].x} y2={STD_COORDS["node-3"].y}
                    stroke={stdActiveLinks.includes("node-1->node-3") || stdActiveLinks.includes("node-3->node-1") ? "#34d399" : "#111827"}
                    strokeWidth={stdActiveLinks.includes("node-1->node-3") || stdActiveLinks.includes("node-3->node-1") ? "2" : "1.2"} />

              {/* N1 -> N4, N5, N6 (WAN) */}
              <line x1={STD_COORDS["node-1"].x} y1={STD_COORDS["node-1"].y} x2={STD_COORDS["node-4"].x} y2={STD_COORDS["node-4"].y}
                    stroke={stdActiveLinks.includes("node-1->node-4") || stdActiveLinks.includes("node-4->node-1") ? "#ef4444" : "#111827"}
                    strokeWidth={stdActiveLinks.includes("node-1->node-4") || stdActiveLinks.includes("node-4->node-1") ? "3" : "1.5"} />
              <line x1={STD_COORDS["node-1"].x} y1={STD_COORDS["node-1"].y} x2={STD_COORDS["node-5"].x} y2={STD_COORDS["node-5"].y}
                    stroke={stdActiveLinks.includes("node-1->node-5") || stdActiveLinks.includes("node-5->node-1") ? "#ef4444" : "#111827"}
                    strokeWidth={stdActiveLinks.includes("node-1->node-5") || stdActiveLinks.includes("node-5->node-1") ? "2.5" : "1.2"} />
              <line x1={STD_COORDS["node-1"].x} y1={STD_COORDS["node-1"].y} x2={STD_COORDS["node-6"].x} y2={STD_COORDS["node-6"].y}
                    stroke={stdActiveLinks.includes("node-1->node-6") || stdActiveLinks.includes("node-6->node-1") ? "#ef4444" : "#111827"}
                    strokeWidth={stdActiveLinks.includes("node-1->node-6") || stdActiveLinks.includes("node-6->node-1") ? "2.5" : "1.2"} />

              {/* Node Circles */}
              <circle cx={STD_COORDS["node-1"].x} cy={STD_COORDS["node-1"].y} r="16" fill="#1f2937" stroke="#ef4444" strokeWidth="2" />
              <text x={STD_COORDS["node-1"].x} y={STD_COORDS["node-1"].y + 3} fill="#fff" fontSize="8" textAnchor="middle" fontWeight="bold">LDR 1</text>

              <circle cx={STD_COORDS["node-2"].x} cy={STD_COORDS["node-2"].y} r="14" fill="#111827" stroke="#10b981" strokeWidth="1" />
              <text x={STD_COORDS["node-2"].x} y={STD_COORDS["node-2"].y + 3} fill="#9ca3af" fontSize="8" textAnchor="middle">N 2</text>

              <circle cx={STD_COORDS["node-3"].x} cy={STD_COORDS["node-3"].y} r="14" fill="#111827" stroke="#10b981" strokeWidth="1" />
              <text x={STD_COORDS["node-3"].x} y={STD_COORDS["node-3"].y + 3} fill="#9ca3af" fontSize="8" textAnchor="middle">N 3</text>

              <circle cx={STD_COORDS["node-4"].x} cy={STD_COORDS["node-4"].y} r="14" fill="#111827" stroke="#10b981" strokeWidth="1" />
              <text x={STD_COORDS["node-4"].x} y={STD_COORDS["node-4"].y + 3} fill="#9ca3af" fontSize="8" textAnchor="middle">N 4</text>

              <circle cx={STD_COORDS["node-5"].x} cy={STD_COORDS["node-5"].y} r="14" fill="#111827" stroke="#10b981" strokeWidth="1" />
              <text x={STD_COORDS["node-5"].x} y={STD_COORDS["node-5"].y + 3} fill="#9ca3af" fontSize="8" textAnchor="middle">N 5</text>

              <circle cx={STD_COORDS["node-6"].x} cy={STD_COORDS["node-6"].y} r="14" fill="#111827" stroke="#10b981" strokeWidth="1" />
              <text x={STD_COORDS["node-6"].x} y={STD_COORDS["node-6"].y + 3} fill="#9ca3af" fontSize="8" textAnchor="middle">N 6</text>

              {/* Client */}
              <polygon points="320,110 332,135 308,135" fill="#0284c7" stroke="#0ea5e9" strokeWidth="1.5" />
              <text x="320" y="145" fill="#38bdf8" fontSize="7" textAnchor="middle" fontWeight="bold">CLIENT</text>

              {/* Animated Packet */}
              {stdPacket && (
                <g>
                  <circle cx={stdPacket.x} cy={stdPacket.y} r="12" fill="#ef4444" opacity="0.25" className="animate-ping" />
                  <circle cx={stdPacket.x} cy={stdPacket.y} r="5" fill="#ef4444" stroke="#fff" strokeWidth="1.5" />
                </g>
              )}
            </svg>
          </div>

          {/* Progress Timeline */}
          <div className="space-y-1.5">
            <div className="w-full bg-gray-900 h-2 rounded-full overflow-hidden border border-gray-800">
              <div className="bg-red-500 h-full transition-all duration-100" style={{ width: `${(stdProgress / 200) * 100}%` }}></div>
            </div>
            <div className="px-3 py-1.5 bg-gray-900 border border-gray-800 rounded-xl text-[10.5px] font-mono text-red-400 min-h-[36px] flex items-center">
              {stdLabel}
            </div>
          </div>
        </div>

        {/* Right: CD-Raft 6-node (3+3) */}
        <div className="bg-gray-950 border border-gray-900 rounded-3xl p-5 flex flex-col justify-between shadow-2xl">
          <div>
            <div className="flex justify-between items-center mb-1">
              <h3 className="text-sm font-extrabold text-white flex items-center gap-1.5">
                🟢 CD-Raft Two-Tier Path (3+3 Dual-Domain)
              </h3>
              <span className="text-[10px] bg-yellow-950/30 text-yellow-400 border border-yellow-900/40 px-2 py-0.5 rounded font-mono font-bold">
                1.0 RTT (106ms) • Quorum: 2/3 + 2/3
              </span>
            </div>
            <p className="text-[11px] text-gray-500 mb-3">
              GL1 commits in Domain A with local quorum (N1+N2, 2/3). DL4 commits in Domain B with local quorum (N4+N5, 2/3) and returns to Client directly!
            </p>
          </div>

          {/* SVG Map CD-Raft */}
          <div className="flex justify-center bg-gray-950 rounded-2xl border border-gray-900 p-2 shadow-inner mb-3">
            <svg width="350" height="230" className="bg-slate-950 rounded-xl">
              <line x1="155" y1="10" x2="155" y2="220" stroke="#1f2937" strokeWidth="1" strokeDasharray="3 3" />
              <text x="25" y="20" fill="#4b5563" fontSize="8" fontWeight="bold">DOM A (VIRGINIA - 3 NODES)</text>
              <text x="170" y="20" fill="#4b5563" fontSize="8" fontWeight="bold">DOM B (LONDON - 3 NODES)</text>

              {/* Client -> GL1 (WAN) */}
              <line x1={CD_COORDS.client.x} y1={CD_COORDS.client.y} x2={CD_COORDS["node-1"].x} y2={CD_COORDS["node-1"].y}
                    stroke={cdActiveLinks.includes("client->node-1") || cdActiveLinks.includes("node-1->client") ? "#fbbf24" : "#111827"}
                    strokeWidth={cdActiveLinks.includes("client->node-1") || cdActiveLinks.includes("node-1->client") ? "3" : "1.5"} />

              {/* GL1 -> N2, N3 (Domain A Local LAN) */}
              <line x1={CD_COORDS["node-1"].x} y1={CD_COORDS["node-1"].y} x2={CD_COORDS["node-2"].x} y2={CD_COORDS["node-2"].y}
                    stroke={cdActiveLinks.includes("node-1->node-2") || cdActiveLinks.includes("node-2->node-1") ? "#34d399" : "#111827"}
                    strokeWidth={cdActiveLinks.includes("node-1->node-2") || cdActiveLinks.includes("node-2->node-1") ? "2" : "1.2"} />
              <line x1={CD_COORDS["node-1"].x} y1={CD_COORDS["node-1"].y} x2={CD_COORDS["node-3"].x} y2={CD_COORDS["node-3"].y}
                    stroke={cdActiveLinks.includes("node-1->node-3") || cdActiveLinks.includes("node-3->node-1") ? "#34d399" : "#111827"}
                    strokeWidth={cdActiveLinks.includes("node-1->node-3") || cdActiveLinks.includes("node-3->node-1") ? "2" : "1.2"} />

              {/* GL1 -> DL4 (Cross-Domain WAN Bridge) */}
              <line x1={CD_COORDS["node-1"].x} y1={CD_COORDS["node-1"].y} x2={CD_COORDS["node-4"].x} y2={CD_COORDS["node-4"].y}
                    stroke={cdActiveLinks.includes("node-1->node-4") || cdActiveLinks.includes("node-4->node-1") ? "#fbbf24" : "#111827"}
                    strokeWidth={cdActiveLinks.includes("node-1->node-4") || cdActiveLinks.includes("node-4->node-1") ? "3.5" : "1.5"} />

              {/* DL4 -> N5, N6 (Domain B Local LAN) */}
              <line x1={CD_COORDS["node-4"].x} y1={CD_COORDS["node-4"].y} x2={CD_COORDS["node-5"].x} y2={CD_COORDS["node-5"].y}
                    stroke={cdActiveLinks.includes("node-4->node-5") || cdActiveLinks.includes("node-5->node-4") ? "#34d399" : "#111827"}
                    strokeWidth={cdActiveLinks.includes("node-4->node-5") || cdActiveLinks.includes("node-5->node-4") ? "2" : "1.2"} />
              <line x1={CD_COORDS["node-4"].x} y1={CD_COORDS["node-4"].y} x2={CD_COORDS["node-6"].x} y2={CD_COORDS["node-6"].y}
                    stroke={cdActiveLinks.includes("node-4->node-6") || cdActiveLinks.includes("node-6->node-4") ? "#34d399" : "#111827"}
                    strokeWidth={cdActiveLinks.includes("node-4->node-6") || cdActiveLinks.includes("node-6->node-4") ? "2" : "1.2"} />

              {/* DL4 -> Client (Fast Return Local LAN) */}
              <line x1={CD_COORDS["node-4"].x} y1={CD_COORDS["node-4"].y} x2={CD_COORDS.client.x} y2={CD_COORDS.client.y}
                    stroke={cdActiveLinks.includes("node-4->client") || cdActiveLinks.includes("client->node-4") ? "#fbbf24" : "#111827"}
                    strokeWidth={cdActiveLinks.includes("node-4->client") || cdActiveLinks.includes("client->node-4") ? "3" : "1.5"} />

              {/* Nodes: Domain A */}
              <circle cx={CD_COORDS["node-1"].x} cy={CD_COORDS["node-1"].y} r="16" fill="#1f2937" stroke="#eab308" strokeWidth="2.5" />
              <text x={CD_COORDS["node-1"].x} y={CD_COORDS["node-1"].y + 3} fill="#fff" fontSize="8" textAnchor="middle" fontWeight="bold">GL 1</text>

              <circle cx={CD_COORDS["node-2"].x} cy={CD_COORDS["node-2"].y} r="14" fill="#111827" stroke="#10b981" strokeWidth="1" />
              <text x={CD_COORDS["node-2"].x} y={CD_COORDS["node-2"].y + 3} fill="#9ca3af" fontSize="8" textAnchor="middle">N 2</text>

              <circle cx={CD_COORDS["node-3"].x} cy={CD_COORDS["node-3"].y} r="14" fill="#111827" stroke="#10b981" strokeWidth="1" />
              <text x={CD_COORDS["node-3"].x} y={CD_COORDS["node-3"].y + 3} fill="#9ca3af" fontSize="8" textAnchor="middle">N 3</text>

              {/* Nodes: Domain B */}
              <circle cx={CD_COORDS["node-4"].x} cy={CD_COORDS["node-4"].y} r="16" fill="#1f2937" stroke="#f97316" strokeWidth="2.5" />
              <text x={CD_COORDS["node-4"].x} y={CD_COORDS["node-4"].y + 3} fill="#fff" fontSize="8" textAnchor="middle" fontWeight="bold">DL 4</text>

              <circle cx={CD_COORDS["node-5"].x} cy={CD_COORDS["node-5"].y} r="14" fill="#111827" stroke="#10b981" strokeWidth="1" />
              <text x={CD_COORDS["node-5"].x} y={CD_COORDS["node-5"].y + 3} fill="#9ca3af" fontSize="8" textAnchor="middle">N 5</text>

              <circle cx={CD_COORDS["node-6"].x} cy={CD_COORDS["node-6"].y} r="14" fill="#111827" stroke="#10b981" strokeWidth="1" />
              <text x={CD_COORDS["node-6"].x} y={CD_COORDS["node-6"].y + 3} fill="#9ca3af" fontSize="8" textAnchor="middle">N 6</text>

              {/* Client */}
              <polygon points="325,110 337,135 313,135" fill="#0284c7" stroke="#0ea5e9" strokeWidth="1.5" />
              <text x="325" y="145" fill="#38bdf8" fontSize="7" textAnchor="middle" fontWeight="bold">CLIENT</text>

              {/* Animated Packet */}
              {cdPacket && (
                <g>
                  <circle cx={cdPacket.x} cy={cdPacket.y} r="12" fill="#fbbf24" opacity="0.25" className="animate-ping" />
                  <circle cx={cdPacket.x} cy={cdPacket.y} r="5" fill="#fbbf24" stroke="#fff" strokeWidth="1.5" />
                </g>
              )}
            </svg>
          </div>

          {/* Progress Timeline */}
          <div className="space-y-1.5">
            <div className="w-full bg-gray-900 h-2 rounded-full overflow-hidden border border-gray-800">
              <div className="bg-yellow-500 h-full transition-all duration-100" style={{ width: `${(cdProgress / 106) * 100}%` }}></div>
            </div>
            <div className="px-3 py-1.5 bg-gray-900 border border-gray-800 rounded-xl text-[10.5px] font-mono text-yellow-400 min-h-[36px] flex items-center">
              {cdLabel}
            </div>
          </div>
        </div>
      </div>

      {/* 6 Cluster Nodes Live State Divided by Domain */}
      <div className="space-y-6">
        {/* DOMAIN A */}
        <div>
          <div className="flex items-center gap-2 mb-3">
            <Globe className="h-4 w-4 text-emerald-400" />
            <h2 className="text-xs font-bold text-gray-300 uppercase tracking-widest">
              DOMAIN A: VIRGINIA DATACENTER (Nodes 1, 2, 3 • Local Quorum: 2/3)
            </h2>
          </div>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
            {domainANodes.map(node => renderNodeCard(node, toggleNode))}
            {domainANodes.length === 0 && (
              <div className="col-span-3 text-xs text-gray-500 bg-gray-950 p-4 rounded-xl border border-gray-900">
                Waiting for Domain A nodes (node-1, node-2, node-3) to connect...
              </div>
            )}
          </div>
        </div>

        {/* DOMAIN B */}
        <div>
          <div className="flex items-center gap-2 mb-3">
            <Globe className="h-4 w-4 text-orange-400" />
            <h2 className="text-xs font-bold text-gray-300 uppercase tracking-widest">
              DOMAIN B: LONDON DATACENTER (Nodes 4, 5, 6 • Local Quorum: 2/3)
            </h2>
          </div>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
            {domainBNodes.map(node => renderNodeCard(node, toggleNode))}
            {domainBNodes.length === 0 && (
              <div className="col-span-3 text-xs text-gray-500 bg-gray-950 p-4 rounded-xl border border-gray-900">
                Waiting for Domain B nodes (node-4, node-5, node-6) to connect...
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

function renderNodeCard(node, toggleNode) {
  const isGL = node.id === 1;
  const isDL = node.id === 4;

  return (
    <div
      key={node.id}
      className={`bg-gray-950 border border-gray-900 rounded-2xl p-5 flex flex-col justify-between transition-all duration-300 shadow-xl ${
        !node.online ? "opacity-30 border-gray-800" : (node.role === "LEADER" ? "border-yellow-500/40 shadow-yellow-500/5" : "border-emerald-500/30")
      }`}
    >
      <div>
        <div className="flex justify-between items-center mb-2">
          <h3 className="text-sm font-bold text-white flex items-center gap-1.5">
            🖥 Node {node.id} {isGL && <span className="text-[10px] bg-yellow-500/20 text-yellow-400 px-1.5 py-0.5 rounded font-mono font-bold">GL</span>}
            {isDL && <span className="text-[10px] bg-orange-500/20 text-orange-400 px-1.5 py-0.5 rounded font-mono font-bold">DL</span>}
          </h3>
          <span className={`px-2 py-0.5 rounded text-[10px] font-bold uppercase ${
            node.online ? (node.role === "LEADER" ? "bg-yellow-500/15 text-yellow-400 border border-yellow-500/20" : "bg-emerald-500/15 text-emerald-400 border border-emerald-500/20") : "bg-gray-800 text-gray-500"
          }`}>
            {node.online ? node.role : "OFFLINE"}
          </span>
        </div>
        <div className="text-[10px] text-gray-500 font-mono tracking-wider mb-4">
          {node.id <= 3 ? "DOMAIN A (VIRGINIA)" : "DOMAIN B (LONDON)"} • TERM {node.term}
        </div>

        {/* DB Store */}
        <div className="mb-4">
          <span className="block text-[9px] text-gray-500 font-bold uppercase mb-1.5 flex items-center gap-1">
            <Database className="h-3 w-3" /> State Machine Store
          </span>
          <div className="bg-gray-900 p-2.5 rounded-xl font-mono text-[10px] min-h-[45px] max-h-[85px] overflow-y-auto border border-gray-900">
            {Object.entries(node.database).map(([k, v]) => (
              <div key={k} className="flex justify-between border-b border-gray-950/20 pb-0.5 mb-0.5">
                <span className="text-yellow-400">{k}</span>
                <span className="text-gray-300">"{v}"</span>
              </div>
            ))}
            {Object.keys(node.database).length === 0 && <span className="text-gray-600 font-sans">Database empty</span>}
          </div>
        </div>

        {/* Persistent Log WAL */}
        <div className="mb-4">
          <span className="block text-[9px] text-gray-500 font-bold uppercase mb-1.5 flex items-center gap-1">
            <Clock className="h-3 w-3" /> Persistent Raft Log
          </span>
          <div className="bg-gray-900 p-2.5 rounded-xl font-mono text-[10px] min-h-[50px] max-h-[85px] overflow-y-auto border border-gray-900">
            {node.log.map((entry, idx) => (
              <div key={idx} className="flex gap-2 items-center text-gray-400 mb-1">
                <span className="text-[8.5px] bg-slate-950 px-1 py-0.5 rounded text-gray-400 font-bold">i:{entry.index}</span>
                <span className="text-[8.5px] bg-yellow-500/10 text-yellow-400 px-1 py-0.5 rounded font-bold">t:{entry.term}</span>
                <span className="text-gray-200">{entry.command}</span>
              </div>
            ))}
            {node.log.length === 0 && <span className="text-gray-600 font-sans">No log entries</span>}
          </div>
        </div>
      </div>

      <button
        onClick={() => toggleNode(node.id)}
        className={`w-full py-1.5 rounded-lg text-xs font-bold transition border ${
          node.online ? "bg-red-500/10 text-red-400 border-red-500/20 hover:bg-red-500/20" : "bg-emerald-500/10 text-emerald-400 border-emerald-500/20 hover:bg-emerald-500/20"
        }`}
      >
        {node.online ? "Simulate Crash (Kill Node)" : "Simulate Recovery (Revive)"}
      </button>
    </div>
  );
}
