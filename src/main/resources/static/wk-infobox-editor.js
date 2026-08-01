/*
 * Visual infobox field editor. Progressive enhancement over the plain-text "fields" textarea, which
 * stays the authoritative form value: this parses that text into rows, edits them with real controls,
 * and serializes straight back on every keystroke. An "Edit Source" toggle hands the raw text back for
 * power users and git sync. Same arrangement (and the same reasons) as wk-nav-editor.js.
 *
 * The line format and every rule about it live server-side in AdminRouting.kt — parseInfoboxFieldLines
 * and fieldsToText. parse() and serialize() below mirror those exactly, so a round-trip through this
 * editor leaves an untouched field byte-identical. Change them together.
 */
(function () {
  var root = document.getElementById('infoboxFieldEditor');
  var ta = document.getElementById('infoboxFields');
  if (!root || !ta) return;
  var rowsEl = root.querySelector('[data-rows]');
  var previewEl = root.querySelector('[data-preview]');
  var previewTitle = root.querySelector('[data-preview-title]');
  var help = document.getElementById('infoboxSyntaxHelp');
  var srcBtn = root.querySelector('[data-toggle-source]');
  var nameInput = document.getElementById('infoboxName');

  // Canonical types, with the label the dropdown shows
  var TYPES = [
    { value: 'string', label: 'Text' },
    { value: 'enum', label: 'Enum (single-select)' },
    { value: 'multi', label: 'Multi (multi-select)' },
    { value: 'boolean', label: 'Boolean (yes/no)' },
  ];
  var CHOICE_TYPES = { enum: 1, multi: 1 };
  // Section headings aren't a type an admin picks from the dropdown; they're their own row kind,
  // written `# Label` in the source format (InfoboxFieldDef.TYPE_HEADING).
  var HEADING = 'heading';
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c];
    });
  }

  // "|" divides columns and a newline divides fields, so neither can appear inside a value. Strip
  // rather than reject: a stray pipe pasted into a label should cost the pipe, not the paste.
  function clean(s) { return String(s == null ? '' : s).replace(/[|\r\n]/g, ' ').replace(/\s+/g, ' ').trim(); }

  // Anything the type column doesn't name falls back to string, matching canonicalInfoboxType's
  // fallback in parseInfoboxFieldLines. Saving such a line is refused server-side, so it can't stick.
  function canonType(raw) {
    var t = String(raw || '').trim().toLowerCase();
    if (!t) return 'string';
    for (var i = 0; i < TYPES.length; i++) if (TYPES[i].value === t) return t;
    return 'string';
  }

  function parse(text) {
    var out = [];
    (text || '').split('\n').forEach(function (raw) {
      var line = raw.trim();
      if (!line) return;
      // `# Label` is a section heading: no columns, no value, just a divider with a title.
      if (line.charAt(0) === '#') {
        var hl = line.replace(/^#\s*/, '').trim();
        if (hl) out.push({ type: HEADING, label: hl, name: '', required: false, options: '', help: '' });
        return;
      }
      var p = line.split('|').map(function (s) { return s.trim(); });
      if (!p[0]) return;
      var rawType = p[2] || '';
      out.push({
        name: p[0],
        label: p[1] || p[0],
        type: canonType(rawType.replace(/\*$/, '')),
        required: /\*\s*$/.test(rawType),
        options: p[3] || '',
        help: p[4] || '',
      });
    });
    return out;
  }

  // Trailing empty columns are dropped, exactly as fieldsToText does — so a field with no options and
  // no help serializes to "name | label | type", not "name | label | type |  |".
  function serialize(f) {
    if (f.type === HEADING) return '# ' + (f.label || '');
    var cols = [f.name, f.label, f.type + (f.required ? '*' : ''), f.options || '', f.help || ''];
    var end = cols.length;
    while (end > 1 && !cols[end - 1]) end--;
    return cols.slice(0, end).join(' | ');
  }
  function sync() { ta.value = fields.map(serialize).join('\n'); }

  var fields = parse(ta.value);

  // --- Card preview -------------------------------------------------------------------------
  // The real reader-facing markup (same classes as InfoboxService.renderOneCard) filled with
  // stand-in values, so the admin sees the actual card rather than a description of one. A label with
  // help is a plain span here, not the button the page renders: no popover script runs on an admin
  // page, so title= gives the same reveal through the browser's own tooltip.
  function sampleValue(f) {
    var opts = (f.options || '').split(',').map(function (s) { return s.trim(); }).filter(Boolean);
    if (f.type === 'boolean') {
      return '<span class="wk-infobox-bool wk-infobox-bool--yes"><i class="mdi mdi-check" aria-hidden="true"></i> Yes</span>';
    }
    if (f.type === 'multi') {
      if (!opts.length) return '<span class="ib-ed-pv-missing">no options set</span>';
      return opts.slice(0, 2).map(function (o) { return '<span class="wk-infobox-tag">' + esc(o) + '</span>'; }).join('');
    }
    if (f.type === 'enum') {
      if (!opts.length) return '<span class="ib-ed-pv-missing">no options set</span>';
      return esc(opts[0]);
    }
    return '<span class="ib-ed-pv-sample">Sample text</span>';
  }

  function rowPreview(f) {
    var label = esc(f.label || f.name);
    var dt = f.help
      ? '<span class="wk-infobox-help" title="' + esc(f.help) + '"><span class="wk-infobox-help-label">' + label + '</span></span>'
      : label;
    return '<div class="wk-infobox-row"><dt>' + dt + '</dt><dd>' + sampleValue(f) + '</dd></div>';
  }

  // Sections of `[heading] + <dl>`, the same shape renderOneCard emits — each heading starts a new
  // list, since a <dl> can't hold one. Unlike the real card, an empty section is kept: an admin who
  // just added a heading needs to see where it landed, and every field here has a sample value
  // anyway, so "empty" only ever means "no fields under it yet".
  function renderPreview() {
    if (previewTitle) previewTitle.textContent = (nameInput && nameInput.value.trim()) || 'Infobox';
    var shown = fields.filter(function (f) { return f.name || f.label; });
    if (!shown.length) { previewEl.innerHTML = '<div class="ib-ed-empty">No fields added</div>'; return; }
    var html = '';
    var open = false;
    shown.forEach(function (f) {
      if (f.type === HEADING) {
        if (open) { html += '</dl>'; open = false; }
        html += '<p class="wk-infobox-section">' + esc(f.label) + '</p>';
        return;
      }
      if (!open) { html += '<dl class="wk-infobox-list">'; open = true; }
      html += rowPreview(f);
    });
    if (open) html += '</dl>';
    previewEl.innerHTML = html;
  }

  // --- Rows ---------------------------------------------------------------------------------
  function actionsHtml(removeTitle) {
    return '<span class="ib-ed-actions">'
      + '<button type="button" class="btn btn-sm btn-outline-secondary" data-act="up" title="Move up"><i class="mdi mdi-chevron-up"></i></button>'
      + '<button type="button" class="btn btn-sm btn-outline-secondary" data-act="down" title="Move down"><i class="mdi mdi-chevron-down"></i></button>'
      + '<button type="button" class="btn btn-sm btn-outline-danger" data-act="del" title="' + removeTitle + '"><i class="mdi mdi-close"></i></button>'
      + '</span>';
  }

  // Every control carries a small standing label above it. A placeholder alone can't do this job: it
  // vanishes the moment the box has a value, which is exactly when a row of look-alike text boxes
  // becomes unreadable. It's also the only way these inputs get an accessible name — a placeholder
  // is a hint, not a label. Placeholders stay, demoted to examples of what to type.
  // `i` is the row index, used only to give each label a matching input id.
  function field(i, key, labelText, hint, controlHtml) {
    var id = 'ib-ed-' + i + '-' + key;
    return '<div class="ib-ed-field ib-ed-c-' + key + '">'
      + '<label class="ib-ed-flabel" for="' + id + '"' + (hint ? ' title="' + esc(hint) + '"' : '') + '>' + esc(labelText) + '</label>'
      + controlHtml.replace('<%id%>', id)
      + '</div>';
  }

  // A heading row is one input and nothing else — it has no name, type, options or help to set. The
  // "section" badge stands in for a label here: it never disappears, whatever is typed.
  function headingRowHtml(f, i) {
    return ''
      + '<span class="ib-ed-grip" title="Drag to reorder" aria-hidden="true"><i class="mdi mdi-drag-vertical"></i></span>'
      + '<div class="ib-ed-body">'
      + '<div class="ib-ed-line">'
      + '<span class="ib-ed-badge">section</span>'
      + '<input class="form-control form-control-sm ib-ed-f-heading fw-semibold" data-f="label" aria-label="Section heading" placeholder="e.g. Geography" value="' + esc(f.label) + '">'
      + '</div>'
      + '</div>'
      + actionsHtml('Remove heading');
  }

  function rowHtml(f, i) {
    var typeOpts = TYPES.map(function (t) {
      return '<option value="' + t.value + '"' + (t.value === f.type ? ' selected' : '') + '>' + esc(t.label) + '</option>';
    }).join('');
    var needsOptions = !!CHOICE_TYPES[f.type];
    var optionsMissing = needsOptions && !(f.options || '').trim();
    return ''
      + '<span class="ib-ed-grip" title="Drag to reorder" aria-hidden="true"><i class="mdi mdi-drag-vertical"></i></span>'
      + '<div class="ib-ed-body">'
      + '<div class="ib-ed-line">'
      + field(i, 'name', 'Name', 'The key this value is stored under. Readers never see it.',
        '<input id="<%id%>" class="form-control form-control-sm ib-ed-f-name font-monospace" data-f="name" placeholder="e.g. population" value="' + esc(f.name) + '">')
      + field(i, 'label', 'Label', 'The caption shown on the card.',
        '<input id="<%id%>" class="form-control form-control-sm ib-ed-f-label" data-f="label" placeholder="e.g. Population" value="' + esc(f.label) + '">')
      + field(i, 'type', 'Type', 'What kind of value this field holds.',
        '<select id="<%id%>" class="form-select form-select-sm ib-ed-f-type" data-f="type">' + typeOpts + '</select>')
      + '<label class="ib-ed-req" title="An editor is prompted for this field"><input type="checkbox" class="form-check-input" data-f="required"' + (f.required ? ' checked' : '') + '> required</label>'
      + '</div>'
      + '<div class="ib-ed-line">'
      + field(i, 'options', 'Options', 'The choices an enum or multi field offers. Not used by the other types.',
        '<input id="<%id%>" class="form-control form-control-sm ib-ed-f-options' + (optionsMissing ? ' is-invalid' : '') + '" data-f="options"'
        + (needsOptions ? '' : ' disabled')
        + ' placeholder="' + (needsOptions ? 'e.g. Tropical, Temperate, Arid' : 'enum and multi only') + '" value="' + esc(f.options) + '">')
      + field(i, 'help', 'Help text (readers see this)', 'Explains the field to editors and, on the page, to readers.',
        '<input id="<%id%>" class="form-control form-control-sm ib-ed-f-help" data-f="help" placeholder="e.g. Residents at the most recent census." value="' + esc(f.help) + '">')
      + '</div>'
      + '</div>'
      + actionsHtml('Remove field');
  }

  function render() {
    rowsEl.innerHTML = '';
    fields.forEach(function (f, i) {
      var row = document.createElement('div');
      row.className = 'ib-ed-row' + (f.type === HEADING ? ' ib-ed-heading' : '');
      row.dataset.i = i;
      row.innerHTML = f.type === HEADING ? headingRowHtml(f, i) : rowHtml(f, i);
      rowsEl.appendChild(row);
    });
    if (!fields.length) rowsEl.innerHTML = '<div class="ib-ed-empty">No fields added</div>';
    renderPreview();
    sync();
  }

  // Text edits update the model in place and refresh only the preview, so the caret is never
  // disturbed mid-word. Type changes DO re-render the row: they flip whether options applies.
  rowsEl.addEventListener('input', function (e) {
    var f = e.target.getAttribute && e.target.getAttribute('data-f');
    if (!f || f === 'type' || f === 'required') return;
    var row = e.target.closest('.ib-ed-row');
    var item = fields[+row.dataset.i];
    item[f] = e.target.value;
    var optionsEl = row.querySelector('.ib-ed-f-options');
    if (optionsEl) optionsEl.classList.toggle('is-invalid', !!CHOICE_TYPES[item.type] && !optionsEl.value.trim());
    renderPreview();
    sync();
  });

  // Pipes and newlines can't survive the line format; clean them out once the field is left, rather
  // than fighting the caret on every keystroke.
  rowsEl.addEventListener('change', function (e) {
    var f = e.target.getAttribute && e.target.getAttribute('data-f');
    if (!f) return;
    var row = e.target.closest('.ib-ed-row');
    var item = fields[+row.dataset.i];
    if (f === 'required') item.required = e.target.checked;
    else if (f === 'type') item.type = e.target.value;
    else { item[f] = clean(e.target.value); e.target.value = item[f]; }
    if (f === 'type') render(); else { renderPreview(); sync(); }
  });

  rowsEl.addEventListener('click', function (e) {
    var btn = e.target.closest('[data-act]');
    if (!btn || btn.disabled) return;
    var i = +btn.closest('.ib-ed-row').dataset.i;
    var act = btn.getAttribute('data-act');
    if (act === 'del') fields.splice(i, 1);
    else if (act === 'up' && i > 0) { var a = fields[i]; fields[i] = fields[i - 1]; fields[i - 1] = a; }
    else if (act === 'down' && i < fields.length - 1) { var b = fields[i]; fields[i] = fields[i + 1]; fields[i + 1] = b; }
    render();
  });

  // Drag to reorder, but only when the grab starts on the grip — a permanently-draggable row would
  // swallow mousedown inside its own text inputs (no caret placement, no selection).
  var dragI = null;
  rowsEl.addEventListener('mousedown', function (e) {
    var row = e.target.closest('.ib-ed-row');
    if (row) row.draggable = !!e.target.closest('.ib-ed-grip');
  });
  rowsEl.addEventListener('dragstart', function (e) {
    var row = e.target.closest('.ib-ed-row'); if (!row) return;
    dragI = +row.dataset.i; row.classList.add('ib-ed-dragging');
    e.dataTransfer.effectAllowed = 'move';
  });
  rowsEl.addEventListener('dragend', function () {
    dragI = null;
    Array.prototype.forEach.call(rowsEl.children, function (r) { r.classList.remove('ib-ed-dragging', 'ib-ed-over'); r.draggable = false; });
  });
  rowsEl.addEventListener('dragover', function (e) {
    e.preventDefault();
    var row = e.target.closest('.ib-ed-row');
    Array.prototype.forEach.call(rowsEl.children, function (r) { r.classList.remove('ib-ed-over'); });
    if (row) row.classList.add('ib-ed-over');
  });
  rowsEl.addEventListener('drop', function (e) {
    e.preventDefault();
    var row = e.target.closest('.ib-ed-row');
    if (row == null || dragI == null) return;
    var to = +row.dataset.i;
    if (to === dragI) return;
    fields.splice(to, 0, fields.splice(dragI, 1)[0]);
    render();
  });

  root.querySelector('[data-add]').addEventListener('click', function () {
    fields.push({ name: '', label: '', type: 'string', required: false, options: '', help: '' });
    render();
    focusLastRow('.ib-ed-f-name');
  });

  root.querySelector('[data-add-heading]').addEventListener('click', function () {
    fields.push({ name: '', label: '', type: HEADING, required: false, options: '', help: '' });
    render();
    focusLastRow('.ib-ed-f-heading');
  });

  function focusLastRow(sel) {
    var el = rowsEl.querySelector('.ib-ed-row:last-child ' + sel);
    if (el) el.focus();
  }

  if (nameInput) nameInput.addEventListener('input', renderPreview);

  // Source toggle: textarea hidden = visual mode (the model is the truth); shown = raw text is.
  function setSource(show) {
    ta.classList.toggle('d-none', !show);
    if (help) help.classList.toggle('d-none', !show);
    root.querySelector('.ib-ed-main').classList.toggle('d-none', show);
    srcBtn.innerHTML = show
      ? '<i class="mdi mdi-view-list" aria-hidden="true"></i> Visual Editor'
      : '<i class="mdi mdi-code-tags" aria-hidden="true"></i> Edit Source';
    if (show) sync(); else { fields = parse(ta.value); render(); }
  }
  srcBtn.addEventListener('click', function () { setSource(ta.classList.contains('d-none')); });

  // The textarea is what posts, so guarantee it's current even if a change slipped through.
  var form = ta.closest('form');
  if (form) form.addEventListener('submit', function () { if (ta.classList.contains('d-none')) sync(); });

  root.hidden = false;
  ta.classList.add('d-none');
  if (help) help.classList.add('d-none');
  render();
})();
