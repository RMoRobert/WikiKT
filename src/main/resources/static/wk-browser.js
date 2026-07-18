/*
 * WikiKT path browser — a self-contained, dependency-free modal for browsing the wiki's path
 * hierarchy. Pages and assets are both (locale, path) rows with no folder entity; the tree is
 * derived purely from the "/"-separated path strings. This one component drives two pickers:
 *
 *   WikiKtAssetBrowser.open({ imagesOnly, title })           -> Promise<asset|null>     (pick a file)
 *   WikiKtPagePicker.open({ defaultLocale, path, locale })   -> Promise<{locale,path,exists}|null>
 *                                                                (browse to OR type a new path)
 *
 * Both share the folder tree, breadcrumb, filter, and modal chrome; they differ only in how the
 * right-pane rows render and what the footer does (insert a file vs. choose/create a path).
 */
(() => {
  if (window.WikiKtAssetBrowser) return;

  const STYLE_ID = 'wkab-style';
  const CSS = [
    // Picker chrome is a Bootstrap modal; these style the two-pane content inside its modal-body/footer.
    // The picker often opens stacked over another modal (e.g. Page Info) at the same modal-lg size, so
    // tint the picker's own full-viewport backdrop area to clearly darken whatever sits beneath it.
    '.wkab-modal{background-color:rgba(0,0,0,.4);}',
    '.wkab-bsbody{padding:0;display:flex;flex-direction:column;}',
    '.wkab-bsbody .wkab-body{min-height:min(72vh,620px);}',
    '.wkab-bsfoot{flex-wrap:nowrap;}',
    '.wkab-toolbar{display:flex;align-items:center;flex-wrap:wrap;gap:.5rem;padding:.5rem .75rem;border-bottom:1px solid var(--bs-border-color,#dee2e6);background:var(--bs-tertiary-bg,#f8f9fa);}',
    '.wkab-crumbs{flex:1;min-width:0;font-size:.9rem;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;}',
    '.wkab-crumbs button{background:none;border:0;color:var(--bs-link-color,var(--bs-primary,#0d9488));cursor:pointer;padding:0 .15rem;font-size:.9rem;}',
    '.wkab-crumbs button:hover{text-decoration:underline;}',
    '.wkab-crumbs .sep{color:var(--bs-secondary-color,#6c757d);}',
    '.wkab-crumbs .cur{color:var(--bs-body-color,#212529);font-weight:600;padding:0 .15rem;}',
    '.wkab-filter{width:180px;}',
    '.wkab-sort{width:130px;}',
    '.wkab-body{flex:1;display:flex;min-height:0;}',
    '.wkab-tree{width:230px;flex-shrink:0;overflow:auto;border-right:1px solid var(--bs-border-color,#dee2e6);padding:.5rem 0;background:var(--bs-body-bg,#fff);}',
    '.wkab-tree ul{list-style:none;margin:0;padding:0;}',
    '.wkab-tree .wkab-row{display:flex;align-items:center;gap:.25rem;padding:.2rem .5rem;cursor:pointer;font-size:.9rem;white-space:nowrap;border-radius:.25rem;}',
    '.wkab-tree .wkab-row:hover{background:var(--bs-tertiary-bg,#f1f3f5);}',
    '.wkab-tree .wkab-row.active{background:var(--bs-primary,#0d9488);color:#fff;}',
    '.wkab-tree .twist{width:1rem;text-align:center;color:var(--bs-secondary-color,#6c757d);flex-shrink:0;}',
    '.wkab-tree .wkab-row.active .twist{color:#fff;}',
    '.wkab-tree .twist.empty{visibility:hidden;}',
    '.wkab-tree ul ul{margin-left:.75rem;}',
    '.wkab-main{flex:1;overflow:auto;min-width:0;}',
    '.wkab-table{width:100%;border-collapse:collapse;font-size:.9rem;}',
    '.wkab-table th{position:sticky;top:0;background:var(--bs-tertiary-bg,#f8f9fa);text-align:left;padding:.4rem .75rem;border-bottom:1px solid var(--bs-border-color,#dee2e6);font-weight:600;color:var(--bs-secondary-color,#6c757d);}',
    '.wkab-table td{padding:.4rem .75rem;border-bottom:1px solid var(--bs-border-color,#f1f3f5);vertical-align:middle;}',
    '.wkab-table tr.file{cursor:pointer;}',
    '.wkab-table tr.file:hover{background:var(--bs-tertiary-bg,#f1f3f5);}',
    '.wkab-table tr.file.sel{background:var(--bs-primary,#0d9488);color:#fff;}',
    '.wkab-table tr.file.sel .wkab-badge,.wkab-table tr.file.sel .wkab-sub{background:rgba(255,255,255,.25);color:#fff;}',
    '.wkab-table tr.dir{cursor:pointer;color:var(--bs-body-color,#212529);}',
    '.wkab-table tr.dir:hover{background:var(--bs-tertiary-bg,#f1f3f5);}',
    '.wkab-name{display:flex;align-items:center;gap:.5rem;}',
    '.wkab-thumb{width:28px;height:28px;object-fit:cover;border-radius:.2rem;background:var(--bs-tertiary-bg,#f1f3f5);flex-shrink:0;}',
    '.wkab-badge{display:inline-block;font-size:.7rem;font-weight:600;letter-spacing:.04em;padding:.1rem .35rem;border-radius:.2rem;background:var(--bs-tertiary-bg,#e9ecef);color:var(--bs-secondary-color,#6c757d);text-transform:uppercase;}',
    '.wkab-sub{color:var(--bs-secondary-color,#6c757d);font-size:.8rem;}',
    '.wkab-num{text-align:right;color:var(--bs-secondary-color,#6c757d);white-space:nowrap;}',
    '.wkab-empty{padding:2rem;text-align:center;color:var(--bs-secondary-color,#6c757d);}',
    '.wkab-count{flex:1;font-size:.85rem;color:var(--bs-secondary-color,#6c757d);}',
    '.wkab-foot-path{flex:1;display:flex;align-items:center;gap:.4rem;min-width:0;}',
    '.wkab-foot-path .pfx{color:var(--bs-secondary-color,#6c757d);}',
    '.wkab-foot-locale{width:84px;flex-shrink:0;}',
    // Embedded (non-modal) browser — used by the /f asset manager.
    '.wkab-embed{display:flex;flex-direction:column;border:1px solid var(--bs-border-color,#dee2e6);border-radius:.5rem;overflow:hidden;height:520px;background:var(--bs-body-bg,#fff);}',
    '.wkab-embed .wkab-body{flex:1;}',
    '.wkab-actions{white-space:nowrap;text-align:right;}',
    '.wkab-actions a{color:var(--bs-link-color,var(--bs-primary,#0d9488));text-decoration:none;font-size:.85rem;}',
    '.wkab-actions a:hover{text-decoration:underline;}',
    '.wkab-table tr.file.sel .wkab-actions a{color:#fff;}',
  ].join('\n');

  function ensureStyle() {
    if (document.getElementById(STYLE_ID)) return;
    const s = document.createElement('style');
    s.id = STYLE_ID;
    s.textContent = CSS;
    document.head.appendChild(s);
  }

  function el(tag, cls, text) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }

  function formatSize(bytes) {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(2)} kB`;
    return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
  }

  function leafOf(path) {
    const i = path.lastIndexOf('/');
    return i >= 0 ? path.slice(i + 1) : path;
  }

  // Fold a flat list of {path,...} items into a folder tree keyed by path segments. Each item gets
  // a .leaf (last segment) and is filed under the node for its parent folder.
  function buildTree(items) {
    const root = { name: '', path: '', children: {}, files: [] };
    items.forEach((it) => {
      const segs = it.path.split('/');
      const leaf = segs.pop();
      let node = root;
      let acc = '';
      segs.forEach((seg) => {
        acc = acc ? `${acc}/${seg}` : seg;
        if (!node.children[seg]) node.children[seg] = { name: seg, path: acc, children: {}, files: [] };
        node = node.children[seg];
      });
      it.leaf = leaf;
      node.files.push(it);
    });
    return root;
  }

  function childFolders(node) {
    return Object.keys(node.children).sort().map((k) => node.children[k]);
  }

  function findNode(root, path) {
    if (!path) return root;
    let node = root;
    const segs = path.split('/');
    for (let i = 0; i < segs.length; i++) {
      node = node.children[segs[i]];
      if (!node) return root;
    }
    return node;
  }

  // The form CSRF token, read from whatever page hosts the picker (editor/settings/new all render one).
  function csrfToken() {
    const inp = document.querySelector('input[name="_csrf"]');
    return inp ? inp.value : '';
  }

  // POST files to the asset upload endpoint as AJAX → {uploaded, conflicts, errors}. _csrf goes first so
  // the server can validate it before streaming bytes; `overwrite` replaces a same-path asset in place.
  function postUpload(files, folder, overwrite) {
    const fd = new FormData();
    fd.append('_csrf', csrfToken());
    fd.append('folder', folder);
    if (overwrite) fd.append('overwrite', '1');
    files.forEach((f) => fd.append('file', f));
    return fetch('/f', { method: 'POST', credentials: 'same-origin', headers: { 'X-Wk-Ajax': '1' }, body: fd })
      .then((r) => (r.ok ? r.json() : { uploaded: 0, conflicts: [], errors: [`Upload failed (${r.status}).`] }))
      .catch(() => ({ uploaded: 0, conflicts: [], errors: ['Upload failed.'] }));
  }

  // Upload each file into the browser's current folder; a same-name file prompts to overwrite (which
  // archives the old as a revision). Reloads the browser afterward so the new/updated asset shows.
  async function uploadFiles(files, api) {
    const folder = api.getCurrent().path;
    for (const file of files) {
      let res = await postUpload([file], folder, false);
      if (res.conflicts && res.conflicts.length && window.confirm(`"${res.conflicts[0]}" already exists here. Overwrite it?`)) {
        res = await postUpload([file], folder, true);
      }
      if (res.errors && res.errors.length) window.alert(res.errors.join('\n'));
    }
    await api.reload();
  }

  /*
   * Generic two-pane browser. `profile` supplies the bits that differ between pickers:
   *   title, confirmLabel, headerIcon
   *   load()                         -> Promise<items[]>  (already normalized, each has .path)
   *   sortOptions: [{value,label,cmp}]
   *   headers: [{label, cls}]        column headers for the file table
   *   renderCells(item) -> [td,...]   cells for a file row (after the implicit nothing)
   *   buildFooter(slot, api) -> {onSelect(item), onNavigate(node), onRender(node,files), getResult(), canConfirm()}
   *   confirmOnDblClick: bool
   *   emptyText(filtered)
   */
  function openBrowser(profile) {
    ensureStyle();
    return new Promise((resolve) => {
      // Bootstrap modal shell (focus-trap, scroll-lock, ARIA, proper stacking); the two-pane content
      // inside is custom. Escape / backdrop / × dismiss natively and resolve null (a cancel); only the
      // Confirm button (or a double-click) sets `result` before hiding.
      const wrap = el('div', 'modal fade wkab-modal');
      wrap.tabIndex = -1;
      wrap.setAttribute('aria-hidden', 'true');
      const content = el('div', 'modal-content');
      const dialog = el('div', 'modal-dialog modal-lg modal-dialog-centered');
      dialog.appendChild(content);
      wrap.appendChild(dialog);

      const header = el('div', 'modal-header');
      const title = el('h5', 'modal-title');
      const icon = el('i', `mdi ${profile.headerIcon || 'mdi-folder-outline'}`);
      icon.setAttribute('aria-hidden', 'true');
      title.appendChild(icon);
      title.appendChild(document.createTextNode(' '));
      title.appendChild(el('span', null, profile.title));
      const xBtn = el('button', 'btn-close');
      xBtn.type = 'button';
      xBtn.setAttribute('data-bs-dismiss', 'modal');
      xBtn.setAttribute('aria-label', 'Close');
      header.appendChild(title);
      header.appendChild(xBtn);
      content.appendChild(header);

      const bodyWrap = el('div', 'modal-body wkab-bsbody');
      const toolbar = el('div', 'wkab-toolbar');
      const crumbs = el('div', 'wkab-crumbs');
      const filter = el('input', 'form-control form-control-sm wkab-filter');
      filter.type = 'search';
      filter.placeholder = 'Filter…';
      const sort = el('select', 'form-select form-select-sm wkab-sort');
      profile.sortOptions.forEach((o) => {
        const opt = el('option', null, o.label); opt.value = o.value; sort.appendChild(opt);
      });
      toolbar.appendChild(crumbs);
      toolbar.appendChild(filter);
      toolbar.appendChild(sort);
      const body = el('div', 'wkab-body');
      const treeEl = el('div', 'wkab-tree');
      const main = el('div', 'wkab-main');
      body.appendChild(treeEl);
      body.appendChild(main);
      bodyWrap.appendChild(toolbar);
      bodyWrap.appendChild(body);
      content.appendChild(bodyWrap);

      const footer = el('div', 'modal-footer wkab-bsfoot');
      const footSlot = el('div', 'wkab-foot-slot', null);
      footSlot.style.cssText = 'flex:1;display:flex;align-items:center;gap:.5rem;min-width:0;';
      const cancelBtn = el('button', 'btn btn-outline-secondary btn-sm', 'Cancel');
      cancelBtn.type = 'button';
      cancelBtn.setAttribute('data-bs-dismiss', 'modal');
      const confirmBtn = el('button', 'btn btn-primary btn-sm', profile.confirmLabel || 'Select');
      confirmBtn.type = 'button';
      footer.appendChild(footSlot);
      footer.appendChild(cancelBtn);
      footer.appendChild(confirmBtn);
      content.appendChild(footer);

      document.body.appendChild(wrap);

      if (!window.bootstrap || !window.bootstrap.Modal) { wrap.remove(); resolve(null); return; }
      let result = null;
      const modal = new window.bootstrap.Modal(wrap, {});
      wrap.addEventListener('hidden.bs.modal', () => {
        modal.dispose();
        if (wrap.parentNode) wrap.parentNode.removeChild(wrap);
        resolve(result);
      });

      let root = { name: '', path: '', children: {}, files: [] };
      let current = root;
      let selected = null;
      const expanded = { '': true };

      function close(r) { result = r || null; modal.hide(); }

      // The footer controller (asset vs page) is built against this small API.
      const api = {
        close,
        getCurrent: () => current,
        getRoot: () => root,
        getSelected: () => selected,
        navigate: (node) => navigate(node),
        refreshConfirm,
      };
      const foot = profile.buildFooter(footSlot, api);

      function refreshConfirm() { confirmBtn.disabled = !foot.canConfirm(); }
      confirmBtn.addEventListener('click', () => {
        if (foot.canConfirm()) close(foot.getResult());
      });

      function setSelected(item, row) {
        selected = item;
        main.querySelectorAll('tr.file.sel').forEach((r) => r.classList.remove('sel'));
        if (row) row.classList.add('sel');
        if (foot.onSelect) foot.onSelect(item);
        refreshConfirm();
      }

      function navigate(node) {
        current = node;
        selected = null;
        let p = node.path;
        while (true) { expanded[p] = true; if (!p) break; p = p.indexOf('/') >= 0 ? p.slice(0, p.lastIndexOf('/')) : ''; }
        if (foot.onNavigate) foot.onNavigate(node);
        renderCrumbs();
        renderTree();
        renderMain();
        refreshConfirm();
      }

      function renderCrumbs() {
        crumbs.textContent = '';
        const rootBtn = el('button', null, profile.rootLabel || '/');
        rootBtn.type = 'button';
        rootBtn.addEventListener('click', () => navigate(root));
        crumbs.appendChild(rootBtn);
        if (current.path) {
          const segs = current.path.split('/');
          let acc = '';
          segs.forEach((seg, i) => {
            acc = acc ? `${acc}/${seg}` : seg;
            crumbs.appendChild(el('span', 'sep', ' › '));
            if (i === segs.length - 1) {
              crumbs.appendChild(el('span', 'cur', seg));
            } else {
              const thisPath = acc;
              const b = el('button', null, seg);
              b.type = 'button';
              b.addEventListener('click', () => navigate(findNode(root, thisPath)));
              crumbs.appendChild(b);
            }
          });
        }
      }

      function renderTree() {
        treeEl.textContent = '';
        const ul = el('ul');
        ul.appendChild(folderRow(root, profile.treeRootLabel || 'All'));
        treeEl.appendChild(ul);
      }

      function folderRow(node, labelOverride) {
        const li = el('li');
        const row = el('div', `wkab-row${node === current ? ' active' : ''}`);
        const kids = childFolders(node);
        const twist = el('span', `twist${kids.length ? '' : ' empty'}`, kids.length ? (expanded[node.path] ? '▾' : '▸') : '•');
        twist.addEventListener('click', (e) => {
          e.stopPropagation();
          if (kids.length) { expanded[node.path] = !expanded[node.path]; renderTree(); }
        });
        const fic = el('i', 'mdi mdi-folder-outline');
        fic.setAttribute('aria-hidden', 'true');
        const lbl = el('span', null, labelOverride || node.name);
        row.appendChild(twist);
        row.appendChild(fic);
        row.appendChild(lbl);
        row.addEventListener('click', () => navigate(node));
        li.appendChild(row);
        if (kids.length && expanded[node.path]) {
          const sub = el('ul');
          kids.forEach((k) => sub.appendChild(folderRow(k)));
          li.appendChild(sub);
        }
        return li;
      }

      function renderMain() {
        main.textContent = '';
        const q = filter.value.trim().toLowerCase();
        const folders = childFolders(current).filter((f) => !q || f.name.toLowerCase().indexOf(q) !== -1);
        const files = current.files.filter((f) => !q || `${f.leaf} ${f.title || ''}`.toLowerCase().indexOf(q) !== -1);
        const cmp = (profile.sortOptions.find((o) => o.value === sort.value) || profile.sortOptions[0]).cmp;
        files.sort(cmp);

        if (foot.onRender) foot.onRender(current, files);

        if (!folders.length && !files.length) {
          main.appendChild(el('div', 'wkab-empty', profile.emptyText ? profile.emptyText(!!q) : (q ? 'No matches.' : 'Empty.')));
          return;
        }

        const table = el('table', 'wkab-table');
        const thead = el('thead');
        const htr = el('tr');
        profile.headers.forEach((hd) => htr.appendChild(el('th', hd.cls || null, hd.label)));
        thead.appendChild(htr);
        table.appendChild(thead);
        const tbody = el('tbody');

        folders.forEach((f) => {
          const tr = el('tr', 'dir');
          const nameTd = el('td');
          const wrap = el('div', 'wkab-name');
          const ic = el('i', 'mdi mdi-folder'); ic.setAttribute('aria-hidden', 'true');
          wrap.appendChild(ic);
          wrap.appendChild(el('span', null, f.name));
          nameTd.appendChild(wrap);
          tr.appendChild(nameTd);
          // pad remaining columns
          for (let i = 1; i < profile.headers.length; i++) tr.appendChild(el('td', profile.headers[i].cls || null, i === 1 ? 'folder' : ''));
          tr.addEventListener('click', () => navigate(f));
          tbody.appendChild(tr);
        });

        files.forEach((item) => {
          const tr = el('tr', 'file');
          profile.renderCells(item, api).forEach((td) => tr.appendChild(td));
          tr.addEventListener('click', () => setSelected(item, tr));
          if (profile.confirmOnDblClick) {
            tr.addEventListener('dblclick', () => { setSelected(item, tr); if (foot.canConfirm()) close(foot.getResult()); });
          }
          tbody.appendChild(tr);
        });

        table.appendChild(tbody);
        main.appendChild(table);
      }

      filter.addEventListener('input', renderMain);
      sort.addEventListener('change', renderMain);

      // Expose render helpers the footer may need (e.g. page picker rebuilding on locale change).
      api.setRoot = (newRoot) => { root = newRoot; navigate(root); };
      api.el = el;
      // Re-fetch the data and rebuild, staying in the current folder (used after upload / edit).
      api.reload = () => profile.load().then((items) => {
        const path = current.path;
        root = buildTree(items);
        navigate(findNode(root, path));
      });
      // Add a (derived) sub-folder under the current one and navigate into it, so the next upload lands
      // there. It only persists once something is uploaded into it (folders are path-derived).
      api.addFolder = (rawName) => {
        const name = rawName.toLowerCase().replace(/[^a-z0-9-]+/g, '-').replace(/^-+|-+$/g, '');
        if (!name) return;
        const cur = current;
        const path = cur.path ? `${cur.path}/${name}` : name;
        if (!cur.children[name]) cur.children[name] = { name, path, children: {}, files: [] };
        navigate(cur.children[name]);
      };
      if (profile.toolbarExtra) profile.toolbarExtra(toolbar, api);

      modal.show();
      profile.load().then((items) => {
        root = buildTree(items);
        navigate(root);
        if (!items.length && profile.onEmptyLoad) profile.onEmptyLoad(main, el);
        filter.focus();
      });
    });
  }

  // ---- Public picker: asset file browser ---------------------------------------------------------

  function isImage(a) { return a.mime && a.mime.indexOf('image/') === 0; }

  function assetTypeLabel(a) {
    const dot = a.path.lastIndexOf('.'), slash = a.path.lastIndexOf('/');
    if (dot > slash && dot >= 0) return a.path.slice(dot + 1);
    const s = a.mime.indexOf('/');
    return s >= 0 ? a.mime.slice(s + 1) : a.mime;
  }

  window.WikiKtAssetBrowser = {
    open(options) {
      options = options || {};
      const imagesOnly = !!options.imagesOnly;
      let countEl;
      return openBrowser({
        title: options.title || 'Select an asset',
        headerIcon: 'mdi-folder-multiple-image',
        confirmLabel: options.confirmLabel || 'Insert',
        rootLabel: '/',
        treeRootLabel: '(root)',
        confirmOnDblClick: true,
        headers: [{ label: 'Name' }, { label: 'Type' }, { label: 'Size', cls: 'wkab-num' }, { label: '', cls: 'wkab-actions' }],
        // Upload (to the current folder) + New folder controls, added to the browser toolbar.
        toolbarExtra: (toolbar, api) => {
          const fileInput = el('input');
          fileInput.type = 'file'; fileInput.multiple = true;
          fileInput.accept = 'image/png,image/jpeg,image/gif,image/webp';
          fileInput.style.display = 'none';
          const uploadBtn = el('button', 'btn btn-outline-primary btn-sm');
          uploadBtn.type = 'button';
          uploadBtn.innerHTML = '<i class="mdi mdi-upload" aria-hidden="true"></i> Upload';
          uploadBtn.title = 'Upload image(s) into the current folder';
          uploadBtn.addEventListener('click', () => fileInput.click());
          fileInput.addEventListener('change', async () => {
            if (!fileInput.files.length) return;
            uploadBtn.disabled = true;
            try { await uploadFiles([...fileInput.files], api); } finally { uploadBtn.disabled = false; fileInput.value = ''; }
          });
          const newFolderBtn = el('button', 'btn btn-outline-secondary btn-sm');
          newFolderBtn.type = 'button';
          newFolderBtn.innerHTML = '<i class="mdi mdi-folder-plus-outline" aria-hidden="true"></i> New folder';
          newFolderBtn.addEventListener('click', () => {
            const name = window.prompt(`New folder under /${api.getCurrent().path}:`);
            if (name && name.trim()) api.addFolder(name.trim());
          });
          toolbar.appendChild(uploadBtn);
          toolbar.appendChild(newFolderBtn);
          toolbar.appendChild(fileInput);
        },
        sortOptions: [
          { value: 'name', label: 'Name', cmp: (a, b) => a.leaf.localeCompare(b.leaf) },
          { value: 'size', label: 'Size', cmp: (a, b) => b.sizeBytes - a.sizeBytes },
          { value: 'newest', label: 'Newest', cmp: (a, b) => b.createdAt - a.createdAt },
        ],
        emptyText: (filtered) => (filtered ? 'No matches in this folder.' : 'This folder is empty.'),
        load: () => fetch('/u/v1/assets', { credentials: 'same-origin' })
          .then((r) => (r.ok ? r.json() : []))
          .catch(() => [])
          .then((assets) => (imagesOnly ? assets.filter(isImage) : assets)),
        onEmptyLoad: (main, el) => {
          main.textContent = '';
          main.appendChild(el('div', 'wkab-empty', imagesOnly
            ? 'No images uploaded yet. Upload some in the file manager (/f).'
            : 'No assets uploaded yet.'));
        },
        renderCells: (a, api) => {
          const nameTd = document.createElement('td');
          const wrap = document.createElement('div'); wrap.className = 'wkab-name';
          if (isImage(a)) {
            const img = document.createElement('img'); img.className = 'wkab-thumb';
            img.src = a.url; img.alt = ''; img.loading = 'lazy';
            wrap.appendChild(img);
          } else {
            const ic = document.createElement('i'); ic.className = 'mdi mdi-file-outline'; ic.setAttribute('aria-hidden', 'true');
            wrap.appendChild(ic);
          }
          const nm = document.createElement('span'); nm.textContent = a.leaf; wrap.appendChild(nm);
          nameTd.appendChild(wrap);
          const typeTd = document.createElement('td');
          const badge = document.createElement('span'); badge.className = 'wkab-badge'; badge.textContent = assetTypeLabel(a);
          typeTd.appendChild(badge);
          const sizeTd = document.createElement('td'); sizeTd.className = 'wkab-num'; sizeTd.textContent = formatSize(a.sizeBytes);
          // "Edit" opens the detail modal over the picker (both Bootstrap → stacks); reload on change.
          const actTd = document.createElement('td'); actTd.className = 'wkab-actions';
          const edit = document.createElement('a'); edit.href = '#'; edit.textContent = 'Edit';
          edit.addEventListener('click', (e) => {
            e.preventDefault(); e.stopPropagation();
            if (window.WikiKtAssetDetail) window.WikiKtAssetDetail.open(a.id, { onChange: () => api.reload() });
          });
          actTd.appendChild(edit);
          return [nameTd, typeTd, sizeTd, actTd];
        },
        buildFooter: (slot, api) => {
          countEl = el('div', 'wkab-count', 'Loading…');
          slot.appendChild(countEl);
          return {
            onRender: (node, files) => {
              const folders = childFolders(node).length;
              countEl.textContent = `${files.length} file${files.length === 1 ? '' : 's'}` +
                (folders ? `, ${folders} folder${folders === 1 ? '' : 's'}` : '');
            },
            onSelect: () => {},
            getResult: () => {
              const s = api.getSelected();
              return s ? { id: s.id, locale: s.locale, path: s.path, url: s.url, mime: s.mime, filename: s.leaf, hasAlt: !!s.hasAlt } : null;
            },
            canConfirm: () => !!api.getSelected(),
          };
        },
      });
    },
  };

  // ---- Public picker: page path browse-or-create -------------------------------------------------

  window.WikiKtPagePicker = {
    open(options) {
      options = options || {};
      const defaultLocale = options.defaultLocale || 'en';
      let allPages = [];                 // every page {locale, path, title}
      let localeSel, pathInput;
      let locale = options.locale || defaultLocale;
      // Caller-supplied enabled-locale set (from site settings); when absent, derive from the data.
      const providedLocales = (options.locales && options.locales.length) ? options.locales : null;

      // Rebuild the tree for the currently-selected locale.
      function pagesForLocale(loc) {
        return allPages.filter((p) => p.locale === loc)
          .map((p) => ({ locale: p.locale, path: p.path, title: p.title }));
      }

      return openBrowser({
        title: options.title || 'Select Page Location',
        headerIcon: 'mdi-file-tree',
        confirmLabel: options.confirmLabel || 'Select',
        rootLabel: '/',
        treeRootLabel: '(root)',
        confirmOnDblClick: false,
        headers: [{ label: 'Pages' }, { label: 'Path' }],
        sortOptions: [
          { value: 'name', label: 'Title', cmp: (a, b) => (a.title || a.leaf).localeCompare(b.title || b.leaf) },
          { value: 'path', label: 'Path', cmp: (a, b) => a.path.localeCompare(b.path) },
        ],
        emptyText: (filtered) => (filtered ? 'No matching pages.' : 'No pages in this folder — type a name below to create one here.'),
        load: () => fetch('/u/v1/pages/paths', { credentials: 'same-origin' })
          .then((r) => (r.ok ? r.json() : []))
          .catch(() => [])
          .then((pages) => { allPages = pages; return pagesForLocale(locale); }),
        renderCells: (p) => {
          const nameTd = document.createElement('td');
          const wrap = document.createElement('div'); wrap.className = 'wkab-name';
          const ic = document.createElement('i'); ic.className = 'mdi mdi-file-document-outline'; ic.setAttribute('aria-hidden', 'true');
          wrap.appendChild(ic);
          const nm = document.createElement('span'); nm.textContent = p.title || p.leaf; wrap.appendChild(nm);
          nameTd.appendChild(wrap);
          const pathTd = document.createElement('td');
          const sub = document.createElement('span'); sub.className = 'wkab-sub'; sub.textContent = `/${p.path}`;
          pathTd.appendChild(sub);
          return [nameTd, pathTd];
        },
        buildFooter: (slot, api) => {
          // Locale selector — use the caller's enabled set if given, else seed with default + chosen
          // and finalize from the data after load.
          localeSel = el('select', 'form-select form-select-sm wkab-foot-locale');
          const seedList = providedLocales || (defaultLocale === locale ? [defaultLocale] : [defaultLocale, locale]);
          seedList.forEach((l) => {
            const o = el('option', null, l); o.value = l; if (l === locale) o.selected = true; localeSel.appendChild(o);
          });
          // Editable path field with a leading "/"
          const pathWrap = el('div', 'wkab-foot-path');
          pathWrap.appendChild(el('span', 'pfx', '/'));
          pathInput = el('input', 'form-control form-control-sm');
          pathInput.type = 'text';
          pathInput.placeholder = 'new-page';
          pathInput.value = options.path || '';
          pathInput.spellcheck = false;
          pathInput.autocomplete = 'off';
          pathWrap.appendChild(pathInput);
          slot.appendChild(localeSel);
          slot.appendChild(pathWrap);

          pathInput.addEventListener('input', api.refreshConfirm);
          localeSel.addEventListener('change', () => {
            locale = localeSel.value;
            api.setRoot(buildTree(pagesForLocale(locale)));
          });

          let navCount = 0;
          return {
            onNavigate: (node) => {
              navCount++;
              if (navCount === 1) {
                // First navigate is the programmatic load to root — keep any prefilled path and, unless
                // the caller supplied an explicit locale set, backfill options from the loaded data.
                if (!providedLocales) {
                  const seen = {};
                  allPages.forEach((p) => { seen[p.locale] = true; });
                  seen[defaultLocale] = true;
                  if (Object.keys(seen).length > localeSel.options.length) {
                    localeSel.textContent = '';
                    Object.keys(seen).sort().forEach((l) => {
                      const o = el('option', null, l); o.value = l; if (l === locale) o.selected = true; localeSel.appendChild(o);
                    });
                  }
                }
                return;
              }
              // Browsing into a folder sets the path prefix; the user appends a slug.
              pathInput.value = node.path ? `${node.path}/` : '';
            },
            onSelect: (item) => {
              // Clicking an existing page targets that exact path.
              if (item) pathInput.value = item.path;
              api.refreshConfirm();
            },
            getResult: () => {
              const v = pathInput.value.trim();
              const match = allPages.filter((p) => p.locale === locale && p.path === v)[0];
              // Canonical view URL always carries the locale (mirrors server-side wikiViewUrl).
              return { locale, path: v, exists: !!match, title: match ? match.title : '', url: `/${locale}/${v}` };
            },
            canConfirm: () => {
              const v = pathInput.value.trim();
              return v.length > 0 && v.indexOf('//') === -1 && v.charAt(0) !== '/';
            },
          };
        },
      });
    },
  };

  // ---- Asset detail/edit modal ------------------------------------------------------------------
  //
  // Opens the existing server-rendered /f/{id} detail body inside a Bootstrap modal and submits its
  // forms (metadata, replace, restore, delete) via AJAX — following the same redirects the no-JS forms
  // use, re-injecting the refreshed detail on success — so there are no separate endpoints to maintain.
  // Resolves with { changed } when closed; calls opts.onChange() if anything was mutated.
  window.WikiKtAssetDetail = {
    open(id, opts) {
      opts = opts || {};
      return new Promise((resolve) => {
        // Uses Bootstrap's Modal (focus trap, scroll-lock, ARIA) + Tab plugins. Falls back to the
        // standalone page if Bootstrap JS isn't present.
        if (!window.bootstrap || !window.bootstrap.Modal) { window.location.href = `/f/${id}`; resolve({ changed: false }); return; }

        let changed = false;
        const wrap = document.createElement('div');
        wrap.className = 'modal fade';
        wrap.tabIndex = -1;
        wrap.setAttribute('aria-hidden', 'true');
        wrap.innerHTML =
          '<div class="modal-dialog modal-lg modal-dialog-scrollable">' +
            '<div class="modal-content">' +
              '<div class="modal-header">' +
                '<h5 class="modal-title"><i class="mdi mdi-image-edit-outline" aria-hidden="true"></i> <span class="wkad-title">Asset</span></h5>' +
                '<button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close"></button>' +
              '</div>' +
              '<div class="modal-body wkad-body"><p class="text-secondary">Loading…</p></div>' +
              '<div class="modal-footer wkad-footer" style="display:none"></div>' +
            '</div>' +
          '</div>';
        document.body.appendChild(wrap);
        const bodyEl = wrap.querySelector('.wkad-body');
        const footerEl = wrap.querySelector('.wkad-footer');
        const titleEl = wrap.querySelector('.wkad-title');
        const modal = new window.bootstrap.Modal(wrap, {});

        wrap.addEventListener('hidden.bs.modal', () => {
          modal.dispose();
          if (wrap.parentNode) wrap.parentNode.removeChild(wrap);
          if (changed && opts.onChange) opts.onChange({ changed });
          resolve({ changed });
        });

        function activeTabTarget() {
          const a = bodyEl.querySelector('.nav-link.active');
          return a ? a.getAttribute('data-bs-target') : null;
        }
        function restoreTab(target) {
          if (!target) return;
          const btn = bodyEl.querySelector(`.nav-link[data-bs-target="${target}"]`);
          if (btn && window.bootstrap.Tab) window.bootstrap.Tab.getOrCreateInstance(btn).show();
        }

        // Fetch the server-rendered detail body and inject it (keeping the active tab across reloads).
        function load(url, init, keepTab) {
          const target = keepTab ? activeTabTarget() : null;
          bodyEl.style.opacity = '.5';
          return fetch(url, Object.assign({ credentials: 'same-origin', redirect: 'follow' }, init || {}))
            .then((res) => res.text().then((t) => ({ url: res.url, text: t, ok: res.ok })))
            .then((r) => {
              bodyEl.style.opacity = '';
              if (!r.ok) { bodyEl.innerHTML = '<p class="text-danger">Could not load this asset.</p>'; return; }
              const doc = new DOMParser().parseFromString(r.text, 'text/html');
              const detail = doc.querySelector('.asset-detail');
              // Landing on the list (no detail body) means the asset was deleted.
              if (!detail || /\/f\/?$/.test(r.url) || doc.querySelector('#assetManager')) { changed = true; modal.hide(); return; }
              const h1 = doc.querySelector('main h1');
              if (h1) titleEl.textContent = h1.textContent;
              // Lift the action bar into the Bootstrap modal-footer (kept fixed below the scrollable body,
              // so it never overlaps content); the rest of the detail goes in the body.
              const actions = detail.querySelector('.asset-actions');
              if (actions) { footerEl.innerHTML = actions.innerHTML; footerEl.style.display = ''; actions.remove(); }
              else { footerEl.innerHTML = ''; footerEl.style.display = 'none'; }
              bodyEl.innerHTML = detail.outerHTML;
              restoreTab(target);
            })
            .catch(() => { bodyEl.style.opacity = ''; bodyEl.innerHTML = '<p class="text-danger">Could not load this asset.</p>'; });
        }

        // AJAX-submit any detail form (submit bubbles to the modal root, so this covers re-injected
        // forms too). Inline confirms gate via defaultPrevented; file forms go multipart, rest urlencoded.
        wrap.addEventListener('submit', (e) => {
          const form = e.target;
          if (!form || form.tagName !== 'FORM' || e.defaultPrevented) return;
          e.preventDefault();
          changed = true;
          // "Save and close" buttons carry data-wkad-close: POST, then close (onChange refreshes /f) —
          // skipping the body re-render we'd otherwise do for an in-place save.
          const closeAfter = !!(e.submitter && e.submitter.getAttribute && e.submitter.getAttribute('data-wkad-close'));
          const fileInput = form.querySelector('input[type=file]');
          const init = (fileInput && fileInput.files.length)
            ? { method: 'POST', body: new FormData(form) }
            : { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams(new FormData(form)).toString() };
          if (closeAfter) {
            fetch(form.action, Object.assign({ credentials: 'same-origin', redirect: 'follow' }, init))
              .then(() => modal.hide(), () => modal.hide());
          } else {
            load(form.action, init, true);
          }
        });

        modal.show();
        load(`/f/${id}`, null, false);
      });
    },
  };

  // Shared path-tree helpers + CSS injection, exposed so non-modal surfaces (the /f asset manager)
  // can reuse the same folder model and visual language without duplicating it.
  window.WkBrowserCore = { el, formatSize, leafOf, buildTree, childFolders, findNode, ensureStyle };
})();
