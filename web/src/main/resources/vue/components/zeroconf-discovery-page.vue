<template id="zeroconf-discovery-page">
  <div class="tool-view zeroconf-page">
    <div class="page-head">
      <div>
        <h2>ZeroConf Discovery</h2>
        <p class="desc">Live local discovery inventory from mDNS, LLMNR, NetBIOS, SSDP, and WS-Discovery.</p>
      </div>
      <div class="head-actions">
        <button class="btn btn-secondary" @click="loadSnapshot" :disabled="loading">Refresh View</button>
        <button class="btn" @click="forceRefresh" :disabled="refreshing">{{ refreshing ? 'Scanning...' : 'Scan Now' }}</button>
      </div>
    </div>

    <section class="summary-grid">
      <div class="summary-card"><span>Devices</span><strong>{{ snapshot?.deviceCount || 0 }}</strong></div>
      <div class="summary-card"><span>Services</span><strong>{{ snapshot?.serviceCount || 0 }}</strong></div>
      <div class="summary-card"><span>Status</span><strong :class="snapshot?.scanning ? 'warn' : 'ok'">{{ snapshot?.scanning ? 'Scanning' : 'Listening' }}</strong></div>
      <div class="summary-card"><span>Updated</span><strong>{{ shortTime(snapshot?.generatedAt) }}</strong></div>
    </section>

    <section class="protocol-strip">
      <div v-for="stat in protocolStats" :key="stat.protocol" class="protocol-pill" :class="stat.status">
        <strong>{{ stat.protocol }}</strong>
        <span>{{ stat.observations }} observations</span>
      </div>
    </section>

    <div v-if="error" class="callout bad-callout">{{ error }}</div>
    <div v-for="warning in visibleWarnings" :key="warning" class="callout">{{ warning }}</div>

    <section class="filter-band">
      <input v-model.trim="filter" class="input search-input" placeholder="Filter devices, services, addresses, protocols" />
      <select v-model="categoryFilter" class="input select-input">
        <option value="">All categories</option>
        <option v-for="category in categories" :key="category" :value="category">{{ category }}</option>
      </select>
      <select v-model="protocolFilter" class="input select-input">
        <option value="">All protocols</option>
        <option v-for="protocol in protocols" :key="protocol" :value="protocol">{{ protocol }}</option>
      </select>
    </section>

    <section class="inventory-layout">
      <div class="device-list">
        <button
          v-for="device in filteredDevices"
          :key="device.id"
          class="device-row"
          :class="{ active: selectedDevice?.id === device.id }"
          @click="selectedId = device.id"
        >
          <div class="device-main">
            <strong>{{ device.displayName }}</strong>
            <span>{{ device.category }}</span>
          </div>
          <div class="device-meta">
            <span>{{ device.protocols.join(', ') }}</span>
            <span>{{ device.addresses[0] || device.locations[0] || 'No address yet' }}</span>
          </div>
          <div class="confidence" :class="device.confidence">{{ device.confidence }}</div>
        </button>
        <div v-if="!filteredDevices.length" class="empty-state">No devices discovered yet. Leave the page open or run Scan Now.</div>
      </div>

      <aside class="detail-panel">
        <template v-if="selectedDevice">
          <div class="detail-head">
            <h3>{{ selectedDevice.displayName }}</h3>
            <span>{{ selectedDevice.category }}</span>
          </div>
          <div class="tag-row">
            <span v-for="protocol in selectedDevice.protocols" :key="protocol" class="tag">{{ protocol }}</span>
          </div>

          <div class="detail-section">
            <h4>Addresses</h4>
            <p v-if="!selectedDevice.addresses.length" class="muted">No address evidence yet.</p>
            <code v-for="address in selectedDevice.addresses" :key="address">{{ address }}</code>
          </div>

          <div class="detail-section">
            <h4>Services</h4>
            <table v-if="selectedDevice.services.length" class="mini-table">
              <thead><tr><th>Protocol</th><th>Type</th><th>Name</th><th>Target</th></tr></thead>
              <tbody>
                <tr v-for="(service, idx) in selectedDevice.services" :key="idx">
                  <td>{{ service.protocol }}</td>
                  <td>{{ service.type }}</td>
                  <td>{{ service.name }}</td>
                  <td>{{ service.target || service.location }}</td>
                </tr>
              </tbody>
            </table>
            <p v-else class="muted">No decoded services yet.</p>
          </div>

          <div class="detail-section">
            <h4>Locations</h4>
            <code v-for="location in selectedDevice.locations" :key="location">{{ location }}</code>
            <p v-if="!selectedDevice.locations.length" class="muted">No metadata URL observed.</p>
          </div>

          <div class="detail-section">
            <h4>Evidence</h4>
            <div class="kv"><span>First seen</span><strong>{{ shortTime(selectedDevice.firstSeen) }}</strong></div>
            <div class="kv"><span>Last seen</span><strong>{{ shortTime(selectedDevice.lastSeen) }}</strong></div>
            <div class="kv"><span>Evidence count</span><strong>{{ selectedDevice.evidenceCount }}</strong></div>
          </div>
        </template>
        <div v-else class="empty-state">Select a discovered device to inspect protocol evidence.</div>
      </aside>
    </section>

    <section class="events-panel">
      <div class="panel-title">
        <h3>Recent Discovery Events</h3>
        <button class="link-button" @click="showAdvanced = !showAdvanced">{{ showAdvanced ? 'Hide' : 'Show' }} advanced tools</button>
      </div>
      <table v-if="events.length" class="result-table">
        <thead><tr><th>Time</th><th>Protocol</th><th>Observation</th></tr></thead>
        <tbody>
          <tr v-for="event in events" :key="event.seenAt + event.summary">
            <td>{{ shortTime(event.seenAt) }}</td>
            <td>{{ event.protocol }}</td>
            <td>{{ event.summary }}</td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty-state">No events recorded yet.</div>
    </section>

    <section v-if="showAdvanced" class="advanced-panel">
      <div class="panel-title"><h3>Manual Protocol Probe</h3></div>
      <div class="control-band">
        <div class="field">
          <label>Protocol</label>
          <select v-model="protocol" class="input">
            <option value="netbios">NetBIOS Name Service</option>
            <option value="mdns">mDNS / DNS-SD</option>
            <option value="llmnr">LLMNR</option>
            <option value="ssdp">SSDP / UPnP</option>
            <option value="wsd">WS-Discovery</option>
          </select>
        </div>
        <div class="field">
          <label>Mode</label>
          <select v-model="mode" class="input">
            <option value="query">Query</option>
            <option value="node-status" :disabled="protocol !== 'netbios'">Node Status</option>
            <option value="listen">Listen</option>
          </select>
        </div>
        <div class="field">
          <label>IP Family</label>
          <select v-model="ipFamily" class="input">
            <option value="ipv4">IPv4</option>
            <option value="ipv6">IPv6</option>
            <option value="both">Both</option>
          </select>
        </div>
        <div class="field field-small">
          <label>Timeout</label>
          <input v-model.number="timeout" class="input" type="number" min="1" max="60" />
        </div>
      </div>

      <form @submit.prevent="runManual" class="lookup-form">
        <input v-if="mode === 'query'" v-model.trim="name" class="input target-input" :placeholder="queryPlaceholder" />
        <input v-if="mode === 'query' && protocol === 'netbios'" v-model.trim="target" class="input target-input" placeholder="255.255.255.255" />
        <input v-if="mode === 'query' && protocol === 'netbios'" v-model.number="suffix" class="input input-sm" type="number" min="0" max="255" />
        <input v-if="mode === 'query' && (protocol === 'mdns' || protocol === 'llmnr')" v-model.trim="recordType" class="input input-sm" :placeholder="protocol === 'llmnr' ? 'A' : 'PTR'" />
        <input v-if="mode === 'query' && protocol === 'wsd'" v-model.trim="scopeFilter" class="input target-input" placeholder="Scopes (optional)" />
        <input v-if="mode === 'node-status' && protocol === 'netbios'" v-model.trim="host" class="input target-input" placeholder="192.168.1.25" />
        <button class="btn" :disabled="manualLoading || !canRun">{{ manualLoading ? 'Running...' : actionLabel }}</button>
      </form>

      <pre v-if="manualResult" class="json-pre">{{ JSON.stringify(manualResult, null, 2) }}</pre>
    </section>
  </div>
