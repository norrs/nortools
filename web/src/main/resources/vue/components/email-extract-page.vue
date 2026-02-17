<template id="email-extract-page">
  <div class="tool-view email-extract-page">
    <h2>Email Extract</h2>
    <p class="desc">Extract email addresses from free text.</p>
    <form @submit.prevent="extract" class="lookup-form">
      <textarea v-model="text" class="input text" placeholder="Paste text here..."></textarea>
      <button type="submit" :disabled="loading || !text.trim()" class="btn">{{ loading ? 'Extracting...' : 'Extract' }}</button>
    </form>
    <div v-if="error" class="error">{{ error }}</div>
    <pre v-if="result" class="result">{{ JSON.stringify(result, null, 2) }}</pre>
  </div>
</template>
<script>
// JavalinVue dependency resolver marker
app.component("email-extract-page", {
  template: "#email-extract-page",
})
</script>
<script>
app.component("email-extract-page", { template: "#email-extract-page", data(){ return { text:'', loading:false, error:'', result:null } }, methods:{ async extract(){ if(!this.text.trim()) return; this.loading=true; this.error=''; this.result=null; try{ const r=await fetch('/api/email-extract', { method:'POST', headers:{'Content-Type':'text/plain'}, body:this.text }); if(!r.ok) throw new Error(`API error: ${r.status} ${r.statusText}`); this.result=await r.json(); } catch(e){ this.error=e instanceof Error?e.message:'An error occurred'; } finally{ this.loading=false; } } } })
</script>
<style>

.email-extract-page .tool-view {padding:1rem 0
}.email-extract-page .desc {color:#666;font-size:.9rem;margin-bottom:1rem
}.email-extract-page .lookup-form {display:flex;flex-direction:column;gap:.5rem;margin-bottom:1rem
}.email-extract-page .input {padding:.5rem;border:1px solid #ddd;border-radius:4px
}.email-extract-page .text {min-height:180px
}.email-extract-page .btn {align-self:flex-start;padding:.5rem 1.2rem;background:#1a1a2e;color:#fff;border:none;border-radius:4px;cursor:pointer
}.email-extract-page .btn:hover {background:#2a2a4e
}.email-extract-page .btn:disabled {opacity:.6
}.email-extract-page .error {color:#d32f2f;margin-bottom:1rem
}.email-extract-page .result {background:#fff;padding:1rem;border-radius:4px;overflow-x:auto;font-size:.85rem}
</style>
