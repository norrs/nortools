<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { aboutInfo } from '../api/client';

interface AboutBuildInfo {
  target?: string;
  mainClass?: string;
  buildTime?: string;
  buildTimestamp?: string;
  gitCommit?: string;
}

interface AboutResponse {
  appName: string;
  version: string;
  build: AboutBuildInfo;
  credits: string;
  inspiration: string;
  rfc: string;
}

const loading = ref(false);
const error = ref('');
const about = ref<AboutResponse | null>(null);

async function load() {
  loading.value = true;
  error.value = '';
  try {
    about.value = await aboutInfo() as AboutResponse;
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : 'Failed to load about info';
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="tool-view">
    <h2>About</h2>
    <p class="desc">Build information, credits, inspiration, and RFC notes.</p>

    <div v-if="loading">Loading...</div>
    <div v-else-if="error" class="error">{{ error }}</div>

    <div v-else-if="about" class="about-grid">
      <section class="card">
        <h3>{{ about.appName }}</h3>
        <div class="kv"><span>Version</span><strong>{{ about.version }}</strong></div>
        <div class="kv"><span>Git Commit</span><code>{{ about.build.gitCommit || 'unknown' }}</code></div>
        <div class="kv"><span>Build Time</span><code>{{ about.build.buildTime || 'unknown' }}</code></div>
        <div class="kv"><span>Build Target</span><code>{{ about.build.target || 'unknown' }}</code></div>
        <div class="kv"><span>Main Class</span><code>{{ about.build.mainClass || 'unknown' }}</code></div>
      </section>

      <section class="card">
        <h3>Credits</h3>
        <pre class="doc">{{ about.credits }}</pre>
      </section>

      <section class="card">
        <h3>Inspiration</h3>
        <pre class="doc">{{ about.inspiration }}</pre>
      </section>

      <section class="card">
        <h3>RFC</h3>
        <pre class="doc">{{ about.rfc }}</pre>
      </section>
    </div>
  </div>
</template>

<style scoped>
.tool-view { padding: 1rem 0; }
h2 { margin-bottom: 0.25rem; }
.desc { color: #666; font-size: 0.9rem; margin-bottom: 1rem; }
.error { color: #d32f2f; margin-bottom: 1rem; }
.about-grid { display: grid; gap: 1rem; }
.card { background: white; border-radius: 8px; padding: 1rem; box-shadow: 0 1px 2px rgba(0,0,0,0.06); }
.kv { display: grid; grid-template-columns: 120px 1fr; gap: 0.75rem; margin: 0.35rem 0; }
.kv span { color: #64748b; }
.doc { white-space: pre-wrap; word-break: break-word; font-size: 0.85rem; line-height: 1.45; background: #f8fafc; border-radius: 6px; padding: 0.75rem; }
</style>
