<script setup lang="ts">
import { computed, ref } from 'vue';
import { spfGenerator } from '../api/client';
import CliCopy from '../components/CliCopy.vue';
import { buildCli } from '../utils/cli';

const includes = ref('');
const ip4 = ref('');
const ip6 = ref('');
const mx = ref(false);
const a = ref(false);
const allPolicy = ref('~all');
const result = ref<unknown>(null);
const error = ref('');
const loading = ref(false);
const cliCommand = computed(() => {
  const parts: Array<string | null> = ['nortools', 'spf-generator', '--json'];
  const includeList = includes.value.split(',').map(s => s.trim()).filter(Boolean);
  const ip4List = ip4.value.split(',').map(s => s.trim()).filter(Boolean);
  const ip6List = ip6.value.split(',').map(s => s.trim()).filter(Boolean);
  for (const inc of includeList) parts.push('--include', inc);
  for (const ip of ip4List) parts.push('--ip4', ip);
  for (const ip of ip6List) parts.push('--ip6', ip);
  if (mx.value) parts.push('--mx');
  if (a.value) parts.push('--a');
  const allMap: Record<string, string> = {
    '~all': 'softfail',
    '-all': 'fail',
    '+all': 'pass',
    '?all': 'neutral',
  };
  parts.push('--all', allMap[allPolicy.value] ?? 'softfail');
  return buildCli(parts);
});
const cliDisabled = computed(() => false);

async function generate() {
  loading.value = true;
  error.value = '';
  result.value = null;
  try {
    result.value = await spfGenerator({
      includes: includes.value || undefined,
      ip4: ip4.value || undefined,
      ip6: ip6.value || undefined,
      mx: mx.value,
      a: a.value,
      all: allPolicy.value,
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
    <CliCopy :command="cliCommand" :disabled="cliDisabled" />
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
.select { padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; }
.checkboxes { display: flex; gap: 1rem; align-items: flex-end; margin-bottom: 0.75rem; font-size: 0.9rem; }
.checkboxes label { display: flex; align-items: center; gap: 0.3rem; cursor: pointer; }
.btn { padding: 0.5rem 1.5rem; background: #1a1a2e; color: white; border: none; border-radius: 4px; cursor: pointer; margin-bottom: 1rem; }
.btn:hover { background: #2a2a4e; }
.btn:disabled { opacity: 0.6; }
.error { color: #d32f2f; margin-bottom: 1rem; }
.result { background: white; padding: 1rem; border-radius: 4px; overflow-x: auto; font-size: 0.85rem; }
</style>
