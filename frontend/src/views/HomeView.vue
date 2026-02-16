<script setup lang="ts">
import { toolGroups } from '../navigation/toolGroups';
</script>

<template>
  <div class="home">
    <h1>NorTools</h1>
    <p class="subtitle">Network diagnostic and DNS tools</p>

    <div v-for="group in toolGroups" :key="group.name" class="tool-group">
      <h2>{{ group.name }}</h2>
      <div class="tool-cards">
        <RouterLink v-for="tool in group.tools" :key="tool.path" :to="tool.path" class="card">
          <h3>{{ tool.icon ? `${tool.icon} ${tool.name}` : tool.name }}</h3>
          <p>{{ tool.description || 'Open tool' }}</p>
        </RouterLink>
      </div>
    </div>
  </div>
</template>

<style scoped>
.home {
  padding: 2rem 0;
}

h1 {
  font-size: 2rem;
  color: #1a1a2e;
}

.subtitle {
  color: #666;
  margin-bottom: 2rem;
}

.tool-group {
  margin-bottom: 2rem;
}

.tool-group h2 {
  font-size: 1rem;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: #475569;
  margin-bottom: 0.75rem;
}

.tool-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 1rem;
}

.card {
  background: white;
  border-radius: 8px;
  padding: 1.5rem;
  text-decoration: none;
  color: inherit;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
  transition: box-shadow 0.2s;
}

.card:hover {
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
}

.card h3 {
  color: #1a1a2e;
  margin-bottom: 0.5rem;
}

.card p {
  color: #666;
  font-size: 0.9rem;
}
</style>
