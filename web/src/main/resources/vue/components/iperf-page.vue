<template id="iperf-page">
  <div class="tool-view iperf-page">
    <div class="page-head">
      <div>
        <h2>iperf3 Throughput</h2>
        <p class="desc">Run controlled iperf3 tests, start a local server, and discover LAN peers.</p>
      </div>
      <button class="btn secondary" @click="refreshAll" :disabled="loadingStatus || discovering">Refresh</button>
    </div>

    <div v-if="binary" class="binary-strip" :class="{ bad: !binary.available }">
      <span>{{ binary.available ? 'iperf3 ready' : 'iperf3 unavailable' }}</span>
      <strong>{{ binary.version || binary.error || 'Not found' }}</strong>
      <code v-if="binary.path">{{ binary.path }}</code>
      <code v-else-if="binary.rejected?.length">{{ binary.rejected[0] }}</code>
    </div>

    <div class="grid">
      <section class="panel">
        <div class="panel-title">Local Server</div>
        <div class="server-state">
          <div>
            <span>Status</span>
            <strong :class="server.running ? 'ok' : 'muted'">{{ server.running ? 'Running' : 'Stopped' }}</strong>
          </div>
          <div>
            <span>Port</span>
            <strong>{{ server.port || serverPort }}</strong>
          </div>
          <div class="mdns-state">
            <span>mDNS</span>
            <strong :class="server.mdnsPublished ? 'ok' : 'muted'">{{ server.mdnsPublished ? '_iperf3._tcp.local' : 'Off' }}</strong>
          </div>
        </div>
        <form @submit.prevent="startServer" class="row-form">
          <input v-model.number="serverPort" type="number" min="1" max="65535" class="input small" />
          <label class="check"><input type="checkbox" v-model="publishMdns" /> Publish</label>
          <button class="btn" :disabled="server.running || serverBusy || !binary?.available">{{ serverBusy ? 'Starting...' : 'Start' }}</button>
          <button type="button" class="btn stop" :disabled="!server.running || serverBusy" @click="stopServer">Stop</button>
          <button type="button" class="btn secondary" :disabled="serverBusy || runningClient || !binary?.available" @click="runLocalLoopbackTest">Test Local</button>
        </form>
        <div v-if="server.lastError" class="error">{{ server.lastError }}</div>
        <pre v-if="server.lastOutput" class="mini-log">{{ server.lastOutput }}</pre>
      </section>

      <section class="panel">
        <div class="panel-title">Client Test</div>
        <form @submit.prevent="runClient" class="client-form">
          <input v-model.trim="targetHost" class="input host" placeholder="Host" />
          <input v-model.number="targetPort" type="number" min="1" max="65535" class="input port" />
          <select v-model="protocol" class="input mode">
            <option value="tcp">TCP</option>
            <option value="udp">UDP</option>
          </select>
          <select v-model="ipVersion" class="input ip-version">
            <option value="auto">Auto IP</option>
            <option value="ipv4">IPv4</option>
            <option value="ipv6">IPv6</option>
          </select>
          <input v-model.number="durationSeconds" type="number" min="1" max="60" class="input small" title="Seconds" />
          <input v-model.number="parallel" type="number" min="1" max="16" class="input small" title="Parallel streams" />
          <input v-if="protocol === 'udp'" v-model.trim="bitrate" class="input bitrate" placeholder="10M" />
          <label class="check"><input type="checkbox" v-model="reverse" /> Reverse</label>
          <button class="btn" :disabled="runningClient || !targetHost || !targetPort || !binary?.available">{{ runningClient ? 'Running...' : 'Run Test' }}</button>
        </form>
        <div v-if="clientJob && runningClient" class="job-state">Running job {{ clientJob.id }}...</div>
        <div v-if="error" class="error">{{ error }}</div>
      </section>
    </div>

    <section class="panel">
      <div class="tabs">
        <button class="tab" :class="{ active: activeTab === 'targets' }" @click="activeTab = 'targets'">Targets</button>
        <button class="tab" :class="{ active: activeTab === 'result' }" @click="activeTab = 'result'">Result</button>
        <button class="tab" :class="{ active: activeTab === 'json' }" @click="activeTab = 'json'">JSON</button>
      </div>

      <div v-show="activeTab === 'targets'" class="tab-panel">
        <div class="target-head">
          <div class="section-title">Local Discovery</div>
          <button class="btn secondary" @click="discover" :disabled="discovering">{{ discovering ? 'Scanning...' : 'Scan mDNS' }}</button>
        </div>
        <div v-if="discovered.length" class="target-list">
          <button v-for="svc in discovered" :key="svc.instance + svc.host + svc.port" class="target-row" @click="selectDiscoveredTarget(svc)">
            <span>
              <strong>{{ svc.instance }}</strong>
              <small>{{ endpointSummary(svc) }}</small>
            </span>
            <code>{{ endpointLabel(svc) }}:{{ svc.port }}</code>
          </button>
        </div>
        <div v-else class="empty">No local iperf3 DNS-SD services found.</div>

        <div class="target-head public-head">
          <div>
            <div class="section-title">Public Servers</div>
            <p v-if="catalog" class="catalog-note">{{ catalog.source }} · retrieved {{ catalog.retrieved }}</p>
          </div>
        </div>
        <div class="target-list public-list">
          <button v-for="srv in publicServers" :key="srv.host" class="target-row" @click="selectPublicTarget(srv)">
            <span>
              <strong>{{ srv.host }}</strong>
              <small>{{ srv.region }} / {{ srv.country }} · {{ portLabel(srv) }} · {{ srv.ipVersions.join(', ') }}</small>
            </span>
            <code>{{ srv.status }}</code>
          </button>
        </div>
      </div>

      <div v-show="activeTab === 'result'" class="tab-panel">
        <div v-if="result" class="summary-grid">
          <div class="summary-card"><span>Status</span><strong :class="result.ok ? 'ok' : 'bad'">{{ result.ok ? 'OK' : 'Failed' }}</strong></div>
          <div class="summary-card"><span>Sent</span><strong>{{ bps(result.summary?.sentBitsPerSecond) }}</strong></div>
          <div class="summary-card"><span>Received</span><strong>{{ bps(result.summary?.receivedBitsPerSecond) }}</strong></div>
          <div class="summary-card"><span>Retransmits</span><strong>{{ result.summary?.retransmits ?? '-' }}</strong></div>
          <div class="summary-card"><span>Jitter</span><strong>{{ result.summary?.jitterMs != null ? result.summary.jitterMs.toFixed(2) + ' ms' : '-' }}</strong></div>
          <div class="summary-card"><span>Loss</span><strong>{{ lossLabel(result.summary) }}</strong></div>
        </div>
        <div v-if="result && result.error" class="callout">{{ result.error }}</div>
        <div v-if="result" class="command-line"><span>Args</span><code>{{ result.command.join(' ') }}</code></div>
        <pre v-if="result && result.rawOutput" class="raw-output">{{ result.rawOutput }}</pre>
        <div v-if="!result" class="empty">No test result yet.</div>
      </div>

      <div v-show="activeTab === 'json'" class="tab-panel">
        <pre class="json-pre">{{ JSON.stringify({ binary, server, discovered, catalog, result }, null, 2) }}</pre>
      </div>
    </section>
  </div>
