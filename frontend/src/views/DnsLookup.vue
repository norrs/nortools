<script setup lang="ts">
import { ref } from 'vue';
import { dnsLookup } from '../api/client';

const domain = ref('');
const recordType = ref('A');
const result = ref<unknown>(null);
const error = ref('');
const loading = ref(false);

const recordTypes = ['A', 'AAAA', 'MX', 'NS', 'TXT', 'CNAME', 'SOA', 'PTR', 'SRV', 'CAA'];

async function lookup() {
  if (!domain.value) return;
  loading.value = true;
  error.value = '';
  result.value = null;
  try {
    result.value = await dnsLookup(recordType.value, domain.value);
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'An error occurred';
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="dns-lookup">
    <h2>DNS Lookup</h2>
    <form @submit.prevent="lookup" class="lookup-form">
      <input v-model="domain" placeholder="Enter domain (e.g. example.com)" class="input" />
      <select v-model="recordType" class="select">
        <option v-for="t in recordTypes" :key="t" :value="t">{{ t }}</option>
      </select>
      <button type="submit" :disabled="loading" class="btn">
        {{ loading ? 'Looking up...' : 'Lookup' }}
      </button>
    </form>
    <div v-if="error" class="error">{{ error }}</div>
    <pre v-if="result" class="result">{{ JSON.stringify(result, null, 2) }}</pre>
  </div>
</template>

<style scoped>
.dns-lookup { padding: 1rem 0; }
h2 { margin-bottom: 1rem; }
.lookup-form { display: flex; gap: 0.5rem; margin-bottom: 1rem; }
.input { flex: 1; padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; font-size: 1rem; }
.select { padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; }
.btn { padding: 0.5rem 1.5rem; background: #1a1a2e; color: white; border: none; border-radius: 4px; cursor: pointer; }
.btn:hover { background: #2a2a4e; }
.btn:disabled { opacity: 0.6; }
.error { color: #d32f2f; margin-bottom: 1rem; }
.result { background: white; padding: 1rem; border-radius: 4px; overflow-x: auto; font-size: 0.85rem; }
</style>

