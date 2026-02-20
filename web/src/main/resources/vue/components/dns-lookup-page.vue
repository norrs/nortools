<template id="dns-lookup-page">
  <div class="tool-view dns-lookup-page">
    <h2>DNS Lookup</h2>
    <p class="desc">Query DNS records using system or custom resolvers.</p>
    <p class="help-line">
      Need record guidance?
      <a href="/help/dns-record-types">DNS Record Types Help</a>
    </p>

    <form @submit.prevent="lookup" class="lookup-form">
      <input v-model="domain" placeholder="Domain (e.g. example.com)" class="input" />
      <select v-model="recordType" class="input input-sm">
        <option>A</option><option>AAAA</option><option>MX</option><option>TXT</option><option>CNAME</option><option>NS</option><option>SOA</option><option>SRV</option><option>CAA</option><option>ANY</option>
        <option value="CUSTOM">Custom…</option>
      </select>
      <input v-if="recordType === 'CUSTOM'" v-model.trim="customRecordType" placeholder="Record type (e.g. CAA, PTR, TLSA)" class="input input-sm" />
      <select v-model="resolverPreset" class="input input-sm">
        <option value="auto">System resolver</option>
        <option value="google">Google (8.8.8.8)</option>
        <option value="cloudflare">Cloudflare (1.1.1.1)</option>
        <option value="quad9">Quad9 (9.9.9.9)</option>
        <option value="custom">Custom</option>
      </select>
      <input v-if="resolverPreset === 'custom'" v-model="customResolver" placeholder="Custom resolver IP" class="input input-sm" />
      <button type="submit" :disabled="loading || (recordType === 'CUSTOM' && !customRecordType.trim())" class="btn">{{ loading ? 'Looking up...' : 'Lookup' }}</button>
    </form>

    <div v-if="error" class="error">{{ error }}</div>

    <div v-if="result" class="result-wrap">
      <div class="tabs">
        <button class="tab" :class="{ active: activeTab === 'report' }" @click="activeTab = 'report'">Report</button>
        <button class="tab" :class="{ active: activeTab === 'json' }" @click="activeTab = 'json'">JSON</button>
      </div>

      <div v-show="activeTab === 'report'" class="tab-panel">
        <div class="summary-grid">
          <div class="summary-card"><span>Name</span><strong>{{ result.name }}</strong></div>
          <div class="summary-card"><span>Type</span><strong>{{ result.type }}</strong></div>
          <div class="summary-card"><span>Status</span><strong :class="result.isSuccessful ? 'ok' : 'bad'">{{ result.status }}</strong></div>
        </div>

        <div v-if="resolverList" class="resolver-line">Resolvers: <code>{{ resolverList }}</code></div>

        <div class="section-title">Records ({{ recordRows.length }})</div>
        <div v-if="recordRows.length" class="table-wrap">
          <table>
            <thead><tr><th>Name</th><th>Type</th><th>TTL</th><th>Value</th></tr></thead>
            <tbody>
              <tr v-for="(row, idx) in recordRows" :key="`dns-${idx}`">
                <td><code>{{ row.name }}</code></td>
                <td><code>{{ row.type }}</code></td>
                <td>{{ row.ttl }}</td>
                <td>{{ row.data }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-else class="empty">No DNS records returned.</div>
      </div>

      <div v-show="activeTab === 'json'" class="tab-panel">
        <pre class="result">{{ JSON.stringify(result, null, 2) }}</pre>
      </div>
    </div>
  </div>
</template>

<script>
app.component("dns-lookup-page", {
  template: "#dns-lookup-page",
  data() {
    return {
      domain: '',
      recordType: 'A',
      customRecordType: '',
      resolverPreset: 'auto',
      customResolver: '',
      loading: false,
      error: '',
      result: null,
      activeTab: 'report',
    }
  },
  computed: {
    selectedResolver() {
      if (this.resolverPreset === 'google') return '8.8.8.8'
      if (this.resolverPreset === 'cloudflare') return '1.1.1.1'
      if (this.resolverPreset === 'quad9') return '9.9.9.9'
      if (this.resolverPreset === 'custom') return this.customResolver.trim()
      return ''
    },
    recordRows() {
      return Array.isArray(this.result?.records) ? this.result.records : []
    },
    effectiveRecordType() {
      if (this.recordType !== 'CUSTOM') return this.recordType
      return String(this.customRecordType || '').trim().toUpperCase()
    },
    resolverList() {
      const list = Array.isArray(this.result?.resolvers) ? this.result.resolvers : []
      return list.length ? list.join(', ') : ''
    },
  },
  methods: {
    async lookup() {
      if (!this.domain) return
      if (!this.effectiveRecordType) {
        this.error = 'Please enter a custom DNS record type.'
        return
      }
      this.loading = true
      this.error = ''
      this.result = null
      try {
        const qs = this.selectedResolver ? `?server=${encodeURIComponent(this.selectedResolver)}` : ''
        const r = await fetch(`/api/dns/${encodeURIComponent(this.effectiveRecordType)}/${encodeURIComponent(this.domain)}${qs}`)
        if (!r.ok) throw new Error(`API error: ${r.status} ${r.statusText}`)
        this.result = await r.json()
        this.activeTab = 'report'
      } catch (e) {
        this.error = e instanceof Error ? e.message : 'An error occurred'
      } finally {
        this.loading = false
      }
    },
  },
})
</script>

<style>
.dns-lookup-page .tool-view { padding: 1rem 0; }
.dns-lookup-page .desc { color:#666; font-size:.9rem; margin-bottom:1rem; }
.dns-lookup-page .help-line { margin-top: -0.55rem; margin-bottom: 0.9rem; color: #475569; font-size: 0.84rem; }
.dns-lookup-page .help-line a { color: #1d4ed8; text-decoration: none; }
.dns-lookup-page .help-line a:hover { text-decoration: underline; }
.dns-lookup-page .lookup-form { display:flex; gap:.5rem; margin-bottom:1rem; flex-wrap:wrap; }
.dns-lookup-page .input { flex:1; min-width:160px; padding:.5rem; border:1px solid #ddd; border-radius:4px; font-size:1rem; }
.dns-lookup-page .input-sm { flex:0; min-width:160px; }
.dns-lookup-page .btn { padding:.5rem 1.5rem; background:#1a1a2e; color:white; border:none; border-radius:4px; cursor:pointer; }
.dns-lookup-page .btn:hover { background:#2a2a4e; }
.dns-lookup-page .btn:disabled { opacity:.6; }
.dns-lookup-page .error { color:#d32f2f; margin-bottom:1rem; }
.dns-lookup-page .result-wrap { background:#fff; border:1px solid #e5e7eb; border-radius:8px; overflow:hidden; }
.dns-lookup-page .tabs { display:flex; border-bottom:1px solid #e5e7eb; background:#f8fafc; }
.dns-lookup-page .tab { border:none; background:none; padding:.55rem .9rem; cursor:pointer; color:#64748b; font-size:.82rem; border-bottom:2px solid transparent; margin-bottom:-1px; }
.dns-lookup-page .tab.active { color:#111827; font-weight:600; border-bottom-color:#111827; }
.dns-lookup-page .tab-panel { padding:.9rem 1rem; }
.dns-lookup-page .summary-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:.5rem; margin-bottom:.8rem; }
.dns-lookup-page .summary-card { border:1px solid #e5e7eb; border-radius:8px; padding:.55rem .65rem; display:flex; justify-content:space-between; align-items:baseline; gap:.6rem; }
.dns-lookup-page .summary-card span { font-size:.74rem; color:#64748b; }
.dns-lookup-page .summary-card strong { font-size:.92rem; color:#0f172a; }
.dns-lookup-page .ok { color:#166534; }
.dns-lookup-page .bad { color:#b91c1c; }
.dns-lookup-page .resolver-line { margin-bottom:.6rem; color:#334155; font-size:.84rem; }
.dns-lookup-page .section-title { font-size:.78rem; text-transform:uppercase; letter-spacing:.06em; color:#64748b; margin-bottom:.45rem; }
.dns-lookup-page .table-wrap { overflow-x:auto; border:1px solid #e2e8f0; border-radius:6px; }
.dns-lookup-page table { width:100%; border-collapse:collapse; font-size:.84rem; }
.dns-lookup-page th, .dns-lookup-page td { text-align:left; padding:.5rem .55rem; border-bottom:1px solid #f1f5f9; vertical-align:top; }
.dns-lookup-page th { background:#f8fafc; color:#334155; font-weight:600; }
.dns-lookup-page .empty { color:#64748b; font-size:.85rem; }
.dns-lookup-page .result { margin:0; background:#0f172a; color:#e2e8f0; padding:1rem; border-radius:6px; overflow-x:auto; font-size:.8rem; }
</style>