</template>

<script>
app.component("iperf-page", {
  template: "#iperf-page",
  data() {
    return {
      binary: null,
      server: { running: false },
      serverPort: 5201,
      publishMdns: true,
      serverBusy: false,
      targetHost: "",
      targetPort: 5201,
      protocol: "tcp",
      ipVersion: "auto",
      durationSeconds: 10,
      parallel: 1,
      bitrate: "10M",
      reverse: false,
      runningClient: false,
      loadingStatus: false,
      discovering: false,
      discovered: [],
      catalog: null,
      result: null,
      clientJob: null,
      error: "",
      activeTab: "targets",
    }
  },
  computed: {
    publicServers() {
      return this.catalog?.servers || []
    },
  },
  mounted() {
    this.refreshAll()
  },
  methods: {
    async refreshAll() {
      await Promise.all([this.loadStatus(), this.loadServer(), this.loadCatalog()])
    },
    async loadStatus() {
      this.loadingStatus = true
      try {
        const r = await fetch("/api/iperf/status")
        this.binary = await r.json()
      } finally {
        this.loadingStatus = false
      }
    },
    async loadServer() {
      const r = await fetch("/api/iperf/server/status")
      this.server = await r.json()
    },
    async loadCatalog() {
      const r = await fetch("/api/iperf/public-servers")
      this.catalog = await r.json()
    },
    async startServer() {
      this.serverBusy = true
      this.error = ""
      try {
        const r = await fetch("/api/iperf/server/start", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ port: this.serverPort, publishMdns: this.publishMdns }),
        })
        if (!r.ok) throw new Error(await r.text())
        this.server = await r.json()
        return this.server
      } catch (e) {
        this.error = e instanceof Error ? e.message : "Failed to start server"
        return null
      } finally {
        this.serverBusy = false
      }
    },
    async stopServer() {
      this.serverBusy = true
      try {
        const r = await fetch("/api/iperf/server/stop", { method: "POST" })
        this.server = await r.json()
      } finally {
        this.serverBusy = false
      }
    },
    async discover() {
      this.discovering = true
      this.error = ""
      try {
        const r = await fetch("/api/iperf/discover?timeoutMs=3000")
        if (!r.ok) throw new Error(await r.text())
        const payload = await r.json()
        this.discovered = payload.services || []
      } catch (e) {
        this.error = e instanceof Error ? e.message : "Discovery failed"
      } finally {
        this.discovering = false
      }
    },
    selectTarget(host, port) {
      this.targetHost = host
      this.targetPort = port
      this.activeTab = "result"
    },
    selectPublicTarget(server) {
      const range = server.ports?.[0] || { from: 5201, to: 5201 }
      const preferredPort = range.from <= 5201 && range.to >= 5201 ? 5201 : range.from
      if (Array.isArray(server.ipVersions)) {
        if (server.ipVersions.includes("ipv4")) {
          this.ipVersion = "ipv4"
        } else if (server.ipVersions.includes("ipv6")) {
          this.ipVersion = "ipv6"
        }
      }
      this.selectTarget(server.host, preferredPort)
    },
    selectDiscoveredTarget(service) {
      this.selectTarget(this.preferredEndpoint(service).connectHost, service.port)
    },
    async runLocalLoopbackTest() {
      this.error = ""
      await this.loadServer()
      let activeServer = this.server
      if (!activeServer?.running) {
        const previousPublish = this.publishMdns
        this.publishMdns = false
        activeServer = await this.startServer()
        this.publishMdns = previousPublish
      }
      const port = activeServer?.port || this.serverPort || 5201
      this.targetHost = "127.0.0.1"
      this.targetPort = port
      this.ipVersion = "ipv4"
      this.protocol = "tcp"
      this.durationSeconds = Math.min(Number(this.durationSeconds) || 1, 3)
      await this.runClient()
    },
    preferredEndpoint(service) {
      const endpoints = Array.isArray(service?.endpoints) ? service.endpoints : []
      const ipv4 = endpoints.find((endpoint) => endpoint.family === "ipv4")
      const globalIpv6 = endpoints.find((endpoint) => endpoint.family === "ipv6" && !endpoint.linkLocal)
      const scopedLinkLocal = endpoints.find((endpoint) => endpoint.family === "ipv6" && endpoint.linkLocal && String(endpoint.connectHost || "").includes("%"))
      const linkLocal = endpoints.find((endpoint) => endpoint.family === "ipv6" && endpoint.linkLocal)
      if (this.ipVersion === "ipv4" && ipv4) return ipv4
      if (this.ipVersion === "ipv6") return globalIpv6 || scopedLinkLocal || linkLocal || { connectHost: service.host, address: service.host, family: "hostname" }
      return ipv4 || globalIpv6 || scopedLinkLocal || linkLocal || { connectHost: service.host, address: service.host, family: "hostname" }
    },
    endpointLabel(service) {
      const preferred = this.preferredEndpoint(service)
      return preferred.connectHost === service.host ? service.host : `${preferred.connectHost} (${service.host})`
    },
    endpointSummary(service) {
      const endpoints = Array.isArray(service?.endpoints) ? service.endpoints : []
      if (!endpoints.length) return service.host
      return endpoints.map((endpoint) => {
        const scope = endpoint.linkLocal && endpoint.interfaceName ? `%${endpoint.interfaceName}${endpoint.scopeId ? `/#${endpoint.scopeId}` : ""}` : ""
        return `${endpoint.address}${scope}`
      }).join(", ")
    },
    async runClient() {
      this.runningClient = true
      this.error = ""
      this.result = null
      this.clientJob = null
      this.activeTab = "result"
      try {
        const r = await fetch("/api/iperf/client/start", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            host: this.targetHost,
            port: this.targetPort,
            protocol: this.protocol,
            ipVersion: this.ipVersion,
            durationSeconds: this.durationSeconds,
            parallel: this.parallel,
            reverse: this.reverse,
            bitrate: this.protocol === "udp" ? this.bitrate : "",
          }),
        })
        if (!r.ok) throw new Error(await r.text())
        this.clientJob = await r.json()
        await this.pollClientJob(this.clientJob.id)
      } catch (e) {
        this.error = e instanceof Error ? e.message : "iperf test failed"
      } finally {
        this.runningClient = false
      }
    },
    async pollClientJob(id) {
      for (let attempt = 0; attempt < 180; attempt += 1) {
        const r = await fetch(`/api/iperf/client/jobs/${encodeURIComponent(id)}`)
        if (!r.ok) throw new Error(await r.text())
        const job = await r.json()
        this.clientJob = job
        if (job.status === "completed" || job.status === "failed") {
          this.result = job.result || {
            ok: false,
            command: [],
            exitCode: null,
            elapsedMs: 0,
            summary: null,
            rawJson: null,
            rawOutput: null,
            error: job.error || "iperf client job failed",
          }
          return
        }
        await new Promise((resolve) => setTimeout(resolve, 500))
      }
      throw new Error("Timed out waiting for iperf client job")
    },
    bps(value) {
      const n = Number(value)
      if (!Number.isFinite(n) || n <= 0) return "-"
      const units = ["bps", "Kbps", "Mbps", "Gbps"]
      let v = n
      let idx = 0
      while (v >= 1000 && idx < units.length - 1) {
        v /= 1000
        idx += 1
      }
      return `${v.toFixed(idx === 0 ? 0 : 2)} ${units[idx]}`
    },
    lossLabel(summary) {
      if (!summary || summary.lostPercent == null) return "-"
      const lost = summary.lostPackets ?? 0
      const total = summary.totalPackets ?? 0
      return `${summary.lostPercent.toFixed(2)}% (${lost}/${total})`
    },
    portLabel(server) {
      const range = server.ports?.[0]
      if (!range) return "5201"
      return range.from === range.to ? String(range.from) : `${range.from}-${range.to}`
    },
  },
})
</script>

