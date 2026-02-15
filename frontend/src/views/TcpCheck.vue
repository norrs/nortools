<script setup lang="ts">
import { ref } from 'vue';
import { tcpCheck } from '../api/client';

const host = ref('');
const port = ref(443);
const result = ref<unknown>(null);
const error = ref('');
const loading = ref(false);

async function check() {
  if (!host.value) return;
  loading.value = true;
  error.value = '';
  result.value = null;
  try {
    result.value = await tcpCheck(host.value, port.value);
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'An error occurred';
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="tool-view">
    <h2>TCP Port Check</h2>
    <p class="desc">Test TCP connectivity to a host and port.</p>
    <form @submit.prevent="check" class="lookup-form">
      <input v-model="host" placeholder="Host (e.g. google.com)" class="input" />
      <input v-model.number="port" type="number" placeholder="Port" class="input input-sm" />
      <button type="submit" :disabled="loading" class="btn">
        {{ loading ? 'Checking...' : 'Check' }}
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
.lookup-form { display: flex; gap: 0.5rem; margin-bottom: 1rem; flex-wrap: wrap; }
.input { flex: 1; min-width: 180px; padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; font-size: 1rem; }
.input-sm { max-width: 100px; min-width: 80px; flex: 0; }
.btn { padding: 0.5rem 1.5rem; background: #1a1a2e; color: white; border: none; border-radius: 4px; cursor: pointer; }
.btn:hover { background: #2a2a4e; }
.btn:disabled { opacity: 0.6; }
.error { color: #d32f2f; margin-bottom: 1rem; }
.result { background: white; padding: 1rem; border-radius: 4px; overflow-x: auto; font-size: 0.85rem; }
</style>

