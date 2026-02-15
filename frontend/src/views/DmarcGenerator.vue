<script setup lang="ts">
import { ref } from 'vue';
import { dmarcGenerator } from '../api/client';

const policy = ref('none');
const sp = ref('');
const pct = ref(100);
const rua = ref('');
const ruf = ref('');
const adkim = ref('r');
const aspf = ref('r');
const result = ref<unknown>(null);
const error = ref('');
const loading = ref(false);

async function generate() {
  loading.value = true;
  error.value = '';
  result.value = null;
  try {
    result.value = await dmarcGenerator({
      policy: policy.value,
      sp: sp.value || undefined,
      pct: pct.value,
      rua: rua.value || undefined,
      ruf: ruf.value || undefined,
      adkim: adkim.value,
      aspf: aspf.value,
    });
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'An error occurred';
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="tool-view">
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
  </div>
</template>

<style scoped>
.tool-view { padding: 1rem 0; }
h2 { margin-bottom: 0.25rem; }
.desc { color: #666; font-size: 0.9rem; margin-bottom: 1rem; }
.field { display: flex; flex-direction: column; gap: 0.25rem; margin-bottom: 0.75rem; flex: 1; }
.field label { font-size: 0.8rem; color: #666; }
.row { display: flex; gap: 0.75rem; flex-wrap: wrap; }
.input { padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; font-size: 1rem; }
.input-sm { max-width: 100px; }
.select { padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; }
.btn { padding: 0.5rem 1.5rem; background: #1a1a2e; color: white; border: none; border-radius: 4px; cursor: pointer; margin-bottom: 1rem; }
.btn:hover { background: #2a2a4e; }
.btn:disabled { opacity: 0.6; }
.error { color: #d32f2f; margin-bottom: 1rem; }
.result { background: white; padding: 1rem; border-radius: 4px; overflow-x: auto; font-size: 0.85rem; }
</style>

