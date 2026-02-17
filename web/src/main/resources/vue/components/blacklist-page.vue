<template id="blacklist-page">
  <div class="tool-view blacklist-page">
    <h2>Blacklist Check</h2>
    <p class="desc">Check if an IP is listed on DNS-based blacklists.</p>
    <form @submit.prevent="check" class="lookup-form">
      <input v-model="ip" placeholder="IP address (e.g. 1.2.3.4)" class="input" />
      <button type="submit" :disabled="loading" class="btn">{{ loading ? 'Checking...' : 'Check' }}</button>
    </form>
    <div v-if="error" class="error">{{ error }}</div>

    <div v-if="result" class="blacklist-results">
      <div class="status-banner">
        <div>
          <div class="label">IP</div>
          <strong>{{ result.ip }}</strong>
        </div>
        <div class="overall" :class="result.clean ? 'status-pass' : 'status-fail'">
          {{ result.clean ? 'CLEAN' : `${result.listedOn} LISTED` }}
        </div>
      </div>

      <div class="summary-grid">
        <div class="summary-card total"><span>Checked</span><strong>{{ result.totalChecked }}</strong></div>
        <div class="summary-card fail"><span>Listed</span><strong>{{ result.listedOn }}</strong></div>
        <div class="summary-card pass"><span>Not Listed</span><strong>{{ cleanCount }}</strong></div>
      </div>

      <div class="tabs">
        <button class="tab" :class="{ active: activeTab === 'report' }" @click="activeTab = 'report'">Report</button>
        <button class="tab" :class="{ active: activeTab === 'json' }" @click="activeTab = 'json'">JSON</button>
      </div>

      <div v-show="activeTab === 'report'" class="tab-panel">
        <div class="section">
          <h3>Listed On</h3>
          <div v-if="listedResults.length === 0" class="empty">No listings found on checked blocklists.</div>
          <div v-else class="table-wrap">
            <table>
              <thead><tr><th>DNSBL</th><th>Reason</th></tr></thead>
              <tbody>
                <tr v-for="row in listedResults" :key="row.server">
                  <td><code>{{ row.server }}</code></td>
                  <td>{{ row.reason || 'Listed (no TXT reason provided)' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <div class="section">
          <h3>Checked And Clean</h3>
          <div class="clean-list">
            <span v-for="row in cleanResults" :key="row.server" class="chip">{{ row.server }}</span>
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
app.component("blacklist-page", {
  template: "#blacklist-page",
  data() {
    return { ip: "", result: null, error: "", loading: false, activeTab: "report", copied: false }
  },
  computed: {
    cliCommand() {
      return `nortools blacklist --json ${this.ip || '<ip>'}`
    },
    cliDisabled() {
      return !this.ip
    },
    listedResults() {
      return this.result?.results?.filter((r) => r.listed) || []
    },
    cleanResults() {
      return this.result?.results?.filter((r) => !r.listed) || []
    },
    cleanCount() {
      return this.result ? this.result.totalChecked - this.result.listedOn : 0
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
    async check() {
      if (!this.ip) return
      this.loading = true
      this.error = ""
      this.result = null
      this.activeTab = "report"
      try {
        const res = await fetch(`/api/blacklist/${encodeURIComponent(this.ip)}`)
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

.blacklist-page .tool-view { padding: 1rem 0; 
}
.blacklist-page h2 { margin-bottom: 0.25rem; 
}
.blacklist-page .desc { color: #666; font-size: 0.9rem; margin-bottom: 1rem; 
}
.blacklist-page .lookup-form { display: flex; gap: 0.5rem; margin-bottom: 1rem; 
}
.blacklist-page .input { flex: 1; padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; font-size: 1rem; 
}
.blacklist-page .btn { padding: 0.5rem 1.5rem; background: #1a1a2e; color: white; border: none; border-radius: 4px; cursor: pointer; 
}
.blacklist-page .btn:hover  { background: #2a2a4e; 
}
.blacklist-page .btn:disabled  { opacity: 0.6; 
}
.blacklist-page .error { color: #d32f2f; margin-bottom: 1rem; 
}
.blacklist-page .blacklist-results { background: white; border: 1px solid #e5e7eb; border-radius: 8px; overflow: hidden; margin-bottom: 1rem; 
}
.blacklist-page .status-banner { display: flex; justify-content: space-between; align-items: center; padding: 0.9rem 1rem; background: #f8fafc; border-bottom: 1px solid #e5e7eb; 
}
.blacklist-page .overall { font-weight: 700; padding: 0.35rem 0.7rem; border-radius: 999px; font-size: 0.76rem; border: 1px solid transparent; 
}
.blacklist-page .status-pass { color: #166534; background: #dcfce7; border-color: #86efac; 
}
.blacklist-page .status-fail { color: #b91c1c; background: #fee2e2; border-color: #fca5a5; 
}
.blacklist-page .summary-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0.5rem; padding: 0.8rem 1rem; border-bottom: 1px solid #e5e7eb; 
}
.blacklist-page .summary-card { border: 1px solid #e5e7eb; border-radius: 6px; padding: 0.45rem 0.55rem; display: flex; justify-content: space-between; align-items: baseline; }
.blacklist-page .summary-card span { font-size: 0.72rem; color: #475569; }
.blacklist-page .summary-card strong { font-size: 1.05rem; }
.blacklist-page .summary-card.fail strong { color: #b91c1c; }
.blacklist-page .summary-card.pass strong { color: #166534; 
}
.blacklist-page .tabs { display: flex; border-bottom: 1px solid #e5e7eb; background: #f8fafc; 
}
.blacklist-page .tab { border: none; background: none; padding: 0.55rem 0.9rem; cursor: pointer; color: #64748b; font-size: 0.82rem; border-bottom: 2px solid transparent; margin-bottom: -1px; }
.blacklist-page .tab.active { color: #111827; font-weight: 600; border-bottom-color: #111827; 
}
.blacklist-page .tab-panel { padding: 0.9rem 1rem; 
}
.blacklist-page .section { margin-bottom: 1rem; 
}
.blacklist-page .section:last-child  { margin-bottom: 0; 
}
.blacklist-page h3 { font-size: 0.92rem; margin-bottom: 0.5rem; color: #0f172a; 
}
.blacklist-page .empty { color: #475569; 
}
.blacklist-page .table-wrap { overflow-x: auto; border: 1px solid #e2e8f0; border-radius: 6px; 
}
.blacklist-page table { width: 100%; border-collapse: collapse; font-size: 0.84rem; 
}
.blacklist-page th, .blacklist-page td { text-align: left; padding: 0.5rem 0.55rem; border-bottom: 1px solid #f1f5f9; vertical-align: top; 
}
.blacklist-page th { background: #f8fafc; color: #334155; font-weight: 600; 
}
.blacklist-page .clean-list { display: flex; flex-wrap: wrap; gap: 0.4rem; 
}
.blacklist-page .chip { border: 1px solid #d1fae5; background: #ecfdf5; color: #065f46; border-radius: 999px; padding: 0.2rem 0.5rem; font-size: 0.76rem; 
}
.blacklist-page .result { background: #0f172a; color: #e2e8f0; padding: 1rem; border-radius: 6px; overflow-x: auto; font-size: 0.8rem; }
.blacklist-page .cli-copy { margin-top: 1.25rem; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 0.75rem 1rem; width: 100%; }
.blacklist-page .cli-label { font-size: 0.75rem; text-transform: uppercase; letter-spacing: 0.08em; color: #64748b; margin-bottom: 0.5rem; }
.blacklist-page .cli-row { display: flex; gap: 0.75rem; align-items: center; flex-wrap: wrap; }
.blacklist-page .cli-command { background: #0f172a; color: #e2e8f0; padding: 0.5rem 0.75rem; border-radius: 6px; font-size: 0.85rem; overflow-x: auto; flex: 1; min-width: 200px; }
.blacklist-page .cli-btn { padding: 0.45rem 0.9rem; background: #1a1a2e; color: white; border: none; border-radius: 6px; cursor: pointer; font-size: 0.85rem; white-space: nowrap; }
.blacklist-page .cli-btn:hover { background: #2a2a4e; }
.blacklist-page .cli-btn:disabled { opacity: 0.6; cursor: not-allowed; }
</style>
