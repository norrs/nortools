<template id="dns-health-page">
  <div class="dns-health dns-health-page">
    <h2>DNS Health Check</h2>
    <p class="desc">Deep DNS validation with nameserver diagnostics, protocol checks, and zone consistency tests.</p>
    <form @submit.prevent="check" class="lookup-form">
      <input v-model="domain" placeholder="Enter domain (e.g. example.com)" class="input" />
      <button type="submit" :disabled="loading" class="btn">{{ loading ? 'Checking...' : 'Check Health' }}</button>
    </form>
    <div v-if="error" class="error">{{ error }}</div>

    <div v-if="result" class="health-results">
      <div class="status-banner">
        <div><div class="label">Domain</div><strong>{{ result.domain }}</strong></div>
        <div class="overall" :class="statusClass(result.overallStatus)">{{ result.overallStatus }}</div>
      </div>

      <div class="summary-grid">
        <div class="summary-card pass"><span>PASS</span><strong>{{ result.summary.pass }}</strong></div>
        <div class="summary-card warn"><span>WARN</span><strong>{{ result.summary.warn }}</strong></div>
        <div class="summary-card fail"><span>FAIL</span><strong>{{ result.summary.fail }}</strong></div>
        <div class="summary-card info"><span>INFO</span><strong>{{ result.summary.info }}</strong></div>
        <div class="summary-card total"><span>TOTAL</span><strong>{{ result.summary.total || result.checks.length }}</strong></div>
      </div>

      <div class="tabs">
        <button class="tab" :class="{ active: activeTab === 'report' }" @click="activeTab = 'report'">Report</button>
        <button class="tab" :class="{ active: activeTab === 'json' }" @click="activeTab = 'json'">JSON</button>
      </div>

      <div v-show="activeTab === 'report'" class="tab-panel">
        <div class="quick-stats">
          <div><span class="label">Nameservers</span><strong>{{ result.nameservers.length }}</strong></div>
          <div><span class="label">Responding</span><strong>{{ respondingNameservers }}</strong></div>
          <div><span class="label">SOA Serial</span><strong>{{ result.soa.serial || 'N/A' }}</strong></div>
        </div>

        <div class="section">
          <h3>SOA</h3>
          <div class="soa-grid">
            <div><span class="label">Primary</span><code>{{ result.soa.primary || 'N/A' }}</code></div>
            <div><span class="label">Admin</span><code>{{ result.soa.admin || 'N/A' }}</code></div>
            <div><span class="label">Refresh</span>{{ result.soa.refresh }}s</div>
            <div><span class="label">Retry</span>{{ result.soa.retry }}s</div>
            <div><span class="label">Expire</span>{{ result.soa.expire }}s</div>
            <div><span class="label">Minimum</span>{{ result.soa.minimum }}s</div>
          </div>
        </div>

        <div class="section">
          <h3>Nameservers</h3>
          <div class="table-wrap">
            <table>
              <thead><tr><th>Name</th><th>IP</th><th>Status</th><th>UDP</th><th>Auth</th><th>Time</th><th>Serial</th></tr></thead>
              <tbody>
                <tr v-for="ns in result.nameservers" :key="ns.name">
                  <td><code>{{ ns.name }}</code></td>
                  <td>{{ ns.ip || 'N/A' }}</td>
                  <td><span class="status-pill" :class="statusClass(ns.status)">{{ ns.status }}</span></td>
                  <td>{{ ns.responding ? 'Yes' : 'No' }}</td>
                  <td>{{ ns.authoritative ? 'Yes' : 'No' }}</td>
                  <td>{{ ns.timeMs != null ? `${ns.timeMs} ms` : 'N/A' }}</td>
                  <td>{{ ns.serial != null ? ns.serial : 'N/A' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div class="section" v-for="group in checksByCategory" :key="group.category">
          <h3>{{ group.category }}</h3>
          <div class="check-list">
            <div v-for="item in group.checks" :key="item.check" class="check-item">
              <span class="status-pill" :class="statusClass(item.status)">{{ item.status }}</span>
              <div class="check-content">
                <strong>{{ item.check }}</strong>
                <p>{{ item.detail }}</p>
              </div>
            </div>
          </div>
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
app.component("dns-health-page", {
  template: "#dns-health-page",
  data() {
    return { domain: "", result: null, error: "", loading: false, activeTab: "report", copied: false }
  },
  computed: {
    cliCommand() {
      return `nortools dns-health --json ${this.domain || '<domain>'}`
    },
    cliDisabled() {
      return !this.domain
    },
    checksByCategory() {
      if (!this.result) return []
      return (this.result.categories || []).map((category) => ({
        category,
        checks: (this.result.checks || []).filter((check) => check.category === category),
      }))
    },
    respondingNameservers() {
      if (!this.result) return 0
      return (this.result.nameservers || []).filter((ns) => ns.responding).length
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
    statusClass(status) {
      return `status-${String(status || '').toLowerCase()}`
    },
    async check() {
      if (!this.domain) return
      this.loading = true
      this.error = ""
      this.result = null
      this.activeTab = "report"
      try {
        const res = await fetch(`/api/dns-health/${encodeURIComponent(this.domain)}`)
        if (!res.ok) throw new Error(`API error: ${res.status} ${res.statusText}`)
        this.result = await res.json()
      } catch (e) {
        this.error = e instanceof Error ? e.message : "An error occurred"
      } finally {
        this.loading = false
      }
    },
  },
})
</script>

<style>

.dns-health-page .dns-health { padding: 1rem 0; 
}
.dns-health-page h2 { margin-bottom: 0.25rem; 
}
.dns-health-page .desc { color: #666; font-size: 0.9rem; margin-bottom: 1rem; 
}
.dns-health-page .lookup-form { display: flex; gap: 0.5rem; margin-bottom: 1rem; 
}
.dns-health-page .input { flex: 1; padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; font-size: 1rem; 
}
.dns-health-page .btn { padding: 0.5rem 1.5rem; background: #1a1a2e; color: white; border: none; border-radius: 4px; cursor: pointer; 
}
.dns-health-page .btn:hover  { background: #2a2a4e; 
}
.dns-health-page .btn:disabled  { opacity: 0.6; 
}
.dns-health-page .error { color: #d32f2f; margin-bottom: 1rem; 
}
.dns-health-page .health-results { background: white; border: 1px solid #e5e7eb; border-radius: 8px; overflow: hidden; margin-bottom: 1rem; 
}
.dns-health-page .status-banner { display: flex; justify-content: space-between; align-items: center; padding: 0.9rem 1rem; background: #f8fafc; border-bottom: 1px solid #e5e7eb; 
}
.dns-health-page .overall { font-weight: 700; padding: 0.35rem 0.7rem; border-radius: 999px; font-size: 0.78rem; 
}
.dns-health-page .summary-grid { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 0.5rem; padding: 0.8rem 1rem; border-bottom: 1px solid #e5e7eb; 
}
.dns-health-page .summary-card { border: 1px solid #e5e7eb; border-radius: 6px; padding: 0.45rem 0.55rem; display: flex; justify-content: space-between; align-items: baseline; }
.dns-health-page .summary-card span { font-size: 0.72rem; color: #475569; }
.dns-health-page .summary-card strong { font-size: 1.05rem; }
.dns-health-page .summary-card.pass strong { color: #166534; }
.dns-health-page .summary-card.warn strong { color: #92400e; }
.dns-health-page .summary-card.fail strong { color: #b91c1c; }
.dns-health-page .summary-card.info strong { color: #0f172a; }
.dns-health-page .summary-card.total strong { color: #1f2937; 
}
.dns-health-page .tabs { display: flex; border-bottom: 1px solid #e5e7eb; background: #f8fafc; 
}
.dns-health-page .tab { border: none; background: none; padding: 0.55rem 0.9rem; cursor: pointer; color: #64748b; font-size: 0.82rem; border-bottom: 2px solid transparent; margin-bottom: -1px; }
.dns-health-page .tab.active { color: #111827; font-weight: 600; border-bottom-color: #111827; 
}
.dns-health-page .tab-panel { padding: 0.9rem 1rem; 
}
.dns-health-page .quick-stats { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0.5rem; margin-bottom: 0.85rem; }
.dns-health-page .quick-stats > div { border: 1px solid #e2e8f0; border-radius: 6px; padding: 0.5rem 0.65rem; display: flex; justify-content: space-between; align-items: center; background: #fff; 
}
.dns-health-page .label { color: #64748b; font-size: 0.72rem; display: block; 
}
.dns-health-page .section { margin-bottom: 1rem; 
}
.dns-health-page h3 { font-size: 0.92rem; margin-bottom: 0.5rem; color: #0f172a; 
}
.dns-health-page .soa-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0.6rem; border: 1px solid #e2e8f0; border-radius: 6px; padding: 0.65rem; 
}
.dns-health-page .table-wrap { overflow-x: auto; border: 1px solid #e2e8f0; border-radius: 6px; 
}
.dns-health-page table { width: 100%; border-collapse: collapse; font-size: 0.84rem; 
}
.dns-health-page th, .dns-health-page td { text-align: left; padding: 0.5rem 0.55rem; border-bottom: 1px solid #f1f5f9; 
}
.dns-health-page th { background: #f8fafc; color: #334155; font-weight: 600; 
}
.dns-health-page .check-list { border: 1px solid #e2e8f0; border-radius: 6px; overflow: hidden; 
}
.dns-health-page .check-item { display: flex; gap: 0.6rem; padding: 0.55rem 0.65rem; border-bottom: 1px solid #f1f5f9; 
}
.dns-health-page .check-item:last-child  { border-bottom: none; }
.dns-health-page .check-content p { margin-top: 0.2rem; color: #475569; font-size: 0.82rem; 
}
.dns-health-page .status-pill { font-size: 0.72rem; font-weight: 700; min-width: 46px; text-align: center; padding: 0.2rem 0.4rem; border-radius: 999px; border: 1px solid transparent; height: fit-content; 
}
.dns-health-page .status-pass { color: #166534; background: #dcfce7; border-color: #86efac; 
}
.dns-health-page .status-warn { color: #92400e; background: #fef3c7; border-color: #fcd34d; 
}
.dns-health-page .status-fail { color: #b91c1c; background: #fee2e2; border-color: #fca5a5; 
}
.dns-health-page .status-info { color: #1e3a8a; background: #dbeafe; border-color: #93c5fd; 
}
.dns-health-page .status-ok { color: #166534; background: #dcfce7; border-color: #86efac; 
}
.dns-health-page .result { background: #0f172a; color: #e2e8f0; padding: 1rem; border-radius: 6px; overflow-x: auto; font-size: 0.8rem; }
.dns-health-page .cli-copy { margin-top: 1.25rem; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 0.75rem 1rem; width: 100%; }
.dns-health-page .cli-label { font-size: 0.75rem; text-transform: uppercase; letter-spacing: 0.08em; color: #64748b; margin-bottom: 0.5rem; }
.dns-health-page .cli-row { display: flex; gap: 0.75rem; align-items: center; flex-wrap: wrap; }
.dns-health-page .cli-command { background: #0f172a; color: #e2e8f0; padding: 0.5rem 0.75rem; border-radius: 6px; font-size: 0.85rem; overflow-x: auto; flex: 1; min-width: 200px; }
.dns-health-page .cli-btn { padding: 0.45rem 0.9rem; background: #1a1a2e; color: white; border: none; border-radius: 6px; cursor: pointer; font-size: 0.85rem; white-space: nowrap; }
.dns-health-page .cli-btn:hover { background: #2a2a4e; }
.dns-health-page .cli-btn:disabled { opacity: 0.6; cursor: not-allowed; }
</style>
