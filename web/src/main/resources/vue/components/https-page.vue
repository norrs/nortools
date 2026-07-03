<template id="https-page">
  <div class="tool-view https-page">
    <h2>HTTPS / TLS Inspector</h2>
    <p class="desc">Inspect HTTPS status, redirects, TLS negotiation, SNI behavior, ALPN, hostname matching, and certificate details.</p>

    <form @submit.prevent="check" class="lookup-form">
      <input v-model="host" placeholder="Host or URL (e.g. example.com or example.com:8443/path)" class="input" />
      <button type="submit" :disabled="loading" class="btn">{{ loading ? 'Checking...' : 'Inspect' }}</button>
    </form>
    <div v-if="error" class="error">{{ error }}</div>

    <div v-if="result" class="inspector">
      <section class="summary">
        <div>
          <div class="eyebrow">Endpoint</div>
          <h3>{{ result.host }}:{{ result.port }}</h3>
          <p>{{ result.url }}</p>
        </div>
        <div class="summary-grid">
          <div class="metric">
            <span>Status</span>
            <strong>{{ result.statusCode > 0 ? result.statusCode : 'Error' }}</strong>
          </div>
          <div class="metric">
            <span>Latency</span>
            <strong>{{ result.responseTimeMs }}ms</strong>
          </div>
          <div class="metric">
            <span>TLS</span>
            <strong>{{ result.tls?.sni?.protocol || result.ssl?.protocol || 'n/a' }}</strong>
          </div>
          <div class="metric">
            <span>ALPN</span>
            <strong>{{ result.tls?.sni?.alpn || 'none' }}</strong>
          </div>
        </div>
      </section>

      <section class="findings">
        <h3>Findings</h3>
        <div v-for="finding in findings" :key="`${finding.severity}-${finding.title}`" :class="['finding', finding.severity]">
          <strong>{{ finding.title }}</strong>
          <span>{{ finding.detail }}</span>
        </div>
      </section>

      <div class="tabs">
        <button :class="['tab', { active: activeTab === 'overview' }]" @click="activeTab = 'overview'">Overview</button>
        <button :class="['tab', { active: activeTab === 'chain' }]" @click="activeTab = 'chain'">Certificate Chain</button>
        <button :class="['tab', { active: activeTab === 'details' }]" @click="activeTab = 'details'">Certificate Details</button>
        <button :class="['tab', { active: activeTab === 'json' }]" @click="activeTab = 'json'">JSON</button>
      </div>

      <div v-show="activeTab === 'overview'" class="tab-panel overview-panel">
        <section class="panel">
          <h3>TLS Negotiation</h3>
          <div class="probe-grid">
            <div class="probe">
              <h4>With SNI</h4>
              <div class="detail-row"><span class="label">Handshake</span><span class="val">{{ probeStatus(result.tls?.sni) }}</span></div>
              <div class="detail-row"><span class="label">Protocol</span><span class="val">{{ result.tls?.sni?.protocol || '-' }}</span></div>
              <div class="detail-row"><span class="label">Cipher</span><span class="val">{{ result.tls?.sni?.cipherSuite || '-' }}</span></div>
              <div class="detail-row"><span class="label">ALPN</span><span class="val">{{ result.tls?.sni?.alpn || 'none' }}</span></div>
              <div class="detail-row"><span class="label">Hostname</span><span class="val">{{ matchText(result.tls?.sni?.hostnameMatched) }}</span></div>
              <div class="detail-row"><span class="label">Leaf CN</span><span class="val">{{ result.tls?.sni?.leafCommonName || '-' }}</span></div>
              <div class="detail-row"><span class="label">Trust</span><span class="val">{{ result.tls?.sni?.validationError || 'Trusted by default store' }}</span></div>
            </div>
            <div class="probe">
              <h4>Without SNI</h4>
              <div class="detail-row"><span class="label">Handshake</span><span class="val">{{ probeStatus(result.tls?.noSni) }}</span></div>
              <div class="detail-row"><span class="label">Protocol</span><span class="val">{{ result.tls?.noSni?.protocol || '-' }}</span></div>
              <div class="detail-row"><span class="label">Cipher</span><span class="val">{{ result.tls?.noSni?.cipherSuite || '-' }}</span></div>
              <div class="detail-row"><span class="label">ALPN</span><span class="val">{{ result.tls?.noSni?.alpn || 'none' }}</span></div>
              <div class="detail-row"><span class="label">Hostname</span><span class="val">{{ matchText(result.tls?.noSni?.hostnameMatched) }}</span></div>
              <div class="detail-row"><span class="label">Leaf CN</span><span class="val">{{ result.tls?.noSni?.leafCommonName || '-' }}</span></div>
              <div class="detail-row"><span class="label">Certificate</span><span class="val">{{ result.tls?.sniChangesCertificate ? 'Different from SNI' : 'Same as SNI or unavailable' }}</span></div>
            </div>
          </div>
        </section>

        <section class="panel">
          <h3>Redirect Chain</h3>
          <div v-if="redirects.length === 0" class="muted">No redirect data collected.</div>
          <div v-else class="redirects">
            <div v-for="(hop, idx) in redirects" :key="`${hop.url}-${idx}`" class="redirect-hop">
              <span class="status-code">{{ hop.statusCode }}</span>
              <span class="redirect-url">{{ hop.url }}</span>
              <span v-if="hop.nextUrl" class="redirect-next">to {{ hop.nextUrl }}</span>
            </div>
          </div>
        </section>
      </div>

      <div v-show="activeTab === 'chain'" class="tab-panel chain-panel">
        <div v-if="chain.length === 0" class="muted">No certificate chain data available.</div>
        <div v-else class="chain-diagram">
          <template v-for="(cert, idx) in chain" :key="cert.index">
            <div class="cert-node" @click="selectedCertIndex = cert.index">
              <div class="cert-header">
                <span>{{ certRole(cert, idx, chain.length) }}</span>
                <span :class="cert.expired ? 'bad' : 'good'">{{ cert.expired ? 'Expired' : 'Valid' }}</span>
              </div>
              <div class="cert-cn">{{ cert.commonName || '(no CN)' }}</div>
              <div class="cert-issuer">Issuer: {{ cert.issuerCommonName || cert.issuer }}</div>
              <div :class="['cert-timeleft', cert.expired ? 'bad' : 'good']">{{ daysRemainingText(cert.daysRemaining) }}</div>
              <div class="cert-valid">{{ cert.validFrom }} to {{ cert.validUntil }}</div>
            </div>
            <div v-if="idx < chain.length - 1" class="cert-arrow"><div></div><span>down</span></div>
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
          <div class="detail-row"><span class="label">SANs</span><span class="val">{{ selectedCert.subjectAltNames.join(', ') || '-' }}</span></div>
          <div class="detail-row"><span class="label">Valid</span><span class="val">{{ selectedCert.validFrom }} to {{ selectedCert.validUntil }}</span></div>
          <div class="detail-row"><span class="label">Days Remaining</span><span class="val">{{ daysRemainingText(selectedCert.daysRemaining) }}</span></div>
          <div class="detail-row"><span class="label">Key</span><span class="val">{{ selectedCert.publicKeyType }} {{ selectedCert.publicKeySize ? `${selectedCert.publicKeySize} bit` : '' }}</span></div>
          <div class="detail-row"><span class="label">Signature</span><span class="val">{{ selectedCert.signatureAlgorithm }}</span></div>
          <div class="detail-row"><span class="label">Usage</span><span class="val">{{ [...selectedCert.keyUsage, ...selectedCert.extendedKeyUsage].join(', ') || '-' }}</span></div>
          <div class="detail-row"><span class="label">Serial</span><span class="val mono">{{ selectedCert.serialNumber }}</span></div>
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
    return { host: '', result: null, error: '', loading: false, activeTab: 'overview', selectedCertIndex: null }
  },
  computed: {
    chain() { return this.result?.certificateChain ?? [] },
    redirects() { return this.result?.redirectChain ?? [] },
    findings() { return this.result?.tls?.findings ?? [] },
    selectedCert() {
      if (!this.chain.length) return null
      if (this.selectedCertIndex == null) return this.chain[0]
      return this.chain.find((c) => c.index === this.selectedCertIndex) || this.chain[0]
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
    probeStatus(probe) {
      if (!probe) return 'not attempted'
      if (probe.success) return `ok in ${probe.handshakeTimeMs}ms`
      return probe.error || 'failed'
    },
    matchText(value) {
      if (value === true) return 'matches'
      if (value === false) return 'mismatch'
      return 'unknown'
    },
    async check() {
      if (!this.host) return
      this.loading = true
      this.error = ''
      this.result = null
      this.selectedCertIndex = null
      this.activeTab = 'overview'
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
.https-page .desc { color: #56616f; font-size: 0.9rem; margin-bottom: 1rem; }
.https-page .lookup-form { display: flex; gap: 0.5rem; margin-bottom: 1rem; }
.https-page .input { flex: 1; padding: 0.55rem 0.65rem; border: 1px solid #cbd5e1; border-radius: 4px; font-size: 1rem; }
.https-page .btn { padding: 0.55rem 1.25rem; background: #1f2937; color: white; border: none; border-radius: 4px; cursor: pointer; }
.https-page .btn:disabled { opacity: 0.6; }
.https-page .error { color: #b91c1c; margin-bottom: 1rem; }
.https-page .inspector { background: white; border: 1px solid #e5e7eb; border-radius: 8px; overflow: hidden; }
.https-page .summary { display: grid; grid-template-columns: minmax(0, 1.4fr) minmax(320px, 1fr); gap: 1rem; padding: 1rem; border-bottom: 1px solid #e5e7eb; background: #f8fafc; }
.https-page .summary h3 { margin: 0.1rem 0; font-size: 1.2rem; }
.https-page .summary p { margin: 0; color: #64748b; word-break: break-all; }
.https-page .eyebrow { color: #64748b; font-size: 0.72rem; text-transform: uppercase; letter-spacing: 0.04em; }
.https-page .summary-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 0.5rem; }
.https-page .metric { border: 1px solid #e2e8f0; border-radius: 6px; padding: 0.6rem; background: white; min-width: 0; }
.https-page .metric span { display: block; color: #64748b; font-size: 0.75rem; }
.https-page .metric strong { display: block; color: #111827; font-size: 0.95rem; overflow-wrap: anywhere; }
.https-page .findings { padding: 1rem; border-bottom: 1px solid #e5e7eb; }
.https-page .findings h3, .https-page .panel h3 { margin: 0 0 0.6rem; }
.https-page .finding { display: grid; grid-template-columns: 220px 1fr; gap: 0.75rem; padding: 0.55rem 0.65rem; border-left: 4px solid #94a3b8; background: #f8fafc; margin-bottom: 0.45rem; }
.https-page .finding.ok { border-left-color: #16a34a; }
.https-page .finding.info { border-left-color: #2563eb; }
.https-page .finding.warning { border-left-color: #d97706; }
.https-page .finding.error { border-left-color: #dc2626; }
.https-page .tabs { display: flex; gap: 0.25rem; border-bottom: 1px solid #e5e7eb; padding: 0 0.75rem; background: #f8fafc; }
.https-page .tab { background: none; border: none; padding: 0.7rem 0.8rem; cursor: pointer; color: #475569; }
.https-page .tab.active { color: #111827; border-bottom: 2px solid #1f2937; font-weight: 600; }
.https-page .tab-panel { padding: 1rem; }
.https-page .overview-panel { display: grid; gap: 1rem; }
.https-page .panel { border: 1px solid #e5e7eb; border-radius: 8px; padding: 1rem; }
.https-page .probe-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 1rem; }
.https-page .probe { border: 1px solid #e2e8f0; border-radius: 6px; padding: 0.8rem; }
.https-page .probe h4 { margin: 0 0 0.5rem; }
.https-page .detail-row { display: grid; grid-template-columns: 145px minmax(0, 1fr); gap: 0.65rem; padding: 0.28rem 0; }
.https-page .label { color: #64748b; font-size: 0.85rem; }
.https-page .val { color: #111827; font-size: 0.85rem; overflow-wrap: anywhere; }
.https-page .mono { font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace; }
.https-page .redirects { display: grid; gap: 0.45rem; }
.https-page .redirect-hop { display: grid; grid-template-columns: 64px minmax(0, 1fr); gap: 0.6rem; align-items: start; border: 1px solid #e2e8f0; border-radius: 6px; padding: 0.55rem; }
.https-page .status-code { font-weight: 700; color: #1f2937; }
.https-page .redirect-url, .https-page .redirect-next { overflow-wrap: anywhere; }
.https-page .redirect-next { grid-column: 2; color: #64748b; font-size: 0.85rem; }
.https-page .chain-diagram { display: flex; flex-direction: column; align-items: center; }
.https-page .cert-node { width: min(760px, 100%); border: 1px solid #e5e7eb; border-radius: 8px; padding: 0.8rem; cursor: pointer; }
.https-page .cert-header { display: flex; justify-content: space-between; color: #64748b; font-size: 0.78rem; text-transform: uppercase; }
.https-page .cert-cn { font-size: 1.05rem; font-weight: 600; margin-top: 0.25rem; }
.https-page .cert-issuer, .https-page .cert-valid { color: #64748b; font-size: 0.85rem; margin-top: 0.25rem; overflow-wrap: anywhere; }
.https-page .cert-timeleft { font-size: 0.85rem; font-weight: 600; margin-top: 0.35rem; }
.https-page .good { color: #166534; }
.https-page .bad { color: #b91c1c; }
.https-page .cert-arrow { display: flex; flex-direction: column; align-items: center; color: #94a3b8; margin: 0.4rem 0; font-size: 0.72rem; text-transform: uppercase; }
.https-page .cert-arrow div { width: 2px; height: 14px; background: #cbd5e1; }
.https-page .cert-selector { display: flex; flex-wrap: wrap; gap: 0.4rem; margin-bottom: 0.8rem; }
.https-page .cert-btn { border: 1px solid #e5e7eb; background: #f8fafc; padding: 0.42rem 0.6rem; border-radius: 4px; cursor: pointer; font-size: 0.82rem; }
.https-page .cert-btn.active { background: #1f2937; color: white; border-color: #1f2937; }
.https-page .cert-details { border: 1px solid #e5e7eb; border-radius: 8px; padding: 1rem; }
.https-page .json-panel .result { background: #111827; color: #e5e7eb; padding: 1rem; border-radius: 6px; overflow-x: auto; font-size: 0.8rem; }
.https-page .muted { color: #64748b; }
@media (max-width: 860px) {
  .https-page .lookup-form, .https-page .summary, .https-page .probe-grid { grid-template-columns: 1fr; flex-direction: column; }
  .https-page .summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .https-page .finding, .https-page .detail-row, .https-page .redirect-hop { grid-template-columns: 1fr; }
  .https-page .redirect-next { grid-column: 1; }
}
</style>
