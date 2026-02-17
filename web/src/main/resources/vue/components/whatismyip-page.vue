<template id="whatismyip-page">
  <div class="tool-view whatismyip-page">
    <h2>What Is My IP</h2>
    <p class="desc">Detect your public IP address using a selected provider.</p>
    <div class="controls">
      <label for="ip-provider" class="label">Provider</label>
      <select id="ip-provider" v-model="provider" class="select">
        <option v-for="option in providerOptions" :key="option.label" :value="option.value">{{ option.label }}</option>
      </select>
    </div>
    <button @click="check" :disabled="loading" class="btn">{{ loading ? 'Detecting...' : 'Detect My IP' }}</button>
    <div v-if="error" class="error">{{ error }}</div>
    <div v-if="result" class="tabs">
      <button class="tab" :class="{ active: activeTab === 'ip' }" @click="activeTab = 'ip'">IP View</button>
      <button class="tab" :class="{ active: activeTab === 'json' }" @click="activeTab = 'json'">JSON</button>
    </div>
    <div v-if="result && activeTab === 'ip'" class="cards">
      <div v-for="row in ipRows" :key="row.name" class="card">
        <div class="card-head">
          <div class="provider-name">{{ row.name }}</div>
          <span class="family" :class="row.family.toLowerCase()"><span class="dot" />{{ row.family }}</span>
        </div>
        <div class="ip-text">{{ row.ip }}</div>
      </div>
    </div>
    <pre v-if="result && activeTab === 'json'" class="result">{{ JSON.stringify(result, null, 2) }}</pre>
  </div>
</template>

<script>
app.component("whatismyip-page", {
  template: "#whatismyip-page",
  data() {
    return {
      result: null,
      error: '',
      loading: false,
      activeTab: 'ip',
      provider: 'https://checkip.dns.he.net/',
      providerOptions: [
        { value: 'https://checkip.dns.he.net/', label: 'Hurricane Electric (https://checkip.dns.he.net/) [Default]' },
        { value: 'https://ifconfig.me/ip', label: 'ifconfig.me (https://ifconfig.me/ip)' },
        { value: 'https://icanhazip.com', label: 'icanhazip.com (https://icanhazip.com)' },
        { value: 'https://api.ipify.org', label: 'ipify (https://api.ipify.org)' },
        { value: 'dns:opendns', label: 'OpenDNS resolver (myip.opendns.com)' },
        { value: '', label: 'All providers' },
      ],
    }
  },
  computed: {
    ipRows() {
      return Object.entries(this.result ?? {}).map(([name, ip]) => ({ name, ip, family: this.getIpFamily(ip) }))
    },
  },
  methods: {
    getIpFamily(value) {
      const input = String(value || '').trim()
      const ipv4 = /^(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$/.test(input)
      if (ipv4) return 'IPv4'
      if (input.includes(':')) return 'IPv6'
      return 'Unknown'
    },
    async check() {
      this.loading = true
      this.error = ''
      this.result = null
      this.activeTab = 'ip'
      try {
        const qs = this.provider ? `?provider=${encodeURIComponent(this.provider)}` : ''
        const res = await fetch(`/api/whatismyip${qs}`)
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
.whatismyip-page { padding: 1rem 0; }
.whatismyip-page h2 { margin-bottom: 0.25rem; }
.whatismyip-page .desc { color: #666; font-size: 0.9rem; margin-bottom: 1rem; }
.whatismyip-page .controls { display: flex; flex-direction: column; gap: 0.35rem; margin-bottom: 0.75rem; max-width: 740px; }
.whatismyip-page .label { font-size: 0.9rem; color: #334155; font-weight: 600; }
.whatismyip-page .select { padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; background: #fff; }
.whatismyip-page .btn { padding: 0.5rem 1.5rem; background: #1a1a2e; color: white; border: none; border-radius: 4px; cursor: pointer; margin-bottom: 1rem; }
.whatismyip-page .btn:hover { background: #2a2a4e; }
.whatismyip-page .btn:disabled { opacity: 0.6; }
.whatismyip-page .error { color: #d32f2f; margin-bottom: 1rem; }
.whatismyip-page .tabs { display: flex; gap: 0.5rem; margin: 0.2rem 0 0.8rem; }
.whatismyip-page .tab { border: 1px solid #cbd5e1; background: #fff; color: #334155; padding: 0.4rem 0.8rem; border-radius: 8px; cursor: pointer; font-size: 0.9rem; }
.whatismyip-page .tab.active { background: #1a1a2e; border-color: #1a1a2e; color: #fff; }
.whatismyip-page .cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 0.7rem; margin-bottom: 0.8rem; }
.whatismyip-page .card { border: 1px solid #e2e8f0; border-radius: 12px; background: #fff; padding: 0.9rem; box-shadow: 0 1px 1px rgb(15 23 42 / 0.04); }
.whatismyip-page .card-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.65rem; gap: 0.5rem; }
.whatismyip-page .provider-name { font-size: 0.88rem; color: #475569; text-transform: lowercase; }
.whatismyip-page .family { display: inline-flex; align-items: center; gap: 0.4rem; font-size: 0.78rem; font-weight: 700; border-radius: 999px; padding: 0.18rem 0.55rem; }
.whatismyip-page .family .dot { width: 0.55rem; height: 0.55rem; border-radius: 999px; display: inline-block; }
.whatismyip-page .family.ipv4 { background: #dbeafe; color: #1e3a8a; }
.whatismyip-page .family.ipv4 .dot { background: #2563eb; }
.whatismyip-page .family.ipv6 { background: #dcfce7; color: #14532d; }
.whatismyip-page .family.ipv6 .dot { background: #16a34a; }
.whatismyip-page .family.unknown { background: #f1f5f9; color: #334155; }
.whatismyip-page .family.unknown .dot { background: #64748b; }
.whatismyip-page .ip-text { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, 'Liberation Mono', monospace; font-size: 1.12rem; color: #0f172a; font-weight: 700; overflow-wrap: anywhere; }
.whatismyip-page .result { background: white; padding: 1rem; border-radius: 4px; overflow-x: auto; font-size: 0.85rem; }
</style>
