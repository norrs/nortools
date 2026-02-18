<template id="dns-lookup-page">
  <div class="tool-view dns-lookup-page">
    <h2>DNS Lookup</h2>
    <p class="desc">Query DNS records using system or custom resolvers.</p>
    <form @submit.prevent="lookup" class="lookup-form">
      <input v-model="domain" placeholder="Domain (e.g. example.com)" class="input" />
      <select v-model="recordType" class="input input-sm">
        <option>A</option><option>AAAA</option><option>MX</option><option>TXT</option><option>CNAME</option><option>NS</option><option>SOA</option><option>SRV</option>
      </select>
      <select v-model="resolverPreset" class="input input-sm">
        <option value="auto">System resolver</option>
        <option value="google">Google (8.8.8.8)</option>
        <option value="cloudflare">Cloudflare (1.1.1.1)</option>
        <option value="quad9">Quad9 (9.9.9.9)</option>
        <option value="custom">Custom</option>
      </select>
      <input v-if="resolverPreset === 'custom'" v-model="customResolver" placeholder="Custom resolver IP" class="input input-sm" />
      <button type="submit" :disabled="loading" class="btn">{{ loading ? 'Looking up...' : 'Lookup' }}</button>
    </form>
    <div v-if="error" class="error">{{ error }}</div>
    <pre v-if="result" class="result">{{ JSON.stringify(result, null, 2) }}</pre>
  </div>
</template>
<script>
// JavalinVue dependency resolver marker
app.component("dns-lookup-page", {
  template: "#dns-lookup-page",
})
</script>
<script>
app.component("dns-lookup-page", { template: "#dns-lookup-page", data() { return { domain: '', recordType: 'A', resolverPreset: 'auto', customResolver: '', loading: false, error: '', result: null } }, computed: { selectedResolver() { if (this.resolverPreset === 'google') return '8.8.8.8'; if (this.resolverPreset === 'cloudflare') return '1.1.1.1'; if (this.resolverPreset === 'quad9') return '9.9.9.9'; if (this.resolverPreset === 'custom') return this.customResolver.trim(); return '' } }, methods: { async lookup() { if (!this.domain) return; this.loading = true; this.error=''; this.result=null; try { const qs = this.selectedResolver ? `?server=${encodeURIComponent(this.selectedResolver)}` : ''; const r = await fetch(`/api/dns/${encodeURIComponent(this.recordType)}/${encodeURIComponent(this.domain)}${qs}`); if (!r.ok) throw new Error(`API error: ${r.status} ${r.statusText}`); this.result = await r.json(); } catch (e) { this.error = e instanceof Error ? e.message : 'An error occurred'; } finally { this.loading = false; } } } })
</script>
<style>

.dns-lookup-page .tool-view { padding: 1rem 0; 
} .dns-lookup-page .desc { color:#666; font-size:.9rem; margin-bottom:1rem; 
} .dns-lookup-page .lookup-form { display:flex; gap:.5rem; margin-bottom:1rem; flex-wrap:wrap; 
} .dns-lookup-page .input { flex:1; min-width:160px; padding:.5rem; border:1px solid #ddd; border-radius:4px; font-size:1rem; 
} .dns-lookup-page .input-sm { flex:0; min-width:160px; 
} .dns-lookup-page .btn { padding:.5rem 1.5rem; background:#1a1a2e; color:white; border:none; border-radius:4px; cursor:pointer; 
} .dns-lookup-page .btn:hover  { background:#2a2a4e; 
} .dns-lookup-page .btn:disabled  { opacity:.6; 
} .dns-lookup-page .error { color:#d32f2f; margin-bottom:1rem; 
} .dns-lookup-page .result { background:white; padding:1rem; border-radius:4px; overflow-x:auto; font-size:.85rem; }
</style>
