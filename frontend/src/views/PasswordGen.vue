<script setup lang="ts">
import { ref } from 'vue';
import { passwordGen } from '../api/client';

const length = ref(16);
const count = ref(5);
const upper = ref(true);
const lower = ref(true);
const digits = ref(true);
const special = ref(true);
const result = ref<unknown>(null);
const error = ref('');
const loading = ref(false);

async function generate() {
  loading.value = true;
  error.value = '';
  result.value = null;
  try {
    result.value = await passwordGen({
      length: length.value,
      count: count.value,
      upper: upper.value,
      lower: lower.value,
      digits: digits.value,
      special: special.value,
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
    <h2>Password Generator</h2>
    <p class="desc">Generate secure random passwords.</p>
    <form @submit.prevent="generate">
      <div class="lookup-form">
        <div class="field">
          <label>Length</label>
          <input v-model.number="length" type="number" min="4" max="128" class="input input-sm" />
        </div>
        <div class="field">
          <label>Count</label>
          <input v-model.number="count" type="number" min="1" max="50" class="input input-sm" />
        </div>
      </div>
      <div class="checkboxes">
        <label><input type="checkbox" v-model="upper" /> Uppercase</label>
        <label><input type="checkbox" v-model="lower" /> Lowercase</label>
        <label><input type="checkbox" v-model="digits" /> Digits</label>
        <label><input type="checkbox" v-model="special" /> Special</label>
      </div>
      <button type="submit" :disabled="loading" class="btn">
        {{ loading ? 'Generating...' : 'Generate' }}
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
.lookup-form { display: flex; gap: 0.75rem; margin-bottom: 0.75rem; flex-wrap: wrap; }
.field { display: flex; flex-direction: column; gap: 0.25rem; }
.field label { font-size: 0.8rem; color: #666; }
.input { padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; font-size: 1rem; }
.input-sm { width: 100px; }
.checkboxes { display: flex; gap: 1rem; margin-bottom: 1rem; font-size: 0.9rem; }
.checkboxes label { display: flex; align-items: center; gap: 0.3rem; cursor: pointer; }
.btn { padding: 0.5rem 1.5rem; background: #1a1a2e; color: white; border: none; border-radius: 4px; cursor: pointer; margin-bottom: 1rem; }
.btn:hover { background: #2a2a4e; }
.btn:disabled { opacity: 0.6; }
.error { color: #d32f2f; margin-bottom: 1rem; }
.result { background: white; padding: 1rem; border-radius: 4px; overflow-x: auto; font-size: 0.85rem; }
</style>

