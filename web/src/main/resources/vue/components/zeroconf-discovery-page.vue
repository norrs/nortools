<template id="zeroconf-discovery-page">
  <div class="tool-view zeroconf-page">
    <h2>ZeroConf Discovery</h2>
    <p class="desc">Inspect local name and service discovery protocols as they are added to NorTools.</p>

    <section class="control-band">
      <div class="field">
        <label>Protocol</label>
        <select v-model="protocol" class="input">
          <option value="netbios">NetBIOS Name Service</option>
          <option value="mdns" disabled>mDNS / DNS-SD</option>
          <option value="llmnr" disabled>LLMNR</option>
          <option value="ssdp" disabled>SSDP / UPnP</option>
          <option value="wsd" disabled>WS-Discovery</option>
        </select>
      </div>

      <div class="field">
        <label>Mode</label>
        <select v-model="mode" class="input">
          <option value="query">Query</option>
          <option value="node-status">Node Status</option>
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

      <div v-if="mode === 'listen'" class="field field-small">
        <label>Packets</label>
        <input v-model.number="maxPackets" class="input" type="number" min="1" max="250" />
      </div>

      <div v-if="mode === 'listen'" class="field">
        <label>Bind Address</label>
        <select v-model="bindAddress" class="input">
          <option value="0.0.0.0">Any IPv4</option>
          <option v-for="choice in bindChoices" :key="choice.value" :value="choice.value">
            {{ choice.label }}
          </option>
        </select>
      </div>
    </section>

    <form @submit.prevent="run" class="lookup-form">
      <input
        v-if="mode === 'query'"
        v-model.trim="name"
        class="input target-input"
        placeholder="MYPC"
      />
      <input
        v-if="mode === 'query'"
        v-model.trim="target"
        class="input target-input"
        placeholder="255.255.255.255"
      />
      <input
        v-if="mode === 'query'"
        v-model.number="suffix"
        class="input input-sm"
        type="number"
        min="0"
        max="255"
      />
      <input
        v-if="mode === 'node-status'"
        v-model.trim="host"
        class="input target-input"
        placeholder="192.168.1.25"
      />
      <button class="btn" :disabled="loading || !canRun">{{ loading ? 'Running...' : actionLabel }}</button>
    </form>

    <div v-if="error" class="error">{{ error }}</div>

    <div v-if="result" class="result-wrap">
      <div class="tabs">
        <button class="tab" :class="{ active: activeTab === 'report' }" @click="activeTab = 'report'">Report</button>
        <button class="tab" :class="{ active: activeTab === 'json' }" @click="activeTab = 'json'">JSON</button>
      </div>

      <div v-show="activeTab === 'report'" class="tab-panel">
        <div class="summary-grid">
          <div class="summary-card"><span>Protocol</span><strong>{{ result.protocol }}</strong></div>
          <div class="summary-card"><span>Status</span><strong :class="statusClass">{{ result.status }}</strong></div>
          <div class="summary-card"><span>Responses</span><strong>{{ result.responseCount || 0 }}</strong></div>
        </div>

        <div v-if="result.reason" class="callout">{{ result.reason }}</div>
        <div v-if="result.error" class="callout bad-callout">{{ result.error }}</div>

        <table v-if="rows.length" class="result-table">
          <thead>
            <tr>
              <th>Source</th>
              <th>Type</th>
              <th>Name</th>
              <th>Suffix</th>
              <th>Address</th>
              <th>Group</th>
              <th>Result</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, idx) in rows" :key="idx">
              <td>{{ row.source }}</td>
              <td>{{ row.type }}</td>
              <td>{{ row.name }}</td>
              <td>{{ row.suffix }}</td>
              <td>{{ row.address }}</td>
              <td>{{ row.group }}</td>
              <td>{{ row.result }}</td>
            </tr>
          </tbody>
        </table>

        <div v-else class="empty-state">No decoded NetBIOS rows.</div>
      </div>

      <div v-show="activeTab === 'json'" class="tab-panel">
        <pre class="json-pre">{{ JSON.stringify(result, null, 2) }}</pre>
      </div>
    </div>
  </div>
</template>

