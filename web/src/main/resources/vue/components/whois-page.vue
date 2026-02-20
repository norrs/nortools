<template id="whois-page">
  <div class="tool-view whois-page">
    <h2>WHOIS Lookup</h2>
    <p class="desc">Look up domain or IP registration information.</p>
    <form @submit.prevent="lookup" class="lookup-form">
      <input v-model="query" placeholder="Domain or IP (e.g. example.com)" class="input" />
      <button type="submit" :disabled="loading" class="btn">{{ loading ? 'Looking up...' : 'Lookup' }}</button>
    </form>
    <div v-if="error" class="error">{{ error }}</div>

    <div v-if="result" class="whois-results">
      <div class="whois-top">
        <div><div class="label">Query</div><div class="value">{{ result.query }}</div></div>
        <div><div class="label">WHOIS Server</div><div class="value mono">{{ result.server }}</div></div>
      </div>

      <div class="tabs">
        <button :class="['tab', { active: activeTab === 'overview' }]" @click="activeTab = 'overview'">Overview</button>
        <button :class="['tab', { active: activeTab === 'raw' }]" @click="activeTab = 'raw'">Raw WHOIS</button>
        <button :class="['tab', { active: activeTab === 'json' }]" @click="activeTab = 'json'">JSON</button>
      </div>

      <div v-show="activeTab === 'overview'" class="tab-panel">
        <div v-if="summaryFields.length" class="field-grid">
          <div v-for="f in summaryFields" :key="f.key" class="field-card">
            <div class="field-key">{{ f.key }}</div>
            <div class="field-val">{{ f.value }}</div>
          </div>
        </div>
        <div v-else class="empty">No parsed fields found. Check Raw WHOIS tab.</div>

        <details v-if="otherFields.length" class="other-section">
          <summary>Other Parsed Fields ({{ otherFields.length }})</summary>
          <div class="other-list">
            <div v-for="f in otherFields" :key="f.key" class="other-row">
              <span class="other-key">{{ f.key }}</span>
              <span class="other-val">{{ f.value }}</span>
            </div>
          </div>
        </details>
      </div>

      <div v-show="activeTab === 'raw'" class="tab-panel">
        <pre class="raw-pre">{{ result.raw }}</pre>
      </div>

      <div v-show="activeTab === 'json'" class="tab-panel">
        <pre class="result">{{ JSON.stringify(result, null, 2) }}</pre>
      </div>
    </div>
  </div>
</template>

<script>
app.component("whois-page", {
  template: "#whois-page",
  data() {
    return { query: "", result: null, error: "", loading: false, activeTab: "overview" }
  },
  computed: {
    hasParsedFields() {
      return this.summaryFields.length > 0 || this.otherFields.length > 0
    },
    summaryFields() {
      if (!this.result) return []
      const keys = ['Domain Name', 'Registrar', 'Creation Date', 'Updated Date', 'Registry Expiry Date', 'Registrant Organization', 'Registrant Country', 'NetName', 'OrgName', 'CIDR', 'NetRange', 'DNSSEC']
      return keys.filter((k) => this.result?.fields?.[k]).map((k) => ({ key: k, value: this.result.fields[k] }))
    },
    otherFields() {
      if (!this.result) return []
      const used = new Set(this.summaryFields.map((f) => f.key))
      return Object.entries(this.result.fields).filter(([k]) => !used.has(k)).map(([key, value]) => ({ key, value }))
    },
  },
  methods: {
    async lookup() {
      if (!this.query) return
      this.loading = true
      this.error = ''
      this.result = null
      this.activeTab = 'overview'
      try {
        const res = await fetch(`/api/whois/${encodeURIComponent(this.query)}`)
        if (!res.ok) throw new Error(`API error: ${res.status} ${res.statusText}`)
        this.result = await res.json()
        this.activeTab = this.hasParsedFields ? 'overview' : 'raw'
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

.whois-page .tool-view { padding: 1rem 0; 
}
.whois-page h2 { margin-bottom: 0.25rem; 
}
.whois-page .desc { color: #666; font-size: 0.9rem; margin-bottom: 1rem; 
}
.whois-page .lookup-form { display: flex; gap: 0.5rem; margin-bottom: 1rem; 
}
.whois-page .input { flex: 1; padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; font-size: 1rem; 
}
.whois-page .btn { padding: 0.5rem 1.5rem; background: #1a1a2e; color: white; border: none; border-radius: 4px; cursor: pointer; 
}
.whois-page .btn:hover  { background: #2a2a4e; 
}
.whois-page .btn:disabled  { opacity: 0.6; 
}
.whois-page .error { color: #d32f2f; margin-bottom: 1rem; 
}
.whois-page .whois-results { background: white; border: 1px solid #e5e7eb; border-radius: 8px; overflow: hidden; margin-bottom: 1rem; 
}
.whois-page .whois-top { display: flex; justify-content: space-between; gap: 1rem; padding: 0.8rem 1rem; background: #f8fafc; border-bottom: 1px solid #e5e7eb; 
}
.whois-page .label { font-size: 0.72rem; color: #64748b; 
}
.whois-page .value { font-weight: 600; color: #0f172a; 
}
.whois-page .mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; 
}
.whois-page .tabs { display: flex; border-bottom: 1px solid #e5e7eb; background: #f8fafc; 
}
.whois-page .tab { border: none; background: none; padding: 0.55rem 0.9rem; cursor: pointer; color: #64748b; font-size: 0.82rem; border-bottom: 2px solid transparent; margin-bottom: -1px; }
.whois-page .tab.active { color: #111827; font-weight: 600; border-bottom-color: #111827; 
}
.whois-page .tab-panel { padding: 0.9rem 1rem; 
}
.whois-page .field-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 0.55rem; 
}
.whois-page .field-card { border: 1px solid #e5e7eb; border-radius: 8px; padding: 0.55rem 0.65rem; background: #fff; 
}
.whois-page .field-key { font-size: 0.72rem; color: #64748b; margin-bottom: 0.22rem; 
}
.whois-page .field-val { font-size: 0.85rem; color: #111827; word-break: break-word; 
}
.whois-page .other-section { margin-top: 0.85rem; }
.whois-page .other-section summary { cursor: pointer; color: #374151; font-size: 0.82rem; 
}
.whois-page .other-list { margin-top: 0.5rem; border-top: 1px solid #f1f5f9; 
}
.whois-page .other-row { display: grid; grid-template-columns: 210px 1fr; gap: 0.5rem; padding: 0.4rem 0; border-bottom: 1px solid #f8fafc; 
}
.whois-page .other-key { color: #64748b; font-size: 0.8rem; 
}
.whois-page .other-val { color: #111827; font-size: 0.82rem; word-break: break-word; 
}
.whois-page .empty { color: #64748b; font-size: 0.85rem; 
}
.whois-page .raw-pre { white-space: pre-wrap; word-break: break-word; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 0.8rem; line-height: 1.45; max-height: 520px; overflow: auto; background: #f8fafc; padding: 0.75rem; border-radius: 6px; 
}
.whois-page .result { background: white; padding: 1rem; border-radius: 4px; overflow-x: auto; font-size: 0.85rem; }
</style>
