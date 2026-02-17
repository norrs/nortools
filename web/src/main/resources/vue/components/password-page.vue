<template id="password-page">
  <div class="tool-view password-page">
    <h2>Password Generator</h2>
    <p class="desc">Generate secure random passwords.</p>
    <form @submit.prevent="generate">
      <div class="lookup-form">
        <div class="field">
          <label>Length</label>
          <input v-model.number="length" type="number" min="4" max="128" class="input input-sm" />
        </div>
        <div class="field">
          <label>Count</label>
          <input v-model.number="count" type="number" min="1" max="50" class="input input-sm" />
        </div>
      </div>
      <div class="checkboxes">
        <label><input type="checkbox" v-model="upper" /> Uppercase</label>
        <label><input type="checkbox" v-model="lower" /> Lowercase</label>
        <label><input type="checkbox" v-model="digits" /> Digits</label>
        <label><input type="checkbox" v-model="special" /> Special</label>
      </div>
      <button type="submit" :disabled="loading" class="btn">
        {{ loading ? 'Generating...' : 'Generate' }}
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
app.component("password-page", {
  template: "#password-page",
  data() {
    return {
      length: 16,
      count: 5,
      upper: true,
      lower: true,
      digits: true,
      special: true,
      result: null,
      error: "",
      loading: false,
      copied: false,
    };
  },
  computed: {
    cliCommand() {
      const parts = [
        "nortools",
        "password-gen",
        "--json",
        "--length", String(this.length),
        "--count", String(this.count),
      ];
      if (!this.upper) parts.push("--no-uppercase");
      if (!this.lower) parts.push("--no-lowercase");
      if (!this.digits) parts.push("--no-digits");
      if (!this.special) parts.push("--no-special");
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
        params.set("length", String(this.length));
        params.set("count", String(this.count));
        params.set("upper", String(this.upper));
        params.set("lower", String(this.lower));
        params.set("digits", String(this.digits));
        params.set("special", String(this.special));
        const response = await fetch(`/api/password?${params.toString()}`);
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

.password-page .tool-view { padding: 1rem 0; 
}
.password-page h2 { margin-bottom: 0.25rem; 
}
.password-page .desc { color: #666; font-size: 0.9rem; margin-bottom: 1rem; 
}
.password-page .lookup-form { display: flex; gap: 0.75rem; margin-bottom: 0.75rem; flex-wrap: wrap; 
}
.password-page .field { display: flex; flex-direction: column; gap: 0.25rem; }
.password-page .field label { font-size: 0.8rem; color: #666; 
}
.password-page .input { padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; font-size: 1rem; 
}
.password-page .input-sm { width: 100px; 
}
.password-page .checkboxes { display: flex; gap: 1rem; margin-bottom: 1rem; font-size: 0.9rem; }
.password-page .checkboxes label { display: flex; align-items: center; gap: 0.3rem; cursor: pointer; 
}
.password-page .btn { padding: 0.5rem 1.5rem; background: #1a1a2e; color: white; border: none; border-radius: 4px; cursor: pointer; margin-bottom: 1rem; 
}
.password-page .btn:hover  { background: #2a2a4e; 
}
.password-page .btn:disabled  { opacity: 0.6; 
}
.password-page .error { color: #d32f2f; margin-bottom: 1rem; 
}
.password-page .result { background: white; padding: 1rem; border-radius: 4px; overflow-x: auto; font-size: 0.85rem; 
}
.password-page .cli-copy { margin-top: 1.25rem; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 0.75rem 1rem; width: 100%; 
}
.password-page .cli-label { font-size: 0.75rem; text-transform: uppercase; letter-spacing: 0.08em; color: #64748b; margin-bottom: 0.5rem; 
}
.password-page .cli-row { display: flex; gap: 0.75rem; align-items: center; flex-wrap: wrap; 
}
.password-page .cli-command { background: #0f172a; color: #e2e8f0; padding: 0.5rem 0.75rem; border-radius: 6px; font-size: 0.85rem; overflow-x: auto; flex: 1; min-width: 200px; 
}
.password-page .cli-btn { padding: 0.45rem 0.9rem; background: #1a1a2e; color: white; border: none; border-radius: 6px; cursor: pointer; font-size: 0.85rem; white-space: nowrap; 
}
.password-page .cli-btn:hover  { background: #2a2a4e; 
}
.password-page .cli-btn:disabled  { opacity: 0.6; cursor: not-allowed; }
</style>
