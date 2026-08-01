/*
 * Visual navigation menu editor. Progressive enhancement over the plain-text "definition" textarea, which is
 * still available. It parses that text into rows (link / header / divider, with one level of nesting), lets
 * an admin edit sidebar items with them with a live sidebar preview, and serializes back into the
 * raw text format the server parses (NavService.parseDefinition: 2-space indent = a nested link).
 * An "Edit Source" toggle reveals the raw text for power users or git sync..
 */
(function () {
  var root = document.getElementById('navEditor');
  var ta = document.getElementById('navDefinition');
  if (!root || !ta) return;
  var rowsEl = root.querySelector('[data-rows]');
  var previewEl = root.querySelector('[data-preview]');
  var previewBox = previewEl.parentNode; // the panel that scrolls once the menu outgrows the viewport
  var help = document.getElementById('navSyntaxHelp');
  var srcBtn = root.querySelector('[data-toggle-source]');
  var defLocale = root.getAttribute('data-default-locale') || 'en';

  var ICON_RE = /^:([a-z0-9-]+):\s*/;
  var DIV_RE = /^-{3,}$/;

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
    });
  }

  function parse(text) {
    var out = [];
    (text || '').split('\n').forEach(function (raw) {
      if (!raw.trim()) return;
      var depth = Math.min(1, Math.floor((raw.match(/^ */)[0].length) / 2));
      var line = raw.trim();
      if (DIV_RE.test(line)) { out.push({ type: 'divider', depth: 0 }); return; }
      var icon = '';
      var m = line.match(ICON_RE);
      if (m) { icon = m[1]; line = line.slice(m[0].length).trim(); }
      if (line.charAt(0) === '#') {
        var hl = line.replace(/^#\s*/, '').trim();
        if (hl) out.push({ type: 'header', icon: icon, label: hl, depth: 0 });
      } else {
        var i = line.indexOf('|');
        if (i < 0) return;
        var label = line.slice(0, i).trim();
        var target = line.slice(i + 1).trim();
        if (label && target) out.push({ type: 'link', icon: icon, label: label, target: target, depth: depth });
      }
    });
    return out;
  }

  function serializeItem(it) {
    if (it.type === 'divider') return '---';
    var pre = it.icon ? ':' + it.icon + ': ' : '';
    if (it.type === 'header') return pre + '# ' + (it.label || '');
    var pad = (it.depth || 0) >= 1 ? '  ' : '';
    return pad + pre + (it.label || '') + ' | ' + (it.target || '');
  }
  function sync() { ta.value = items.map(serializeItem).join('\n'); }

  var items = parse(ta.value);

  function iconTag(name) {
    return name
      ? '<i class="mdi mdi-' + esc(name) + '" aria-hidden="true"></i>'
      : '<i class="mdi mdi-link-variant text-secondary" aria-hidden="true"></i>';
  }

  // Index of the row that currently holds focus, so its entry in the preview can be marked. Kept in a
  // variable rather than toggled on the DOM because renderPreview() rebuilds the panel on every
  // keystroke — the mark has to be re-applied as part of the render, or it would flicker away as you type.
  var activeIndex = -1;

  function renderPreview() {
    if (!items.length) { previewEl.innerHTML = '<div class="nav-ed-empty">No items yet</div>'; return; }
    var html = '';
    items.forEach(function (it, k) {
      var on = k === activeIndex ? ' nav-ed-pv-active' : '';
      if (it.type === 'divider') { html += '<hr class="nav-divider' + on + '">'; return; }
      if (it.type === 'header') {
        html += '<p class="nav-heading' + on + '">' + (it.icon ? '<i class="mdi mdi-' + esc(it.icon) + '"></i> ' : '') + esc(it.label || 'Section') + '</p>';
        return;
      }
      var child = (it.depth || 0) >= 1;
      var next = items[k + 1];
      var parent = !child && next && next.type === 'link' && (next.depth || 0) >= 1;
      // An <a> with no href: still styled by the .wiki-sidebar rules this panel reuses, but not
      // focusable and not announced as a link — these go nowhere, and the panel is aria-hidden anyway.
      html += '<a class="nav-ed-pv-link' + (child ? ' nav-ed-pv-child' : '') + on + '">'
        + iconTag(it.icon) + ' <span>' + esc(it.label || 'Link') + '</span>'
        + (parent ? '<i class="mdi mdi-chevron-down nav-ed-pv-caret"></i>' : '')
        + '</a>';
    });
    previewEl.innerHTML = html;
  }

  // Bring the marked entry into view inside the preview panel only. Deliberately not scrollIntoView:
  // that walks up and scrolls the page too, which would drag the row you are editing out of sight.
  function revealActive() {
    var el = previewEl.querySelector('.nav-ed-pv-active');
    if (!el || previewBox.scrollHeight <= previewBox.clientHeight) return;
    var box = previewBox.getBoundingClientRect();
    var rect = el.getBoundingClientRect();
    if (rect.top < box.top) previewBox.scrollTop -= box.top - rect.top;
    else if (rect.bottom > box.bottom) previewBox.scrollTop += rect.bottom - box.bottom;
  }

  function setActive(i) {
    if (i === activeIndex) return;
    activeIndex = i;
    renderPreview();
    revealActive();
  }

  // Every row's fields look and read alike, so a bare "Label" name leaves a screen-reader user with no
  // idea which of twenty items they are in — the same thing the preview highlight fixes for sighted
  // users. Name each field by its position and kind instead ("Item 3 link label"). Placeholders alone
  // are not accessible names: they are only a fallback, and they vanish as soon as the field has a value.
  function fieldName(it, i, what) {
    return 'Item ' + (i + 1) + ' ' + it.type + ' ' + what;
  }

  // Each row's fields are stacked on two lines inside .nav-ed-body: line 1 = optional icon + label,
  // line 2 = the target path + Browse button (links only). Headers have just line 1; dividers a
  // single placeholder line. The grip / badge / actions sit beside the body (see render()).
  function fieldsFor(it, i) {
    if (it.type === 'divider') {
      return '<div class="nav-ed-body"><div class="nav-ed-line"><span class="nav-ed-divline">divider</span></div></div>';
    }
    var iconPrev = it.type === 'link'
      ? iconTag(it.icon)
      : (it.icon ? '<i class="mdi mdi-' + esc(it.icon) + '"></i>' : '');
    var line1 =
      '<div class="nav-ed-line">'
      + '<span class="nav-ed-iconprev">' + iconPrev + '</span>'
      + '<input class="form-control form-control-sm nav-ed-f-icon" data-f="icon" placeholder="icon" aria-label="' + esc(fieldName(it, i, 'icon')) + '" value="' + esc(it.icon) + '">'
      + '<input class="form-control form-control-sm nav-ed-f-label' + (it.type === 'header' ? ' fw-semibold' : '') + '" data-f="label" placeholder="' + (it.type === 'header' ? 'Section label' : 'Label') + '" aria-label="' + esc(fieldName(it, i, 'label')) + '" value="' + esc(it.label) + '">'
      + '</div>';
    if (it.type === 'header') return '<div class="nav-ed-body">' + line1 + '</div>';
    var line2 =
      '<div class="nav-ed-line nav-ed-target">'
      + '<input class="form-control form-control-sm" data-f="target" placeholder="/page-path or https://…" aria-label="' + esc(fieldName(it, i, 'target')) + '" value="' + esc(it.target) + '">'
      + '<button type="button" class="btn btn-sm btn-outline-secondary" data-browse title="Browse pages" aria-label="' + esc(fieldName(it, i, 'target')) + ': browse pages"><i class="mdi mdi-folder-search-outline" aria-hidden="true"></i></button>'
      + '</div>';
    return '<div class="nav-ed-body">' + line1 + line2 + '</div>';
  }

  // Action buttons are icon-only, so their tooltip doubles as the spoken name. Keep the tooltip short
  // but scope the spoken name to the item — "Remove" alone gives no clue which row is about to go.
  function actBtn(act, icon, tip, item, opts) {
    opts = opts || {};
    return '<button type="button" class="btn btn-sm btn-outline-' + (opts.danger ? 'danger' : 'secondary') + '"'
      + ' data-act="' + act + '" title="' + esc(tip) + '" aria-label="' + esc(tip + ', ' + item) + '"'
      + (opts.disabled ? ' disabled' : '') + '><i class="mdi mdi-' + icon + '" aria-hidden="true"></i></button>';
  }

  function render() {
    rowsEl.innerHTML = '';
    items.forEach(function (it, i) {
      var row = document.createElement('div');
      row.className = 'nav-ed-row nav-ed-' + it.type + ((it.depth || 0) >= 1 ? ' nav-ed-nested' : '');
      // NOT draggable by default: a draggable row makes the browser swallow mousedown inside its
      // text inputs (no cursor placement / selection). We flip it on only while the grab starts on
      // the grip (see the mousedown handler below).
      row.dataset.i = i;
      var item = 'item ' + (i + 1) + ' ' + it.type;
      // Only links nest; a link can indent when it follows another link (its parent or a sibling).
      var nest = '';
      if (it.type === 'link') {
        var canIn = (it.depth || 0) < 1 && i > 0 && items[i - 1].type === 'link';
        var canOut = (it.depth || 0) >= 1;
        nest = actBtn('out', 'format-indent-decrease', 'Un-nest', item, { disabled: !canOut })
          + actBtn('in', 'format-indent-increase', 'Nest under previous', item, { disabled: !canIn });
      }
      row.innerHTML =
        '<span class="nav-ed-grip" title="Drag to reorder" aria-hidden="true"><i class="mdi mdi-drag-vertical"></i></span>'
        + '<span class="nav-ed-badge">' + it.type + '</span>'
        + fieldsFor(it, i)
        + '<span class="nav-ed-actions">'
        + nest
        + actBtn('up', 'chevron-up', 'Move up', item)
        + actBtn('down', 'chevron-down', 'Move down', item)
        + actBtn('del', 'close', 'Remove', item, { danger: true })
        + '</span>';
      rowsEl.appendChild(row);
    });
    renderPreview();
    sync();
  }

  // Tie the row being edited to the entry it produces: focusing anywhere in a row marks that entry in
  // the preview (and scrolls the panel to it). With a handful of items the pairing is obvious; with a
  // long menu the two columns drift apart and there is otherwise nothing connecting them.
  rowsEl.addEventListener('focusin', function (e) {
    var row = e.target.closest('.nav-ed-row');
    setActive(row ? +row.dataset.i : -1);
  });
  rowsEl.addEventListener('focusout', function (e) {
    if (!rowsEl.contains(e.relatedTarget)) setActive(-1);
  });

  // Inline field edits: update the model in place, refresh preview + source, keep focus.
  rowsEl.addEventListener('input', function (e) {
    var f = e.target.getAttribute && e.target.getAttribute('data-f');
    if (!f) return;
    var row = e.target.closest('.nav-ed-row');
    var it = items[+row.dataset.i];
    it[f] = e.target.value;
    if (f === 'icon') {
      var prev = row.querySelector('.nav-ed-iconprev');
      if (prev) prev.innerHTML = it.type === 'link' ? iconTag(it.icon) : (it.icon ? '<i class="mdi mdi-' + esc(it.icon) + '"></i>' : '');
    }
    renderPreview();
    sync();
  });

  // Browse to a page path (keeps the freeform field for external URLs / not-yet-created pages).
  function openPicker(row) {
    if (!window.WikiKtPagePicker) return;
    var it = items[+row.dataset.i];
    var cur = (it.target || '').trim();
    var isExternal = /^[a-z][a-z0-9+.-]*:/i.test(cur); // http:, https:, mailto:, tel:, etc.
    window.WikiKtPagePicker.open({
      defaultLocale: defLocale,
      path: isExternal ? '' : cur.replace(/^\//, ''),
      title: 'Select a page',
      confirmLabel: 'Use page',
    }).then(function (res) {
      if (!res) return;
      it.target = res.locale === defLocale ? '/' + res.path : res.url;
      render();
    });
  }

  rowsEl.addEventListener('click', function (e) {
    var browseBtn = e.target.closest('[data-browse]');
    if (browseBtn) { openPicker(browseBtn.closest('.nav-ed-row')); return; }
    var btn = e.target.closest('[data-act]');
    if (!btn || btn.disabled) return;
    var row = btn.closest('.nav-ed-row');
    var i = +row.dataset.i;
    var act = btn.getAttribute('data-act');
    if (act === 'del') items.splice(i, 1);
    else if (act === 'up' && i > 0) { var a = items[i]; items[i] = items[i - 1]; items[i - 1] = a; }
    else if (act === 'down' && i < items.length - 1) { var b = items[i]; items[i] = items[i + 1]; items[i + 1] = b; }
    else if (act === 'in' && items[i].type === 'link' && i > 0 && items[i - 1].type === 'link') items[i].depth = 1;
    else if (act === 'out') items[i].depth = 0;
    render();
  });

  // Drag to reorder — but only when the grab starts on the grip. Each mousedown recomputes the
  // row's `draggable`: true only if the target is (within) the grip, false otherwise. That keeps
  // the row's text inputs fully clickable/selectable (a permanently-draggable row swallows them).
  var dragI = null;
  rowsEl.addEventListener('mousedown', function (e) {
    var row = e.target.closest('.nav-ed-row');
    if (row) row.draggable = !!e.target.closest('.nav-ed-grip');
  });
  rowsEl.addEventListener('dragstart', function (e) {
    var row = e.target.closest('.nav-ed-row'); if (!row) return;
    dragI = +row.dataset.i; row.classList.add('nav-ed-dragging');
    e.dataTransfer.effectAllowed = 'move';
  });
  rowsEl.addEventListener('dragend', function () {
    dragI = null;
    Array.prototype.forEach.call(rowsEl.children, function (r) { r.classList.remove('nav-ed-dragging', 'nav-ed-over'); r.draggable = false; });
  });
  rowsEl.addEventListener('dragover', function (e) {
    e.preventDefault();
    var row = e.target.closest('.nav-ed-row');
    Array.prototype.forEach.call(rowsEl.children, function (r) { r.classList.remove('nav-ed-over'); });
    if (row) row.classList.add('nav-ed-over');
  });
  rowsEl.addEventListener('drop', function (e) {
    e.preventDefault();
    var row = e.target.closest('.nav-ed-row');
    if (row == null || dragI == null) return;
    var to = +row.dataset.i;
    if (to === dragI) return;
    var moved = items.splice(dragI, 1)[0];
    items.splice(to, 0, moved);
    render();
  });

  root.querySelectorAll('[data-add]').forEach(function (b) {
    b.addEventListener('click', function () {
      var t = b.getAttribute('data-add');
      if (t === 'link') items.push({ type: 'link', icon: '', label: 'New link', target: '/', depth: 0 });
      else if (t === 'header') items.push({ type: 'header', icon: '', label: 'New section', depth: 0 });
      else items.push({ type: 'divider', depth: 0 });
      render();
    });
  });

  // Source toggle: hidden textarea = visual mode (source-of-truth is the model); shown = edit raw text.
  function setSource(show) {
    ta.classList.toggle('d-none', !show);
    if (help) help.classList.toggle('d-none', !show);
    rowsEl.classList.toggle('d-none', show);
    srcBtn.innerHTML = show ? '<i class="mdi mdi-view-list"></i> Visual Editor' : '<i class="mdi mdi-code-tags"></i> Edit Source';
    if (show) sync(); else { items = parse(ta.value); render(); }
  }
  srcBtn.addEventListener('click', function () { setSource(ta.classList.contains('d-none')); });

  // Guarantee the textarea is authoritative on submit (visual mode syncs; source mode is already text).
  var form = ta.closest('form');
  if (form) form.addEventListener('submit', function () { if (ta.classList.contains('d-none')) sync(); });

  // Activate: reveal the editor, hide the raw textarea + syntax help.
  root.hidden = false;
  ta.classList.add('d-none');
  if (help) help.classList.add('d-none');
  render();
})();
