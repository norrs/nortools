<template id="tcp-page">
  <section><div class="card gp-card"><h2>TCP Check</h2><form class="gp-form" @submit.prevent="go"><input v-model.trim="host" class="gp-input" placeholder="example.com"/><input v-model.number="port" class="gp-input" placeholder="443" type="number"/><button class="gp-btn" :disabled="loading||!host||!port">{{loading?'Checking...':'Check'}}</button></form><div v-if="error" class="gp-error">{{error}}</div></div><div v-if="result" class="card gp-card"><pre class="gp-json">{{pretty(result)}}</pre></div>  </section>
</template>
<script>
// JavalinVue dependency resolver marker
app.component("tcp-page", {
  template: "#tcp-page",
})
</script>
<script>
app.component("tcp-page", {template:"#tcp-page",data(){return{host:"",port:443,loading:false,error:"",result:null}},methods:{pretty(v){return JSON.stringify(v,null,2)},async go(){this.loading=true;this.error="";this.result=null;try{const r=await fetch(`/api/tcp/${encodeURIComponent(this.host)}/${encodeURIComponent(String(this.port))}`);if(!r.ok)throw new Error(`API error: ${r.status} ${r.statusText}`);this.result=await r.json()}catch(e){this.error=e instanceof Error?e.message:"Unexpected error"}finally{this.loading=false}}}})
</script>

<style>
.gp-card { margin-bottom: .8rem; }
.gp-form { display: flex; gap: .5rem; flex-wrap: wrap; margin-top: .6rem; }
.gp-input { min-width: 160px; padding: .55rem .65rem; border: 1px solid #d1d5db; border-radius: 6px; }
.gp-text { width: 100%; min-height: 160px; margin-bottom: .5rem; padding: .55rem .65rem; border: 1px solid #d1d5db; border-radius: 6px; }
.gp-btn { border: 0; background: #0f172a; color: #fff; border-radius: 6px; padding: .55rem .9rem; cursor: pointer; }
.gp-btn:disabled { opacity: .6; cursor: default; }
.gp-error { color: #b91c1c; margin-top: .55rem; }
.gp-json { margin: 0; }
.gp-check { font-size: .85rem; display: inline-flex; align-items: center; gap: .2rem; }
</style>
