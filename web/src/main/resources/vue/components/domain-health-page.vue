<template id="domain-health-page">
  <div class="tool-view domain-health-page">
    <h2>Domain Health Check</h2>
    <p class="desc">Comprehensive domain health check — DNS, email auth, HTTPS, and more.</p>
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
        <div v-for="group in checkGroups" :key="group.title" class="section">
          <h3>{{ group.title }}</h3>
          <div class="check-list">
            <div v-for="item in group.checks" :key="item.check" class="check-item">
              <span class="status-pill" :class="statusClass(item.status)">{{ item.status }}</span>
              <div class="check-content">
                <strong>{{ item.check }}</strong>
                <p>{{ item.detail }}</p>
                <template v-if="remediationForCheck(item.check, item.status, item.detail)">
                  <p class="hint">{{ remediationForCheck(item.check, item.status, item.detail).text }}</p>
                  <a
                    v-if="remediationForCheck(item.check, item.status, item.detail).helpPath"
                    :href="remediationForCheck(item.check, item.status, item.detail).helpPath"
                    class="help-link"
                  >
                    {{ remediationForCheck(item.check, item.status, item.detail).helpLabel || 'Learn more' }}
                  </a>
                </template>
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
app.component("domain-health-page", {
  template: "#domain-health-page",
  data() {
    return { domain: "", result: null, error: "", loading: false, activeTab: "report", copied: false }
  },
  computed: {
    cliCommand() {
      return `nortools domain-health --json ${this.domain || '<domain>'}`
    },
    cliDisabled() {
      return !this.domain
    },
    checkGroups() {
      if (!this.result) return []

      const isMailCheck = (name) =>
        String(name || '').startsWith('Mail /') || ['MX Records', 'SPF Record', 'DMARC Record'].includes(name)

      const checks = this.result.checks || []
      const groups = [
        {
          title: 'DNS',
          checks: checks.filter((c) => ['SOA Record', 'NS Records', 'A Record', 'AAAA Record', 'DNSSEC', 'CAA Record'].includes(c.check)),
        },
        {
          title: 'Mail',
          checks: checks.filter((c) => isMailCheck(c.check)),
        },
        {
          title: 'Web',
          checks: checks.filter((c) => ['HTTPS'].includes(c.check)),
        },
        {
          title: 'Other',
          checks: checks.filter((c) =>
            !['SOA Record', 'NS Records', 'A Record', 'AAAA Record', 'DNSSEC', 'CAA Record', 'HTTPS'].includes(c.check) && !isMailCheck(c.check),
          ),
        },
      ]
      return groups.filter((g) => g.checks.length > 0)
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
    remediationForCheck(check, status, detail) {
      if (status === 'PASS') return null
      if (check === 'Mail / STARTTLS Available') {
        return {
          text: 'Enable STARTTLS on every public MX host and confirm each server returns a successful STARTTLS response.',
          helpPath: '/help/mail-starttls',
          helpLabel: 'Mail STARTTLS Help',
        }
      }
      if ([
        'Mail / TLS Version',
        'Mail / Ciphers (Algorithm Selections)',
        'Mail / Cipher Order',
        'Mail / Key Exchange Parameters',
        'Mail / Hash Function For Key Exchange',
        'Mail / TLS Compression',
        'Mail / Secure Renegotiation',
        'Mail / Client-Initiated Renegotiation',
        'Mail / 0-RTT',
      ].includes(check)) {
        return {
          text: 'Harden SMTP TLS: keep modern versions/ciphers, prefer ephemeral key exchange, and disable legacy TLS behaviors.',
          helpPath: '/help/mail-tls',
          helpLabel: 'Mail TLS Help',
        }
      }
      if ([
        'Mail / Trust Chain Of Certificate',
        'Mail / Public Key Of Certificate',
        'Mail / Signature Of Certificate',
        'Mail / Domain Name On Certificate',
      ].includes(check)) {
        return {
          text: 'Install a trusted certificate chain on each MX host with strong key/signature settings and correct SAN/CN names.',
          helpPath: '/help/mail-certificate',
          helpLabel: 'Mail Certificate Help',
        }
      }
      if (check === 'Mail / CAA For Mail Server') {
        return {
          text: 'Publish CAA records on each MX hostname to restrict which CAs can issue mail server certificates.',
          helpPath: '/help/mail-caa',
          helpLabel: 'Mail CAA Help',
        }
      }
      if ([
        'Mail / DANE Existence',
        'Mail / DANE Validity',
        'Mail / DANE Rollover Scheme',
      ].includes(check)) {
        return {
          text: 'Publish DNSSEC-signed TLSA records per MX host and use a rollover-safe multi-record strategy.',
          helpPath: '/help/mail-dane',
          helpLabel: 'Mail DANE Help',
        }
      }
      if (String(check || '').startsWith('Mail / RPKI')) {
        return {
          text: 'Ensure your host/network provider publishes ROAs for MX prefixes and validates announced routes to prevent hijacks.',
          helpPath: '/rpki-route',
          helpLabel: 'Open RPKI Validator',
        }
      }
      if (check === 'Mail / MTA-STS DNS') {
        return {
          text: 'Publish `_mta-sts.<domain> TXT "v=STSv1; id=..."` to enable MTA-STS.',
          helpPath: '/help/mta-sts-dns',
          helpLabel: 'MTA-STS DNS Help',
        }
      }
      if (check === 'Mail / DMARC Record') return { text: 'Publish a DMARC TXT record at `_dmarc.<domain>` with at least `v=DMARC1; p=...`.' }
      if (check === 'Mail / SPF Record') return { text: 'Publish exactly one SPF TXT record starting with `v=spf1` and ending in `-all` or `~all`.' }
      if (check === 'Mail / MX Records') return { text: 'Publish valid MX records for receiving mail, or use Null MX (`MX 0 .`) if this domain should not receive email.' }
      if (check === 'HTTPS') return { text: 'Make sure HTTPS is reachable with a valid certificate and stable 2xx/3xx response.' }
      if (status === 'FAIL') return { text: `Needs attention: ${detail}` }
      return null
    },
    async check() {
      if (!this.domain) return
      this.loading = true
      this.error = ""
      this.result = null
      this.activeTab = "report"
      try {
        const res = await fetch(`/api/domain-health/${encodeURIComponent(this.domain)}`)
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

.domain-health-page .tool-view { padding: 1rem 0; 
}
.domain-health-page h2 { margin-bottom: 0.25rem; 
}
.domain-health-page .desc { color: #666; font-size: 0.9rem; margin-bottom: 1rem; 
}
.domain-health-page .lookup-form { display: flex; gap: 0.5rem; margin-bottom: 1rem; 
}
.domain-health-page .input { flex: 1; padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; font-size: 1rem; 
}
.domain-health-page .btn { padding: 0.5rem 1.5rem; background: #1a1a2e; color: white; border: none; border-radius: 4px; cursor: pointer; 
}
.domain-health-page .btn:hover  { background: #2a2a4e; 
}
.domain-health-page .btn:disabled  { opacity: 0.6; 
}
.domain-health-page .error { color: #d32f2f; margin-bottom: 1rem; 
}
.domain-health-page .health-results { background: white; border: 1px solid #e5e7eb; border-radius: 8px; overflow: hidden; margin-bottom: 1rem; 
}
.domain-health-page .status-banner { display: flex; justify-content: space-between; align-items: center; padding: 0.9rem 1rem; background: #f8fafc; border-bottom: 1px solid #e5e7eb; 
}
.domain-health-page .overall { font-weight: 700; padding: 0.35rem 0.7rem; border-radius: 999px; font-size: 0.78rem; 
}
.domain-health-page .summary-grid { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 0.5rem; padding: 0.8rem 1rem; border-bottom: 1px solid #e5e7eb; 
}
.domain-health-page .summary-card { border: 1px solid #e5e7eb; border-radius: 6px; padding: 0.45rem 0.55rem; display: flex; justify-content: space-between; align-items: baseline; }
.domain-health-page .summary-card span { font-size: 0.72rem; color: #475569; }
.domain-health-page .summary-card strong { font-size: 1.05rem; }
.domain-health-page .summary-card.pass strong { color: #166534; }
.domain-health-page .summary-card.warn strong { color: #92400e; }
.domain-health-page .summary-card.fail strong { color: #b91c1c; }
.domain-health-page .summary-card.info strong { color: #0f172a; }
.domain-health-page .summary-card.total strong { color: #1f2937; 
}
.domain-health-page .tabs { display: flex; border-bottom: 1px solid #e5e7eb; background: #f8fafc; 
}
.domain-health-page .tab { border: none; background: none; padding: 0.55rem 0.9rem; cursor: pointer; color: #64748b; font-size: 0.82rem; border-bottom: 2px solid transparent; margin-bottom: -1px; }
.domain-health-page .tab.active { color: #111827; font-weight: 600; border-bottom-color: #111827; 
}
.domain-health-page .tab-panel { padding: 0.9rem 1rem; 
}
.domain-health-page .label { color: #64748b; font-size: 0.72rem; display: block; 
}
.domain-health-page .section { margin-bottom: 1rem; 
}
.domain-health-page .section:last-child  { margin-bottom: 0; 
}
.domain-health-page h3 { font-size: 0.92rem; margin-bottom: 0.5rem; color: #0f172a; 
}
.domain-health-page .check-list { border: 1px solid #e2e8f0; border-radius: 6px; overflow: hidden; 
}
.domain-health-page .check-item { display: flex; gap: 0.6rem; padding: 0.55rem 0.65rem; border-bottom: 1px solid #f1f5f9; 
}
.domain-health-page .check-item:last-child  { border-bottom: none; }
.domain-health-page .check-content p { margin-top: 0.2rem; color: #475569; font-size: 0.82rem; }
.domain-health-page .check-content .hint { color: #334155; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 6px; padding: 0.35rem 0.45rem; margin-top: 0.35rem; 
}
.domain-health-page .help-link { display: inline-block; margin-top: 0.35rem; font-size: 0.8rem; color: #1d4ed8; text-decoration: none; 
}
.domain-health-page .help-link:hover  { text-decoration: underline; 
}
.domain-health-page .status-pill { font-size: 0.72rem; font-weight: 700; min-width: 46px; text-align: center; padding: 0.2rem 0.4rem; border-radius: 999px; border: 1px solid transparent; height: fit-content; 
}
.domain-health-page .status-pass { color: #166534; background: #dcfce7; border-color: #86efac; 
}
.domain-health-page .status-warn { color: #92400e; background: #fef3c7; border-color: #fcd34d; 
}
.domain-health-page .status-fail { color: #b91c1c; background: #fee2e2; border-color: #fca5a5; 
}
.domain-health-page .status-info { color: #1e3a8a; background: #dbeafe; border-color: #93c5fd; 
}
.domain-health-page .result { background: #0f172a; color: #e2e8f0; padding: 1rem; border-radius: 6px; overflow-x: auto; font-size: 0.8rem; }
.domain-health-page .cli-copy { margin-top: 1.25rem; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 0.75rem 1rem; width: 100%; }
.domain-health-page .cli-label { font-size: 0.75rem; text-transform: uppercase; letter-spacing: 0.08em; color: #64748b; margin-bottom: 0.5rem; }
.domain-health-page .cli-row { display: flex; gap: 0.75rem; align-items: center; flex-wrap: wrap; }
.domain-health-page .cli-command { background: #0f172a; color: #e2e8f0; padding: 0.5rem 0.75rem; border-radius: 6px; font-size: 0.85rem; overflow-x: auto; flex: 1; min-width: 200px; }
.domain-health-page .cli-btn { padding: 0.45rem 0.9rem; background: #1a1a2e; color: white; border: none; border-radius: 6px; cursor: pointer; font-size: 0.85rem; white-space: nowrap; }
.domain-health-page .cli-btn:hover { background: #2a2a4e; }
.domain-health-page .cli-btn:disabled { opacity: 0.6; cursor: not-allowed; }
</style>
