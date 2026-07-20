/*
 * WikiKT /f asset manager — an embedded (non-modal) two-pane folder browser for the asset library.
 * Reuses the shared folder model + visual language from wk-browser.js (window.WkBrowserCore), but
 * renders inline with per-asset actions instead of a pick-and-confirm modal. The asset data is
 * embedded in the page as window.__WK_ASSETS__ (so we get usage counts and avoid a second fetch);
 * mutations still go through the existing server endpoints (upload form, /f/{id} detail/delete).
 *
 * Browsing into a folder sets the upload form's target folder, so you "browse to a folder" instead
 * of typing its name. Columns are click-to-sort and can be shown/hidden from the "Columns" menu
 * (both choices persisted per browser); Name and the Edit action are always shown.
 */
(() => {
  const mount = document.getElementById('assetManager');
  if (!mount || !window.WkBrowserCore) return;
  const C = window.WkBrowserCore;
  C.ensureStyle();

  const data = window.__WK_ASSETS__ || [];
  const uploadFolder = document.getElementById('uploadFolder');
  const uploadTargetLabel = document.getElementById('uploadTarget');

  const root = C.buildTree(data);
  let current = root;
  const expanded = { '': true };

  // Derive a file's short type label (extension, else the mime subtype). Declared before COLUMNS uses it.
  function assetTypeLabel(a) {
    const dot = a.path.lastIndexOf('.'), slash = a.path.lastIndexOf('/');
    if (dot > slash && dot >= 0) return a.path.slice(dot + 1);
    const s = a.mime.indexOf('/');
    return s >= 0 ? a.mime.slice(s + 1) : a.mime;
  }
  function isImage(a) { return a.mime && a.mime.indexOf('image/') === 0; }

  // The file columns, in display order. `cmp` sorts ascending (a header click flips direction).
  // `toggle: true` = offered in the Columns menu; Name (and the fixed Edit action) are always shown.
  // The Edit/actions column is appended separately — it is neither sortable nor toggleable.
  const COLUMNS = [
    { key: 'name', label: 'Name', cmp: (a, b) => a.leaf.localeCompare(b.leaf) },
    { key: 'locale', label: 'Locale', toggle: true, cmp: (a, b) => (a.locale || '').localeCompare(b.locale || '') },
    { key: 'type', label: 'Type', toggle: true, cmp: (a, b) => assetTypeLabel(a).localeCompare(assetTypeLabel(b)) },
    { key: 'size', label: 'Size', toggle: true, cls: 'wkab-num', cmp: (a, b) => a.sizeBytes - b.sizeBytes },
    { key: 'modified', label: 'Modified', toggle: true, cls: 'wkab-when', cmp: (a, b) => a.updatedAt - b.updatedAt },
    { key: 'used', label: 'Used by', toggle: true, cls: 'wkab-num', cmp: (a, b) => a.usedBy - b.usedBy },
  ];
  const TOGGLEABLE = COLUMNS.filter((c) => c.toggle).map((c) => c.key);

  // Persisted view state: sort (key + direction) and which columns are hidden. "Used by" is hidden by
  // default (first visit, before any stored preference).
  const LS_SORT = 'wkab-sort:assetManager';
  const LS_COLS = 'wkab-cols-hidden:assetManager';
  function loadJson(key, fallback) {
    try { const v = JSON.parse(localStorage.getItem(key)); return v == null ? fallback : v; } catch (e) { return fallback; }
  }
  function saveJson(key, val) { try { localStorage.setItem(key, JSON.stringify(val)); } catch (e) { /* ignore */ } }

  const savedSort = loadJson(LS_SORT, null);
  let sortKey = (savedSort && COLUMNS.some((c) => c.key === savedSort.key)) ? savedSort.key : 'name';
  let sortDir = (savedSort && savedSort.dir === -1) ? -1 : 1;
  const hidden = new Set((loadJson(LS_COLS, null) || ['used']).filter((k) => TOGGLEABLE.indexOf(k) !== -1));
  function persistSort() { saveJson(LS_SORT, { key: sortKey, dir: sortDir }); }
  function persistCols() { saveJson(LS_COLS, [...hidden]); }
  function visibleCols() { return COLUMNS.filter((c) => !hidden.has(c.key)); }

  // Scaffold: toolbar (breadcrumb | filter | Columns menu) over a body (tree | main).
  const embed = C.el('div', 'wkab-embed');
  const toolbar = C.el('div', 'wkab-toolbar');
  const crumbs = C.el('div', 'wkab-crumbs');
  const filter = C.el('input', 'form-control form-control-sm wkab-filter');
  filter.type = 'search'; filter.placeholder = 'Filter…';
  toolbar.appendChild(crumbs);
  toolbar.appendChild(filter);
  toolbar.appendChild(buildColumnsMenu());

  const body = C.el('div', 'wkab-body');
  const treeEl = C.el('div', 'wkab-tree');
  const main = C.el('div', 'wkab-main');
  body.appendChild(treeEl);
  body.appendChild(main);
  embed.appendChild(toolbar);
  embed.appendChild(body);
  mount.appendChild(embed);

  // A lightweight "Columns" dropdown of checkboxes (no Bootstrap dependency). Toggling a box shows/hides
  // that column and persists the choice. Closes on outside click or Escape.
  function buildColumnsMenu() {
    const wrap = C.el('div', 'wkab-colmenu');
    const btn = C.el('button', 'btn btn-outline-secondary btn-sm');
    btn.type = 'button';
    btn.innerHTML = '<i class="mdi mdi-view-column-outline" aria-hidden="true"></i> Show Columns <i class="mdi mdi-menu-down" aria-hidden="true"></i>';
    btn.setAttribute('aria-haspopup', 'true');
    btn.setAttribute('aria-expanded', 'false');
    const panel = C.el('div', 'wkab-colmenu-panel');
    panel.hidden = true;
    COLUMNS.filter((c) => c.toggle).forEach((c) => {
      const label = C.el('label', 'wkab-colmenu-item');
      const cb = C.el('input'); cb.type = 'checkbox'; cb.checked = !hidden.has(c.key);
      cb.addEventListener('change', () => {
        if (cb.checked) hidden.delete(c.key); else hidden.add(c.key);
        persistCols();
        renderMain();
      });
      label.appendChild(cb);
      label.appendChild(document.createTextNode(' ' + c.label));
      panel.appendChild(label);
    });
    function onOutside(e) { if (!wrap.contains(e.target)) close(); }
    function onKey(e) { if (e.key === 'Escape') { close(); btn.focus(); } }
    function open() {
      panel.hidden = false; btn.setAttribute('aria-expanded', 'true');
      document.addEventListener('mousedown', onOutside); document.addEventListener('keydown', onKey);
    }
    function close() {
      panel.hidden = true; btn.setAttribute('aria-expanded', 'false');
      document.removeEventListener('mousedown', onOutside); document.removeEventListener('keydown', onKey);
    }
    btn.addEventListener('click', () => { if (panel.hidden) open(); else close(); });
    wrap.appendChild(btn); wrap.appendChild(panel);
    return wrap;
  }

  // The Folder field and the "Upload will go to …" line are two views of one value, so the label is
  // always rendered *from* the field rather than from whatever last set it — browsing writes the
  // field, typing leaves it alone, and both end up here. Normalization mirrors the server
  // (`folder.trim().trim('/')` in AssetRouting) so the preview is the path the upload will actually
  // use; internal slashes are left as typed for the same reason.
  function normalizeFolder(value) {
    return String(value || '').trim().replace(/^\/+/, '').replace(/\/+$/, '');
  }
  function renderUploadTarget() {
    if (!uploadTargetLabel) return;
    const folder = normalizeFolder(uploadFolder ? uploadFolder.value : '');
    uploadTargetLabel.textContent = folder ? `/${folder}` : '/ (root)';
  }

  function navigate(node, opts) {
    current = node;
    let p = node.path;
    while (true) { expanded[p] = true; if (!p) break; p = p.indexOf('/') >= 0 ? p.slice(0, p.lastIndexOf('/')) : ''; }
    // Browsing sets the upload target folder — but not when the navigation came *from* the field,
    // where rewriting the value would fight the user mid-keystroke (e.g. eating a trailing '/').
    if (uploadFolder && !(opts && opts.fromInput)) uploadFolder.value = node.path;
    renderUploadTarget();
    renderCrumbs();
    renderTree();
    renderMain();
  }

  function renderCrumbs() {
    crumbs.textContent = '';
    const rootBtn = C.el('button', null, '/'); rootBtn.type = 'button';
    rootBtn.addEventListener('click', () => navigate(root));
    crumbs.appendChild(rootBtn);
    if (current.path) {
      const segs = current.path.split('/'); let acc = '';
      segs.forEach((seg, i) => {
        acc = acc ? `${acc}/${seg}` : seg;
        crumbs.appendChild(C.el('span', 'sep', ' › '));
        if (i === segs.length - 1) { crumbs.appendChild(C.el('span', 'cur', seg)); }
        else {
          const thisPath = acc; const b = C.el('button', null, seg); b.type = 'button';
          b.addEventListener('click', () => navigate(C.findNode(root, thisPath)));
          crumbs.appendChild(b);
        }
      });
    }
  }

  function renderTree() {
    treeEl.textContent = '';
    const ul = C.el('ul');
    ul.appendChild(folderRow(root, '(root)'));
    treeEl.appendChild(ul);
  }

  function folderRow(node, labelOverride) {
    const li = C.el('li');
    const row = C.el('div', `wkab-row${node === current ? ' active' : ''}`);
    const kids = C.childFolders(node);
    const twist = C.el('span', `twist${kids.length ? '' : ' empty'}`, kids.length ? (expanded[node.path] ? '▾' : '▸') : '•');
    twist.addEventListener('click', (e) => { e.stopPropagation(); if (kids.length) { expanded[node.path] = !expanded[node.path]; renderTree(); } });
    const fic = C.el('i', 'mdi mdi-folder-outline'); fic.setAttribute('aria-hidden', 'true');
    row.appendChild(twist); row.appendChild(fic); row.appendChild(C.el('span', null, labelOverride || node.name));
    row.addEventListener('click', () => navigate(node));
    li.appendChild(row);
    if (kids.length && expanded[node.path]) {
      const sub = C.el('ul');
      kids.forEach((k) => sub.appendChild(folderRow(k)));
      li.appendChild(sub);
    }
    return li;
  }

  // The <td> for `col` on file `a`. Cells for hidden columns are simply never built (see renderMain).
  function fileCell(col, a) {
    switch (col.key) {
      case 'name': {
        const td = C.el('td');
        const wrap = C.el('div', 'wkab-name');
        if (isImage(a)) {
          const img = C.el('img', 'wkab-thumb'); img.src = a.url; img.alt = ''; img.loading = 'lazy';
          const link = C.el('a'); link.href = a.url; link.target = '_blank'; link.rel = 'noopener'; link.appendChild(img);
          wrap.appendChild(link);
        } else {
          const fic = C.el('i', 'mdi mdi-file-outline'); fic.setAttribute('aria-hidden', 'true'); wrap.appendChild(fic);
        }
        wrap.appendChild(C.el('span', null, a.leaf));
        td.appendChild(wrap);
        return td;
      }
      case 'locale': return C.el('td', null, a.locale);
      case 'type': { const td = C.el('td'); td.appendChild(C.el('span', 'wkab-badge', assetTypeLabel(a))); return td; }
      case 'size': return C.el('td', 'wkab-num', C.formatSize(a.sizeBytes));
      case 'modified': { const td = C.el('td', 'wkab-when', C.relTime(a.updatedAt)); td.title = C.absTime(a.updatedAt); return td; }
      case 'used': return C.el('td', 'wkab-num', String(a.usedBy));
      default: return C.el('td', null, '');
    }
  }

  function renderMain() {
    main.textContent = '';
    const q = filter.value.trim().toLowerCase();
    const folders = C.childFolders(current).filter((f) => !q || f.name.toLowerCase().indexOf(q) !== -1);
    // Filter matches the filename AND the editor-only description (so editors can find by either).
    const files = current.files.filter((f) => !q || `${f.leaf} ${f.description || ''}`.toLowerCase().indexOf(q) !== -1);
    const sortCol = COLUMNS.find((c) => c.key === sortKey) || COLUMNS[0];
    files.sort((a, b) => sortCol.cmp(a, b) * sortDir);

    if (!folders.length && !files.length) {
      main.appendChild(C.el('div', 'wkab-empty', q ? 'No matches in this folder.' : 'This folder is empty — upload an image below to add one here.'));
      return;
    }

    const cols = visibleCols();
    const table = C.el('table', 'wkab-table');
    const thead = C.el('thead');
    const htr = C.el('tr');
    cols.forEach((col) => {
      const th = C.el('th', `wkab-th-sort${col.key === sortKey ? ' active' : ''}${col.cls ? ' ' + col.cls : ''}`);
      th.appendChild(C.el('span', null, col.label));
      th.appendChild(C.el('span', 'wkab-th-caret', col.key === sortKey ? (sortDir > 0 ? '▲' : '▼') : ''));
      th.title = `Sort by ${col.label}`;
      th.setAttribute('aria-sort', col.key === sortKey ? (sortDir > 0 ? 'ascending' : 'descending') : 'none');
      th.addEventListener('click', () => {
        if (sortKey === col.key) sortDir = -sortDir; else { sortKey = col.key; sortDir = 1; }
        persistSort();
        renderMain();
      });
      htr.appendChild(th);
    });
    htr.appendChild(C.el('th', 'wkab-actions', '')); // Edit column: always shown, not sortable
    thead.appendChild(htr); table.appendChild(thead);
    const tbody = C.el('tbody');

    folders.forEach((f) => {
      const tr = C.el('tr', 'dir');
      cols.forEach((col) => {
        if (col.key === 'name') {
          const nameTd = C.el('td');
          const wrap = C.el('div', 'wkab-name');
          const ic = C.el('i', 'mdi mdi-folder'); ic.setAttribute('aria-hidden', 'true');
          wrap.appendChild(ic); wrap.appendChild(C.el('span', null, f.name));
          nameTd.appendChild(wrap); tr.appendChild(nameTd);
        } else {
          tr.appendChild(C.el('td', col.cls || null, col.key === 'type' ? 'folder' : ''));
        }
      });
      tr.appendChild(C.el('td', 'wkab-actions', ''));
      tr.addEventListener('click', () => navigate(f));
      tbody.appendChild(tr);
    });

    files.forEach((a) => {
      const tr = C.el('tr', 'file');
      cols.forEach((col) => tr.appendChild(fileCell(col, a)));
      const actTd = C.el('td', 'wkab-actions');
      const details = C.el('a', null, 'Edit'); details.href = `/f/${a.id}`;
      // Open the detail/edit modal in place; reload to refresh the embedded data if anything changed.
      details.addEventListener('click', (e) => {
        if (!window.WikiKtAssetDetail) return; // fall back to navigation
        e.preventDefault();
        window.WikiKtAssetDetail.open(a.id, { onChange: () => window.location.reload() });
      });
      actTd.appendChild(details); tr.appendChild(actTd);
      tbody.appendChild(tr);
    });

    table.appendChild(tbody);
    main.appendChild(table);
    // Key resize widths by the visible column set, so hiding/showing a column doesn't apply one set's
    // widths to another (different sets can share a column count).
    C.enableColumnResize(table, 'assetManager:' + cols.map((c) => c.key).join(','));
  }

  filter.addEventListener('input', renderMain);
  if (uploadFolder) {
    uploadFolder.addEventListener('input', () => {
      renderUploadTarget();
      // If what was typed names an existing folder, follow it in the browser too, so the tree and
      // breadcrumb don't sit on a different folder than the one being uploaded to. Typing a folder
      // that doesn't exist yet is still fine — it just creates it on upload.
      const folder = normalizeFolder(uploadFolder.value);
      const node = C.findNode(root, folder);
      if (node.path === folder && node !== current) navigate(node, { fromInput: true });
    });
  }
  navigate(root);
})();
