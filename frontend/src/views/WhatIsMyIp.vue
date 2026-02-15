<script setup lang="ts">
import { ref } from 'vue';
import { whatIsMyIp } from '../api/client';

const result = ref<unknown>(null);
const error = ref('');
const loading = ref(false);

async function check() {
  loading.value = true;
  error.value = '';
  result.value = null;
  try {
    result.value = await whatIsMyIp();
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'An error occurred';
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="tool-view">
    <h2>What Is My IP</h2>
    <p class="desc">Detect your public IP address using multiple services.</p>
    <button @click="check" :disabled="loading" class="btn">
      {{ loading ? 'Detecting...' : 'Detect My IP' }}
    </button>
    <div v-if="error" class="error">{{ error }}</div>
    <pre v-if="result" class="result">{{ JSON.stringify(result, null, 2) }}</pre>
  </div>
</template>

<style scoped>
.tool-view { padding: 1rem 0; }
h2 { margin-bottom: 0.25rem; }
.desc { color: #666; font-size: 0.9rem; margin-bottom: 1rem; }
.btn { padding: 0.5rem 1.5rem; background: #1a1a2e; color: white; border: none; border-radius: 4px; cursor: pointer; margin-bottom: 1rem; }
.btn:hover { background: #2a2a4e; }
.btn:disabled { opacity: 0.6; }
.error { color: #d32f2f; margin-bottom: 1rem; }
.result { background: white; padding: 1rem; border-radius: 4px; overflow-x: auto; font-size: 0.85rem; }
</style>

