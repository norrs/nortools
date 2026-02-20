<template id="http-page">
  <div class="tool-view http-page">
    <h2>HTTP Check</h2>
    <p class="desc">Check status code, response time, and response headers.</p>

    <form @submit.prevent="go" class="lookup-form">
      <input v-model.trim="url" class="input" placeholder="http://example.com" />
      <button class="btn" :disabled="loading || !url">{{ loading ? 'Checking...' : 'Check' }}</button>
    </form>

    <div v-if="error" class="error">{{ error }}</div>

    <div v-if="result" class="result-wrap">
      <div class="tabs">
        <button class="tab" :class="{ active: activeTab === 'report' }" @click="activeTab = 'report'">Report</button>
        <button class="tab" :class="{ active: activeTab === 'json' }" @click="activeTab = 'json'">JSON</button>
      </div>

      <div v-show="activeTab === 'report'" class="tab-panel">
        <div class="summary-grid">
          <div class="summary-card"><span>Status</span><strong :class="statusClass">{{ statusLabel }}</strong></div>
          <div class="summary-card"><span>Response Time</span><strong>{{ result.responseTimeMs }} ms</strong></div>
          <div class="summary-card"><span>URL</span><strong class="truncate" :title="result.url">{{ result.url }}</strong></div>
        </div>

        <div v-if="result.error" class="callout callout-error">Error: {{ result.error }}</div>

        <div class="section-title">Headers ({{ headerRows.length }})</div>
        <div v-if="headerRows.length" class="table-wrap">
          <table>
            <thead><tr><th>Header</th><th>Value</th></tr></thead>
            <tbody>
              <tr v-for="row in headerRows" :key="row.name">
                <td><code>{{ row.name }}</code></td>
                <td>{{ row.value }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-else class="empty">No response headers available.</div>
      </div>

      <div v-show="activeTab === 'json'" class="tab-panel">
        <pre class="json-pre">{{ JSON.stringify(result, null, 2) }}</pre>
      </div>
    </div>
  </div>
</template>

<script>
app.component("http-page", {
  template: "#http-page",
  data() {
    return { url: "", loading: false, error: "", result: null, activeTab: 'report' }
  },
  computed: {
    headerRows() {
      if (!this.result || !this.result.headers) return []
      return Object.entries(this.result.headers).map(([name, value]) => ({ name, value }))
    },
    statusLabel() {
      if (!this.result) return '-'
      if (this.result.statusCode < 0) return 'Failed'
      return `${this.result.statusCode}`
    },
    statusClass() {
      if (!this.result) return ''
      const code = this.result.statusCode
      if (code >= 200 && code < 400) return 'status-good'
      if (code >= 400) return 'status-bad'
      return 'status-neutral'
    },
  },
  methods: {
    async go() {
      this.loading = true
      this.error = ""
      this.result = null
      try {
        const r = await fetch(`/api/http/${encodeURIComponent(this.url)}`)
        if (!r.ok) throw new Error(`API error: ${r.status} ${r.statusText}`)
        this.result = await r.json()
        this.activeTab = 'report'
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
.http-page { padding: 1rem 0; }
.http-page .desc { color: #666; font-size: 0.9rem; margin-bottom: 1rem; }
.http-page .lookup-form { display: flex; gap: 0.5rem; margin-bottom: 1rem; flex-wrap: wrap; }
.http-page .input { flex: 1; min-width: 240px; padding: 0.55rem 0.65rem; border: 1px solid #d1d5db; border-radius: 6px; }
.http-page .btn { border: 0; background: #0f172a; color: #fff; border-radius: 6px; padding: 0.55rem 0.95rem; cursor: pointer; }
.http-page .btn:disabled { opacity: .6; cursor: default; }
.http-page .error { color: #b91c1c; margin-bottom: 0.7rem; }
.http-page .result-wrap { background: #fff; border: 1px solid #e5e7eb; border-radius: 8px; overflow: hidden; }
.http-page .tabs { display: flex; border-bottom: 1px solid #e5e7eb; background: #f8fafc; }
.http-page .tab { border: none; background: none; padding: 0.55rem 0.9rem; cursor: pointer; color: #64748b; font-size: 0.82rem; border-bottom: 2px solid transparent; margin-bottom: -1px; }
.http-page .tab.active { color: #111827; font-weight: 600; border-bottom-color: #111827; }
.http-page .tab-panel { padding: 0.9rem 1rem; }
.http-page .summary-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0.5rem; margin-bottom: 0.8rem; }
.http-page .summary-card { border: 1px solid #e5e7eb; border-radius: 8px; padding: 0.55rem 0.65rem; display: flex; justify-content: space-between; align-items: baseline; gap: 0.6rem; }
.http-page .summary-card span { font-size: 0.74rem; color: #64748b; }
.http-page .summary-card strong { font-size: 0.92rem; color: #0f172a; }
.http-page .truncate { max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.http-page .status-good { color: #166534; }
.http-page .status-bad { color: #b91c1c; }
.http-page .status-neutral { color: #475569; }
.http-page .callout { margin-bottom: 0.75rem; border-radius: 6px; padding: 0.55rem 0.65rem; font-size: 0.83rem; }
.http-page .callout-error { background: #fef2f2; color: #991b1b; border: 1px solid #fecaca; }
.http-page .section-title { font-size: 0.78rem; text-transform: uppercase; letter-spacing: 0.06em; color: #64748b; margin-bottom: 0.45rem; }
.http-page .table-wrap { overflow-x: auto; border: 1px solid #e2e8f0; border-radius: 6px; }
.http-page table { width: 100%; border-collapse: collapse; font-size: 0.84rem; }
.http-page th, .http-page td { text-align: left; padding: 0.5rem 0.55rem; border-bottom: 1px solid #f1f5f9; vertical-align: top; }
.http-page th { background: #f8fafc; color: #334155; font-weight: 600; }
.http-page .empty { color: #64748b; font-size: 0.85rem; }
.http-page .json-pre { margin: 0; background: #0f172a; color: #e2e8f0; border-radius: 6px; padding: 0.8rem; overflow-x: auto; font-size: 0.8rem; }
</style>
