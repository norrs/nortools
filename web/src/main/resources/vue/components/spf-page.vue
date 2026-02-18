<template id="spf-page">
  <div class="tool-view spf-page">
    <h2>SPF Record Lookup</h2>
    <p class="desc">Check SPF policy and mechanism breakdown for a domain.</p>

    <form @submit.prevent="lookup" class="lookup-form">
      <input v-model="domain" placeholder="Enter domain (e.g. example.com)" class="input" />
      <button type="submit" :disabled="loading" class="btn">{{ loading ? 'Looking up...' : 'Lookup' }}</button>
    </form>

    <div v-if="error" class="error">{{ error }}</div>

    <div v-if="result" class="spf-results">
      <div class="status-banner">
        <div>
          <div class="label">Domain</div>
          <strong>{{ result.domain }}</strong>
        </div>
        <div class="overall" :class="hasRecord ? 'status-pass' : 'status-fail'">
          {{ hasRecord ? 'SPF FOUND' : 'SPF MISSING' }}
        </div>
      </div>

      <div class="tabs">
        <button class="tab" :class="{ active: activeTab === 'report' }" @click="activeTab = 'report'">Report</button>
        <button class="tab" :class="{ active: activeTab === 'json' }" @click="activeTab = 'json'">JSON</button>
      </div>

      <div v-show="activeTab === 'report'" class="tab-panel">
        <div class="summary-grid">
          <div><span class="label">TTL</span><strong>{{ result.ttl != null ? result.ttl : 'N/A' }}</strong></div>
          <div><span class="label">Mechanisms</span><strong>{{ result.mechanisms ? result.mechanisms.length : 0 }}</strong></div>
          <div><span class="label">Multiple SPF</span><strong>{{ result.multipleRecords ? 'Yes' : 'No' }}</strong></div>
        </div>

        <div v-if="hasRecord" class="section">
          <h3>Raw Record</h3>
          <pre class="raw-pre">{{ result.record }}</pre>
        </div>

        <div v-if="hasRecord" class="section">
          <h3>Mechanism Breakdown</h3>
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Qualifier</th>
                  <th>Mechanism</th>
                  <th>Value</th>
                  <th>Meaning</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(m, idx) in result.mechanisms" :key="`${m.mechanism}-${idx}`">
                  <td><span class="pill" :class="`q-${(m.qualifier || '').toLowerCase()}`">{{ m.qualifier }}</span></td>
                  <td><code>{{ m.mechanism }}</code></td>
                  <td><code>{{ m.value || '-' }}</code></td>
                  <td>{{ mechanismMeaning(m) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div class="section">
          <h3>Recommended Next Steps</h3>
          <ul class="tips">
            <li v-for="tip in recommendations" :key="tip">{{ tip }}</li>
          </ul>
        </div>
      </div>

      <div v-show="activeTab === 'json'" class="tab-panel">
        <pre class="result">{{ JSON.stringify(result, null, 2) }}</pre>
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
app.component("spf-page", {
  template: "#spf-page",
  data() {
    return {
      domain: "",
      result: null,
      error: "",
      loading: false,
      activeTab: "report",
      copied: false,
    }
  },
  computed: {
    cliCommand() {
      return `nortools spf --json ${this.domain || '<domain>'}`
    },
    cliDisabled() {
      return !this.domain
    },
    hasRecord() {
      return !!this.result?.record
    },
    recommendations() {
      const tips = []
      if (!this.result?.record) {
        tips.push('Publish one SPF TXT record starting with `v=spf1`.')
        tips.push('Add authorized sending sources (`include`, `ip4`, `ip6`, `a`, `mx`).')
        tips.push('Finish with `-all` (strict) or `~all` (soft rollout).')
        return tips
      }
      if (this.result.multipleRecords) {
        tips.push('Publish exactly one SPF record. Multiple SPF TXT records break SPF evaluation.')
      }
      const mechs = this.result.mechanisms || []
      const all = mechs.find((m) => (m.mechanism || '').toLowerCase() === 'all')
      if (!all) tips.push('Add a terminal `all` mechanism (`-all` or `~all`).')
      else if ((all.qualifier || '').toLowerCase() === 'pass') tips.push('Avoid `+all`; use `-all` or `~all` for sender control.')
      if (!mechs.find((m) => (m.mechanism || '').toLowerCase() === 'include')) {
        tips.push('Use explicit includes for your mail providers if applicable.')
      }
      if (tips.length === 0) tips.push('SPF structure looks good. Re-validate after provider changes.')
      return tips
    },
  },
  methods: {
    mechanismMeaning(m) {
      const q = (m.qualifier || '').toLowerCase()
      const action = q === 'fail' ? 'hard-fail' : q === 'softfail' ? 'soft-fail' : q === 'neutral' ? 'neutral' : 'allow'
      return `${m.mechanism} => ${action}`
    },
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
      this.result = null
      this.activeTab = 'report'
      try {
        const res = await fetch(`/api/spf/${encodeURIComponent(this.domain)}`)
        if (!res.ok) throw new Error(`API error: ${res.status} ${res.statusText}`)
        this.result = await res.json()
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
.spf-page { padding: 1rem 0; }
.spf-page h2 { margin-bottom: 0.25rem; }
.spf-page .desc { color: #666; font-size: 0.9rem; margin-bottom: 1rem; }
.spf-page .lookup-form { display: flex; gap: 0.5rem; margin-bottom: 1rem; }
.spf-page .input { flex: 1; padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; font-size: 1rem; }
.spf-page .btn { padding: 0.5rem 1.5rem; background: #1a1a2e; color: white; border: none; border-radius: 4px; cursor: pointer; }
.spf-page .btn:hover { background: #2a2a4e; }
.spf-page .btn:disabled { opacity: 0.6; }
.spf-page .error { color: #d32f2f; margin-bottom: 1rem; }
.spf-page .spf-results { background: white; border: 1px solid #e5e7eb; border-radius: 8px; overflow: hidden; margin-bottom: 1rem; }
.spf-page .status-banner { display: flex; justify-content: space-between; align-items: center; padding: 0.9rem 1rem; background: #f8fafc; border-bottom: 1px solid #e5e7eb; }
.spf-page .overall { font-weight: 700; padding: 0.35rem 0.7rem; border-radius: 999px; font-size: 0.76rem; border: 1px solid transparent; }
.spf-page .tabs { display: flex; border-bottom: 1px solid #e5e7eb; background: #f8fafc; }
.spf-page .tab { border: none; background: none; padding: 0.55rem 0.9rem; cursor: pointer; color: #64748b; font-size: 0.82rem; border-bottom: 2px solid transparent; margin-bottom: -1px; }
.spf-page .tab.active { color: #111827; font-weight: 600; border-bottom-color: #111827; }
.spf-page .tab-panel { padding: 0.9rem 1rem; }
.spf-page .summary-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0.5rem; margin-bottom: 0.9rem; }
.spf-page .summary-grid > div { border: 1px solid #e2e8f0; border-radius: 6px; padding: 0.55rem 0.65rem; display: flex; justify-content: space-between; align-items: center; background: #fff; }
.spf-page .label { color: #64748b; font-size: 0.72rem; display: block; }
.spf-page .section { margin-bottom: 1rem; }
.spf-page .section:last-child { margin-bottom: 0; }
.spf-page h3 { font-size: 0.92rem; margin-bottom: 0.5rem; color: #0f172a; }
.spf-page .table-wrap { overflow-x: auto; border: 1px solid #e2e8f0; border-radius: 6px; }
.spf-page table { width: 100%; border-collapse: collapse; font-size: 0.84rem; }
.spf-page th, .spf-page td { text-align: left; padding: 0.5rem 0.55rem; border-bottom: 1px solid #f1f5f9; vertical-align: top; }
.spf-page th { background: #f8fafc; color: #334155; font-weight: 600; }
.spf-page .pill { font-size: 0.72rem; font-weight: 700; min-width: 64px; text-align: center; padding: 0.2rem 0.45rem; border-radius: 999px; display: inline-block; }
.spf-page .q-pass { color: #166534; background: #dcfce7; }
.spf-page .q-fail { color: #b91c1c; background: #fee2e2; }
.spf-page .q-softfail { color: #92400e; background: #fef3c7; }
.spf-page .q-neutral { color: #1e3a8a; background: #dbeafe; }
.spf-page .raw-pre { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 6px; padding: 0.75rem; overflow-x: auto; font-size: 0.8rem; }
.spf-page .tips { margin-left: 1rem; color: #334155; font-size: 0.84rem; }
.spf-page .tips li { margin-bottom: 0.3rem; }
.spf-page .status-pass { color: #166534; background: #dcfce7; border-color: #86efac; }
.spf-page .status-fail { color: #b91c1c; background: #fee2e2; border-color: #fca5a5; }
.spf-page .result { background: #0f172a; color: #e2e8f0; padding: 1rem; border-radius: 6px; overflow-x: auto; font-size: 0.8rem; }
.spf-page .cli-copy { margin-top: 1.25rem; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 0.75rem 1rem; width: 100%; }
.spf-page .cli-label { font-size: 0.75rem; text-transform: uppercase; letter-spacing: 0.08em; color: #64748b; margin-bottom: 0.5rem; }
.spf-page .cli-row { display: flex; gap: 0.75rem; align-items: center; flex-wrap: wrap; }
.spf-page .cli-command { background: #0f172a; color: #e2e8f0; padding: 0.5rem 0.75rem; border-radius: 6px; font-size: 0.85rem; overflow-x: auto; flex: 1; min-width: 200px; }
.spf-page .cli-btn { padding: 0.45rem 0.9rem; background: #1a1a2e; color: white; border: none; border-radius: 6px; cursor: pointer; font-size: 0.85rem; white-space: nowrap; }
.spf-page .cli-btn:hover { background: #2a2a4e; }
.spf-page .cli-btn:disabled { opacity: 0.6; cursor: not-allowed; }
</style>