<script>
app.component("zeroconf-discovery-page", {
  template: "#zeroconf-discovery-page",
  data() {
    return {
      protocol: "netbios",
      mode: "query",
      ipFamily: "ipv4",
      timeout: 5,
      maxPackets: 25,
      bindAddress: "0.0.0.0",
      bindChoices: [],
      name: "",
      target: "255.255.255.255",
      suffix: 32,
      host: "",
      loading: false,
      error: "",
      result: null,
      activeTab: "report",
    }
  },
  mounted() {
    this.loadBindChoices()
  },
  computed: {
    canRun() {
      if (this.mode === "query") return this.name.length > 0
      if (this.mode === "node-status") return this.host.length > 0
      return this.mode === "listen"
    },
    actionLabel() {
      if (this.mode === "listen") return "Start Listener"
      if (this.mode === "node-status") return "Read Node Status"
      return "Query Name"
    },
    rows() {
      return Array.isArray(this.result?.rows) ? this.result.rows : []
    },
    statusClass() {
      return this.result?.status === "ok" ? "ok" : "warn"
    },
  },
  methods: {
    async run() {
      this.loading = true
      this.error = ""
      this.result = null
      try {
        const params = new URLSearchParams()
        params.set("ipFamily", this.ipFamily)
        params.set("timeout", String(this.timeout || 5))
        let url = ""
        if (this.mode === "query") {
          params.set("target", this.target || "255.255.255.255")
          params.set("suffix", String(this.suffix ?? 32))
          url = `/api/zeroconf/netbios/query/${encodeURIComponent(this.name)}?${params}`
        } else if (this.mode === "node-status") {
          url = `/api/zeroconf/netbios/node-status/${encodeURIComponent(this.host)}?${params}`
        } else {
          params.set("maxPackets", String(this.maxPackets || 25))
          params.set("bindAddress", this.bindAddress || "0.0.0.0")
          url = `/api/zeroconf/netbios/listen?${params}`
        }
        const r = await fetch(url)
        if (!r.ok) throw new Error(`API error: ${r.status} ${r.statusText}`)
        this.result = await r.json()
        this.activeTab = "report"
      } catch (e) {
        this.error = e instanceof Error ? e.message : "Unexpected error"
      } finally {
        this.loading = false
      }
    },
    async loadBindChoices() {
      try {
        const r = await fetch("/api/network-interfaces")
        if (!r.ok) return
        const snapshot = await r.json()
        const choices = []
        for (const iface of snapshot.interfaces || []) {
          for (const address of iface.addresses || []) {
            if (address.family !== "IPv4") continue
            choices.push({
              value: address.ip,
              label: `${iface.displayName || iface.name} - ${address.ip}`,
            })
          }
        }
        this.bindChoices = choices
      } catch (_) {
        this.bindChoices = []
      }
    },
  },
})
</script>

<style>
.zeroconf-page { padding: 1rem 0; }
.zeroconf-page .desc { color: #666; font-size: 0.9rem; margin-bottom: 1rem; }
.zeroconf-page .control-band { display: grid; grid-template-columns: repeat(5, minmax(120px, 1fr)); gap: 0.65rem; margin-bottom: 0.8rem; }
.zeroconf-page .field { display: flex; flex-direction: column; gap: 0.25rem; }
.zeroconf-page label { font-size: 0.74rem; color: #64748b; font-weight: 600; }
.zeroconf-page .lookup-form { display: flex; gap: 0.5rem; margin-bottom: 1rem; flex-wrap: wrap; }
.zeroconf-page .input { width: 100%; padding: 0.55rem 0.65rem; border: 1px solid #d1d5db; border-radius: 6px; background: #fff; }
.zeroconf-page .target-input { flex: 1; min-width: 220px; }
.zeroconf-page .input-sm { flex: 0 0 110px; }
.zeroconf-page .btn { border: 0; background: #0f172a; color: #fff; border-radius: 6px; padding: 0.55rem 0.95rem; cursor: pointer; }
.zeroconf-page .btn:disabled { opacity: .6; cursor: default; }
.zeroconf-page .error { color: #b91c1c; margin-bottom: 0.7rem; }
.zeroconf-page .result-wrap { background: #fff; border: 1px solid #e5e7eb; border-radius: 8px; overflow: hidden; }
.zeroconf-page .tabs { display: flex; border-bottom: 1px solid #e5e7eb; background: #f8fafc; }
.zeroconf-page .tab { border: none; background: none; padding: 0.55rem 0.9rem; cursor: pointer; color: #64748b; font-size: 0.82rem; border-bottom: 2px solid transparent; margin-bottom: -1px; }
.zeroconf-page .tab.active { color: #111827; font-weight: 600; border-bottom-color: #111827; }
.zeroconf-page .tab-panel { padding: 0.9rem 1rem; }
.zeroconf-page .summary-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0.5rem; margin-bottom: 0.8rem; }
.zeroconf-page .summary-card { border: 1px solid #e5e7eb; border-radius: 8px; padding: 0.55rem 0.65rem; display: flex; justify-content: space-between; align-items: baseline; gap: 0.6rem; }
.zeroconf-page .summary-card span { font-size: 0.74rem; color: #64748b; }
.zeroconf-page .summary-card strong { font-size: 0.92rem; color: #0f172a; }
.zeroconf-page .ok { color: #166534; }
.zeroconf-page .warn { color: #92400e; }
.zeroconf-page .callout { margin-bottom: 0.75rem; border-radius: 6px; padding: 0.55rem 0.65rem; font-size: 0.83rem; background: #fff7ed; color: #92400e; border: 1px solid #fed7aa; }
.zeroconf-page .bad-callout { background: #fef2f2; color: #991b1b; border-color: #fecaca; }
.zeroconf-page .result-table { width: 100%; border-collapse: collapse; font-size: 0.82rem; }
.zeroconf-page .result-table th, .zeroconf-page .result-table td { border-bottom: 1px solid #e5e7eb; padding: 0.48rem 0.45rem; text-align: left; }
.zeroconf-page .result-table th { color: #475569; background: #f8fafc; font-weight: 600; }
.zeroconf-page .empty-state { color: #64748b; font-size: 0.86rem; padding: 0.5rem 0; }
.zeroconf-page .json-pre { margin: 0; background: #0f172a; color: #e2e8f0; border-radius: 6px; padding: 0.8rem; overflow-x: auto; font-size: 0.8rem; }
@media (max-width: 900px) {
  .zeroconf-page .control-band { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .zeroconf-page .summary-grid { grid-template-columns: 1fr; }
}
</style>
