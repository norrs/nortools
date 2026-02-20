<template id="tcp-page">
  <div class="tool-view tcp-page">
    <h2>TCP Check</h2>
    <p class="desc">Test TCP connectivity and timing for a host and port.</p>

    <form @submit.prevent="go" class="lookup-form">
      <input v-model.trim="host" class="input" placeholder="example.com" />
      <input v-model.number="port" class="input input-sm" placeholder="443" type="number" min="1" max="65535" />
      <button class="btn" :disabled="loading || !host || !port">{{ loading ? 'Checking...' : 'Check' }}</button>
    </form>

    <div v-if="error" class="error">{{ error }}</div>

    <div v-if="result" class="result-wrap">
      <div class="tabs">
        <button class="tab" :class="{ active: activeTab === 'report' }" @click="activeTab = 'report'">Report</button>
        <button class="tab" :class="{ active: activeTab === 'json' }" @click="activeTab = 'json'">JSON</button>
      </div>

      <div v-show="activeTab === 'report'" class="tab-panel">
        <div class="summary-grid">
          <div class="summary-card"><span>Target</span><strong>{{ result.host }}:{{ result.port }}</strong></div>
          <div class="summary-card"><span>Status</span><strong :class="result.connected ? 'ok' : 'bad'">{{ result.connected ? 'Connected' : 'Failed' }}</strong></div>
          <div class="summary-card"><span>Response Time</span><strong>{{ result.responseTimeMs }} ms</strong></div>
        </div>

        <div v-if="result.error" class="callout">Error: {{ result.error }}</div>
        <div v-if="result.banner" class="banner-box">
          <div class="section-title">Banner</div>
          <pre>{{ result.banner }}</pre>
        </div>
      </div>

      <div v-show="activeTab === 'json'" class="tab-panel">
        <pre class="json-pre">{{ JSON.stringify(result, null, 2) }}</pre>
      </div>
    </div>
  </div>
</template>

<script>
app.component("tcp-page", {
  template: "#tcp-page",
  data() {
    return { host: "", port: 443, loading: false, error: "", result: null, activeTab: 'report' }
  },
  methods: {
    async go() {
      this.loading = true
      this.error = ""
      this.result = null
      try {
        const r = await fetch(`/api/tcp/${encodeURIComponent(this.host)}/${encodeURIComponent(String(this.port))}`)
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
.tcp-page { padding: 1rem 0; }
.tcp-page .desc { color: #666; font-size: 0.9rem; margin-bottom: 1rem; }
.tcp-page .lookup-form { display: flex; gap: 0.5rem; margin-bottom: 1rem; flex-wrap: wrap; }
.tcp-page .input { flex: 1; min-width: 200px; padding: 0.55rem 0.65rem; border: 1px solid #d1d5db; border-radius: 6px; }
.tcp-page .input-sm { flex: 0; min-width: 110px; max-width: 120px; }
.tcp-page .btn { border: 0; background: #0f172a; color: #fff; border-radius: 6px; padding: 0.55rem 0.95rem; cursor: pointer; }
.tcp-page .btn:disabled { opacity: .6; cursor: default; }
.tcp-page .error { color: #b91c1c; margin-bottom: 0.7rem; }
.tcp-page .result-wrap { background: #fff; border: 1px solid #e5e7eb; border-radius: 8px; overflow: hidden; }
.tcp-page .tabs { display: flex; border-bottom: 1px solid #e5e7eb; background: #f8fafc; }
.tcp-page .tab { border: none; background: none; padding: 0.55rem 0.9rem; cursor: pointer; color: #64748b; font-size: 0.82rem; border-bottom: 2px solid transparent; margin-bottom: -1px; }
.tcp-page .tab.active { color: #111827; font-weight: 600; border-bottom-color: #111827; }
.tcp-page .tab-panel { padding: 0.9rem 1rem; }
.tcp-page .summary-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0.5rem; margin-bottom: 0.8rem; }
.tcp-page .summary-card { border: 1px solid #e5e7eb; border-radius: 8px; padding: 0.55rem 0.65rem; display: flex; justify-content: space-between; align-items: baseline; gap: 0.6rem; }
.tcp-page .summary-card span { font-size: 0.74rem; color: #64748b; }
.tcp-page .summary-card strong { font-size: 0.92rem; color: #0f172a; }
.tcp-page .ok { color: #166534; }
.tcp-page .bad { color: #b91c1c; }
.tcp-page .callout { margin-bottom: 0.75rem; border-radius: 6px; padding: 0.55rem 0.65rem; font-size: 0.83rem; background: #fef2f2; color: #991b1b; border: 1px solid #fecaca; }
.tcp-page .section-title { font-size: 0.78rem; text-transform: uppercase; letter-spacing: 0.06em; color: #64748b; margin-bottom: 0.4rem; }
.tcp-page .banner-box pre { margin: 0; border: 1px solid #e2e8f0; border-radius: 6px; padding: 0.7rem; background: #f8fafc; white-space: pre-wrap; word-break: break-word; font-size: 0.82rem; }
.tcp-page .json-pre { margin: 0; background: #0f172a; color: #e2e8f0; border-radius: 6px; padding: 0.8rem; overflow-x: auto; font-size: 0.8rem; }
</style>
