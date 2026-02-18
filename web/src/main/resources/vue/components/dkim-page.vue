<template id="dkim-page">
  <div class="tool-view dkim-page">
    <h2>DKIM Record Lookup</h2>
    <p class="desc">Look up DKIM public key records by selector, or auto-discover common selectors.</p>

    <form @submit.prevent="lookup" class="lookup-form">
      <input v-model="domain" placeholder="Domain (e.g. example.com)" class="input" />
      <input v-model="selector" placeholder="Selector (optional)" class="input" />
      <button type="submit" :disabled="loading" class="btn">{{ loading ? 'Looking up...' : 'Lookup' }}</button>
    </form>

    <button @click="discover" :disabled="discovering || !domain" class="btn btn-secondary">
      {{ discovering ? 'Discovering...' : 'Auto-Discover Selectors' }}
    </button>

    <div v-if="error" class="error">{{ error }}</div>

    <div v-if="hasAnyResult" class="dkim-results">
      <div class="status-banner">
        <div>
          <div class="label">Domain</div>
          <strong>{{ domain }}</strong>
        </div>
        <div class="overall" :class="hasLookupRecord || selectorsFound > 0 ? 'status-pass' : 'status-fail'">
          {{ hasLookupRecord ? 'DKIM FOUND' : selectorsFound > 0 ? `${selectorsFound} SELECTOR(S)` : 'NO DKIM FOUND' }}
        </div>
      </div>

      <div class="tabs">
        <button class="tab" :class="{ active: activeTab === 'report' }" @click="activeTab = 'report'">Report</button>
        <button class="tab" :class="{ active: activeTab === 'json' }" @click="activeTab = 'json'">JSON</button>
      </div>

      <div v-show="activeTab === 'report'" class="tab-panel">
        <div v-if="lookupResult" class="section">
          <h3>Lookup Result</h3>
          <div class="summary-grid">
            <div><span class="label">Selector</span><strong>{{ lookupResult.selector }}</strong></div>
            <div><span class="label">DKIM Domain</span><strong>{{ lookupResult.dkimDomain }}</strong></div>
            <div><span class="label">Version</span><strong>{{ lookupResult.version }}</strong></div>
            <div><span class="label">Key Type</span><strong>{{ lookupResult.keyType }}</strong></div>
            <div><span class="label">Hash</span><strong>{{ lookupResult.hashAlgorithms }}</strong></div>
            <div><span class="label">TTL</span><strong>{{ lookupResult.ttl != null ? lookupResult.ttl : 'N/A' }}</strong></div>
          </div>
          <div v-if="lookupResult.record" class="raw-block">
            <div class="label">Record</div>
            <pre class="raw-pre">{{ lookupResult.record }}</pre>
          </div>
        </div>

        <div v-if="discoverResult" class="section">
          <h3>Selector Discovery</h3>
          <div class="summary-grid">
            <div><span class="label">Probed</span><strong>{{ discoverResult.selectorsProbed }}</strong></div>
            <div><span class="label">Found</span><strong>{{ discoverResult.selectorsFound }}</strong></div>
          </div>

          <div v-if="discoverResult.selectors && discoverResult.selectors.length" class="table-wrap">
            <table>
              <thead>
                <tr><th>Selector</th><th>DKIM Domain</th><th>Key Type</th><th>Flags</th><th>TTL</th></tr>
              </thead>
              <tbody>
                <tr v-for="row in discoverResult.selectors" :key="row.selector">
                  <td><code>{{ row.selector }}</code></td>
                  <td><code>{{ row.dkimDomain }}</code></td>
                  <td>{{ row.keyType }}</td>
                  <td>{{ row.flags || 'none' }}</td>
                  <td>{{ row.ttl != null ? row.ttl : 'N/A' }}</td>
                </tr>
              </tbody>
            </table>
          </div>

          <div v-else class="empty">No selectors were discovered.</div>
        </div>
      </div>

      <div v-show="activeTab === 'json'" class="tab-panel">
        <pre class="result">{{ JSON.stringify(jsonView, null, 2) }}</pre>
      </div>
    </div>

    <div class="cli-copy">
      <div class="cli-label">CLI (JSON)</div>
      <div class="cli-row">
        <code class="cli-command">{{ cliCommand }}</code>
        <button class="cli-btn" :disabled="cliDisabled" @click="copyCli(cliCommand, cliDisabled)">
          {{ copied ? 'Copied' : 'Copy CLI Command' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script>
app.component("dkim-page", {
  template: "#dkim-page",
  data() {
    return {
      domain: "",
      selector: "",
      lookupResult: null,
      discoverResult: null,
      error: "",
      loading: false,
      discovering: false,
      activeTab: 'report',
      copied: false,
    }
  },
  computed: {
    cliCommand() {
      if (this.selector) return `nortools dkim --json ${this.selector} ${this.domain || '<domain>'}`
      return `nortools dkim --json --discover ${this.domain || '<domain>'}`
    },
    cliDisabled() {
      return !this.domain
    },
    hasLookupRecord() {
      return !!this.lookupResult?.record
    },
    selectorsFound() {
      return this.discoverResult?.selectorsFound || 0
    },
    hasAnyResult() {
      return !!this.lookupResult || !!this.discoverResult
    },
    jsonView() {
      return {
        lookup: this.lookupResult,
        discover: this.discoverResult,
      }
    },
  },
  methods: {
    async copyCli(command, disabled) {
      if (disabled) return
      try {
        await navigator.clipboard.writeText(command)
        this.copied = true
        setTimeout(() => { this.copied = false }, 1500)
      } catch {
        this.copied = false
      }
    },
    async lookup() {
      if (!this.domain) return
      this.loading = true
      this.error = ""
      this.activeTab = 'report'
      try {
        if (this.selector) {
          const response = await fetch(`/api/dkim/${encodeURIComponent(this.selector)}/${encodeURIComponent(this.domain)}`)
          if (!response.ok) throw new Error(`API error: ${response.status} ${response.statusText}`)
          this.lookupResult = await response.json()
        } else {
          await this.discover()
        }
      } catch (e) {
        this.error = e instanceof Error ? e.message : "An error occurred"
      } finally {
        this.loading = false
      }
    },
    async discover() {
      if (!this.domain) return
      this.discovering = true
      this.error = ""
      this.activeTab = 'report'
      try {
        const response = await fetch(`/api/dkim-discover/${encodeURIComponent(this.domain)}`)
        if (!response.ok) throw new Error(`API error: ${response.status} ${response.statusText}`)
        this.discoverResult = await response.json()
      } catch (e) {
        this.error = e instanceof Error ? e.message : "An error occurred"
      } finally {
        this.discovering = false
      }
    },
  },
})
</script>

<style>
.dkim-page { padding: 1rem 0; }
.dkim-page h2 { margin-bottom: 0.25rem; }
.dkim-page .desc { color: #666; font-size: 0.9rem; margin-bottom: 1rem; }
.dkim-page .lookup-form { display: flex; gap: 0.5rem; margin-bottom: 0.75rem; flex-wrap: wrap; }
.dkim-page .input { flex: 1; min-width: 180px; padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; font-size: 1rem; }
.dkim-page .btn { padding: 0.5rem 1.5rem; background: #1a1a2e; color: white; border: none; border-radius: 4px; cursor: pointer; }
.dkim-page .btn:hover { background: #2a2a4e; }
.dkim-page .btn:disabled { opacity: 0.6; }
.dkim-page .btn-secondary { background: #555; margin-bottom: 1rem; }
.dkim-page .btn-secondary:hover { background: #666; }
.dkim-page .error { color: #d32f2f; margin-bottom: 1rem; }
.dkim-page .dkim-results { background: white; border: 1px solid #e5e7eb; border-radius: 8px; overflow: hidden; margin-bottom: 1rem; }
.dkim-page .status-banner { display: flex; justify-content: space-between; align-items: center; padding: 0.9rem 1rem; background: #f8fafc; border-bottom: 1px solid #e5e7eb; }
.dkim-page .overall { font-weight: 700; padding: 0.35rem 0.7rem; border-radius: 999px; font-size: 0.76rem; border: 1px solid transparent; }
.dkim-page .status-pass { color: #166534; background: #dcfce7; border-color: #86efac; }
.dkim-page .status-fail { color: #b91c1c; background: #fee2e2; border-color: #fca5a5; }
.dkim-page .tabs { display: flex; border-bottom: 1px solid #e5e7eb; background: #f8fafc; }
.dkim-page .tab { border: none; background: none; padding: 0.55rem 0.9rem; cursor: pointer; color: #64748b; font-size: 0.82rem; border-bottom: 2px solid transparent; margin-bottom: -1px; }
.dkim-page .tab.active { color: #111827; font-weight: 600; border-bottom-color: #111827; }
.dkim-page .tab-panel { padding: 0.9rem 1rem; }
.dkim-page .section { margin-bottom: 1rem; }
.dkim-page .section:last-child { margin-bottom: 0; }
.dkim-page h3 { font-size: 0.92rem; margin-bottom: 0.5rem; color: #0f172a; }
.dkim-page .summary-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0.5rem; margin-bottom: 0.8rem; }
.dkim-page .summary-grid > div { border: 1px solid #e2e8f0; border-radius: 6px; padding: 0.55rem 0.65rem; display: flex; justify-content: space-between; align-items: center; background: #fff; }
.dkim-page .label { color: #64748b; font-size: 0.72rem; display: block; }
.dkim-page .table-wrap { overflow-x: auto; border: 1px solid #e2e8f0; border-radius: 6px; }
.dkim-page table { width: 100%; border-collapse: collapse; font-size: 0.84rem; }
.dkim-page th, .dkim-page td { text-align: left; padding: 0.5rem 0.55rem; border-bottom: 1px solid #f1f5f9; vertical-align: top; }
.dkim-page th { background: #f8fafc; color: #334155; font-weight: 600; }
.dkim-page .raw-block { margin-top: 0.6rem; }
.dkim-page .raw-pre { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 6px; padding: 0.75rem; overflow-x: auto; font-size: 0.8rem; }
.dkim-page .empty { color: #475569; }
.dkim-page .result { background: #0f172a; color: #e2e8f0; padding: 1rem; border-radius: 6px; overflow-x: auto; font-size: 0.8rem; }
.dkim-page .cli-copy { margin-top: 1.25rem; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 0.75rem 1rem; width: 100%; }
.dkim-page .cli-label { font-size: 0.75rem; text-transform: uppercase; letter-spacing: 0.08em; color: #64748b; margin-bottom: 0.5rem; }
.dkim-page .cli-row { display: flex; gap: 0.75rem; align-items: center; flex-wrap: wrap; }
.dkim-page .cli-command { background: #0f172a; color: #e2e8f0; padding: 0.5rem 0.75rem; border-radius: 6px; font-size: 0.85rem; overflow-x: auto; flex: 1; min-width: 200px; }
.dkim-page .cli-btn { padding: 0.45rem 0.9rem; background: #1a1a2e; color: white; border: none; border-radius: 6px; cursor: pointer; font-size: 0.85rem; white-space: nowrap; }
.dkim-page .cli-btn:hover { background: #2a2a4e; }
.dkim-page .cli-btn:disabled { opacity: 0.6; cursor: not-allowed; }
</style>
