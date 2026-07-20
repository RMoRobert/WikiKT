/*
 * Draggable Bootstrap modals — grab a modal's title bar to slide it aside and reveal whatever sits
 * beneath (handy for the stacked asset picker → "Edit" detail modal). Pure progressive enhancement:
 * no dependency beyond the modal markup Bootstrap already renders, and it degrades to a plain,
 * centred modal wherever it's switched off.
 *
 * Scope & safety (deliberately conservative so responsive/touch layouts are untouched):
 *  - Enabled only for a fine pointer (mouse) at >=576px — `(min-width:576px) and (pointer:fine)`.
 *    Touch devices and phone-width viewports keep Bootstrap's default centred/full layout, so a
 *    stray finger-drag can never shove a modal off-screen.
 *  - The translate is applied to `.modal-content`, never `.modal-dialog`, so it can't fight
 *    Bootstrap's own dialog fade transform. Every modal still opens centred.
 *  - Position is reset on `hidden.bs.modal`, so reopening a modal always starts centred again.
 *  - A drag only begins on the header chrome; the close button and any other control in the header
 *    keep working. Full-screen modals are left alone.
 *  - Movement is clamped to keep the title bar on-screen, so a modal can always be grabbed back.
 */
(() => {
  if (window.__wkModalDrag) return;
  window.__wkModalDrag = true;

  const mq = window.matchMedia('(min-width: 576px) and (pointer: fine)');

  // One tiny stylesheet: a move cursor on draggable headers (gated by the same media query so touch
  // devices show no affordance), and a grabbing cursor + no text-selection while a drag is in flight.
  const style = document.createElement('style');
  style.textContent = [
    '@media (min-width:576px) and (pointer:fine){',
    '  .modal:not(.modal-fullscreen) .modal-header{cursor:move;}',
    '  .modal-header.wk-dragging{cursor:grabbing;}',
    '}',
    '.wk-modal-dragging, .wk-modal-dragging *{user-select:none !important;}',
  ].join('');
  document.head.appendChild(style);

  // Return the {header, content} to drag for a mousedown target, or null if this press shouldn't
  // start a drag (outside a header, on an interactive control, or a full-screen modal).
  function grabTarget(target) {
    const header = target.closest('.modal-header');
    if (!header) return null;
    if (target.closest('button, a, input, select, textarea, label, [data-bs-dismiss]')) return null;
    const content = header.closest('.modal-content');
    if (!content) return null;
    const dialog = content.closest('.modal-dialog');
    if (dialog && /modal-fullscreen/.test(dialog.className)) return null;
    return { header, content };
  }

  let drag = null;

  document.addEventListener('mousedown', (e) => {
    if (e.button !== 0 || !mq.matches) return;
    const hit = grabTarget(e.target);
    if (!hit) return;

    const rect = hit.content.getBoundingClientRect();
    const m = /translate\(\s*(-?[\d.]+)px\s*,\s*(-?[\d.]+)px/.exec(hit.content.style.transform);
    const baseX = m ? parseFloat(m[1]) : 0;
    const baseY = m ? parseFloat(m[2]) : 0;
    drag = {
      content: hit.content,
      startX: e.clientX,
      startY: e.clientY,
      baseX,
      baseY,
      // Where the content sits with zero translate, so we can clamp against the viewport.
      layoutX: rect.left - baseX,
      layoutY: rect.top - baseY,
      w: rect.width,
      h: rect.height,
    };
    hit.header.classList.add('wk-dragging');
    document.body.classList.add('wk-modal-dragging');
    e.preventDefault(); // suppress text selection / focus flicker on the title
  });

  document.addEventListener('mousemove', (e) => {
    if (!drag) return;
    const margin = 48; // keep at least this much of the title bar reachable on every edge
    const vw = window.innerWidth;
    const vh = window.innerHeight;
    let nx = drag.baseX + (e.clientX - drag.startX);
    let ny = drag.baseY + (e.clientY - drag.startY);
    // Clamp: the box may not leave more than (width|height - margin) past any edge.
    nx = Math.max(margin - drag.w - drag.layoutX, Math.min(vw - margin - drag.layoutX, nx));
    ny = Math.max(-drag.layoutY, Math.min(vh - margin - drag.layoutY, ny));
    drag.content.style.transform = `translate(${nx}px, ${ny}px)`;
  });

  function endDrag() {
    if (!drag) return;
    drag = null;
    document.body.classList.remove('wk-modal-dragging');
    document.querySelectorAll('.modal-header.wk-dragging').forEach((h) => h.classList.remove('wk-dragging'));
  }
  document.addEventListener('mouseup', endDrag);
  // If the button is released outside the window (or focus is lost), don't leave a stuck drag.
  window.addEventListener('blur', endDrag);

  // Bootstrap modal events bubble to document — reset any drag offset when a modal closes so it
  // reopens centred rather than wherever it was last left.
  document.addEventListener('hidden.bs.modal', (e) => {
    const content = e.target.querySelector('.modal-content');
    if (content) content.style.transform = '';
  });
})();
