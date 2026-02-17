<template id="dmarc-page">
  <div class="tool-view dmarc-page">
    <h2>DMARC Record Lookup</h2>
    <p class="desc">Check DMARC policy for a domain.</p>
    <form @submit.prevent="lookup" class="lookup-form">
      <input v-model="domain" placeholder="Enter domain (e.g. google.com)" class="input" />
      <button type="submit" :disabled="loading" class="btn">
        {{ loading ? 'Looking up...' : 'Lookup' }}
      </button>
    </form>
    <div v-if="error" class="error">{{ error }}</div>
    <pre v-if="result" class="result">{{ JSON.stringify(result, null, 2) }}</pre>
  </div>
</template>

<script>
app.component("dmarc-page", {
  template: "#dmarc-page",
  data() {
    return {
      domain: "",
      result: null,
      error: "",
      loading: false,
    }
  },
  methods: {
    async lookup() {
      if (!this.domain) return
      this.loading = true
      this.error = ""
      this.result = null
      try {
        const res = await fetch(`/api/dmarc/${encodeURIComponent(this.domain)}`)
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

.dmarc-page .tool-view { padding: 1rem 0; 
}
.dmarc-page h2 { margin-bottom: 0.25rem; 
}
.dmarc-page .desc { color: #666; font-size: 0.9rem; margin-bottom: 1rem; 
}
.dmarc-page .lookup-form { display: flex; gap: 0.5rem; margin-bottom: 1rem; 
}
.dmarc-page .input { flex: 1; padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; font-size: 1rem; 
}
.dmarc-page .btn { padding: 0.5rem 1.5rem; background: #1a1a2e; color: white; border: none; border-radius: 4px; cursor: pointer; 
}
.dmarc-page .btn:hover  { background: #2a2a4e; 
}
.dmarc-page .btn:disabled  { opacity: 0.6; 
}
.dmarc-page .error { color: #d32f2f; margin-bottom: 1rem; 
}
.dmarc-page .result { background: white; padding: 1rem; border-radius: 4px; overflow-x: auto; font-size: 0.85rem; }
</style>
