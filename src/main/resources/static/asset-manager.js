/*
 * WikiKT /f asset manager — an embedded (non-modal) two-pane folder browser for the asset library.
 * Reuses the shared folder model + visual language from wk-browser.js (window.WkBrowserCore), but
 * renders inline with per-asset actions instead of a pick-and-confirm modal. The asset data is
 * embedded in the page as window.__WK_ASSETS__ (so we get usage counts and avoid a second fetch);
 * mutations still go through the existing server endpoints (upload form, /f/{id} detail/delete).
 *
 * Browsing into a folder sets the upload form's target folder, so you "browse to a folder" instead
 * of typing its name.
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

  // Scaffold: toolbar (breadcrumb | filter | sort) over a body (tree | main).
  const embed = C.el('div', 'wkab-embed');
  const toolbar = C.el('div', 'wkab-toolbar');
  const crumbs = C.el('div', 'wkab-crumbs');
  const filter = C.el('input', 'form-control form-control-sm wkab-filter');
  filter.type = 'search'; filter.placeholder = 'Filter…';
  const sort = C.el('select', 'form-select form-select-sm wkab-sort');
  [['name', 'Name'], ['size', 'Size'], ['newest', 'Newest'], ['used', 'Most used']].forEach((o) => {
    const opt = C.el('option', null, o[1]); opt.value = o[0]; sort.appendChild(opt);
  });
  toolbar.appendChild(crumbs);
  toolbar.appendChild(filter);
  toolbar.appendChild(sort);

  const body = C.el('div', 'wkab-body');
  const treeEl = C.el('div', 'wkab-tree');
  const main = C.el('div', 'wkab-main');
  body.appendChild(treeEl);
  body.appendChild(main);
  embed.appendChild(toolbar);
  embed.appendChild(body);
  mount.appendChild(embed);

  function assetTypeLabel(a) {
    const dot = a.path.lastIndexOf('.'), slash = a.path.lastIndexOf('/');
    if (dot > slash && dot >= 0) return a.path.slice(dot + 1);
    const s = a.mime.indexOf('/');
    return s >= 0 ? a.mime.slice(s + 1) : a.mime;
  }
  function isImage(a) { return a.mime && a.mime.indexOf('image/') === 0; }

  function navigate(node) {
    current = node;
    let p = node.path;
    while (true) { expanded[p] = true; if (!p) break; p = p.indexOf('/') >= 0 ? p.slice(0, p.lastIndexOf('/')) : ''; }
    // Browsing sets the upload target folder.
    if (uploadFolder) uploadFolder.value = node.path;
    if (uploadTargetLabel) uploadTargetLabel.textContent = node.path ? `/${node.path}` : '/ (root)';
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

  function renderMain() {
    main.textContent = '';
    const q = filter.value.trim().toLowerCase();
    const folders = C.childFolders(current).filter((f) => !q || f.name.toLowerCase().indexOf(q) !== -1);
    // Filter matches the filename AND the editor-only description (so editors can find by either).
    const files = current.files.filter((f) => !q || `${f.leaf} ${f.description || ''}`.toLowerCase().indexOf(q) !== -1);
    files.sort((a, b) => {
      switch (sort.value) {
        case 'size': return b.sizeBytes - a.sizeBytes;
        case 'newest': return b.createdAt - a.createdAt;
        case 'used': return b.usedBy - a.usedBy;
        default: return a.leaf.localeCompare(b.leaf);
      }
    });

    if (!folders.length && !files.length) {
      main.appendChild(C.el('div', 'wkab-empty', q ? 'No matches in this folder.' : 'This folder is empty — upload an image below to add one here.'));
      return;
    }

    const table = C.el('table', 'wkab-table');
    const thead = C.el('thead');
    const htr = C.el('tr');
    [{ l: 'Name' }, { l: 'Locale' }, { l: 'Type' }, { l: 'Size', c: 'wkab-num' }, { l: 'Used by', c: 'wkab-num' }, { l: '', c: 'wkab-actions' }].forEach((h) => {
      htr.appendChild(C.el('th', h.c || null, h.l));
    });
    thead.appendChild(htr); table.appendChild(thead);
    const tbody = C.el('tbody');

    folders.forEach((f) => {
      const tr = C.el('tr', 'dir');
      const nameTd = C.el('td');
      const wrap = C.el('div', 'wkab-name');
      const ic = C.el('i', 'mdi mdi-folder'); ic.setAttribute('aria-hidden', 'true');
      wrap.appendChild(ic); wrap.appendChild(C.el('span', null, f.name));
      nameTd.appendChild(wrap); tr.appendChild(nameTd);
      tr.appendChild(C.el('td', null, ''));
      tr.appendChild(C.el('td', null, 'folder'));
      tr.appendChild(C.el('td', 'wkab-num', ''));
      tr.appendChild(C.el('td', 'wkab-num', ''));
      tr.appendChild(C.el('td', 'wkab-actions', ''));
      tr.addEventListener('click', () => navigate(f));
      tbody.appendChild(tr);
    });

    files.forEach((a) => {
      const tr = C.el('tr', 'file');
      const nameTd = C.el('td');
      const wrap = C.el('div', 'wkab-name');
      if (isImage(a)) {
        const img = C.el('img', 'wkab-thumb'); img.src = a.url; img.alt = ''; img.loading = 'lazy';
        const link = C.el('a'); link.href = a.url; link.target = '_blank'; link.rel = 'noopener'; link.appendChild(img);
        wrap.appendChild(link);
      } else {
        const fic = C.el('i', 'mdi mdi-file-outline'); fic.setAttribute('aria-hidden', 'true'); wrap.appendChild(fic);
      }
      wrap.appendChild(C.el('span', null, a.leaf));
      nameTd.appendChild(wrap); tr.appendChild(nameTd);
      tr.appendChild(C.el('td', null, a.locale));
      const typeTd = C.el('td'); typeTd.appendChild(C.el('span', 'wkab-badge', assetTypeLabel(a))); tr.appendChild(typeTd);
      tr.appendChild(C.el('td', 'wkab-num', C.formatSize(a.sizeBytes)));
      tr.appendChild(C.el('td', 'wkab-num', String(a.usedBy)));
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
  }

  filter.addEventListener('input', renderMain);
  sort.addEventListener('change', renderMain);
  navigate(root);
})();
