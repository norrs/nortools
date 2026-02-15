import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      // Use process.cwd() instead of import.meta.url to work in Bazel sandbox
      '@': process.cwd() + '/src',
    },
    dedupe: ['vue'],
  },
});

