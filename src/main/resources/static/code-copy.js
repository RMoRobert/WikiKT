// Adds a small "copy to clipboard" button to each rendered code block. Loaded after highlight.js so it
// runs on the already-highlighted <pre><code>; copying uses code.textContent (clean source, no markup).
(() => {
  const copyIcon = '<i class="mdi mdi-content-copy" aria-hidden="true"></i>';
  const okIcon = '<i class="mdi mdi-check" aria-hidden="true"></i>';

  // Screen-reader parity for the visual checkmark: announce "Copied" via a shared off-screen live
  // region (WCAG 4.1.3), so keyboard/AT users get the same confirmation sighted users do.
  const announceCopied = () => {
    let r = document.getElementById('wk-copy-status');
    if (!r) {
      r = document.createElement('div');
      r.id = 'wk-copy-status';
      r.className = 'visually-hidden';
      r.setAttribute('role', 'status');
      r.setAttribute('aria-live', 'polite');
      document.body.appendChild(r);
    }
    // Toggle a trailing space so an identical consecutive message still registers as a change.
    r.textContent = r.textContent === 'Copied' ? 'Copied ' : 'Copied';
  };

  document.querySelectorAll('.wiki-content pre > code').forEach((code) => {
    const pre = code.parentElement;
    if (pre.querySelector('.code-copy-btn')) return; // idempotent
    pre.classList.add('code-copy');

    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'code-copy-btn';
    btn.title = 'Copy';
    btn.setAttribute('aria-label', 'Copy code to clipboard');
    btn.innerHTML = copyIcon;

    const flash = () => {
      btn.classList.add('copied');
      btn.innerHTML = okIcon;
      announceCopied();
      setTimeout(() => { btn.classList.remove('copied'); btn.innerHTML = copyIcon; }, 1500);
    };
    const fallbackCopy = (text) => {
      const ta = document.createElement('textarea');
      ta.value = text; ta.style.position = 'fixed'; ta.style.opacity = '0';
      document.body.appendChild(ta); ta.focus(); ta.select();
      try { document.execCommand('copy'); flash(); } catch (e) { /* no-op */ }
      document.body.removeChild(ta);
    };
    btn.addEventListener('click', () => {
      const text = code.textContent;
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(flash, () => fallbackCopy(text));
      } else {
        fallbackCopy(text);
      }
    });

    pre.appendChild(btn);
  });
})();
