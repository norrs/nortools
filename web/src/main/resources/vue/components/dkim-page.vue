<template id="dkim-page">
  <div class="tool-view dkim-page">
    <h2>DKIM Record Lookup</h2>
    <p class="desc">Look up DKIM public key records. Use a known selector, or auto-discover selectors.</p>
    <form @submit.prevent="lookup" class="lookup-form">
      <input v-model="domain" placeholder="Domain (e.g. google.com)" class="input" />
      <input v-model="selector" placeholder="Selector (leave blank to auto-discover)" class="input" />
      <button type="submit" :disabled="loading" class="btn">
        {{ loading ? 'Looking up...' : 'Lookup' }}
      </button>
    </form>
    <button @click="discover" :disabled="discovering || !domain" class="btn btn-secondary">
      {{ discovering ? 'Discovering...' : '🔍 Auto-Discover Selectors' }}
    </button>
    <div v-if="error" class="error">{{ error }}</div>
    <pre v-if="discoverResult" class="result">{{ JSON.stringify(discoverResult, null, 2) }}</pre>
    <pre v-if="result && !discoverResult" class="result">{{ JSON.stringify(result, null, 2) }}</pre>
    <div class="cli-copy">
      <div class="cli-label">CLI (JSON)</div>
      <div class="cli-row">
        <code class="cli-command">{{ cliCommand }}</code>
        <button class="cli-btn" :disabled="!domain" @click="copyCli">
          {{ copied ? "Copied" : "Copy CLI Command" }}
        </button>
      </div>
    </div>
  </div>
</template>

<script>
app.component("dkim-page", {
  template: "#dkim-page",
  data() {
    return {
      domain: "",
      selector: "",
      result: null,
      discoverResult: null,
      error: "",
      loading: false,
      discovering: false,
      copied: false,
    };
  },
  computed: {
    cliCommand() {
      if (this.selector) {
        return `nortools dkim --json ${this.selector} ${this.domain}`;
      }
      return `nortools dkim --json --discover ${this.domain}`;
    },
  },
  methods: {
    async copyCli() {
      if (!this.domain) return;
      try {
        await navigator.clipboard.writeText(this.cliCommand);
        this.copied = true;
        setTimeout(() => { this.copied = false; }, 1500);
      } catch (_) {
        this.copied = false;
      }
    },
    async lookup() {
      if (!this.domain) return;
      this.loading = true;
      this.error = "";
      this.result = null;
      this.discoverResult = null;
      try {
        let response;
        if (this.selector) {
          response = await fetch(`/api/dkim/${encodeURIComponent(this.selector)}/${encodeURIComponent(this.domain)}`);
        } else {
          response = await fetch(`/api/dkim-discover/${encodeURIComponent(this.domain)}`);
        }
        if (!response.ok) throw new Error(`API error: ${response.status} ${response.statusText}`);
        this.result = await response.json();
      } catch (e) {
        this.error = e instanceof Error ? e.message : "An error occurred";
      } finally {
        this.loading = false;
      }
    },
    async discover() {
      if (!this.domain) return;
      this.discovering = true;
      this.discoverResult = null;
      this.error = "";
      try {
        const response = await fetch(`/api/dkim-discover/${encodeURIComponent(this.domain)}`);
        if (!response.ok) throw new Error(`API error: ${response.status} ${response.statusText}`);
        this.discoverResult = await response.json();
      } catch (e) {
        this.error = e instanceof Error ? e.message : "An error occurred";
      } finally {
        this.discovering = false;
      }
    },
  },
});
</script>

<style>

.dkim-page .tool-view { padding: 1rem 0; 
}
.dkim-page h2 { margin-bottom: 0.25rem; 
}
.dkim-page .desc { color: #666; font-size: 0.9rem; margin-bottom: 1rem; 
}
.dkim-page .lookup-form { display: flex; gap: 0.5rem; margin-bottom: 0.75rem; flex-wrap: wrap; 
}
.dkim-page .input { flex: 1; min-width: 180px; padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; font-size: 1rem; 
}
.dkim-page .btn { padding: 0.5rem 1.5rem; background: #1a1a2e; color: white; border: none; border-radius: 4px; cursor: pointer; 
}
.dkim-page .btn:hover  { background: #2a2a4e; 
}
.dkim-page .btn:disabled  { opacity: 0.6; 
}
.dkim-page .btn-secondary { background: #555; margin-bottom: 1rem; 
}
.dkim-page .btn-secondary:hover  { background: #666; 
}
.dkim-page .error { color: #d32f2f; margin-bottom: 1rem; 
}
.dkim-page .result { background: white; padding: 1rem; border-radius: 4px; overflow-x: auto; font-size: 0.85rem; 
}
.dkim-page .cli-copy { margin-top: 1.25rem; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 0.75rem 1rem; width: 100%; 
}
.dkim-page .cli-label { font-size: 0.75rem; text-transform: uppercase; letter-spacing: 0.08em; color: #64748b; margin-bottom: 0.5rem; 
}
.dkim-page .cli-row { display: flex; gap: 0.75rem; align-items: center; flex-wrap: wrap; 
}
.dkim-page .cli-command { background: #0f172a; color: #e2e8f0; padding: 0.5rem 0.75rem; border-radius: 6px; font-size: 0.85rem; overflow-x: auto; flex: 1; min-width: 200px; 
}
.dkim-page .cli-btn { padding: 0.45rem 0.9rem; background: #1a1a2e; color: white; border: none; border-radius: 6px; cursor: pointer; font-size: 0.85rem; white-space: nowrap; 
}
.dkim-page .cli-btn:hover  { background: #2a2a4e; 
}
.dkim-page .cli-btn:disabled  { opacity: 0.6; cursor: not-allowed; }
</style>
