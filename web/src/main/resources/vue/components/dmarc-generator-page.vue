<template id="dmarc-generator-page">
  <div class="tool-view dmarc-generator-page">
    <h2>DMARC Record Generator</h2>
    <p class="desc">Build a DMARC record from policy options.</p>
    <form @submit.prevent="generate">
      <div class="row">
        <div class="field">
          <label>Policy</label>
          <select v-model="policy" class="select">
            <option value="none">none</option>
            <option value="quarantine">quarantine</option>
            <option value="reject">reject</option>
          </select>
        </div>
        <div class="field">
          <label>Subdomain Policy</label>
          <select v-model="sp" class="select">
            <option value="">inherit</option>
            <option value="none">none</option>
            <option value="quarantine">quarantine</option>
            <option value="reject">reject</option>
          </select>
        </div>
        <div class="field">
          <label>Percentage (0-100)</label>
          <input v-model.number="pct" type="number" min="0" max="100" class="input input-sm" />
        </div>
      </div>
      <div class="row">
        <div class="field">
          <label>Aggregate Reports (rua)</label>
          <input v-model="rua" placeholder="mailto:dmarc@example.com" class="input" />
        </div>
        <div class="field">
          <label>Forensic Reports (ruf)</label>
          <input v-model="ruf" placeholder="mailto:forensic@example.com" class="input" />
        </div>
      </div>
      <div class="row">
        <div class="field">
          <label>DKIM Alignment</label>
          <select v-model="adkim" class="select">
            <option value="r">relaxed</option>
            <option value="s">strict</option>
          </select>
        </div>
        <div class="field">
          <label>SPF Alignment</label>
          <select v-model="aspf" class="select">
            <option value="r">relaxed</option>
            <option value="s">strict</option>
          </select>
        </div>
      </div>
      <button type="submit" :disabled="loading" class="btn">
        {{ loading ? 'Generating...' : 'Generate DMARC' }}
      </button>
    </form>
    <div v-if="error" class="error">{{ error }}</div>
    <pre v-if="result" class="result">{{ JSON.stringify(result, null, 2) }}</pre>
    <div class="cli-copy">
      <div class="cli-label">CLI (JSON)</div>
      <div class="cli-row">
        <code class="cli-command">{{ cliCommand }}</code>
        <button class="cli-btn" @click="copyCli">
          {{ copied ? "Copied" : "Copy CLI Command" }}
        </button>
      </div>
    </div>
  </div>
</template>

<script>
app.component("dmarc-generator-page", {
  template: "#dmarc-generator-page",
  data() {
    return {
      policy: "none",
      sp: "",
      pct: 100,
      rua: "",
      ruf: "",
      adkim: "r",
      aspf: "r",
      result: null,
      error: "",
      loading: false,
      copied: false,
    };
  },
  computed: {
    cliCommand() {
      const parts = ["nortools", "dmarc-generator", "--json", "--policy", this.policy];
      if (this.sp) parts.push("--sp", this.sp);
      if (this.pct !== 100) parts.push("--pct", String(this.pct));
      if (this.rua) parts.push("--rua", this.rua);
      if (this.ruf) parts.push("--ruf", this.ruf);
      if (this.adkim !== "r") parts.push("--adkim", this.adkim);
      if (this.aspf !== "r") parts.push("--aspf", this.aspf);
      return parts.join(" ");
    },
  },
  methods: {
    async copyCli() {
      try {
        await navigator.clipboard.writeText(this.cliCommand);
        this.copied = true;
        setTimeout(() => { this.copied = false; }, 1500);
      } catch (_) {
        this.copied = false;
      }
    },
    async generate() {
      this.loading = true;
      this.error = "";
      this.result = null;
      try {
        const params = new URLSearchParams();
        params.set("policy", this.policy);
        if (this.sp) params.set("sp", this.sp);
        if (this.pct !== 100) params.set("pct", String(this.pct));
        if (this.rua) params.set("rua", this.rua);
        if (this.ruf) params.set("ruf", this.ruf);
        if (this.adkim !== "r") params.set("adkim", this.adkim);
        if (this.aspf !== "r") params.set("aspf", this.aspf);
        const response = await fetch(`/api/dmarc-generator?${params.toString()}`);
        if (!response.ok) throw new Error(`API error: ${response.status} ${response.statusText}`);
        this.result = await response.json();
      } catch (e) {
        this.error = e instanceof Error ? e.message : "An error occurred";
      } finally {
        this.loading = false;
      }
    },
  },
});
</script>

<style>

.dmarc-generator-page .tool-view { padding: 1rem 0; 
}
.dmarc-generator-page h2 { margin-bottom: 0.25rem; 
}
.dmarc-generator-page .desc { color: #666; font-size: 0.9rem; margin-bottom: 1rem; 
}
.dmarc-generator-page .field { display: flex; flex-direction: column; gap: 0.25rem; margin-bottom: 0.75rem; flex: 1; }
.dmarc-generator-page .field label { font-size: 0.8rem; color: #666; 
}
.dmarc-generator-page .row { display: flex; gap: 0.75rem; flex-wrap: wrap; 
}
.dmarc-generator-page .input { padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; font-size: 1rem; 
}
.dmarc-generator-page .input-sm { max-width: 100px; 
}
.dmarc-generator-page .select { padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; 
}
.dmarc-generator-page .btn { padding: 0.5rem 1.5rem; background: #1a1a2e; color: white; border: none; border-radius: 4px; cursor: pointer; margin-bottom: 1rem; 
}
.dmarc-generator-page .btn:hover  { background: #2a2a4e; 
}
.dmarc-generator-page .btn:disabled  { opacity: 0.6; 
}
.dmarc-generator-page .error { color: #d32f2f; margin-bottom: 1rem; 
}
.dmarc-generator-page .result { background: white; padding: 1rem; border-radius: 4px; overflow-x: auto; font-size: 0.85rem; 
}
.dmarc-generator-page .cli-copy { margin-top: 1.25rem; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 0.75rem 1rem; width: 100%; 
}
.dmarc-generator-page .cli-label { font-size: 0.75rem; text-transform: uppercase; letter-spacing: 0.08em; color: #64748b; margin-bottom: 0.5rem; 
}
.dmarc-generator-page .cli-row { display: flex; gap: 0.75rem; align-items: center; flex-wrap: wrap; 
}
.dmarc-generator-page .cli-command { background: #0f172a; color: #e2e8f0; padding: 0.5rem 0.75rem; border-radius: 6px; font-size: 0.85rem; overflow-x: auto; flex: 1; min-width: 200px; 
}
.dmarc-generator-page .cli-btn { padding: 0.45rem 0.9rem; background: #1a1a2e; color: white; border: none; border-radius: 6px; cursor: pointer; font-size: 0.85rem; white-space: nowrap; 
}
.dmarc-generator-page .cli-btn:hover  { background: #2a2a4e; 
}
.dmarc-generator-page .cli-btn:disabled  { opacity: 0.6; cursor: not-allowed; }
</style>