<style>
.iperf-page { padding: 1rem 0; }
.iperf-page .page-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 1rem; margin-bottom: 1rem; }
.iperf-page h2 { margin-bottom: 0.25rem; }
.iperf-page .desc { color: #666; font-size: 0.9rem; }
.iperf-page .binary-strip { display: flex; align-items: center; gap: 0.75rem; flex-wrap: wrap; border: 1px solid #bbf7d0; background: #f0fdf4; color: #14532d; border-radius: 8px; padding: 0.65rem 0.8rem; margin-bottom: 1rem; font-size: 0.85rem; }
.iperf-page .binary-strip.bad { border-color: #fecaca; background: #fef2f2; color: #991b1b; }
.iperf-page .binary-strip code { color: inherit; opacity: 0.8; overflow-wrap: anywhere; }
.iperf-page .grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 1rem; margin-bottom: 1rem; }
.iperf-page .panel { background: #fff; border: 1px solid #e5e7eb; border-radius: 8px; overflow: hidden; padding: 1rem; }
.iperf-page .panel-title,
.iperf-page .section-title { font-size: 0.78rem; text-transform: uppercase; letter-spacing: 0.06em; color: #64748b; font-weight: 700; margin-bottom: 0.65rem; }
.iperf-page .server-state { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0.5rem; margin-bottom: 0.75rem; }
.iperf-page .server-state div,
.iperf-page .summary-card { border: 1px solid #e5e7eb; border-radius: 8px; padding: 0.55rem 0.65rem; }
.iperf-page .server-state span,
.iperf-page .summary-card span { display: block; font-size: 0.72rem; color: #64748b; margin-bottom: 0.2rem; }
.iperf-page .server-state strong,
.iperf-page .summary-card strong { display: block; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 0.92rem; color: #0f172a; }
.iperf-page .server-state .mdns-state strong { font-size: 0.86rem; }
.iperf-page .ok { color: #166534 !important; }
.iperf-page .bad { color: #b91c1c !important; }
.iperf-page .muted { color: #64748b !important; }
.iperf-page .row-form,
.iperf-page .client-form { display: flex; gap: 0.5rem; flex-wrap: wrap; align-items: center; }
.iperf-page .input { padding: 0.55rem 0.65rem; border: 1px solid #d1d5db; border-radius: 6px; min-width: 120px; min-height: 36px; }
.iperf-page .host { flex: 1; min-width: 220px; }
.iperf-page .port { width: 110px; min-width: 100px; }
.iperf-page .small { width: 92px; min-width: 86px; }
.iperf-page .mode { width: 110px; min-width: 104px; }
.iperf-page .ip-version { width: 116px; min-width: 108px; }
.iperf-page .bitrate { width: 100px; min-width: 90px; }
.iperf-page .check { display: inline-flex; align-items: center; gap: 0.35rem; color: #334155; font-size: 0.85rem; white-space: nowrap; }
.iperf-page .btn { border: 0; background: #0f172a; color: #fff; border-radius: 6px; padding: 0.55rem 0.95rem; cursor: pointer; }
.iperf-page .btn.secondary { background: #f8fafc; color: #0f172a; border: 1px solid #cbd5e1; }
.iperf-page .btn.stop { background: #991b1b; }
.iperf-page .btn:disabled { opacity: .6; cursor: default; }
.iperf-page .mini-log { margin: 0.75rem 0 0; max-height: 120px; overflow: auto; background: #0f172a; color: #e2e8f0; border-radius: 6px; padding: 0.6rem; font-size: 0.78rem; }
.iperf-page .job-state { margin-top: 0.75rem; color: #475569; font-size: 0.84rem; }
.iperf-page .error,
.iperf-page .callout { margin-top: 0.75rem; border-radius: 6px; padding: 0.55rem 0.65rem; font-size: 0.83rem; background: #fef2f2; color: #991b1b; border: 1px solid #fecaca; }
.iperf-page .tabs { display: flex; border-bottom: 1px solid #e5e7eb; background: #f8fafc; margin: -1rem -1rem 1rem; }
.iperf-page .tab { border: none; background: none; padding: 0.65rem 0.95rem; cursor: pointer; color: #64748b; font-size: 0.84rem; border-bottom: 2px solid transparent; margin-bottom: -1px; }
.iperf-page .tab.active { color: #111827; font-weight: 600; border-bottom-color: #111827; }
.iperf-page .target-head { display: flex; align-items: center; justify-content: space-between; gap: 1rem; margin-bottom: 0.6rem; }
.iperf-page .public-head { margin-top: 1.1rem; }
.iperf-page .catalog-note { color: #64748b; font-size: 0.78rem; margin: -0.45rem 0 0; }
.iperf-page .target-list { display: grid; gap: 0.45rem; }
.iperf-page .public-list { grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); }
.iperf-page .target-row { border: 1px solid #e5e7eb; border-radius: 8px; background: #fff; padding: 0.6rem 0.7rem; display: flex; justify-content: space-between; gap: 1rem; text-align: left; cursor: pointer; align-items: center; }
.iperf-page .target-row:hover { border-color: #94a3b8; background: #f8fafc; }
.iperf-page .target-row strong { display: block; color: #0f172a; font-size: 0.9rem; }
.iperf-page .target-row small { display: block; color: #64748b; font-size: 0.78rem; margin-top: 0.15rem; }
.iperf-page .target-row code { color: #334155; white-space: nowrap; }
.iperf-page .empty { color: #64748b; font-size: 0.9rem; padding: 0.75rem 0; }
.iperf-page .summary-grid { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: 0.5rem; }
.iperf-page .command-line { margin-top: 0.85rem; display: flex; gap: 0.75rem; align-items: baseline; flex-wrap: wrap; color: #64748b; font-size: 0.85rem; }
.iperf-page .command-line code { background: #f1f5f9; color: #0f172a; border-radius: 6px; padding: 0.4rem 0.5rem; overflow-wrap: anywhere; }
.iperf-page .raw-output { margin: 0.85rem 0 0; background: #fff7ed; color: #7c2d12; border: 1px solid #fed7aa; border-radius: 6px; padding: 0.7rem; overflow-x: auto; font-size: 0.8rem; white-space: pre-wrap; }
.iperf-page .json-pre { margin: 0; background: #0f172a; color: #e2e8f0; border-radius: 6px; padding: 0.8rem; overflow-x: auto; font-size: 0.8rem; }
@media (max-width: 1100px) {
  .iperf-page .grid { grid-template-columns: 1fr; }
  .iperf-page .summary-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}
@media (max-width: 700px) {
  .iperf-page .page-head { flex-direction: column; }
  .iperf-page .server-state,
  .iperf-page .summary-grid { grid-template-columns: 1fr; }
}
</style>
