// Page-editor behaviors for page/edit.hbs (EasyMDE/CodeMirror setup, Page Info modal, sticky-bar
// height sync, asset/fragment affordances, save handling). Server values arrive via data-*
// attributes on the editor form, never by templating this file. Loaded after wk-browser.js and
// the vendored EasyMDE/Bootstrap bundles, in the same spot the inline block used to occupy.
(function () {
  // Page Info is a Bootstrap modal (opened by the toolbar button via data-bs-toggle). Auto-open it when
  // the save came back with a validation error, since the offending fields (path/locale/…) live inside it.
  var pageInfoEl = document.getElementById('pageInfo');
  if (pageInfoEl && document.querySelector('.editor-error') && window.bootstrap) {
    window.bootstrap.Modal.getOrCreateInstance(pageInfoEl).show();
  }
  // Title shows in the bar and in Page Info, backed by one field — keep them in sync.
  var titleBar = document.getElementById('titleBar');
  var titlePanel = document.getElementById('titlePanel');
  if (titleBar && titlePanel) {
    titleBar.addEventListener('input', function () { titlePanel.value = titleBar.value; });
    titlePanel.addEventListener('input', function () { titleBar.value = titlePanel.value; });
  }

  // Tags (Page Info): chip editor with autocomplete. Chips are rendered from / synced to the hidden
  // "tags" field (comma-separated), suggestions come from /u/v1/tags (fetched once, on first use).
  // Free-typed values that match no suggestion are simply new tags — the server creates them on save.
  (function () {
    var editor = document.getElementById('tagEditor');
    var input = document.getElementById('tagInput');
    var field = document.getElementById('tagsField');
    var box = document.getElementById('tagSuggest');
    if (!editor || !input || !field || !box) return;
    var tags = field.value.split(',').map(function (t) { return t.trim().toLowerCase(); }).filter(Boolean);
    var allPromise = null, items = [], active = -1;

    function loadAll() {
      if (!allPromise) {
        allPromise = fetch('/u/v1/tags', { credentials: 'same-origin' })
          .then(function (r) { return r.ok ? r.json() : []; })
          .catch(function () { return []; });
      }
      return allPromise;
    }
    function sync() { field.value = tags.join(', '); }
    function renderChips() {
      editor.querySelectorAll('.tag-chip').forEach(function (c) { c.remove(); });
      tags.forEach(function (t) {
        var chip = document.createElement('span');
        chip.className = 'tag-chip';
        var label = document.createElement('span');
        label.textContent = t;
        var x = document.createElement('button');
        x.type = 'button';
        x.className = 'tag-chip-remove';
        x.setAttribute('aria-label', 'Remove tag ' + t);
        x.innerHTML = '<i class="mdi mdi-close-circle" aria-hidden="true"></i>';
        x.addEventListener('click', function () {
          tags = tags.filter(function (o) { return o !== t; });
          sync(); renderChips(); input.focus();
        });
        chip.appendChild(label); chip.appendChild(x);
        editor.insertBefore(chip, input);
      });
    }
    function closeSuggest() { box.hidden = true; box.innerHTML = ''; items = []; active = -1; }
    // Mirrors the server's normalizeTags: trimmed, lowercased, capped at 100 chars, deduped.
    function addTag(raw) {
      var t = raw.trim().toLowerCase().slice(0, 100);
      if (t && tags.indexOf(t) === -1) { tags.push(t); sync(); renderChips(); }
      input.value = '';
      closeSuggest();
    }
    function renderSuggest(matches, q) {
      closeSuggest();
      if (!matches.length) return;
      items = matches;
      matches.forEach(function (m) {
        var li = document.createElement('li');
        var idx = q ? m.indexOf(q) : -1;
        if (idx !== -1) {
          li.appendChild(document.createTextNode(m.slice(0, idx)));
          var mark = document.createElement('mark');
          mark.textContent = m.slice(idx, idx + q.length);
          li.appendChild(mark);
          li.appendChild(document.createTextNode(m.slice(idx + q.length)));
        } else {
          li.textContent = m;
        }
        li.addEventListener('mousedown', function (e) { e.preventDefault(); addTag(m); });
        box.appendChild(li);
      });
      active = -1;
      box.hidden = false;
    }
    function refreshSuggest() {
      var q = input.value.trim().toLowerCase();
      loadAll().then(function (all) {
        if (input.value.trim().toLowerCase() !== q) return; // input changed while loading
        var matches = all.filter(function (t) {
          return tags.indexOf(t) === -1 && (!q || t.indexOf(q) !== -1);
        }).slice(0, 8);
        renderSuggest(matches, q);
      });
    }
    function move(delta) {
      if (!items.length) return;
      if (active >= 0) box.childNodes[active].classList.remove('active');
      active = (active + delta + items.length) % items.length;
      var el = box.childNodes[active];
      el.classList.add('active');
      el.scrollIntoView({ block: 'nearest' });
    }
    input.addEventListener('input', refreshSuggest);
    input.addEventListener('focus', refreshSuggest);
    // Commit typed text on blur (Vuetify-combobox behavior, like WikiJS); the delay lets a suggestion
    // mousedown run first (it clears the input, so nothing double-commits).
    input.addEventListener('blur', function () {
      setTimeout(function () { if (input.value.trim()) addTag(input.value); else closeSuggest(); }, 150);
    });
    input.addEventListener('keydown', function (e) {
      if (e.key === 'Enter') {
        e.preventDefault(); // the tag input must never submit the surrounding page form
        if (!box.hidden && active >= 0) addTag(items[active]);
        else if (input.value.trim()) addTag(input.value);
      } else if (e.key === ',') {
        e.preventDefault();
        if (input.value.trim()) addTag(input.value);
      } else if (e.key === 'ArrowDown' && !box.hidden) { e.preventDefault(); move(1); }
      else if (e.key === 'ArrowUp' && !box.hidden) { e.preventDefault(); move(-1); }
      else if (e.key === 'Escape' && !box.hidden) { e.preventDefault(); closeSuggest(); }
      else if (e.key === 'Backspace' && !input.value && tags.length) { tags.pop(); sync(); renderChips(); }
    });
    // Clicking anywhere in the box focuses the input, so it feels like one big field.
    editor.addEventListener('mousedown', function (e) { if (e.target === editor) { e.preventDefault(); input.focus(); } });
    // A half-typed tag still counts when saving directly.
    var f = editor.closest('form');
    if (f) f.addEventListener('submit', function () { if (input.value.trim()) addTag(input.value); });
    renderChips();
  })();

  // The enabled content locales, read from the Page Info locale <select> (shared by the move/link pickers).
  function localeOptionValues() {
    var sel = document.getElementById('moveLocale');
    return sel ? [].map.call(sel.options, function (o) { return o.value; }) : [];
  }

  var textarea = document.querySelector('textarea[name="content"]');
  if (!textarea || typeof EasyMDE === 'undefined') return;
  var formatSelect = document.querySelector('[name="contentFormat"]');
  var mde = function (name, action, icon, title, noDisable) {
    return { name: name, action: action, className: "mdi mdi-" + icon, title: title, noDisable: !!noDisable };
  };
  var easymde = new EasyMDE({
    element: textarea,
    autoDownloadFontAwesome: false,
    spellChecker: false,
    nativeSpellcheck: true,
    inputStyle: "contenteditable",
    status: false,
    // EasyMDE turns each button's `name` into a CSS class on the <button>; prefix them all so e.g.
    // the "table" button doesn't pick up Bootstrap's .table styling (and to avoid future collisions).
    toolbarButtonClassPrefix: "mde",
    // Side-by-side preview should NOT force fullscreen, so our sticky top bar stays visible.
    sideBySideFullscreen: false,
    toolbar: [
      mde("bold", EasyMDE.toggleBold, "format-bold", "Bold"),
      mde("italic", EasyMDE.toggleItalic, "format-italic", "Italic"),
      mde("heading", EasyMDE.toggleHeadingSmaller, "format-header-pound", "Heading"),
      "|",
      mde("quote", EasyMDE.toggleBlockquote, "format-quote-close", "Quote"),
      mde("unordered-list", EasyMDE.toggleUnorderedList, "format-list-bulleted", "Bulleted list"),
      mde("ordered-list", EasyMDE.toggleOrderedList, "format-list-numbered", "Numbered list"),
      "|",
      mde("link", function (editor) {
        // Prefer the page picker (link to an existing wiki page); fall back to EasyMDE's URL prompt.
        if (!window.WikiKtPagePicker) { EasyMDE.drawLink(editor); return; }
        var cm = editor.codemirror;
        var sel = cm.getSelection();
        var ed = document.querySelector("form.editor");
        var dl = (ed && ed.dataset.defaultLocale) || "";
        WikiKtPagePicker.open({
          title: "Link to Page", confirmLabel: "Insert link",
          defaultLocale: dl, locale: (ed && ed.dataset.pageLocale) || dl,
          locales: localeOptionValues()
        }).then(function (r) {
          if (!r) return;
          var text = sel || r.title || r.path;
          cm.replaceSelection("[" + text + "](" + r.url + ")");
          cm.focus();
        });
      }, "link-variant", "Link"),
      mde("image", function (editor) {
        // Prefer the asset browser; fall back to EasyMDE's URL prompt if it failed to load.
        if (!window.WikiKtAssetBrowser) { EasyMDE.drawImage(editor); return; }
        // Tell the picker how many files its "Upload" may send at once (admin setting; the server enforces
        // the same cap regardless). Read from the editor form's data attribute rendered by editModel.
        var edForm = document.querySelector("form.editor");
        var maxUp = edForm && parseInt(edForm.dataset.maxUploadFiles, 10);
        if (maxUp > 0) window.__WK_UPLOAD_MAX__ = maxUp;
        WikiKtAssetBrowser.open({ imagesOnly: true, title: "Insert Image", confirmLabel: "Insert" })
          .then(function (asset) {
            if (!asset) return;
            var cm = editor.codemirror;
            // If the asset has a stored default alt, use the {alt} token so it stays in sync; otherwise
            // fall back to the filename as a starting alt.
            var alt = asset.hasAlt ? "{alt}" : asset.filename.replace(/\.[^.]+$/, "");
            // Author a locale-relative path: omit the locale when the asset is in this page's locale (it
            // resolves to the page locale at render), use an explicit /<locale>/ for a cross-locale asset.
            var ed = document.querySelector("form.editor");
            var pageLoc = (ed && ed.dataset.pageLocale) || (ed && ed.dataset.defaultLocale) || "";
            var ref = asset.locale === pageLoc ? "/" + asset.path : "/" + asset.locale + "/" + asset.path;
            cm.replaceSelection("![" + alt + "](" + ref + ")");
            cm.focus();
          });
      }, "image", "Image"),
      mde("code", EasyMDE.toggleCodeBlock, "code-tags", "Code block"),
      mde("table", EasyMDE.drawTable, "table", "Table"),
      "|",
      // Full-pane preview (swaps in place of the editor) — shown only on compact screens, where
      // the side-by-side split is impractical; CSS hides one or the other per breakpoint.
      mde("preview", EasyMDE.togglePreview, "eye-outline", "Preview", true),
      mde("side-by-side", function (editor) {
        EasyMDE.toggleSideBySide(editor);
        positionSidePreview();
      }, "view-split-vertical", "Side-by-side", true),
      mde("fullscreen", function (editor) {
        EasyMDE.toggleFullScreen(editor);
        positionSidePreview();
      }, "fullscreen", "Fullscreen", true),
      "|",
      mde("plain-view", function (editor) {
        var c = editor.codemirror.getWrapperElement().parentNode;
        if (c) c.classList.toggle("editor--plain");
      }, "file-code-outline", "Plain text view", true),
    ],
    previewRender: function (plainText, preview) {
      var fmt = formatSelect ? formatSelect.value : 'MARKDOWN';
      var pageLoc = (form && form.dataset.pageLocale) || '';
      var pagePath = (form && form.dataset.pagePath) || '';
      fetch('/preview', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        credentials: 'same-origin',
        body: 'contentFormat=' + encodeURIComponent(fmt) + '&locale=' + encodeURIComponent(pageLoc)
          + '&path=' + encodeURIComponent(pagePath) + '&content=' + encodeURIComponent(plainText)
      })
        .then(function (r) { return r.text(); })
        .then(function (html) { preview.innerHTML = '<div class="wiki-content">' + html + '</div>'; })
        .catch(function () { preview.innerHTML = '<p class="text-danger">Preview failed.</p>'; });
      return 'Loading preview…';
    }
  });
  // Firefox doesn't reliably paint ::before icons on <button>; move each toolbar icon into a
  // child <i> (the same pattern as the header icons, which render everywhere).
  document.querySelectorAll('.editor-toolbar button[class*="mdi-"]').forEach(function (btn) {
    var iconClass = Array.from(btn.classList).find(function (c) { return c.indexOf('mdi-') === 0; });
    if (!iconClass) return;
    btn.classList.remove('mdi', iconClass);
    var icon = document.createElement('i');
    icon.className = 'mdi ' + iconClass;
    icon.setAttribute('aria-hidden', 'true');
    btn.insertBefore(icon, btn.firstChild);
  });
  // Keep the formatting toolbar pinned just below the editor bar: expose the bar's rendered height
  // (it wraps to two rows on narrow screens) as --wk-editor-bar-h so the sticky offset in site.css
  // tracks it. ResizeObserver covers the wrap/unwrap; resize covers browsers without it.
  var editorBar = document.querySelector('.editor-bar');
  if (editorBar) {
    var syncBarHeight = function () {
      document.documentElement.style.setProperty('--wk-editor-bar-h', editorBar.offsetHeight + 'px');
    };
    syncBarHeight();
    window.addEventListener('resize', syncBarHeight);
    if (window.ResizeObserver) new ResizeObserver(syncBarHeight).observe(editorBar);
  }

  var cm = easymde.codemirror;
  var form = textarea.closest('form');
  var editorContainer = cm.getWrapperElement().parentNode;

  // Apply the global "plain view" default (monospace, no inline styling). The toolbar's
  // plain-view button toggles it for the current session.
  if (form && editorContainer && form.dataset.plainEditor === 'true') {
    editorContainer.classList.add('editor--plain');
  }

  setupLinkAutocomplete(cm, form);
  setupAssetLinkAffordance(cm, form);
  setupFragmentAffordance(cm, form);

  // "Browse…" beside the Path field (Page Info): pick a destination to move the page to.
  var browseMove = document.getElementById('browseMove');
  var movePath = document.getElementById('movePath');
  var moveLocale = document.getElementById('moveLocale');
  if (browseMove && window.WikiKtPagePicker) {
    browseMove.addEventListener('click', function () {
      WikiKtPagePicker.open({
        title: 'Move page to…',
        confirmLabel: 'Choose location',
        defaultLocale: form.dataset.defaultLocale || '',
        locale: (moveLocale && moveLocale.value.trim()) || form.dataset.defaultLocale || '',
        locales: localeOptionValues(),
        path: movePath.value.trim()
      }).then(function (r) {
        if (!r) return;
        movePath.value = r.path;
        if (moveLocale) moveLocale.value = r.locale;
      });
    });
  }

  // Editor starts inline (NOT side-by-side/fullscreen) so the top bar stays visible.
  // Guarantee the latest editor content is written back to the textarea before submit.
  if (form) form.addEventListener('submit', function () { cm.save(); });

  // EasyMDE's side-by-side preview is position:fixed and sized for fullscreen. With
  // sideBySideFullscreen:false it shows inline, so re-anchor it to the editor's box (below the
  // sticky bar). In real fullscreen, clear our overrides and let EasyMDE's own CSS govern.
  function positionSidePreview() {
    var preview = editorContainer.querySelector('.editor-preview-side');
    if (!preview) return;
    if (cm.getWrapperElement().classList.contains('CodeMirror-fullscreen')) {
      preview.style.top = '';
      preview.style.height = '';
      return;
    }
    var rect = cm.getWrapperElement().getBoundingClientRect();
    preview.style.top = rect.top + 'px';
    preview.style.height = rect.height + 'px';
  }
  // Keep the inline preview aligned as the page scrolls, the window resizes, or the editor grows.
  window.addEventListener('scroll', positionSidePreview, { passive: true });
  window.addEventListener('resize', positionSidePreview);
  cm.on('changes', positionSidePreview);

  // After each Markdown image link that points at a known wiki asset, show a small "open in asset
  // editor" icon in the source. These are CodeMirror bookmark WIDGETS — display only: they are never
  // part of the document (they don't appear in cm.getValue() or the saved Markdown), so they can't
  // corrupt the source. Clicking one opens that image's asset detail modal. Re-scans (debounced) on edit.
  function setupAssetLinkAffordance(cm, form) {
    if (!window.WikiKtAssetDetail) return;                       // needs the detail modal (wk-browser.js)
    var pageLoc = (form && (form.dataset.pageLocale || form.dataset.defaultLocale)) || '';
    var byUrl = null;                                            // asset.url -> asset (fetched once)
    var marks = [];

    // Resolve a Markdown image URL to a wiki asset, matching the two link shapes Insert Image authors:
    // an explicit-locale "/en/foo.png" (equals asset.url) and a locale-omitted "/foo.png" (page locale).
    function resolve(url) {
      if (!byUrl || !url) return null;
      if (byUrl[url]) return byUrl[url];
      if (url.charAt(0) === '/' && pageLoc) return byUrl['/' + pageLoc + url] || null;
      return null;
    }

    function makeIcon(asset) {
      var span = document.createElement('span');
      span.className = 'cm-asset-open';
      span.title = 'Open “' + asset.path + '” in the asset editor';
      span.setAttribute('role', 'button');
      span.setAttribute('aria-label', span.title);
      span.innerHTML = '<i class="mdi mdi-image-edit-outline" aria-hidden="true"></i>';
      // Don't let the click reposition the editor caret — just open the asset detail modal.
      span.addEventListener('mousedown', function (e) { e.preventDefault(); e.stopPropagation(); });
      span.addEventListener('click', function (e) {
        e.preventDefault(); e.stopPropagation();
        WikiKtAssetDetail.open(asset.id, {});
      });
      return span;
    }

    var IMG_RE = /!\[[^\]]*\]\(\s*(<[^>]+>|[^)\s]+)(?:\s+(?:"[^"]*"|'[^']*'|\([^)]*\)))?\s*\)/g;
    function rescan() {
      marks.forEach(function (m) { m.clear(); });
      marks = [];
      if (!byUrl) return;
      for (var line = 0; line < cm.lineCount(); line++) {
        var text = cm.getLine(line);
        IMG_RE.lastIndex = 0;
        var m;
        while ((m = IMG_RE.exec(text)) !== null) {
          var asset = resolve(m[1].replace(/^<|>$/g, ''));
          if (!asset) continue;
          marks.push(cm.setBookmark({ line: line, ch: m.index + m[0].length },
            { widget: makeIcon(asset), insertLeft: false }));
        }
      }
    }

    var timer = null;
    cm.on('changes', function () { if (timer) clearTimeout(timer); timer = setTimeout(rescan, 250); });

    fetch('/u/v1/assets', { credentials: 'same-origin' })
      .then(function (r) { return r.ok ? r.json() : []; })
      .catch(function () { return []; })
      .then(function (assets) {
        byUrl = {};
        assets.forEach(function (a) { if (a.url) byUrl[a.url] = a; });
        rescan();
      });
  }

  // After each {{fragment:key}} reference that resolves to a known fragment, show a small "open in
  // fragment editor" icon in the source — the fragment sibling of setupAssetLinkAffordance above. Same
  // display-only CodeMirror bookmark WIDGETS: never part of the document (absent from cm.getValue() and
  // the saved Markdown), so they can't corrupt the source. Clicking opens that fragment's admin editor
  // in a NEW tab — a same-tab navigation would discard the unsaved page edits. Re-scans (debounced) on
  // edit. Gated by manage:pages: /u/v1/fragments 403s for other users, so no icons appear for them.
  function setupFragmentAffordance(cm, form) {
    var pageLoc = (form && form.dataset.pageLocale) || '';
    var defLoc = (form && form.dataset.defaultLocale) || '';
    var byKey = null;                                           // "<locale> <key>" -> fragment (fetched once)
    var marks = [];

    // Resolve a reference key to the fragment {{fragment:key}} would render, mirroring
    // FragmentService.expand: the page locale wins, else the default locale.
    function resolve(key) {
      if (!byKey) return null;
      return byKey[pageLoc + ' ' + key] || byKey[defLoc + ' ' + key] || null;
    }

    function makeIcon(fragment) {
      var span = document.createElement('span');
      span.className = 'cm-asset-open';
      span.title = 'Open the “' + fragment.key + '” fragment in the editor';
      span.setAttribute('role', 'button');
      span.setAttribute('aria-label', span.title);
      span.innerHTML = '<i class="mdi mdi-puzzle-edit-outline" aria-hidden="true"></i>';
      // Don't let the click reposition the editor caret — just open the fragment editor.
      span.addEventListener('mousedown', function (e) { e.preventDefault(); e.stopPropagation(); });
      span.addEventListener('click', function (e) {
        e.preventDefault(); e.stopPropagation();
        window.open('/a/fragments/' + fragment.id + '/edit', '_blank', 'noopener');
      });
      return span;
    }

    // Matches FragmentService.REFERENCE ({{fragment:<key>}} with the same key charset).
    var FRAG_RE = /\{\{fragment:([a-zA-Z0-9._/-]+)}}/g;
    function rescan() {
      marks.forEach(function (m) { m.clear(); });
      marks = [];
      if (!byKey) return;
      for (var line = 0; line < cm.lineCount(); line++) {
        var text = cm.getLine(line);
        FRAG_RE.lastIndex = 0;
        var m;
        while ((m = FRAG_RE.exec(text)) !== null) {
          var fragment = resolve(m[1]);
          if (!fragment) continue;
          marks.push(cm.setBookmark({ line: line, ch: m.index + m[0].length },
            { widget: makeIcon(fragment), insertLeft: false }));
        }
      }
    }

    var timer = null;
    cm.on('changes', function () { if (timer) clearTimeout(timer); timer = setTimeout(rescan, 250); });

    fetch('/u/v1/fragments', { credentials: 'same-origin' })
      .then(function (r) { return r.ok ? r.json() : []; })
      .catch(function () { return []; })
      .then(function (fragments) {
        byKey = {};
        fragments.forEach(function (f) { byKey[f.locale + ' ' + f.key] = f; });
        rescan();
      });
  }

  // Suggest existing page paths when typing a Markdown link target: "[text](<partial>".
  function setupLinkAutocomplete(cm, form) {
    var defaultLocale = (form && form.dataset.defaultLocale) || '';
    var pagesPromise = null;
    function loadPages() {
      if (!pagesPromise) {
        pagesPromise = fetch('/u/v1/pages/paths', { credentials: 'same-origin' })
          .then(function (r) { return r.ok ? r.json() : []; })
          .catch(function () { return []; });
      }
      return pagesPromise;
    }
    // Canonical view URL for a page (mirrors server-side wikiViewUrl): default locale has no prefix.
    function viewUrl(p) {
      return '/' + p.locale + '/' + p.path;
    }

    var box = null, items = [], active = -1, fromCh = 0, toCh = 0, lineNo = 0, suppress = false;

    function close() {
      if (box && box.parentNode) box.parentNode.removeChild(box);
      box = null; items = []; active = -1;
    }
    function render(matches) {
      close();
      if (!matches.length) return;
      items = matches;
      box = document.createElement('ul');
      box.className = 'cm-path-hints';
      matches.forEach(function (m, i) {
        var li = document.createElement('li');
        var u = document.createElement('span'); u.className = 'cm-path-hint-url'; u.textContent = m.url;
        var t = document.createElement('span'); t.className = 'cm-path-hint-title'; t.textContent = m.title;
        li.appendChild(u); li.appendChild(t);
        li.addEventListener('mousedown', function (e) { e.preventDefault(); choose(i); });
        box.appendChild(li);
      });
      active = 0;
      box.childNodes[0].classList.add('active');
      var coords = cm.cursorCoords(true, 'page');
      box.style.left = coords.left + 'px';
      box.style.top = coords.bottom + 'px';
      document.body.appendChild(box);
    }
    function move(delta) {
      if (!box) return;
      box.childNodes[active].classList.remove('active');
      active = (active + delta + items.length) % items.length;
      var el = box.childNodes[active];
      el.classList.add('active');
      el.scrollIntoView({ block: 'nearest' });
    }
    function choose(i) {
      var m = items[i];
      if (!m) return;
      suppress = true; // the resulting cursorActivity should not reopen the dropdown
      // Auto-close the link: append ")" unless one is already right after the cursor (e.g. the editor
      // auto-inserted it). Either way the cursor ends just past the ")" so typing continues after the link.
      var hasClose = cm.getLine(lineNo).charAt(toCh) === ')';
      cm.replaceRange(hasClose ? m.url : m.url + ')', { line: lineNo, ch: fromCh }, { line: lineNo, ch: toCh });
      cm.setCursor({ line: lineNo, ch: fromCh + m.url.length + 1 });
      close();
      cm.focus();
    }
    function refresh() {
      if (suppress) { suppress = false; close(); return; }
      var cur = cm.getCursor();
      var before = cm.getLine(cur.line).slice(0, cur.ch);
      // "[text](" or "![alt](" followed by a partial path, no closing ")" yet. The optional leading
      // "!" distinguishes an image target from a link target -- necessary to not show for image Markdown, onyl links:
      var m = /(!?)\[[^\]]*\]\(([^)\s]*)$/.exec(before);
      if (!m || m[1] === '!') { close(); return; } // image target ("![alt](") gets no page-path hints
      var partial = m[2];
      lineNo = cur.line; fromCh = cur.ch - partial.length; toCh = cur.ch;
      var q = partial.toLowerCase();
      loadPages().then(function (pages) {
        var c2 = cm.getCursor();
        if (c2.line !== lineNo || c2.ch !== toCh) return; // cursor moved while resolving
        var matches = [];
        for (var i = 0; i < pages.length && matches.length < 12; i++) {
          var url = viewUrl(pages[i]);
          if (!q || url.toLowerCase().indexOf(q) !== -1 || pages[i].title.toLowerCase().indexOf(q) !== -1) {
            matches.push({ url: url, title: pages[i].title });
          }
        }
        render(matches);
      });
    }

    cm.on('cursorActivity', refresh);
    cm.on('blur', function () { setTimeout(close, 150); });
    // Intercept navigation keys while the dropdown is open (capture phase, before CodeMirror).
    cm.getWrapperElement().addEventListener('keydown', function (e) {
      if (!box) return;
      if (e.key === 'ArrowDown') { e.preventDefault(); move(1); }
      else if (e.key === 'ArrowUp') { e.preventDefault(); move(-1); }
      else if (e.key === 'Enter' || e.key === 'Tab') { e.preventDefault(); choose(active); }
      else if (e.key === 'Escape') { e.preventDefault(); close(); }
    }, true);
  }
})();
