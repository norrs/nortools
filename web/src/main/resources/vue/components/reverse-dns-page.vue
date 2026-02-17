<template id="reverse-dns-page">
  <div class="tool-view reverse-dns-page">
    <h2>Reverse DNS Lookup</h2>
    <p class="desc">Resolve PTR records for an IP address.</p>
    <form @submit.prevent="lookup" class="lookup-form">
      <input v-model="ip" placeholder="IP address (e.g. 8.8.8.8)" class="input" />
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
app.component("reverse-dns-page", {
  template: "#reverse-dns-page",
})
</script>
<script>
app.component("reverse-dns-page", { template: "#reverse-dns-page", data() { return { ip:'', resolverPreset:'auto', customResolver:'', result:null, error:'', loading:false } }, computed: { selectedResolver() { if (this.resolverPreset === 'google') return '8.8.8.8'; if (this.resolverPreset === 'cloudflare') return '1.1.1.1'; if (this.resolverPreset === 'quad9') return '9.9.9.9'; if (this.resolverPreset === 'custom') return this.customResolver.trim(); return '' } }, methods: { async lookup() { if (!this.ip) return; this.loading=true; this.error=''; this.result=null; try { const qs = this.selectedResolver ? `?server=${encodeURIComponent(this.selectedResolver)}` : ''; const r = await fetch(`/api/reverse/${encodeURIComponent(this.ip)}${qs}`); if (!r.ok) throw new Error(`API error: ${r.status} ${r.statusText}`); this.result = await r.json(); } catch (e) { this.error = e instanceof Error ? e.message : 'An error occurred'; } finally { this.loading=false; } } } })
</script>
<style>

.reverse-dns-page .tool-view { padding:1rem 0; 
} .reverse-dns-page .desc { color:#666; font-size:.9rem; margin-bottom:1rem; 
} .reverse-dns-page .lookup-form { display:flex; gap:.5rem; margin-bottom:1rem; flex-wrap:wrap; 
} .reverse-dns-page .input { flex:1; min-width:160px; padding:.5rem; border:1px solid #ddd; border-radius:4px; font-size:1rem; 
} .reverse-dns-page .input-sm { flex:0; min-width:160px; 
} .reverse-dns-page .btn { padding:.5rem 1.5rem; background:#1a1a2e; color:white; border:none; border-radius:4px; cursor:pointer; 
} .reverse-dns-page .btn:hover  { background:#2a2a4e; 
} .reverse-dns-page .btn:disabled  { opacity:.6; 
} .reverse-dns-page .error { color:#d32f2f; margin-bottom:1rem; 
} .reverse-dns-page .result { background:white; padding:1rem; border-radius:4px; overflow-x:auto; font-size:.85rem; }
</style>
