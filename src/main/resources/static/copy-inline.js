// Site-wide click-to-copy for small inline buttons: any <button data-copy="text"> copies that text to
// the clipboard and flashes a tick. Delegated from the document, so markup rendered later (modals,
// AJAX-loaded tables) works without re-wiring. Sibling of code-copy.js, which handles code blocks.
(() => {
  const copyIcon = '<i class="mdi mdi-content-copy" aria-hidden="true"></i>';
  const okIcon = '<i class="mdi mdi-check" aria-hidden="true"></i>';

  // Screen-reader parity for the visual checkmark: announce "Copied" via a shared off-screen live
  // region (WCAG 4.1.3). Shared id with code-copy.js so both reuse the one region.
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
    r.textContent = r.textContent === 'Copied' ? 'Copied ' : 'Copied';
  };

  const flash = (btn) => {
    btn.classList.add('copied');
    btn.innerHTML = okIcon;
    announceCopied();
    setTimeout(() => { btn.classList.remove('copied'); btn.innerHTML = copyIcon; }, 1500);
  };
  // execCommand path for insecure origins (plain http on a LAN) and older browsers, where
  // navigator.clipboard is absent or rejects.
  const fallbackCopy = (text, btn) => {
    const ta = document.createElement('textarea');
    ta.value = text; ta.style.position = 'fixed'; ta.style.opacity = '0';
    document.body.appendChild(ta); ta.focus(); ta.select();
    try { document.execCommand('copy'); flash(btn); } catch (e) { /* no-op */ }
    document.body.removeChild(ta);
  };

  document.addEventListener('click', (e) => {
    const btn = e.target && e.target.closest ? e.target.closest('button[data-copy]') : null;
    if (!btn) return;
    e.preventDefault();
    e.stopPropagation();   // keep the click off enclosing controls (e.g. a sortable table header cell)
    const text = btn.getAttribute('data-copy') || '';
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(() => flash(btn), () => fallbackCopy(text, btn));
    } else {
      fallbackCopy(text, btn);
    }
  });
})();
