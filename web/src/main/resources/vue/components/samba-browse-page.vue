<template id="samba-browse-page">
  <div class="tool-view samba-browse-page">
    <h2>Samba Browse</h2>
    <p class="desc">Negotiate SMB and enumerate shares exposed by a Samba or Windows file server.</p>

    <div class="discovery-panel">
      <div class="panel-title">
        <div>
          <h3>Local SMB Discovery</h3>
          <p>Uses the same SMB sweep as ZeroConf Discovery and attempts share enumeration on reachable hosts.</p>
        </div>
        <button class="btn btn-secondary" @click="discover" :disabled="discovering">
          {{ discovering ? 'Scanning...' : 'Scan Now' }}
        </button>
      </div>
      <div v-if="discoverError" class="error">{{ discoverError }}</div>
      <div v-if="discovering && !discoveredHosts.length" class="empty-state">Scanning local private IPv4 networks for TCP 445.</div>
      <div v-else-if="!discoveredHosts.length" class="empty-state">No local SMB hosts discovered yet.</div>
      <div v-else class="host-list">
        <button
          v-for="item in discoveredHosts"
          :key="item.address"
          class="host-row"
          :class="{ active: item.address === host }"
          @click="selectDiscovered(item)"
          type="button"
        >
          <div>
            <strong>{{ item.hostname || item.address }}</strong>
            <span>{{ item.address }}</span>
          </div>
          <div class="host-meta">
            <span v-if="item.browse?.dialect">{{ item.browse.dialect }}</span>
            <span v-if="item.browse?.authenticationStatus">{{ item.browse.authenticationStatus }}</span>
            <span>{{ item.browse?.shares?.length || 0 }} shares</span>
            <span v-if="item.wsdTcpOpen">WSD</span>
          </div>
        </button>
      </div>
    </div>

    <form @submit.prevent="go" class="lookup-form">
      <input v-model.trim="host" class="input target-input" placeholder="server.local or 192.168.1.25" />
      <input v-model.trim="username" class="input input-med" placeholder="username" autocomplete="username" />
      <input v-model="password" class="input input-med" placeholder="password" type="password" autocomplete="current-password" />
      <input v-model.trim="domain" class="input input-sm" placeholder="domain" />
      <input v-model.number="timeout" class="input input-xs" placeholder="10" type="number" min="1" max="60" />
      <button class="btn" :disabled="loading || !host">{{ loading ? 'Browsing...' : 'Browse' }}</button>
    </form>

    <div v-if="error" class="error">{{ error }}</div>

    <div v-if="result" class="result-wrap">
      <div class="tabs">
        <button class="tab" :class="{ active: activeTab === 'report' }" @click="activeTab = 'report'">Report</button>
        <button class="tab" :class="{ active: activeTab === 'json' }" @click="activeTab = 'json'">JSON</button>
      </div>

      <div v-show="activeTab === 'report'" class="tab-panel">
        <div class="summary-grid">
          <div class="summary-card"><span>Host</span><strong>{{ result.host }}</strong></div>
          <div class="summary-card"><span>Dialect</span><strong>{{ result.dialect || '-' }}</strong></div>
          <div class="summary-card"><span>Auth</span><strong>{{ result.authenticationStatus || result.authenticationMode || '-' }}</strong></div>
          <div class="summary-card"><span>Shares</span><strong>{{ result.shares?.length || 0 }}</strong></div>
        </div>

        <div class="security-grid">
          <div><span>Signing</span><strong>{{ signingLabel }}</strong></div>
          <div><span>Encryption</span><strong>{{ result.encryptionSupported ? 'Supported' : 'Not reported' }}</strong></div>
          <div><span>Server GUID</span><strong>{{ result.serverGuid || '-' }}</strong></div>
        </div>

        <div v-if="result.error" class="callout bad-callout">{{ result.error }}</div>
        <div v-else-if="result.note" class="callout">{{ result.note }}</div>

        <div v-if="result.shares?.length" class="table-wrap">
          <table>
            <thead>
              <tr><th>Name</th><th>Type</th><th>Comment</th></tr>
            </thead>
            <tbody>
              <tr v-for="share in result.shares" :key="`${share.name}:${share.type}`">
                <td><strong>{{ share.name }}</strong></td>
                <td>{{ share.type }}</td>
                <td>{{ share.comment || '-' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div v-show="activeTab === 'json'" class="tab-panel">
        <pre class="json-pre">{{ JSON.stringify(result, null, 2) }}</pre>
      </div>
    </div>
  </div>
</template>

<script>
app.component("samba-browse-page", {
  template: "#samba-browse-page",
  data() {
    return {
      host: "",
      username: "",
      password: "",
      domain: "",
      timeout: 10,
      loading: false,
      discovering: false,
      error: "",
      discoverError: "",
      result: null,
      discovery: null,
      activeTab: "report",
    }
  },
  mounted() {
    this.discover()
  },
  computed: {
    discoveredHosts() {
      return this.discovery?.hosts || []
    },
    signingLabel() {
      if (!this.result) return "-"
      if (this.result.signingRequired) return "Required"
      if (this.result.signingEnabled) return "Enabled"
      return "Not reported"
    },
  },
  methods: {
    async discover() {
      this.discovering = true
      this.discoverError = ""
      try {
        const params = new URLSearchParams()
        params.set("timeout", String(Math.max(1, Math.min(15, Number(this.timeout) || 3))))
        const r = await fetch(`/api/samba-browse/discover?${params.toString()}`)
        if (!r.ok) throw new Error(`API error: ${r.status} ${r.statusText}`)
        this.discovery = await r.json()
        const firstWithShares = this.discoveredHosts.find((item) => item.browse?.shares?.length)
        const firstHost = firstWithShares || this.discoveredHosts[0]
        if (firstHost && !this.result) this.selectDiscovered(firstHost)
      } catch (e) {
        this.discoverError = e instanceof Error ? e.message : "Unexpected discovery error"
      } finally {
        this.discovering = false
      }
    },
    selectDiscovered(item) {
      this.host = item.address
      if (item.browse) {
        this.result = item.browse
        this.activeTab = "report"
      }
    },
    async go() {
      this.loading = true
      this.error = ""
      this.result = null
      try {
        const params = new URLSearchParams()
        if (this.username) params.set("username", this.username)
        if (this.username || this.password) params.set("password", this.password)
        if (this.domain) params.set("domain", this.domain)
        if (this.timeout) params.set("timeout", String(this.timeout))
        const suffix = params.toString() ? `?${params.toString()}` : ""
        const r = await fetch(`/api/samba-browse/${encodeURIComponent(this.host)}${suffix}`)
        if (!r.ok) throw new Error(`API error: ${r.status} ${r.statusText}`)
        this.result = await r.json()
        this.activeTab = "report"
      } catch (e) {
        this.error = e instanceof Error ? e.message : "Unexpected error"
      } finally {
        this.loading = false
      }
    },
  },
})
</script>

<style>
.samba-browse-page { padding: 1rem 0; }
.samba-browse-page .desc { color: #666; font-size: 0.9rem; margin-bottom: 1rem; }
.samba-browse-page .lookup-form { display: flex; gap: 0.5rem; margin-bottom: 1rem; flex-wrap: wrap; }
.samba-browse-page .discovery-panel { background: #fff; border: 1px solid #e5e7eb; border-radius: 8px; padding: 0.9rem 1rem; margin-bottom: 1rem; }
.samba-browse-page .panel-title { display: flex; justify-content: space-between; align-items: flex-start; gap: 1rem; margin-bottom: 0.75rem; }
.samba-browse-page .panel-title h3 { margin: 0 0 0.2rem; font-size: 0.98rem; }
.samba-browse-page .panel-title p { margin: 0; color: #64748b; font-size: 0.82rem; }
.samba-browse-page .input { padding: 0.55rem 0.65rem; border: 1px solid #d1d5db; border-radius: 6px; }
.samba-browse-page .target-input { flex: 1 1 260px; min-width: 220px; }
.samba-browse-page .input-med { flex: 1 1 150px; min-width: 140px; }
.samba-browse-page .input-sm { flex: 0 1 120px; min-width: 100px; }
.samba-browse-page .input-xs { flex: 0 0 82px; min-width: 82px; }
.samba-browse-page .btn { border: 0; background: #0f172a; color: #fff; border-radius: 6px; padding: 0.55rem 0.95rem; cursor: pointer; }
.samba-browse-page .btn-secondary { background: #e5e7eb; color: #111827; }
.samba-browse-page .btn:disabled { opacity: .6; cursor: default; }
.samba-browse-page .error { color: #b91c1c; margin-bottom: 0.7rem; }
.samba-browse-page .empty-state { color: #64748b; font-size: 0.86rem; padding: 0.35rem 0; }
.samba-browse-page .host-list { display: grid; gap: 0.45rem; }
.samba-browse-page .host-row { width: 100%; border: 1px solid #e5e7eb; border-radius: 8px; background: #f8fafc; padding: 0.65rem; display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 0.75rem; text-align: left; cursor: pointer; align-items: center; }
.samba-browse-page .host-row.active { border-color: #0f172a; box-shadow: inset 3px 0 0 #0f172a; }
.samba-browse-page .host-row strong { display: block; color: #0f172a; overflow-wrap: anywhere; }
.samba-browse-page .host-row span { color: #64748b; font-size: 0.78rem; }
.samba-browse-page .host-meta { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 0.35rem; }
.samba-browse-page .host-meta span { background: #fff; border: 1px solid #e5e7eb; border-radius: 999px; padding: 0.18rem 0.45rem; color: #334155; white-space: nowrap; }
.samba-browse-page .result-wrap { background: #fff; border: 1px solid #e5e7eb; border-radius: 8px; overflow: hidden; }
.samba-browse-page .tabs { display: flex; border-bottom: 1px solid #e5e7eb; background: #f8fafc; }
.samba-browse-page .tab { border: none; background: none; padding: 0.55rem 0.9rem; cursor: pointer; color: #64748b; font-size: 0.82rem; border-bottom: 2px solid transparent; margin-bottom: -1px; }
.samba-browse-page .tab.active { color: #111827; font-weight: 600; border-bottom-color: #111827; }
.samba-browse-page .tab-panel { padding: 0.9rem 1rem; }
.samba-browse-page .summary-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 0.5rem; margin-bottom: 0.8rem; }
.samba-browse-page .summary-card, .samba-browse-page .security-grid > div { border: 1px solid #e5e7eb; border-radius: 8px; padding: 0.55rem 0.65rem; display: flex; justify-content: space-between; align-items: baseline; gap: 0.6rem; min-width: 0; }
.samba-browse-page .summary-card span, .samba-browse-page .security-grid span { font-size: 0.74rem; color: #64748b; }
.samba-browse-page .summary-card strong, .samba-browse-page .security-grid strong { font-size: 0.9rem; color: #0f172a; overflow-wrap: anywhere; text-align: right; }
.samba-browse-page .security-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0.5rem; margin-bottom: 0.8rem; }
.samba-browse-page .callout { margin-bottom: 0.75rem; border-radius: 6px; padding: 0.55rem 0.65rem; font-size: 0.83rem; background: #fff7ed; color: #92400e; border: 1px solid #fed7aa; }
.samba-browse-page .bad-callout { background: #fef2f2; color: #991b1b; border-color: #fecaca; }
.samba-browse-page .table-wrap { overflow-x: auto; border: 1px solid #e2e8f0; border-radius: 6px; }
.samba-browse-page table { width: 100%; border-collapse: collapse; font-size: 0.84rem; }
.samba-browse-page th, .samba-browse-page td { text-align: left; padding: 0.5rem 0.55rem; border-bottom: 1px solid #f1f5f9; vertical-align: top; overflow-wrap: anywhere; }
.samba-browse-page th { background: #f8fafc; color: #334155; font-weight: 600; }
.samba-browse-page .json-pre { margin: 0; background: #0f172a; color: #e2e8f0; border-radius: 6px; padding: 0.8rem; overflow-x: auto; font-size: 0.8rem; }
@media (max-width: 860px) {
  .samba-browse-page .summary-grid, .samba-browse-page .security-grid { grid-template-columns: 1fr; }
  .samba-browse-page .panel-title, .samba-browse-page .host-row { grid-template-columns: 1fr; }
  .samba-browse-page .panel-title { flex-direction: column; }
  .samba-browse-page .host-meta { justify-content: flex-start; }
}
</style>
