<script setup lang="ts">
import { ref } from 'vue';

defineProps<{ command: string; disabled?: boolean }>();

const copied = ref(false);

async function copy(command: string, disabled?: boolean) {
  if (disabled) return;
  try {
    await navigator.clipboard.writeText(command);
    copied.value = true;
    setTimeout(() => { copied.value = false; }, 1500);
  } catch {
    copied.value = false;
  }
}
</script>

<template>
  <div class="cli-copy">
    <div class="cli-label">CLI (JSON)</div>
    <div class="cli-row">
      <code class="cli-command">{{ command }}</code>
      <button class="cli-btn" :disabled="disabled" @click="copy(command, disabled)">
        {{ copied ? 'Copied' : 'Copy CLI Command' }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.cli-copy { margin-top: 1.25rem; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 0.75rem 1rem; width: 100%; }
.cli-label { font-size: 0.75rem; text-transform: uppercase; letter-spacing: 0.08em; color: #64748b; margin-bottom: 0.5rem; }
.cli-row { display: flex; gap: 0.75rem; align-items: center; flex-wrap: wrap; }
.cli-command { background: #0f172a; color: #e2e8f0; padding: 0.5rem 0.75rem; border-radius: 6px; font-size: 0.85rem; overflow-x: auto; flex: 1; min-width: 200px; }
.cli-btn { padding: 0.45rem 0.9rem; background: #1a1a2e; color: white; border: none; border-radius: 6px; cursor: pointer; font-size: 0.85rem; white-space: nowrap; }
.cli-btn:hover { background: #2a2a4e; }
.cli-btn:disabled { opacity: 0.6; cursor: not-allowed; }
</style>
