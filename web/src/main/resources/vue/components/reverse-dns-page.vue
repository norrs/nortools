<template id="reverse-dns-page">
  <div class="tool-view reverse-dns-page">
    <h2>Reverse DNS Lookup</h2>
    <p class="desc">Resolve PTR records for an IP address.</p>

    <form @submit.prevent="lookup" class="lookup-form">
      <input v-model="ip" placeholder="IP address (e.g. 8.8.8.8)" class="input" />
      <select v-model="resolverPreset" class="input input-sm">
        <option value="auto">System resolver</option>
        <option value="google">Google (8.8.8.8)</option>
        <option value="cloudflare">Cloudflare (1.1.1.1)</option>
        <option value="quad9">Quad9 (9.9.9.9)</option>
        <option value="custom">Custom</option>
      </select>
      <input v-if="resolverPreset === 'custom'" v-model="customResolver" placeholder="Custom resolver IP" class="input input-sm" />
      <button type="submit" :disabled="loading" class="btn">{{ loading ? 'Looking up...' : 'Lookup' }}</button>
    </form>

    <div v-if="error" class="error">{{ error }}</div>

    <div v-if="result" class="result-wrap">
      <div class="tabs">
        <button class="tab" :class="{ active: activeTab === 'report' }" @click="activeTab = 'report'">Report</button>
        <button class="tab" :class="{ active: activeTab === 'json' }" @click="activeTab = 'json'">JSON</button>
      </div>

      <div v-show="activeTab === 'report'" class="tab-panel">
        <div class="summary-grid">
          <div class="summary-card"><span>Query</span><strong>{{ result.name }}</strong></div>
          <div class="summary-card"><span>Type</span><strong>{{ result.type }}</strong></div>
          <div class="summary-card"><span>Status</span><strong :class="result.isSuccessful ? 'ok' : 'bad'">{{ result.status }}</strong></div>
        </div>

        <div class="section-title">PTR Records ({{ recordRows.length }})</div>
        <div v-if="recordRows.length" class="table-wrap">
          <table>
            <thead><tr><th>Name</th><th>Type</th><th>TTL</th><th>Value</th></tr></thead>
            <tbody>
              <tr v-for="(row, idx) in recordRows" :key="`ptr-${idx}`">
                <td><code>{{ row.name }}</code></td>
                <td><code>{{ row.type }}</code></td>
                <td>{{ row.ttl }}</td>
                <td>{{ row.data }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-else class="empty">No PTR records found.</div>
      </div>

      <div v-show="activeTab === 'json'" class="tab-panel">
        <pre class="result">{{ JSON.stringify(result, null, 2) }}</pre>
      </div>
    </div>
  </div>
</template>

<script>
app.component("reverse-dns-page", {
  template: "#reverse-dns-page",
  data() {
    return {
      ip: '',
      resolverPreset: 'auto',
      customResolver: '',
      result: null,
      error: '',
      loading: false,
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
  },
  methods: {
    async lookup() {
      if (!this.ip) return
      this.loading = true
      this.error = ''
      this.result = null
      try {
        const qs = this.selectedResolver ? `?server=${encodeURIComponent(this.selectedResolver)}` : ''
        const r = await fetch(`/api/reverse/${encodeURIComponent(this.ip)}${qs}`)
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
.reverse-dns-page .tool-view { padding: 1rem 0; }
.reverse-dns-page .desc { color:#666; font-size:.9rem; margin-bottom:1rem; }
.reverse-dns-page .lookup-form { display:flex; gap:.5rem; margin-bottom:1rem; flex-wrap:wrap; }
.reverse-dns-page .input { flex:1; min-width:160px; padding:.5rem; border:1px solid #ddd; border-radius:4px; font-size:1rem; }
.reverse-dns-page .input-sm { flex:0; min-width:160px; }
.reverse-dns-page .btn { padding:.5rem 1.5rem; background:#1a1a2e; color:white; border:none; border-radius:4px; cursor:pointer; }
.reverse-dns-page .btn:hover { background:#2a2a4e; }
.reverse-dns-page .btn:disabled { opacity:.6; }
.reverse-dns-page .error { color:#d32f2f; margin-bottom:1rem; }
.reverse-dns-page .result-wrap { background:#fff; border:1px solid #e5e7eb; border-radius:8px; overflow:hidden; }
.reverse-dns-page .tabs { display:flex; border-bottom:1px solid #e5e7eb; background:#f8fafc; }
.reverse-dns-page .tab { border:none; background:none; padding:.55rem .9rem; cursor:pointer; color:#64748b; font-size:.82rem; border-bottom:2px solid transparent; margin-bottom:-1px; }
.reverse-dns-page .tab.active { color:#111827; font-weight:600; border-bottom-color:#111827; }
.reverse-dns-page .tab-panel { padding:.9rem 1rem; }
.reverse-dns-page .summary-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:.5rem; margin-bottom:.8rem; }
.reverse-dns-page .summary-card { border:1px solid #e5e7eb; border-radius:8px; padding:.55rem .65rem; display:flex; justify-content:space-between; align-items:baseline; gap:.6rem; }
.reverse-dns-page .summary-card span { font-size:.74rem; color:#64748b; }
.reverse-dns-page .summary-card strong { font-size:.92rem; color:#0f172a; }
.reverse-dns-page .ok { color:#166534; }
.reverse-dns-page .bad { color:#b91c1c; }
.reverse-dns-page .section-title { font-size:.78rem; text-transform:uppercase; letter-spacing:.06em; color:#64748b; margin-bottom:.45rem; }
.reverse-dns-page .table-wrap { overflow-x:auto; border:1px solid #e2e8f0; border-radius:6px; }
.reverse-dns-page table { width:100%; border-collapse:collapse; font-size:.84rem; }
.reverse-dns-page th, .reverse-dns-page td { text-align:left; padding:.5rem .55rem; border-bottom:1px solid #f1f5f9; vertical-align:top; }
.reverse-dns-page th { background:#f8fafc; color:#334155; font-weight:600; }
.reverse-dns-page .empty { color:#64748b; font-size:.85rem; }
.reverse-dns-page .result { margin:0; background:#0f172a; color:#e2e8f0; padding:1rem; border-radius:6px; overflow-x:auto; font-size:.8rem; }
</style>
