<script setup lang="ts">
import { ref } from 'vue';
import { dkimLookup, dkimDiscover } from '../api/client';

const domain = ref('');
const selector = ref('');
const result = ref<unknown>(null);
const discoverResult = ref<unknown>(null);
const error = ref('');
const loading = ref(false);
const discovering = ref(false);

async function lookup() {
  if (!domain.value) return;
  loading.value = true;
  error.value = '';
  result.value = null;
  try {
    if (selector.value) {
      result.value = await dkimLookup(selector.value, domain.value);
    } else {
      result.value = await dkimDiscover(domain.value);
    }
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'An error occurred';
  } finally {
    loading.value = false;
  }
}

async function discover() {
  if (!domain.value) return;
  discovering.value = true;
  discoverResult.value = null;
  error.value = '';
  try {
    discoverResult.value = await dkimDiscover(domain.value);
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'An error occurred';
  } finally {
    discovering.value = false;
  }
}
</script>

<template>
  <div class="tool-view">
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
  </div>
</template>

<style scoped>
.tool-view { padding: 1rem 0; }
h2 { margin-bottom: 0.25rem; }
.desc { color: #666; font-size: 0.9rem; margin-bottom: 1rem; }
.lookup-form { display: flex; gap: 0.5rem; margin-bottom: 0.75rem; flex-wrap: wrap; }
.input { flex: 1; min-width: 180px; padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; font-size: 1rem; }
.btn { padding: 0.5rem 1.5rem; background: #1a1a2e; color: white; border: none; border-radius: 4px; cursor: pointer; }
.btn:hover { background: #2a2a4e; }
.btn:disabled { opacity: 0.6; }
.btn-secondary { background: #555; margin-bottom: 1rem; }
.btn-secondary:hover { background: #666; }
.error { color: #d32f2f; margin-bottom: 1rem; }
.result { background: white; padding: 1rem; border-radius: 4px; overflow-x: auto; font-size: 0.85rem; }
</style>

