<template id="https-page">
  <div class="tool-view https-page">
    <h2>HTTPS / SSL Check</h2>
    <p class="desc">Inspect TLS settings and visualize the SSL certificate chain.</p>
    <form @submit.prevent="check" class="lookup-form">
      <input v-model="host" placeholder="Host (e.g. google.com)" class="input" />
      <button type="submit" :disabled="loading" class="btn">{{ loading ? 'Checking...' : 'Check' }}</button>
    </form>
    <div v-if="error" class="error">{{ error }}</div>

    <div v-if="result" class="chain-results">
      <div class="chain-banner" :style="{ borderLeftColor: hasChain ? (chainSummary?.expired ? '#dc2626' : '#16a34a') : '#94a3b8' }">
        <span class="chain-icon">{{ hasChain ? (chainSummary?.expired ? '⚠️' : '🔒') : 'ℹ️' }}</span>
        <div class="banner-content">
          <div class="banner-title">
            <strong>{{ host }}</strong>
            <span class="status-pill" :style="{ background: hasChain ? (chainSummary?.expired ? '#dc2626' : '#16a34a') : '#94a3b8' }">
              {{ hasChain ? (chainSummary?.expired ? 'Expired certs' : 'All certs valid') : 'No chain data' }}
            </span>
          </div>
          <div class="banner-subtitle">
            {{ chainSummary?.count ?? 0 }} certs in chain
            <span v-if="chainSummary"> · {{ daysRemainingText(chainSummary.minDays) }} until first expiry</span>
            <span v-if="result.ssl"> · {{ result.ssl.protocol }} / {{ result.ssl.cipherSuite }}</span>
          </div>
          <div v-if="result.certificateError" class="banner-error">Certificate error: {{ result.certificateError }}</div>
        </div>
      </div>

      <div class="tabs">
        <button :class="['tab', { active: activeTab === 'chain' }]" @click="activeTab = 'chain'">🔗 Chain Diagram</button>
        <button :class="['tab', { active: activeTab === 'details' }]" @click="activeTab = 'details'">📋 Certificate Details</button>
        <button :class="['tab', { active: activeTab === 'json' }]" @click="activeTab = 'json'">{ } JSON</button>
      </div>

      <div v-show="activeTab === 'chain'" class="tab-panel chain-panel">
        <div v-if="chain.length === 0" class="no-chain">No certificate chain data available.</div>
        <div v-else class="chain-diagram">
          <template v-for="(cert, idx) in chain" :key="cert.index">
            <div class="cert-node" @click="selectedCertIndex = cert.index">
              <div class="cert-header">
                <span class="cert-role">{{ certRole(cert, idx, chain.length) }}</span>
                <span class="cert-status" :style="{ color: statusColor(cert.expired) }">{{ cert.expired ? 'Expired' : 'Valid' }}</span>
              </div>
              <div class="cert-cn">{{ cert.commonName || '(no CN)' }}</div>
              <div class="cert-issuer">Issuer: {{ cert.issuerCommonName || cert.issuer }}</div>
              <div class="cert-timeleft" :class="cert.expired ? 'cert-timeleft-expired' : 'cert-timeleft-valid'">
                {{ daysRemainingText(cert.daysRemaining) }}
              </div>
              <div class="cert-valid">{{ cert.validFrom }} → {{ cert.validUntil }}</div>
            </div>
            <div v-if="idx < chain.length - 1" class="cert-arrow"><div class="arrow-line"></div><div class="arrow-head">▼</div></div>
          </template>
        </div>
      </div>

      <div v-show="activeTab === 'details'" class="tab-panel details-panel">
        <div v-if="chain.length > 0" class="cert-selector">
          <button v-for="cert in chain" :key="`cert-${cert.index}`" :class="['cert-btn', { active: selectedCert?.index === cert.index }]" @click="selectedCertIndex = cert.index">
            {{ cert.commonName || cert.subject }}
          </button>
        </div>
        <div v-if="selectedCert" class="cert-details">
          <h3>{{ selectedCert.commonName || selectedCert.subject }}</h3>
          <div class="detail-row"><span class="label">Subject</span><span class="val">{{ selectedCert.subject }}</span></div>
          <div class="detail-row"><span class="label">Issuer</span><span class="val">{{ selectedCert.issuer }}</span></div>
          <div class="detail-row"><span class="label">Valid</span><span class="val">{{ selectedCert.validFrom }} → {{ selectedCert.validUntil }}</span></div>
          <div class="detail-row"><span class="label">Days Remaining</span><span class="val">{{ daysRemainingText(selectedCert.daysRemaining) }}</span></div>
          <div class="detail-row"><span class="label">Expired</span><span class="val">{{ selectedCert.expired ? 'Yes' : 'No' }}</span></div>
          <div class="detail-row"><span class="label">SHA-256</span><span class="val mono">{{ selectedCert.sha256Fingerprint }}</span></div>
        </div>
      </div>

      <div v-show="activeTab === 'json'" class="tab-panel json-panel">
        <pre class="result">{{ JSON.stringify(result, null, 2) }}</pre>
      </div>
    </div>
  </div>
