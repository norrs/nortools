<template id="spf-generator-page">
  <div class="tool-view spf-generator-page">
    <h2>SPF Record Generator</h2>
    <p class="desc">Build an SPF record from components.</p>
    <form @submit.prevent="generate">
      <div class="field">
        <label>Includes (comma-separated)</label>
        <input v-model="includes" placeholder="_spf.google.com, spf.protection.outlook.com" class="input" />
      </div>
      <div class="row">
        <div class="field">
          <label>IPv4 (comma-separated)</label>
          <input v-model="ip4" placeholder="203.0.113.0/24" class="input" />
        </div>
        <div class="field">
          <label>IPv6 (comma-separated)</label>
          <input v-model="ip6" placeholder="" class="input" />
        </div>
      </div>
      <div class="row">
        <div class="checkboxes">
          <label><input type="checkbox" v-model="mx" /> Include MX</label>
          <label><input type="checkbox" v-model="a" /> Include A</label>
        </div>
        <div class="field">
          <label>All policy</label>
          <select v-model="allPolicy" class="select">
            <option>~all</option>
            <option>-all</option>
            <option>+all</option>
            <option>?all</option>
          </select>
        </div>
      </div>
      <button type="submit" :disabled="loading" class="btn">
        {{ loading ? 'Generating...' : 'Generate SPF' }}
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
app.component("spf-generator-page", {
  template: "#spf-generator-page",
  data() {
    return {
      includes: "",
      ip4: "",
      ip6: "",
      mx: false,
      a: false,
      allPolicy: "~all",
      result: null,
      error: "",
      loading: false,
      copied: false,
    };
  },
  computed: {
    cliCommand() {
      const parts = ["nortools", "spf-generator", "--json"];
      const includeList = this.includes.split(",").map((s) => s.trim()).filter(Boolean);
      const ip4List = this.ip4.split(",").map((s) => s.trim()).filter(Boolean);
      const ip6List = this.ip6.split(",").map((s) => s.trim()).filter(Boolean);
      includeList.forEach((inc) => parts.push("--include", inc));
      ip4List.forEach((ip) => parts.push("--ip4", ip));
      ip6List.forEach((ip) => parts.push("--ip6", ip));
      if (this.mx) parts.push("--mx");
      if (this.a) parts.push("--a");
      const allMap = { "~all": "softfail", "-all": "fail", "+all": "pass", "?all": "neutral" };
      parts.push("--all", allMap[this.allPolicy] || "softfail");
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
        if (this.includes) params.set("includes", this.includes);
        if (this.ip4) params.set("ip4", this.ip4);
        if (this.ip6) params.set("ip6", this.ip6);
        if (this.mx) params.set("mx", "true");
        if (this.a) params.set("a", "true");
        params.set("all", this.allPolicy);
        const response = await fetch(`/api/spf-generator?${params.toString()}`);
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

.spf-generator-page .tool-view { padding: 1rem 0; 
}
.spf-generator-page h2 { margin-bottom: 0.25rem; 
}
.spf-generator-page .desc { color: #666; font-size: 0.9rem; margin-bottom: 1rem; 
}
.spf-generator-page .field { display: flex; flex-direction: column; gap: 0.25rem; margin-bottom: 0.75rem; flex: 1; }
.spf-generator-page .field label { font-size: 0.8rem; color: #666; 
}
.spf-generator-page .row { display: flex; gap: 0.75rem; flex-wrap: wrap; 
}
.spf-generator-page .input { padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; font-size: 1rem; 
}
.spf-generator-page .select { padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; 
}
.spf-generator-page .checkboxes { display: flex; gap: 1rem; align-items: flex-end; margin-bottom: 0.75rem; font-size: 0.9rem; }
.spf-generator-page .checkboxes label { display: flex; align-items: center; gap: 0.3rem; cursor: pointer; 
}
.spf-generator-page .btn { padding: 0.5rem 1.5rem; background: #1a1a2e; color: white; border: none; border-radius: 4px; cursor: pointer; margin-bottom: 1rem; 
}
.spf-generator-page .btn:hover  { background: #2a2a4e; 
}
.spf-generator-page .btn:disabled  { opacity: 0.6; 
}
.spf-generator-page .error { color: #d32f2f; margin-bottom: 1rem; 
}
.spf-generator-page .result { background: white; padding: 1rem; border-radius: 4px; overflow-x: auto; font-size: 0.85rem; 
}
.spf-generator-page .cli-copy { margin-top: 1.25rem; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 0.75rem 1rem; width: 100%; 
}
.spf-generator-page .cli-label { font-size: 0.75rem; text-transform: uppercase; letter-spacing: 0.08em; color: #64748b; margin-bottom: 0.5rem; 
}
.spf-generator-page .cli-row { display: flex; gap: 0.75rem; align-items: center; flex-wrap: wrap; 
}
.spf-generator-page .cli-command { background: #0f172a; color: #e2e8f0; padding: 0.5rem 0.75rem; border-radius: 6px; font-size: 0.85rem; overflow-x: auto; flex: 1; min-width: 200px; 
}
.spf-generator-page .cli-btn { padding: 0.45rem 0.9rem; background: #1a1a2e; color: white; border: none; border-radius: 6px; cursor: pointer; font-size: 0.85rem; white-space: nowrap; 
}
.spf-generator-page .cli-btn:hover  { background: #2a2a4e; 
}
.spf-generator-page .cli-btn:disabled  { opacity: 0.6; cursor: not-allowed; }
</style>
