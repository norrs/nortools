<script setup lang="ts">
import { RouterLink } from 'vue-router';
import { toolGroups } from '../navigation/toolGroups';
</script>

<template>
  <nav class="sidebar">
    <RouterLink to="/" class="logo">
      <h1>NorTools</h1>
    </RouterLink>
    <div v-for="group in toolGroups" :key="group.name" class="tool-group">
      <h3>{{ group.name }}</h3>
      <ul>
        <li v-for="tool in group.tools" :key="tool.path">
          <RouterLink :to="tool.path">{{ tool.name }}</RouterLink>
        </li>
      </ul>
    </div>
  </nav>
</template>

<style scoped>
.sidebar {
  width: 240px;
  background: #1a1a2e;
  color: #eee;
  padding: 1rem;
  height: 100vh;
  position: sticky;
  top: 0;
  align-self: flex-start;
  overflow-y: auto;
}

.logo {
  text-decoration: none;
  color: #4fc3f7;
}

.logo h1 {
  font-size: 1.4rem;
  margin-bottom: 1.5rem;
}

.tool-group {
  margin-bottom: 1.5rem;
}

.tool-group h3 {
  font-size: 0.75rem;
  text-transform: uppercase;
  letter-spacing: 0.1em;
  color: #888;
  margin-bottom: 0.5rem;
}

.tool-group ul {
  list-style: none;
}

.tool-group li a {
  display: block;
  padding: 0.4rem 0.5rem;
  color: #ccc;
  text-decoration: none;
  border-radius: 4px;
  font-size: 0.9rem;
}

.tool-group li a:hover,
.tool-group li a.router-link-active {
  background: rgba(255, 255, 255, 0.1);
  color: #4fc3f7;
}
</style>