</template>

<script>
app.component("zeroconf-discovery-page", {
  template: "#zeroconf-discovery-page",
  data() {
    return {
      snapshot: null,
      loading: false,
      refreshing: false,
      error: "",
      poller: null,
      selectedId: "",
      filter: "",
      categoryFilter: "",
      protocolFilter: "",
      showAdvanced: false,
      protocol: "mdns",
      mode: "query",
      ipFamily: "ipv4",
      timeout: 5,
      maxPackets: 25,
      bindAddress: "0.0.0.0",
      name: "",
      recordType: "PTR",
      scopeFilter: "",
      target: "255.255.255.255",
      suffix: 32,
      host: "",
      manualLoading: false,
      manualResult: null,
    }
  },
  mounted() {
    this.loadSnapshot()
    this.poller = setInterval(this.loadSnapshot, 5000)
  },
  unmounted() {
    if (this.poller) clearInterval(this.poller)
  },
  computed: {
    devices() {
      return Array.isArray(this.snapshot?.devices) ? this.snapshot.devices : []
    },
    events() {
      return Array.isArray(this.snapshot?.events) ? this.snapshot.events : []
    },
    protocolStats() {
      const stats = Array.isArray(this.snapshot?.protocolStats) ? this.snapshot.protocolStats : []
      const known = ["mDNS", "LLMNR", "NetBIOS", "SSDP", "WS-Discovery"]
      return known.map(protocol => stats.find(s => s.protocol === protocol) || { protocol, status: "idle", observations: 0 })
    },
    visibleWarnings() {
      return (this.snapshot?.warnings || []).slice(0, 3)
    },
    categories() {
      return [...new Set(this.devices.map(d => d.category).filter(Boolean))].sort()
    },
    protocols() {
      return [...new Set(this.devices.flatMap(d => d.protocols || []))].sort()
    },
    filteredDevices() {
      const term = this.filter.toLowerCase()
      return this.devices.filter(device => {
        const blob = JSON.stringify(device).toLowerCase()
        return (!term || blob.includes(term)) &&
          (!this.categoryFilter || device.category === this.categoryFilter) &&
          (!this.protocolFilter || (device.protocols || []).includes(this.protocolFilter))
      })
    },
    selectedDevice() {
      if (!this.filteredDevices.length) return null
      return this.filteredDevices.find(d => d.id === this.selectedId) || this.filteredDevices[0]
    },
    canRun() {
      if (this.mode === "query") return this.protocol === "wsd" || this.name.length > 0
      if (this.mode === "node-status") return this.protocol === "netbios" && this.host.length > 0
      return this.mode === "listen"
    },
    queryPlaceholder() {
      if (this.protocol === "mdns") return "_services._dns-sd._udp.local"
      if (this.protocol === "llmnr") return "printer.local"
      if (this.protocol === "ssdp") return "ssdp:all"
      if (this.protocol === "wsd") return "wsdp:Device or dn:NetworkVideoTransmitter"
      return "MYPC"
    },
    actionLabel() {
      if (this.mode === "listen") return "Start Listener"
      if (this.mode === "node-status") return "Read Node Status"
      if (this.protocol === "ssdp") return "Search Services"
      if (this.protocol === "wsd") return "Probe Devices"
      return "Query Name"
    },
  },
  methods: {
    async loadSnapshot() {
      this.loading = true
      try {
        const r = await fetch("/api/zeroconf/dashboard")
        if (!r.ok) throw new Error(`Dashboard error: ${r.status}`)
        this.snapshot = await r.json()
        this.error = ""
      } catch (e) {
        this.error = e instanceof Error ? e.message : "Could not load discovery dashboard"
      } finally {
        this.loading = false
      }
    },
    async forceRefresh() {
      this.refreshing = true
      try {
        const r = await fetch("/api/zeroconf/dashboard/refresh")
        if (!r.ok) throw new Error(`Refresh error: ${r.status}`)
        this.snapshot = await r.json()
        setTimeout(this.loadSnapshot, 2500)
      } catch (e) {
        this.error = e instanceof Error ? e.message : "Could not start discovery scan"
      } finally {
        this.refreshing = false
      }
    },
    shortTime(value) {
      if (!value) return "-"
      try { return new Date(value).toLocaleTimeString() } catch (_) { return value }
    },
    async runManual() {
      this.manualLoading = true
      this.manualResult = null
      try {
        const params = new URLSearchParams()
        params.set("ipFamily", this.ipFamily)
        params.set("timeout", String(this.timeout || 5))
        let url = ""
        if (this.protocol === "mdns" && this.mode === "query") {
          params.set("type", this.recordType || "PTR")
          url = `/api/zeroconf/mdns/query/${encodeURIComponent(this.name)}?${params}`
        } else if (this.protocol === "llmnr" && this.mode === "query") {
          params.set("type", this.recordType || "A")
          url = `/api/zeroconf/llmnr/query/${encodeURIComponent(this.name)}?${params}`
        } else if (this.protocol === "llmnr" && this.mode === "listen") {
          url = `/api/zeroconf/llmnr/listen?${params}`
        } else if (this.protocol === "ssdp" && this.mode === "query") {
          params.set("searchTarget", this.name || "ssdp:all")
          url = `/api/zeroconf/ssdp/search?${params}`
        } else if (this.protocol === "ssdp" && this.mode === "listen") {
          url = `/api/zeroconf/ssdp/listen?${params}`
        } else if (this.protocol === "wsd" && this.mode === "query") {
          if (this.name) params.set("types", this.name)
          if (this.scopeFilter) params.set("scopes", this.scopeFilter)
          url = `/api/zeroconf/wsd/probe?${params}`
        } else if (this.protocol === "wsd" && this.mode === "listen") {
          url = `/api/zeroconf/wsd/listen?${params}`
        } else if (this.protocol === "mdns" && this.mode === "listen") {
          url = `/api/zeroconf/mdns/listen?${params}`
        } else if (this.mode === "query") {
          params.set("target", this.target || "255.255.255.255")
          params.set("suffix", String(this.suffix ?? 32))
          url = `/api/zeroconf/netbios/query/${encodeURIComponent(this.name)}?${params}`
        } else if (this.mode === "node-status") {
          url = `/api/zeroconf/netbios/node-status/${encodeURIComponent(this.host)}?${params}`
        } else {
          url = `/api/zeroconf/netbios/listen?${params}`
        }
        const r = await fetch(url)
        if (!r.ok) throw new Error(`API error: ${r.status} ${r.statusText}`)
        this.manualResult = await r.json()
      } catch (e) {
        this.manualResult = { status: "error", error: e instanceof Error ? e.message : "Unexpected error" }
      } finally {
        this.manualLoading = false
      }
    },
  },
})
</script>

