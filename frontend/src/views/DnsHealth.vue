<script setup lang="ts">
import { computed, ref } from 'vue';
import { dnsHealthCheck } from '../api/client';
import CliCopy from '../components/CliCopy.vue';
import { buildCli } from '../utils/cli';

const domain = ref('');
const result = ref<unknown>(null);
const error = ref('');
const loading = ref(false);
const cliCommand = computed(() => buildCli(['nortools', 'dns-health', '--json', domain.value]));
const cliDisabled = computed(() => !domain.value);

async function check() {
  if (!domain.value) return;
  loading.value = true;
  error.value = '';
  result.value = null;
  try {
    result.value = await dnsHealthCheck(domain.value);
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'An error occurred';
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="dns-health">
    <h2>DNS Health Check</h2>
    <form @submit.prevent="check" class="lookup-form">
      <input v-model="domain" placeholder="Enter domain (e.g. example.com)" class="input" />
      <button type="submit" :disabled="loading" class="btn">
        {{ loading ? 'Checking...' : 'Check Health' }}
      </button>
    </form>
    <div v-if="error" class="error">{{ error }}</div>
    <pre v-if="result" class="result">{{ JSON.stringify(result, null, 2) }}</pre>
    <CliCopy :command="cliCommand" :disabled="cliDisabled" />
  </div>
</template>

<style scoped>
.dns-health { padding: 1rem 0; }
h2 { margin-bottom: 1rem; }
.lookup-form { display: flex; gap: 0.5rem; margin-bottom: 1rem; }
.input { flex: 1; padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; font-size: 1rem; }
.btn { padding: 0.5rem 1.5rem; background: #1a1a2e; color: white; border: none; border-radius: 4px; cursor: pointer; }
.btn:hover { background: #2a2a4e; }
.btn:disabled { opacity: 0.6; }
.error { color: #d32f2f; margin-bottom: 1rem; }
.result { background: white; padding: 1rem; border-radius: 4px; overflow-x: auto; font-size: 0.85rem; }
</style>
