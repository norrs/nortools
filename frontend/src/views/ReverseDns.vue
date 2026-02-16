<script setup lang="ts">
import { computed, ref } from 'vue';
import { reverseLookup } from '../api/client';
import CliCopy from '../components/CliCopy.vue';
import { buildCli } from '../utils/cli';

interface ReverseDnsResult {
  name: string;
  type: string;
  status: string;
  records: Array<{
    name: string;
    type: string;
    ttl: number;
    data: string;
  }>;
  isSuccessful: boolean;
  resolvers?: string[];
}

const ip = ref('');
const resolverPreset = ref('auto');
const customResolver = ref('');
const result = ref<ReverseDnsResult | null>(null);
const error = ref('');
const loading = ref(false);
const resolverOptions = [
  { value: 'auto', label: 'Local network resolver (auto-detect)' },
  { value: '1.1.1.1', label: 'Cloudflare (1.1.1.1)' },
  { value: '8.8.8.8', label: 'Google (8.8.8.8)' },
  { value: '9.9.9.9', label: 'Quad9 (9.9.9.9)' },
  { value: '208.67.222.222', label: 'OpenDNS (208.67.222.222)' },
  { value: '94.140.14.14', label: 'AdGuard (94.140.14.14)' },
  { value: 'custom', label: 'Custom resolver...' },
];
const selectedResolver = computed(() => {
  if (resolverPreset.value === 'auto') return undefined;
  if (resolverPreset.value === 'custom') return customResolver.value.trim() || undefined;
  return resolverPreset.value;
});
const cliCommand = computed(() => buildCli([
  'nortools',
  'ptr',
  '--json',
  ...(selectedResolver.value ? ['--server', selectedResolver.value] : []),
  ip.value,
]));
const cliDisabled = computed(() => !ip.value);

async function lookup() {
  if (!ip.value) return;
  loading.value = true;
  error.value = '';
  result.value = null;
  try {
    result.value = await reverseLookup(ip.value, selectedResolver.value);
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'An error occurred';
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="tool-view">
    <h2>Reverse DNS Lookup</h2>
    <p class="desc">Find the hostname for an IP address.</p>
    <form @submit.prevent="lookup" class="lookup-form">
      <input v-model="ip" placeholder="Enter IP address (e.g. 8.8.8.8)" class="input" />
      <select v-model="resolverPreset" class="select resolver-select">
        <option v-for="r in resolverOptions" :key="r.value" :value="r.value">{{ r.label }}</option>
      </select>
      <input
        v-if="resolverPreset === 'custom'"
        v-model="customResolver"
        placeholder="Custom DNS resolver (IP or host)"
        class="input resolver-input"
      />
      <button type="submit" :disabled="loading" class="btn">
        {{ loading ? 'Looking up...' : 'Lookup' }}
      </button>
    </form>
    <div v-if="error" class="error">{{ error }}</div>
    <div v-if="result?.resolvers?.length" class="resolver-info">
      Resolver{{ result.resolvers.length > 1 ? 's' : '' }}:
      <strong>{{ result.resolvers.join(', ') }}</strong>
    </div>
    <pre v-if="result" class="result">{{ JSON.stringify(result, null, 2) }}</pre>
    <CliCopy :command="cliCommand" :disabled="cliDisabled" />
  </div>
</template>

<style scoped>
.tool-view { padding: 1rem 0; }
h2 { margin-bottom: 0.25rem; }
.desc { color: #666; font-size: 0.9rem; margin-bottom: 1rem; }
.lookup-form { display: flex; gap: 0.5rem; margin-bottom: 1rem; }
.input { flex: 1; padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; font-size: 1rem; }
.select { padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; }
.resolver-select { min-width: 280px; }
.resolver-input { min-width: 240px; flex: 0.7; }
.btn { padding: 0.5rem 1.5rem; background: #1a1a2e; color: white; border: none; border-radius: 4px; cursor: pointer; }
.btn:hover { background: #2a2a4e; }
.btn:disabled { opacity: 0.6; }
.error { color: #d32f2f; margin-bottom: 1rem; }
.resolver-info { margin-bottom: 0.75rem; color: #334155; font-size: 0.9rem; }
.result { background: white; padding: 1rem; border-radius: 4px; overflow-x: auto; font-size: 0.85rem; }
</style>
