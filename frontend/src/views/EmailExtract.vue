<script setup lang="ts">
import { ref } from 'vue';
import { emailExtract } from '../api/client';

const text = ref('');
const result = ref<unknown>(null);
const error = ref('');
const loading = ref(false);

async function extract() {
  if (!text.value) return;
  loading.value = true;
  error.value = '';
  result.value = null;
  try {
    result.value = await emailExtract(text.value);
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'An error occurred';
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="tool-view">
    <h2>Email Extractor</h2>
    <p class="desc">Extract email addresses from text.</p>
    <form @submit.prevent="extract">
      <textarea v-model="text" rows="6" placeholder="Paste headers or text here..." class="textarea"></textarea>
      <button type="submit" :disabled="loading" class="btn">
        {{ loading ? 'Extracting...' : 'Extract Emails' }}
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
.textarea { width: 100%; padding: 0.5rem; border: 1px solid #ddd; border-radius: 4px; font-size: 0.9rem; font-family: monospace; resize: vertical; margin-bottom: 0.75rem; }
.btn { padding: 0.5rem 1.5rem; background: #1a1a2e; color: white; border: none; border-radius: 4px; cursor: pointer; margin-bottom: 1rem; }
.btn:hover { background: #2a2a4e; }
.btn:disabled { opacity: 0.6; }
.error { color: #d32f2f; margin-bottom: 1rem; }
.result { background: white; padding: 1rem; border-radius: 4px; overflow-x: auto; font-size: 0.85rem; }
</style>

