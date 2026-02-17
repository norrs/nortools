<template>
  <div class="help-view">
    <h2>MTA-STS DNS Help</h2>
    <p class="desc">
      How to fix: <code>Mail / MTA-STS DNS</code> -> <em>No _mta-sts TXT record</em>
    </p>

    <section class="card">
      <h3>What Is MTA-STS?</h3>
      <p>
        <strong>MTA-STS</strong> means <em>Mail Transfer Agent Strict Transport Security</em>.
        It is a policy that tells other mail servers how your domain expects inbound email to be delivered securely.
      </p>
      <p>
        In simple terms: it helps prevent email from being sent to your domain over insecure or tampered connections.
      </p>
      <p>
        Technically: it lets a receiving domain publish rules (via DNS + HTTPS policy file) that require sending MTAs
        to use TLS and validate the MX destination against policy.
      </p>
    </section>

    <section class="card">
      <h3>Why It Exists</h3>
      <p>
        SMTP delivery on the internet has historically used opportunistic TLS. That means encryption is attempted,
        but delivery may fall back to plaintext or weak trust if the path is attacked or misconfigured.
      </p>
      <p>
        MTA-STS was created to reduce downgrade and MX redirection risks. It gives sending servers a signed/intended
        view of which mail hosts are valid for your domain and whether TLS is required.
      </p>
    </section>

    <section class="card">
      <h3>How This Helps Mail Servers</h3>
      <p>
        When a sender delivers mail to <code>user@yourdomain.com</code>, it can:
      </p>
      <ul>
        <li>discover your MTA-STS policy via <code>_mta-sts.yourdomain.com</code></li>
        <li>fetch policy from <code>https://mta-sts.yourdomain.com/.well-known/mta-sts.txt</code></li>
        <li>enforce TLS and verify it is connecting to an allowed MX host</li>
      </ul>
      <p>
        Result: stronger transport security and fewer chances that delivery is silently downgraded or redirected to a malicious endpoint.
      </p>
    </section>

    <section class="card">
      <h3>Before You Enable MTA-STS</h3>
      <p>Make sure these checks are in place first:</p>
      <ul>
        <li>Your domain has valid MX records and is intended to receive mail.</li>
        <li>All published MX servers support STARTTLS on SMTP port 25.</li>
        <li>All MX servers present valid public TLS certificates (trusted, unexpired, hostname matches).</li>
        <li><code>https://mta-sts.&lt;domain&gt;/.well-known/mta-sts.txt</code> is reachable with valid HTTPS.</li>
        <li>The DNS TXT record <code>_mta-sts.&lt;domain&gt; TXT "v=STSv1; id=..."</code> is published.</li>
        <li>Policy file syntax is valid: <code>version</code>, <code>mode</code>, <code>mx</code>, <code>max_age</code>.</li>
      </ul>
      <p><strong>Recommended rollout:</strong></p>
      <ul>
        <li>Start with <code>mode: testing</code>.</li>
        <li>Publish TLS-RPT (<code>_smtp._tls.&lt;domain&gt;</code>) to collect failure reports.</li>
        <li>Monitor reports and fix issues.</li>
        <li>Move to <code>mode: enforce</code> when all MX paths are consistently compliant.</li>
      </ul>
    </section>

    <section class="card">
      <h3>What This Check Means</h3>
      <p>
        Your domain does not publish the TXT record at <code>_mta-sts.&lt;domain&gt;</code>.
        Without this, receiving mail servers cannot discover your MTA-STS policy.
      </p>
    </section>

    <section class="card">
      <h3>DNS Record To Publish</h3>
      <p>Create this TXT record:</p>
      <pre class="code">_mta-sts.example.com.  IN  TXT  "v=STSv1; id=20260217T000000"</pre>
      <p class="hint">
        Replace <code>example.com</code> with your domain. Update <code>id=</code> whenever your policy changes.
      </p>
    </section>

    <section class="card">
      <h3>Also Required (Policy File)</h3>
      <p>MTA-STS also requires a policy file served over HTTPS:</p>
      <pre class="code">https://mta-sts.example.com/.well-known/mta-sts.txt</pre>
      <p>Example file content:</p>
      <pre class="code">version: STSv1
mode: enforce
mx: mail.example.com
max_age: 86400</pre>
    </section>

    <section class="card">
      <h3>Common Pitfalls</h3>
      <ul>
        <li>Wrong hostname: record must be under <code>_mta-sts.&lt;domain&gt;</code></li>
        <li>TXT value missing <code>v=STSv1</code></li>
        <li>Policy URL not reachable with valid TLS certificate</li>
        <li>Policy file missing required fields (<code>version</code>, <code>mode</code>, <code>max_age</code>)</li>
      </ul>
    </section>
  </div>
</template>

<style scoped>
.help-view { padding: 1rem 0; }
h2 { margin-bottom: 0.25rem; }
.desc { color: #666; font-size: 0.92rem; margin-bottom: 1rem; }
.card { background: white; border: 1px solid #e5e7eb; border-radius: 8px; padding: 0.9rem 1rem; margin-bottom: 0.8rem; }
h3 { font-size: 0.95rem; margin-bottom: 0.45rem; color: #0f172a; }
p { color: #334155; line-height: 1.45; }
.code { background: #0f172a; color: #e2e8f0; border-radius: 6px; padding: 0.7rem; overflow-x: auto; font-size: 0.82rem; margin: 0.5rem 0; }
.hint { font-size: 0.84rem; color: #475569; }
ul { margin-left: 1rem; color: #334155; line-height: 1.4; }
li { margin: 0.25rem 0; }
</style>
