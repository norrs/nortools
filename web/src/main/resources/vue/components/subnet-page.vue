<template id="subnet-page">
  <div class="tool-view subnet-page">
    <h2>Subnet Calculator</h2>
    <p class="desc">Calculate network range, masks, and usable host space from CIDR input.</p>

    <form @submit.prevent="go" class="lookup-form">
      <input v-model.trim="cidr" class="input" placeholder="192.168.1.0/24" />
      <button class="btn" :disabled="loading || !cidr">{{ loading ? 'Calculating...' : 'Calculate' }}</button>
    </form>

    <div v-if="error" class="error">{{ error }}</div>

    <div v-if="result" class="result-wrap">
      <div class="tabs">
        <button class="tab" :class="{ active: activeTab === 'report' }" @click="activeTab = 'report'">Report</button>
        <button class="tab" :class="{ active: activeTab === 'json' }" @click="activeTab = 'json'">JSON</button>
      </div>

      <div v-show="activeTab === 'report'" class="tab-panel">
        <div class="summary-grid">
          <div class="summary-card"><span>CIDR</span><strong>{{ result.cidr }}</strong></div>
          <div class="summary-card"><span>Total Hosts</span><strong>{{ result.totalHosts }}</strong></div>
          <div class="summary-card"><span>Prefix</span><strong>{{ result.prefixLength }}</strong></div>
        </div>

        <div class="kv-grid">
          <div class="kv"><span>Input IP</span><code>{{ result.ip }}</code></div>
          <div class="kv"><span>Subnet Mask</span><code>{{ result.subnetMask }}</code></div>
          <div class="kv"><span>Wildcard Mask</span><code>{{ result.wildcardMask }}</code></div>
          <div class="kv"><span>Network Address</span><code>{{ result.networkAddress }}</code></div>
          <div class="kv"><span>Broadcast Address</span><code>{{ result.broadcastAddress }}</code></div>
          <div class="kv"><span>First Host</span><code>{{ result.firstHost }}</code></div>
          <div class="kv"><span>Last Host</span><code>{{ result.lastHost }}</code></div>
        </div>
      </div>

      <div v-show="activeTab === 'json'" class="tab-panel">
        <pre class="json-pre">{{ JSON.stringify(result, null, 2) }}</pre>
      </div>
    </div>
  </div>
</template>

<script>
app.component("subnet-page", {
  template: "#subnet-page",
  data() {
    return {
      cidr: "",
      loading: false,
      error: "",
      result: null,
      activeTab: "report",
    }
  },
  methods: {
    async go() {
      this.loading = true
      this.error = ""
      this.result = null
      try {
        const r = await fetch(`/api/subnet/${encodeURIComponent(this.cidr)}`)
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
.subnet-page { padding: 1rem 0; }
.subnet-page .desc { color: #666; font-size: 0.9rem; margin-bottom: 1rem; }
.subnet-page .lookup-form { display: flex; gap: 0.5rem; margin-bottom: 1rem; flex-wrap: wrap; }
.subnet-page .input { flex: 1; min-width: 220px; padding: 0.55rem 0.65rem; border: 1px solid #d1d5db; border-radius: 6px; }
.subnet-page .btn { border: 0; background: #0f172a; color: #fff; border-radius: 6px; padding: 0.55rem 0.95rem; cursor: pointer; }
.subnet-page .btn:disabled { opacity: 0.6; cursor: default; }
.subnet-page .error { color: #b91c1c; margin-bottom: 0.7rem; }
.subnet-page .result-wrap { background: #fff; border: 1px solid #e5e7eb; border-radius: 8px; overflow: hidden; }
.subnet-page .tabs { display: flex; border-bottom: 1px solid #e5e7eb; background: #f8fafc; }
.subnet-page .tab { border: none; background: none; padding: 0.55rem 0.9rem; cursor: pointer; color: #64748b; font-size: 0.82rem; border-bottom: 2px solid transparent; margin-bottom: -1px; }
.subnet-page .tab.active { color: #111827; font-weight: 600; border-bottom-color: #111827; }
.subnet-page .tab-panel { padding: 0.9rem 1rem; }
.subnet-page .summary-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0.5rem; margin-bottom: 0.8rem; }
.subnet-page .summary-card { border: 1px solid #e5e7eb; border-radius: 8px; padding: 0.55rem 0.65rem; display: flex; justify-content: space-between; align-items: baseline; gap: 0.6rem; }
.subnet-page .summary-card span { font-size: 0.74rem; color: #64748b; }
.subnet-page .summary-card strong { font-size: 0.95rem; color: #0f172a; }
.subnet-page .kv-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 0.5rem; }
.subnet-page .kv { border: 1px solid #e5e7eb; border-radius: 8px; padding: 0.55rem 0.65rem; }
.subnet-page .kv span { display: block; font-size: 0.74rem; color: #64748b; margin-bottom: 0.2rem; }
.subnet-page .kv code { font-size: 0.84rem; color: #0f172a; }
.subnet-page .json-pre { margin: 0; background: #0f172a; color: #e2e8f0; border-radius: 6px; padding: 0.8rem; overflow-x: auto; font-size: 0.8rem; }
</style>