<style>
.zeroconf-page { padding: 1rem 0; color: #111827; }
.zeroconf-page .page-head { display: flex; justify-content: space-between; gap: 1rem; align-items: flex-start; margin-bottom: 1rem; }
.zeroconf-page .desc { color: #64748b; font-size: 0.9rem; margin: 0.25rem 0 0; }
.zeroconf-page .head-actions { display: flex; gap: 0.5rem; flex-wrap: wrap; justify-content: flex-end; }
.zeroconf-page .btn { border: 0; background: #0f172a; color: #fff; border-radius: 6px; padding: 0.55rem 0.95rem; cursor: pointer; }
.zeroconf-page .btn-secondary { background: #e5e7eb; color: #111827; }
.zeroconf-page .btn:disabled { opacity: .6; cursor: default; }
.zeroconf-page .summary-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 0.6rem; margin-bottom: 0.8rem; }
.zeroconf-page .summary-card { border: 1px solid #e5e7eb; border-radius: 8px; padding: 0.65rem 0.75rem; display: flex; justify-content: space-between; align-items: baseline; gap: 0.6rem; background: #fff; }
.zeroconf-page .summary-card span { font-size: 0.74rem; color: #64748b; font-weight: 600; }
.zeroconf-page .summary-card strong { font-size: 1rem; color: #0f172a; }
.zeroconf-page .ok { color: #166534; }
.zeroconf-page .warn { color: #92400e; }
.zeroconf-page .protocol-strip { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 0.5rem; margin-bottom: 0.8rem; }
.zeroconf-page .protocol-pill { border: 1px solid #e5e7eb; border-left: 4px solid #94a3b8; border-radius: 8px; padding: 0.55rem 0.65rem; background: #fff; display: flex; flex-direction: column; gap: 0.15rem; min-width: 0; }
.zeroconf-page .protocol-pill.ok { border-left-color: #16a34a; }
.zeroconf-page .protocol-pill.warning { border-left-color: #f59e0b; }
.zeroconf-page .protocol-pill strong { font-size: 0.82rem; color: #111827; }
.zeroconf-page .protocol-pill span { font-size: 0.74rem; color: #64748b; }
.zeroconf-page .filter-band { display: grid; grid-template-columns: minmax(220px, 1fr) 180px 180px; gap: 0.55rem; margin-bottom: 0.8rem; }
.zeroconf-page .input { width: 100%; padding: 0.55rem 0.65rem; border: 1px solid #d1d5db; border-radius: 6px; background: #fff; }
.zeroconf-page .inventory-layout { display: grid; grid-template-columns: minmax(320px, 0.95fr) minmax(360px, 1.2fr); gap: 0.8rem; align-items: start; }
.zeroconf-page .device-list { display: flex; flex-direction: column; gap: 0.45rem; }
.zeroconf-page .device-row { border: 1px solid #e5e7eb; border-radius: 8px; padding: 0.65rem; background: #fff; text-align: left; cursor: pointer; display: grid; grid-template-columns: 1fr auto; gap: 0.45rem 0.7rem; }
.zeroconf-page .device-row.active { border-color: #0f172a; box-shadow: inset 3px 0 0 #0f172a; }
.zeroconf-page .device-main { display: flex; flex-direction: column; gap: 0.15rem; min-width: 0; }
.zeroconf-page .device-main strong { font-size: 0.92rem; overflow-wrap: anywhere; }
.zeroconf-page .device-main span, .zeroconf-page .device-meta { color: #64748b; font-size: 0.78rem; }
.zeroconf-page .device-meta { grid-column: 1 / -1; display: flex; justify-content: space-between; gap: 0.6rem; overflow-wrap: anywhere; }
.zeroconf-page .confidence { font-size: 0.7rem; text-transform: uppercase; letter-spacing: 0; border-radius: 999px; padding: 0.18rem 0.45rem; background: #f1f5f9; color: #475569; height: fit-content; }
.zeroconf-page .confidence.high { background: #dcfce7; color: #166534; }
.zeroconf-page .confidence.medium { background: #fef3c7; color: #92400e; }
.zeroconf-page .detail-panel, .zeroconf-page .events-panel, .zeroconf-page .advanced-panel { border: 1px solid #e5e7eb; border-radius: 8px; background: #fff; padding: 0.85rem; }
.zeroconf-page .detail-head { display: flex; justify-content: space-between; gap: 0.75rem; align-items: baseline; margin-bottom: 0.6rem; }
.zeroconf-page h3, .zeroconf-page h4 { margin: 0; }
.zeroconf-page .detail-head h3 { font-size: 1rem; overflow-wrap: anywhere; }
.zeroconf-page .detail-head span, .zeroconf-page .muted { color: #64748b; font-size: 0.82rem; }
.zeroconf-page .tag-row { display: flex; flex-wrap: wrap; gap: 0.35rem; margin-bottom: 0.75rem; }
.zeroconf-page .tag { background: #eef2ff; color: #3730a3; border-radius: 999px; padding: 0.2rem 0.5rem; font-size: 0.74rem; }
.zeroconf-page .detail-section { border-top: 1px solid #e5e7eb; padding-top: 0.7rem; margin-top: 0.7rem; display: flex; flex-direction: column; gap: 0.35rem; }
.zeroconf-page code { display: block; background: #f8fafc; border: 1px solid #e5e7eb; border-radius: 6px; padding: 0.35rem 0.45rem; overflow-wrap: anywhere; }
.zeroconf-page .kv { display: flex; justify-content: space-between; gap: 0.7rem; font-size: 0.82rem; }
.zeroconf-page .kv span { color: #64748b; }
.zeroconf-page .mini-table, .zeroconf-page .result-table { width: 100%; border-collapse: collapse; font-size: 0.8rem; }
.zeroconf-page .mini-table th, .zeroconf-page .mini-table td, .zeroconf-page .result-table th, .zeroconf-page .result-table td { border-bottom: 1px solid #e5e7eb; padding: 0.45rem; text-align: left; vertical-align: top; overflow-wrap: anywhere; }
.zeroconf-page .mini-table th, .zeroconf-page .result-table th { color: #475569; background: #f8fafc; font-weight: 600; }
.zeroconf-page .events-panel, .zeroconf-page .advanced-panel { margin-top: 0.9rem; }
.zeroconf-page .panel-title { display: flex; justify-content: space-between; gap: 1rem; align-items: center; margin-bottom: 0.65rem; }
.zeroconf-page .link-button { border: 0; background: transparent; color: #1d4ed8; cursor: pointer; padding: 0.25rem; }
.zeroconf-page .callout { margin-bottom: 0.75rem; border-radius: 6px; padding: 0.55rem 0.65rem; font-size: 0.83rem; background: #fff7ed; color: #92400e; border: 1px solid #fed7aa; }
.zeroconf-page .bad-callout { background: #fef2f2; color: #991b1b; border-color: #fecaca; }
.zeroconf-page .empty-state { color: #64748b; font-size: 0.86rem; padding: 0.7rem 0; }
.zeroconf-page .control-band { display: grid; grid-template-columns: repeat(4, minmax(120px, 1fr)); gap: 0.65rem; margin-bottom: 0.8rem; }
.zeroconf-page .field { display: flex; flex-direction: column; gap: 0.25rem; }
.zeroconf-page label { font-size: 0.74rem; color: #64748b; font-weight: 600; }
.zeroconf-page .lookup-form { display: flex; gap: 0.5rem; margin-bottom: 1rem; flex-wrap: wrap; }
.zeroconf-page .target-input { flex: 1; min-width: 220px; }
.zeroconf-page .input-sm { flex: 0 0 110px; }
.zeroconf-page .json-pre { margin: 0; background: #0f172a; color: #e2e8f0; border-radius: 6px; padding: 0.8rem; overflow-x: auto; font-size: 0.8rem; }
@media (max-width: 1000px) {
  .zeroconf-page .summary-grid, .zeroconf-page .protocol-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .zeroconf-page .inventory-layout, .zeroconf-page .filter-band { grid-template-columns: 1fr; }
  .zeroconf-page .page-head { flex-direction: column; }
  .zeroconf-page .head-actions { justify-content: flex-start; }
}
</style>
