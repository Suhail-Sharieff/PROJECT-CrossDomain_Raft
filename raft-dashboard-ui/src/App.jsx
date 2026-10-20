import React, { useState, useEffect } from 'react';
import { Server, Zap, Database, Network, Activity, Wifi, WifiOff, RotateCcw, Play, CheckCircle2, XCircle, Clock, Globe, ShieldAlert, ShieldCheck } from 'lucide-react';

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

  // Overall operation result tracker
  const [lastOpResult, setLastOpResult] = useState(null);

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
    const interval = setInterval(fetchState, 700);
    return () => clearInterval(interval);
  }, []);

  // Compute live leadership & quorum state dynamically from real cluster
  const activeLeader = nodes.find(n => n.online && n.role === "LEADER");
  const globalLeaderId = activeLeader ? activeLeader.id : (nodes.find(n => n.leaderId > 0)?.leaderId || 1);

  // Dynamic Domain Leaders
  const domainAOnlineNodes = nodes.filter(n => n.id <= 3 && n.online);
  const domainBOnlineNodes = nodes.filter(n => n.id > 3 && n.online);
  const totalOnlineCount = nodes.filter(n => n.online).length;

  const domainALeaderId = (globalLeaderId <= 3 && nodes.find(n => n.id === globalLeaderId)?.online)
    ? globalLeaderId
    : (domainAOnlineNodes[0]?.id || 1);

  const domainBLeaderId = (globalLeaderId > 3 && nodes.find(n => n.id === globalLeaderId)?.online)
    ? globalLeaderId
    : (domainBOnlineNodes[0]?.id || 4);

  const domainAHasQuorum = domainAOnlineNodes.length >= 2;
  const domainBHasQuorum = domainBOnlineNodes.length >= 2;
  const classicHasQuorum = totalOnlineCount >= 4;

  const getNodeInfo = (id) => {
    const found = nodes.find(n => n.id === id);
    if (!found) return { id, online: true, role: "FOLLOWER", term: 0 };
    return found;
  };

  const handleRace = (e) => {
    e.preventDefault();
    if (running) return;
    setRunning(true);
    setStdProgress(0);
    setCdProgress(0);
    setStdLabel("Client dispatching write to Classic Leader...");
    setCdLabel("Client dispatching write to Global Leader...");
    setLastOpResult({
      status: "running",
      key: putKey,
      value: putValue,
      message: "Replicating across network links and awaiting quorum ACKs..."
    });

    Promise.all([
      fetch(`/api/simulate-write?type=standard&key=${encodeURIComponent(putKey)}&value=${encodeURIComponent(putValue)}`, { method: "POST" }).then(r => r.json()),
      fetch(`/api/simulate-write?type=cd&key=${encodeURIComponent(putKey)}&value=${encodeURIComponent(putValue)}`, { method: "POST" }).then(r => r.json())
    ])
    .then(([stdData, cdData]) => {
      // Success is determined by the CD-Raft write result (only one real write submitted)
      // stdData.success is based on live leader presence, not an actual write
      startConsensusRace(stdData.steps, cdData.steps, cdData.success, cdData);
    })
    .catch(err => {
      setLastOpResult({
        status: "failed",
        success: false,
        key: putKey,
        value: putValue,
        message: "Failed to contact cluster or write rejected due to network partition / dead nodes."
      });
      setRunning(false);
    });
  };

  const startConsensusRace = (stdSteps, cdSteps, isSuccess, cdData) => {
    const startWallTime = performance.now();
    const SPEED_FACTOR = 16; // 16x visual slowdown

    const animate = (time) => {
      let elapsed = (time - startWallTime) / SPEED_FACTOR;

      // 1. Classic Raft
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
        if (classicHasQuorum) {
          setStdLabel("Classic Raft: COMMITTED in 200ms (2.0 WAN RTTs).");
        } else {
          setStdLabel("Classic Raft: FAILED (Quorum lost: need 4 nodes, only " + totalOnlineCount + " online)!");
        }
      }

      // 2. CD-Raft
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
        if (domainAHasQuorum && domainBHasQuorum) {
          setCdLabel("CD-Raft: COMMITTED in 106ms (1.0 WAN RTT Fast Return via DL)!");
        } else {
          setCdLabel("CD-Raft: FAILED (Domain Quorum Lost: Domain A=" + domainAOnlineNodes.length + "/3, Domain B=" + domainBOnlineNodes.length + "/3)!");
        }
      }

      if (elapsed < 200) {
        requestAnimationFrame(animate);
      } else {
        setRunning(false);
        fetchState();

        const cdOk = domainAHasQuorum && domainBHasQuorum && isSuccess;
        const globalOk = classicHasQuorum && isSuccess;

        // Determine failure reason accurately
        let failureReason = "";
        if (!isSuccess) {
          failureReason = "Cluster write rejected (no leader or leader election in progress). Wait for election to complete and retry.";
        } else if (!domainAHasQuorum) {
          failureReason = `Domain A quorum lost (${domainAOnlineNodes.length}/3 alive, need 2). CD-Raft cannot commit.`;
        } else if (!domainBHasQuorum) {
          failureReason = `Domain B quorum lost (${domainBOnlineNodes.length}/3 alive, need 2). CD-Raft cannot commit.`;
        }

        setLastOpResult({
          status: cdOk ? "success" : "failed",
          success: cdOk,
          key: putKey,
          value: putValue,
          leaderId: cdData?.leaderId || globalLeaderId,
          commitIndex: cdData?.commitIndex || 0,
          term: cdData?.term || 0,
          message: cdOk
            ? `Consensus write committed successfully across quorum (106ms Fast Return)! Applied to all ${totalOnlineCount} live state machines.`
            : failureReason
        });
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

  // Helper to render an SVG node dynamically
  const renderClassicSvgNode = (id) => {
    const coords = STD_COORDS[`node-${id}`];
    const n = getNodeInfo(id);
    const isLdr = n.online && n.role === "LEADER";

    let fill = isLdr ? "#7f1d1d" : "#111827";
    let stroke = !n.online ? "#ef4444" : (isLdr ? "#ef4444" : "#10b981");
    let strokeWidth = isLdr ? 2.5 : 1.5;
    let label = !n.online ? `N${id} ✕` : (isLdr ? `LDR ${id}` : `N ${id}`);
    let dash = !n.online ? "3 3" : "none";

    return (
      <g key={id} opacity={!n.online ? 0.35 : 1}>
        <circle cx={coords.x} cy={coords.y} r={isLdr ? 17 : 14} fill={fill} stroke={stroke} strokeWidth={strokeWidth} strokeDasharray={dash} />
        <text x={coords.x} y={coords.y + 3} fill="#fff" fontSize={isLdr ? 7.5 : 8} textAnchor="middle" fontWeight="bold">
          {label}
        </text>
      </g>
    );
  };

  const renderCdSvgNode = (id) => {
    const coords = CD_COORDS[`node-${id}`];
    const n = getNodeInfo(id);

    // Is this node the active Global Leader (GL)?
    const isGL = n.online && (n.id === globalLeaderId || n.role === "LEADER");

    // Is this node the Domain Leader (DL) of the secondary domain?
    const isDL = n.online && !isGL && (
      globalLeaderId <= 3 ? id === domainBLeaderId : id === domainALeaderId
    );

    let fill = !n.online ? "#030712" : (isGL ? "#713f12" : (isDL ? "#7c2d12" : "#111827"));
    let stroke = !n.online ? "#ef4444" : (isGL ? "#eab308" : (isDL ? "#f97316" : "#10b981"));
    let strokeWidth = isGL ? 3 : (isDL ? 2.5 : 1.5);
    let label = !n.online ? `N${id} ✕` : (isGL ? `GL ${id}` : (isDL ? `DL ${id}` : `N ${id}`));
    let dash = !n.online ? "3 3" : "none";

    return (
      <g key={id} opacity={!n.online ? 0.35 : 1}>
        <circle cx={coords.x} cy={coords.y} r={isGL ? 17 : (isDL ? 16 : 14)} fill={fill} stroke={stroke} strokeWidth={strokeWidth} strokeDasharray={dash} />
        <text x={coords.x} y={coords.y + 3} fill="#fff" fontSize={isGL || isDL ? 7.5 : 8} textAnchor="middle" fontWeight="bold">
          {label}
        </text>
      </g>
    );
  };

  return (
    <div className="min-h-screen p-6 max-w-7xl mx-auto flex flex-col justify-between">
      {/* Header */}
      <header className="flex justify-between items-center mb-6 border-b border-gray-900 pb-4">
        <div>
          <span className="text-yellow-500 text-xs font-semibold uppercase tracking-wider">
            Live Fault Tolerance & Quorum Invariant Monitor
          </span>
          <h1 className="text-2xl font-extrabold tracking-tight text-white mt-0.5 flex items-center gap-2">
            🌐 CD-Raft 6-Node Cross-Domain Cluster
          </h1>
        </div>
        <div className="flex items-center gap-4 bg-gray-950 border border-gray-900 px-4 py-2 rounded-xl text-xs">
          <div className="flex items-center gap-1.5">
            <span className={`h-2.5 w-2.5 rounded-full ${totalOnlineCount >= 4 ? "bg-emerald-500 animate-pulse" : "bg-red-500"}`}></span>
            <span className="text-gray-300 font-mono font-bold">Online: {totalOnlineCount}/6</span>
          </div>
          <span className="text-gray-700">|</span>
          <span className="text-yellow-400 font-mono font-bold">Active Leader: Node {globalLeaderId}</span>
          <span className="text-gray-700">|</span>
          <span className="text-red-400 font-mono">WAN RTT: 100ms</span>
        </div>
      </header>

      {/* Live Quorum Health Monitor Bar */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4 mb-6">
        {/* Domain A Quorum */}
        <div className={`p-3.5 rounded-xl border flex items-center justify-between ${
          domainAHasQuorum ? "bg-emerald-950/20 border-emerald-500/30 text-emerald-300" : "bg-red-950/30 border-red-500/40 text-red-300"
        }`}>
          <div className="flex items-center gap-2">
            {domainAHasQuorum ? <ShieldCheck className="h-5 w-5 text-emerald-400" /> : <ShieldAlert className="h-5 w-5 text-red-400 animate-pulse" />}
            <div>
              <div className="text-xs font-bold uppercase tracking-wider">Domain A (Virginia)</div>
              <div className="text-[11px] opacity-80">{domainAOnlineNodes.length}/3 Replicas Online</div>
            </div>
          </div>
          <span className="text-[10px] px-2 py-0.5 rounded font-mono font-bold uppercase bg-black/40">
            {domainAHasQuorum ? "Quorum: 2/3 (Healthy)" : "Quorum Lost"}
          </span>
        </div>

        {/* Domain B Quorum */}
        <div className={`p-3.5 rounded-xl border flex items-center justify-between ${
          domainBHasQuorum ? "bg-emerald-950/20 border-emerald-500/30 text-emerald-300" : "bg-red-950/30 border-red-500/40 text-red-300"
        }`}>
          <div className="flex items-center gap-2">
            {domainBHasQuorum ? <ShieldCheck className="h-5 w-5 text-emerald-400" /> : <ShieldAlert className="h-5 w-5 text-red-400 animate-pulse" />}
            <div>
              <div className="text-xs font-bold uppercase tracking-wider">Domain B (London)</div>
              <div className="text-[11px] opacity-80">{domainBOnlineNodes.length}/3 Replicas Online</div>
            </div>
          </div>
          <span className="text-[10px] px-2 py-0.5 rounded font-mono font-bold uppercase bg-black/40">
            {domainBHasQuorum ? "Quorum: 2/3 (Healthy)" : "Quorum Lost"}
          </span>
        </div>

        {/* Classic Raft Quorum */}
        <div className={`p-3.5 rounded-xl border flex items-center justify-between ${
          classicHasQuorum ? "bg-emerald-950/20 border-emerald-500/30 text-emerald-300" : "bg-red-950/30 border-red-500/40 text-red-300"
        }`}>
          <div className="flex items-center gap-2">
            {classicHasQuorum ? <ShieldCheck className="h-5 w-5 text-emerald-400" /> : <ShieldAlert className="h-5 w-5 text-red-400 animate-pulse" />}
            <div>
              <div className="text-xs font-bold uppercase tracking-wider">Classic Global Quorum</div>
              <div className="text-[11px] opacity-80">{totalOnlineCount}/6 Replicas (Need 4)</div>
            </div>
          </div>
          <span className="text-[10px] px-2 py-0.5 rounded font-mono font-bold uppercase bg-black/40">
            {classicHasQuorum ? "Quorum: 4/6 (Healthy)" : "Quorum Lost"}
          </span>
        </div>
      </div>

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
                  {running ? "Executing Consensus Protocol..." : "Run 6-Node Consensus Race"}
                </button>
              </div>
            </form>
          </div>

          {/* Operation Result Alert Banner */}
          {lastOpResult && (
            <div className={`mt-4 p-3.5 rounded-xl border flex items-center justify-between transition-all ${
              lastOpResult.status === "running"
                ? "bg-yellow-950/20 border-yellow-500/30 text-yellow-300"
                : (lastOpResult.success
                  ? "bg-emerald-950/30 border-emerald-500/40 text-emerald-300"
                  : "bg-red-950/30 border-red-500/40 text-red-300")
            }`}>
              <div className="flex items-center gap-3">
                {lastOpResult.status === "running" ? (
                  <Clock className="h-5 w-5 text-yellow-400 animate-spin shrink-0" />
                ) : (lastOpResult.success ? (
                  <CheckCircle2 className="h-5 w-5 text-emerald-400 shrink-0" />
                ) : (
                  <XCircle className="h-5 w-5 text-red-400 shrink-0" />
                ))}
                <div>
                  <div className="font-bold text-xs">
                    {lastOpResult.status === "running"
                      ? "Dispatched: Awaiting quorum consensus..."
                      : (lastOpResult.success ? `SUCCESS: Key "${lastOpResult.key}" committed!` : "OPERATION FAILED: Quorum Lost!")}
                  </div>
                  <div className="text-[11px] opacity-80 font-mono mt-0.5">
                    {lastOpResult.message}
                  </div>
                </div>
              </div>
              <span className={`text-[10px] px-2.5 py-1 rounded-full font-mono font-bold uppercase bg-black/40 border ${
                lastOpResult.status === "running" ? "border-yellow-500 text-yellow-400" : (lastOpResult.success ? "border-emerald-500 text-emerald-400" : "border-red-500 text-red-400")
              }`}>
                {lastOpResult.status === "running" ? "RUNNING" : (lastOpResult.success ? "COMMITTED" : "REJECTED")}
              </span>
            </div>
          )}
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

      {/* Side-by-Side Visual Race Grid (Dynamic Nodes with Live Status) */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-6 mb-6">
        {/* Left: Classic Raft 6-node */}
        <div className="bg-gray-950 border border-gray-900 rounded-3xl p-5 flex flex-col justify-between shadow-2xl">
          <div>
            <div className="flex justify-between items-center mb-1">
              <h3 className="text-sm font-extrabold text-white flex items-center gap-1.5">
                🔴 Classic Raft Write Path (Flat 6-Node)
              </h3>
              <span className={`text-[10px] px-2 py-0.5 rounded font-mono font-bold border ${
                classicHasQuorum ? "bg-red-950/30 text-red-400 border-red-900/40" : "bg-red-900 text-white border-red-500"
              }`}>
                {classicHasQuorum ? "2.0 RTTs (200ms) • Quorum: 4/6" : "QUORUM LOST"}
              </span>
            </div>
            <p className="text-[11px] text-gray-500 mb-3">
              Classic Leader must wait for majority (4/6) across the ocean before committing and replying to London client.
            </p>
          </div>

          {/* SVG Map Classic Raft */}
          <div className="flex justify-center bg-gray-950 rounded-2xl border border-gray-900 p-2 shadow-inner mb-3">
            <svg width="350" height="230" className="bg-slate-950 rounded-xl">
              <rect x="5" y="5" width="340" height="220" fill="none" stroke="#1f2937" strokeWidth="1" strokeDasharray="3 3" />
              <text x="15" y="20" fill="#4b5563" fontSize="8" fontWeight="bold">FLAT 6-NODE TOPOLOGY</text>

              {/* Links */}
              <line x1={STD_COORDS.client.x} y1={STD_COORDS.client.y} x2={STD_COORDS["node-1"].x} y2={STD_COORDS["node-1"].y}
                    stroke={stdActiveLinks.includes("client->node-1") || stdActiveLinks.includes("node-1->client") ? "#ef4444" : "#111827"}
                    strokeWidth={stdActiveLinks.includes("client->node-1") || stdActiveLinks.includes("node-1->client") ? "3" : "1.5"}
                    opacity={!getNodeInfo(1).online ? 0.2 : 1} />
              
              <line x1={STD_COORDS["node-1"].x} y1={STD_COORDS["node-1"].y} x2={STD_COORDS["node-2"].x} y2={STD_COORDS["node-2"].y}
                    stroke={stdActiveLinks.includes("node-1->node-2") || stdActiveLinks.includes("node-2->node-1") ? "#34d399" : "#111827"}
                    strokeWidth={stdActiveLinks.includes("node-1->node-2") || stdActiveLinks.includes("node-2->node-1") ? "2" : "1.2"}
                    opacity={!getNodeInfo(1).online || !getNodeInfo(2).online ? 0.2 : 1} />
              <line x1={STD_COORDS["node-1"].x} y1={STD_COORDS["node-1"].y} x2={STD_COORDS["node-3"].x} y2={STD_COORDS["node-3"].y}
                    stroke={stdActiveLinks.includes("node-1->node-3") || stdActiveLinks.includes("node-3->node-1") ? "#34d399" : "#111827"}
                    strokeWidth={stdActiveLinks.includes("node-1->node-3") || stdActiveLinks.includes("node-3->node-1") ? "2" : "1.2"}
                    opacity={!getNodeInfo(1).online || !getNodeInfo(3).online ? 0.2 : 1} />

              <line x1={STD_COORDS["node-1"].x} y1={STD_COORDS["node-1"].y} x2={STD_COORDS["node-4"].x} y2={STD_COORDS["node-4"].y}
                    stroke={stdActiveLinks.includes("node-1->node-4") || stdActiveLinks.includes("node-4->node-1") ? "#ef4444" : "#111827"}
                    strokeWidth={stdActiveLinks.includes("node-1->node-4") || stdActiveLinks.includes("node-4->node-1") ? "3" : "1.5"}
                    opacity={!getNodeInfo(1).online || !getNodeInfo(4).online ? 0.2 : 1} />
              <line x1={STD_COORDS["node-1"].x} y1={STD_COORDS["node-1"].y} x2={STD_COORDS["node-5"].x} y2={STD_COORDS["node-5"].y}
                    stroke={stdActiveLinks.includes("node-1->node-5") || stdActiveLinks.includes("node-5->node-1") ? "#ef4444" : "#111827"}
                    strokeWidth={stdActiveLinks.includes("node-1->node-5") || stdActiveLinks.includes("node-5->node-1") ? "2.5" : "1.2"}
                    opacity={!getNodeInfo(1).online || !getNodeInfo(5).online ? 0.2 : 1} />
              <line x1={STD_COORDS["node-1"].x} y1={STD_COORDS["node-1"].y} x2={STD_COORDS["node-6"].x} y2={STD_COORDS["node-6"].y}
                    stroke={stdActiveLinks.includes("node-1->node-6") || stdActiveLinks.includes("node-6->node-1") ? "#ef4444" : "#111827"}
                    strokeWidth={stdActiveLinks.includes("node-1->node-6") || stdActiveLinks.includes("node-6->node-1") ? "2.5" : "1.2"}
                    opacity={!getNodeInfo(1).online || !getNodeInfo(6).online ? 0.2 : 1} />

              {/* Dynamic Nodes */}
              {[1, 2, 3, 4, 5, 6].map(id => renderClassicSvgNode(id))}

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

        {/* Right: CD-Raft 6-node (3+3 Dynamic GL and DL) */}
        <div className="bg-gray-950 border border-gray-900 rounded-3xl p-5 flex flex-col justify-between shadow-2xl">
          <div>
            <div className="flex justify-between items-center mb-1">
              <h3 className="text-sm font-extrabold text-white flex items-center gap-1.5">
                🟢 CD-Raft Two-Tier Path (3+3 Dual-Domain)
              </h3>
              <span className={`text-[10px] px-2 py-0.5 rounded font-mono font-bold border ${
                domainAHasQuorum && domainBHasQuorum ? "bg-yellow-950/30 text-yellow-400 border-yellow-900/40" : "bg-red-900 text-white border-red-500"
              }`}>
                {domainAHasQuorum && domainBHasQuorum ? "1.0 RTT (106ms) • Quorum: 2/3 + 2/3" : "DOMAIN QUORUM LOST"}
              </span>
            </div>
            <p className="text-[11px] text-gray-500 mb-3">
              GL in Domain A and DL in Domain B coordinate. Fast Return triggered directly from local DL to client as soon as 2/3 quorums are achieved!
            </p>
          </div>

          {/* SVG Map CD-Raft */}
          <div className="flex justify-center bg-gray-950 rounded-2xl border border-gray-900 p-2 shadow-inner mb-3">
            <svg width="350" height="230" className="bg-slate-950 rounded-xl">
              <line x1="155" y1="10" x2="155" y2="220" stroke="#1f2937" strokeWidth="1" strokeDasharray="3 3" />
              <text x="25" y="20" fill="#4b5563" fontSize="8" fontWeight="bold">DOM A (VIRGINIA)</text>
              <text x="170" y="20" fill="#4b5563" fontSize="8" fontWeight="bold">DOM B (LONDON)</text>

              {/* Client -> GL1 (WAN) */}
              <line x1={CD_COORDS.client.x} y1={CD_COORDS.client.y} x2={CD_COORDS["node-1"].x} y2={CD_COORDS["node-1"].y}
                    stroke={cdActiveLinks.includes("client->node-1") || cdActiveLinks.includes("node-1->client") ? "#fbbf24" : "#111827"}
                    strokeWidth={cdActiveLinks.includes("client->node-1") || cdActiveLinks.includes("node-1->client") ? "3" : "1.5"}
                    opacity={!getNodeInfo(1).online ? 0.2 : 1} />

              {/* GL1 -> N2, N3 (Domain A Local LAN) */}
              <line x1={CD_COORDS["node-1"].x} y1={CD_COORDS["node-1"].y} x2={CD_COORDS["node-2"].x} y2={CD_COORDS["node-2"].y}
                    stroke={cdActiveLinks.includes("node-1->node-2") || cdActiveLinks.includes("node-2->node-1") ? "#34d399" : "#111827"}
                    strokeWidth={cdActiveLinks.includes("node-1->node-2") || cdActiveLinks.includes("node-2->node-1") ? "2" : "1.2"}
                    opacity={!getNodeInfo(1).online || !getNodeInfo(2).online ? 0.2 : 1} />
              <line x1={CD_COORDS["node-1"].x} y1={CD_COORDS["node-1"].y} x2={CD_COORDS["node-3"].x} y2={CD_COORDS["node-3"].y}
                    stroke={cdActiveLinks.includes("node-1->node-3") || cdActiveLinks.includes("node-3->node-1") ? "#34d399" : "#111827"}
                    strokeWidth={cdActiveLinks.includes("node-1->node-3") || cdActiveLinks.includes("node-3->node-1") ? "2" : "1.2"}
                    opacity={!getNodeInfo(1).online || !getNodeInfo(3).online ? 0.2 : 1} />

              {/* GL1 -> DL4 (Cross-Domain WAN Bridge) */}
              <line x1={CD_COORDS["node-1"].x} y1={CD_COORDS["node-1"].y} x2={CD_COORDS["node-4"].x} y2={CD_COORDS["node-4"].y}
                    stroke={cdActiveLinks.includes("node-1->node-4") || cdActiveLinks.includes("node-4->node-1") ? "#fbbf24" : "#111827"}
                    strokeWidth={cdActiveLinks.includes("node-1->node-4") || cdActiveLinks.includes("node-4->node-1") ? "3.5" : "1.5"}
                    opacity={!getNodeInfo(1).online || !getNodeInfo(4).online ? 0.2 : 1} />

              {/* DL4 -> N5, N6 (Domain B Local LAN) */}
              <line x1={CD_COORDS["node-4"].x} y1={CD_COORDS["node-4"].y} x2={CD_COORDS["node-5"].x} y2={CD_COORDS["node-5"].y}
                    stroke={cdActiveLinks.includes("node-4->node-5") || cdActiveLinks.includes("node-5->node-4") ? "#34d399" : "#111827"}
                    strokeWidth={cdActiveLinks.includes("node-4->node-5") || cdActiveLinks.includes("node-5->node-4") ? "2" : "1.2"}
                    opacity={!getNodeInfo(4).online || !getNodeInfo(5).online ? 0.2 : 1} />
              <line x1={CD_COORDS["node-4"].x} y1={CD_COORDS["node-4"].y} x2={CD_COORDS["node-6"].x} y2={CD_COORDS["node-6"].y}
                    stroke={cdActiveLinks.includes("node-4->node-6") || cdActiveLinks.includes("node-6->node-4") ? "#34d399" : "#111827"}
                    strokeWidth={cdActiveLinks.includes("node-4->node-6") || cdActiveLinks.includes("node-6->node-4") ? "2" : "1.2"}
                    opacity={!getNodeInfo(4).online || !getNodeInfo(6).online ? 0.2 : 1} />

              {/* DL4 -> Client (Fast Return Local LAN) */}
              <line x1={CD_COORDS["node-4"].x} y1={CD_COORDS["node-4"].y} x2={CD_COORDS.client.x} y2={CD_COORDS.client.y}
                    stroke={cdActiveLinks.includes("node-4->client") || cdActiveLinks.includes("client->node-4") ? "#fbbf24" : "#111827"}
                    strokeWidth={cdActiveLinks.includes("node-4->client") || cdActiveLinks.includes("client->node-4") ? "3" : "1.5"}
                    opacity={!getNodeInfo(4).online ? 0.2 : 1} />

              {/* Dynamic CD-Raft Nodes */}
              {[1, 2, 3, 4, 5, 6].map(id => renderCdSvgNode(id))}

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
            {domainANodes.map(node => renderNodeCard(node, toggleNode, globalLeaderId, domainALeaderId, domainBLeaderId))}
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
            {domainBNodes.map(node => renderNodeCard(node, toggleNode, globalLeaderId, domainALeaderId, domainBLeaderId))}
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

function renderNodeCard(node, toggleNode, globalLeaderId, domainALeaderId, domainBLeaderId) {
  const isGL = node.online && (node.id === globalLeaderId || node.role === "LEADER");
  const isDL = node.online && !isGL && (
    globalLeaderId <= 3 ? node.id === domainBLeaderId : node.id === domainALeaderId
  );

  return (
    <div
      key={node.id}
      className={`bg-gray-950 border border-gray-900 rounded-2xl p-5 flex flex-col justify-between transition-all duration-300 shadow-xl ${
        !node.online ? "opacity-35 border-red-900/40" : (node.role === "LEADER" ? "border-yellow-500/50 shadow-yellow-500/10" : "border-emerald-500/30")
      }`}
    >
      <div>
        <div className="flex justify-between items-center mb-2">
          <h3 className="text-sm font-bold text-white flex items-center gap-1.5">
            🖥 Node {node.id}
            {isGL && <span className="text-[10px] bg-yellow-500/20 text-yellow-400 border border-yellow-500/30 px-1.5 py-0.5 rounded font-mono font-bold">GL</span>}
            {isDL && <span className="text-[10px] bg-orange-500/20 text-orange-400 border border-orange-500/30 px-1.5 py-0.5 rounded font-mono font-bold">DL</span>}
          </h3>
          <span className={`px-2 py-0.5 rounded text-[10px] font-bold uppercase ${
            node.online ? (node.role === "LEADER" ? "bg-yellow-500/15 text-yellow-400 border border-yellow-500/20" : "bg-emerald-500/15 text-emerald-400 border border-emerald-500/20") : "bg-red-950/40 text-red-400 border border-red-900/40"
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