</template>

<script>
app.component("https-page", {
  template: "#https-page",
  data() {
    return { host: '', result: null, error: '', loading: false, activeTab: 'chain', selectedCertIndex: null }
  },
  computed: {
    chain() { return this.result?.certificateChain ?? [] },
    hasChain() { return this.chain.length > 0 },
    selectedCert() {
      if (!this.chain.length) return null
      if (this.selectedCertIndex == null) return this.chain[0]
      return this.chain.find((c) => c.index === this.selectedCertIndex) || this.chain[0]
    },
    chainSummary() {
      if (!this.chain.length) return null
      const expired = this.chain.some((c) => c.expired)
      const minDays = Math.min(...this.chain.map((c) => c.daysRemaining))
      return { expired, minDays, count: this.chain.length }
    },
  },
  methods: {
    certRole(cert, idx, total) {
      if (idx === 0) return 'Leaf'
      if (idx === total - 1) return cert.selfSigned ? 'Root' : 'Top CA'
      return 'Intermediate'
    },
    daysRemainingText(days) {
      if (days == null) return 'Expiry unknown'
      if (days < 0) return `Expired ${Math.abs(days)} day${Math.abs(days) === 1 ? '' : 's'} ago`
      if (days === 0) return 'Expires today'
      if (days === 1) return 'Expires in 1 day'
      return `Expires in ${days} days`
    },
    statusColor(expired) { return expired ? '#dc2626' : '#16a34a' },
    async check() {
      if (!this.host) return
      this.loading = true
      this.error = ''
      this.result = null
      this.selectedCertIndex = null
      this.activeTab = 'chain'
      try {
        const res = await fetch(`/api/https/${encodeURIComponent(this.host)}`)
        if (!res.ok) throw new Error(`API error: ${res.status} ${res.statusText}`)
        this.result = await res.json()
        if (this.result?.certificateChain?.length) this.selectedCertIndex = this.result.certificateChain[0].index
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

.https-page .tool-view { padding: 1rem 0; 
}
.https-page h2 { margin-bottom: 0.25rem; 
}
.https-page .desc { color: #666; font-size: 0.9rem; margin-bottom: 1rem; 
}
.https-page .lookup-form { display: flex; gap: 0.5rem; margin-bottom: 1rem; 
}
.https-page .input { flex: 1; padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; font-size: 1rem; 
}
.https-page .btn { padding: 0.5rem 1.5rem; background: #1a1a2e; color: white; border: none; border-radius: 4px; cursor: pointer; 
}
.https-page .btn:hover  { background: #2a2a4e; 
}
.https-page .btn:disabled  { opacity: 0.6; 
}
.https-page .error { color: #d32f2f; margin-bottom: 1rem; 
}
.https-page .chain-results { background: white; border-radius: 8px; overflow: hidden; box-shadow: 0 1px 3px rgba(0,0,0,0.1); 
}
.https-page .chain-banner { display: flex; align-items: center; gap: 12px; padding: 16px 20px; border-left: 4px solid; background: #f8f9fa; 
}
.https-page .chain-icon { font-size: 1.5rem; 
}
.https-page .banner-content { display: flex; flex-direction: column; gap: 4px; 
}
.https-page .banner-title { display: flex; align-items: center; gap: 10px; 
}
.https-page .status-pill { color: white; padding: 2px 8px; border-radius: 999px; font-size: 0.75rem; 
}
.https-page .banner-subtitle { font-size: 0.8rem; color: #666; 
}
.https-page .banner-error { color: #b91c1c; font-size: 0.8rem; 
}
.https-page .tabs { display: flex; gap: 8px; border-bottom: 1px solid #eee; padding: 0 12px; background: #fafafa; 
}
.https-page .tab { background: none; border: none; padding: 10px 12px; cursor: pointer; font-size: 0.9rem; color: #555; }
.https-page .tab.active { color: #111; border-bottom: 2px solid #1a1a2e; font-weight: 600; 
}
.https-page .tab-panel { padding: 16px; 
}
.https-page .chain-panel { overflow-x: auto; 
}
.https-page .chain-diagram { display: flex; flex-direction: column; align-items: center; gap: 0; 
}
.https-page .cert-node { width: min(640px, 100%); border: 1px solid #e5e7eb; border-radius: 8px; padding: 12px 14px; background: #fff; box-shadow: 0 1px 2px rgba(0,0,0,0.05); cursor: pointer; 
}
.https-page .cert-header { display: flex; justify-content: space-between; font-size: 0.8rem; text-transform: uppercase; letter-spacing: 0.04em; color: #6b7280; margin-bottom: 6px; 
}
.https-page .cert-cn { font-size: 1.05rem; font-weight: 600; margin-bottom: 4px; color: #111827; 
}
.https-page .cert-issuer { font-size: 0.85rem; color: #4b5563; margin-bottom: 6px; 
}
.https-page .cert-timeleft { font-size: 0.81rem; margin-bottom: 6px; font-weight: 600; }
.https-page .cert-timeleft-valid { color: #166534; }
.https-page .cert-timeleft-expired { color: #b91c1c; }
.https-page .cert-valid { font-size: 0.8rem; color: #6b7280; margin-bottom: 8px; 
}
.https-page .cert-arrow { display: flex; flex-direction: column; align-items: center; margin: 8px 0; color: #9ca3af; 
}
.https-page .arrow-line { width: 2px; height: 14px; background: #d1d5db; 
}
.https-page .arrow-head { font-size: 0.8rem; margin-top: 2px; 
}
.https-page .no-chain { color: #6b7280; 
}
.https-page .details-panel { display: flex; flex-direction: column; gap: 12px; 
}
.https-page .cert-selector { display: flex; flex-wrap: wrap; gap: 6px; 
}
.https-page .cert-btn { border: 1px solid #e5e7eb; background: #f9fafb; padding: 6px 10px; border-radius: 6px; cursor: pointer; font-size: 0.8rem; }
.https-page .cert-btn.active { background: #1a1a2e; color: white; border-color: #1a1a2e; 
}
.https-page .cert-details { background: #fff; border: 1px solid #e5e7eb; border-radius: 8px; padding: 14px; }
.https-page .cert-details h3 { margin: 0 0 8px 0; 
}
.https-page .detail-row { display: grid; grid-template-columns: 140px 1fr; gap: 8px; padding: 4px 0; 
}
.https-page .label { color: #6b7280; font-size: 0.85rem; 
}
.https-page .val { color: #111827; font-size: 0.85rem; 
}
.https-page .mono { font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace; }
.https-page .json-panel .result { background: #111827; color: #e5e7eb; padding: 1rem; border-radius: 6px; overflow-x: auto; font-size: 0.8rem; }
@media (max-width: 720px) { .lookup-form { flex-direction: column; 
} .https-page .detail-row { grid-template-columns: 1fr; 
} .https-page .cert-node { width: 100%; 
} .https-page .banner-title { flex-direction: column; align-items: flex-start; } }
</style>
