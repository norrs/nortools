<script setup lang="ts">
import { computed, ref } from 'vue';
import { pingCheck } from '../api/client';
import CliCopy from '../components/CliCopy.vue';
import { buildCli } from '../utils/cli';

const host = ref('');
const count = ref(4);
const result = ref<unknown>(null);
const error = ref('');
const loading = ref(false);
const cliCommand = computed(() => buildCli([
  'nortools',
  'ping',
  '--json',
  '--count',
  String(count.value),
  host.value,
]));
const cliDisabled = computed(() => !host.value);

async function check() {
  if (!host.value) return;
  loading.value = true;
  error.value = '';
  result.value = null;
  try {
    result.value = await pingCheck(host.value, count.value);
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'An error occurred';
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="tool-view">
    <h2>Ping</h2>
    <p class="desc">Ping a host to check reachability.</p>
    <form @submit.prevent="check" class="lookup-form">
      <input v-model="host" placeholder="Host (e.g. google.com)" class="input" />
      <input v-model.number="count" type="number" min="1" max="20" class="input input-sm" />
      <button type="submit" :disabled="loading" class="btn">
        {{ loading ? 'Pinging...' : 'Ping' }}
      </button>
    </form>
    <div v-if="error" class="error">{{ error }}</div>
    <pre v-if="result" class="result">{{ JSON.stringify(result, null, 2) }}</pre>
    <CliCopy :command="cliCommand" :disabled="cliDisabled" />
  </div>
</template>

<style scoped>
.tool-view { padding: 1rem 0; }
h2 { margin-bottom: 0.25rem; }
.desc { color: #666; font-size: 0.9rem; margin-bottom: 1rem; }
.lookup-form { display: flex; gap: 0.5rem; margin-bottom: 1rem; flex-wrap: wrap; }
.input { flex: 1; min-width: 180px; padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; font-size: 1rem; }
.input-sm { max-width: 80px; min-width: 60px; flex: 0; }
.btn { padding: 0.5rem 1.5rem; background: #1a1a2e; color: white; border: none; border-radius: 4px; cursor: pointer; }
.btn:hover { background: #2a2a4e; }
.btn:disabled { opacity: 0.6; }
.error { color: #d32f2f; margin-bottom: 1rem; }
.result { background: white; padding: 1rem; border-radius: 4px; overflow-x: auto; font-size: 0.85rem; }
</style>
