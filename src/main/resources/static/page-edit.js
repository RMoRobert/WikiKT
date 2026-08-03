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
  // A dropdown menu row: a regular EasyMDE button plus a visible text label (site.css lays the
  // rows out as a vertical menu; the label doubles as the hover tooltip).
  var mdeItem = function (name, action, icon, label) {
    var b = mde(name, action, icon, label);
    b.text = label;
    return b;
  };
  // Wiki.js-style callout inserts: quote the selection (or current line) and tag it with a
  // `{.is-x}` marker — the decorate syntax the renderer turns into a styled callout box. On an
  // already-tagged block the buttons retag instead: a different class switches the callout type,
  // the same class removes the tag (leaving a plain quote).
  var CALLOUT_CLASS = "is-(?:info|success|warning|danger|error)";
  var CALLOUT_ONLY = new RegExp("^\\s*\\{\\.(" + CALLOUT_CLASS + ")}\\s*$");
  var CALLOUT_TRAILING = new RegExp("\\s*\\{\\.(" + CALLOUT_CLASS + ")}\\s*$");
  function insertCallout(editor, cls) {
    var cm = editor.codemirror;
    var start = cm.getCursor("from").line;
    var to = cm.getCursor("to");
    // A selection ending at column 0 shouldn't drag that line into the quote.
    var end = (to.line > start && to.ch === 0) ? to.line - 1 : to.line;
    cm.operation(function () {
      var lastText = cm.getLine(end);
      var below = end + 1 < cm.lineCount() ? cm.getLine(end + 1) : "";
      var m = CALLOUT_ONLY.exec(below);
      if (m) { // marker on its own line below the block
        if (m[1] === cls) cm.replaceRange("", { line: end, ch: lastText.length }, { line: end + 1, ch: below.length });
        else cm.replaceRange("{." + cls + "}", { line: end + 1, ch: 0 }, { line: end + 1, ch: below.length });
        return;
      }
      m = CALLOUT_TRAILING.exec(lastText);
      if (m) { // `> text {.is-x}` trailing form (also covers the cursor sitting on a marker line)
        var head = lastText.slice(0, m.index).replace(/\s+$/, "");
        cm.replaceRange(m[1] === cls ? head : head + " {." + cls + "}",
          { line: end, ch: 0 }, { line: end, ch: lastText.length });
        return;
      }
      // New callout: blank lines are quoted too, so a multi-paragraph selection stays one box.
      for (var i = start; i <= end; i++) {
        if (!/^\s*>/.test(cm.getLine(i))) cm.replaceRange("> ", { line: i, ch: 0 });
      }
      var endText = cm.getLine(end);
      cm.replaceRange("\n{." + cls + "}", { line: end, ch: endText.length });
      cm.setCursor({ line: end, ch: endText.length });
    });
    cm.focus();
  }
  var easymde = new EasyMDE({
    element: textarea,
    autoDownloadFontAwesome: false,
    spellChecker: false,
    nativeSpellcheck: true,
    // Line numbers count *source* lines, so a wrapped paragraph keeps one number — which is what makes
    // them useful here: they're the same lines the preview's scroll sync anchors on, and the same ones
    // a diff or a "line 42" in a review comment refers to.
    lineNumbers: true,
    inputStyle: "contenteditable",
    status: false,
    // EasyMDE turns each button's `name` into a CSS class on the <button>; prefix them all so e.g.
    // the "table" button doesn't pick up Bootstrap's .table styling (and to avoid future collisions).
    toolbarButtonClassPrefix: "mde",
    // No fullscreen mode at all: the editor bar it would hide is short and holds Save / Page Info /
    // Close, so covering it costs more than the pixels it buys. Two separate switches are needed —
    // toggleSideBySide calls toggleFullScreen internally unless sideBySideFullscreen is false, and the
    // shortcut is bound independently of the (removed) toolbar button. Unbinding it also hands F11
    // back to the browser's own fullscreen, where people expect it.
    sideBySideFullscreen: false,
    shortcuts: { toggleFullScreen: null },
    // EasyMDE's own split-view sync scrolls both panes to the same *proportion* of their heights, so
    // the preview drifts away from what you're editing as soon as the two differ in density (a fenced
    // block is tall in the source and short rendered, an image is the reverse). Replaced by the
    // line-anchored sync further down, which follows the source line each rendered block came from.
    syncSideBySidePreviewScroll: false,
    toolbar: [
      mde("bold", EasyMDE.toggleBold, "format-bold", "Bold"),
      mde("italic", EasyMDE.toggleItalic, "format-italic", "Italic"),
      mde("heading", EasyMDE.toggleHeadingSmaller, "format-header-pound", "Heading"),
      "|",
      {
        // An item with `children` renders as an EasyMDE dropdown (open while the button holds
        // focus). Quote and Code block live here too, so all block inserts share one menu.
        name: "insert-block",
        className: "mdi mdi-alpha-t-box-outline",
        title: "Insert block",
        children: [
          mdeItem("quote", EasyMDE.toggleBlockquote, "format-quote-close", "Quote"),
          mdeItem("callout-info", function (editor) { insertCallout(editor, "is-info"); }, "information-outline", "Info"),
          mdeItem("callout-success", function (editor) { insertCallout(editor, "is-success"); }, "check-circle-outline", "Success"),
          mdeItem("callout-warning", function (editor) { insertCallout(editor, "is-warning"); }, "alert-outline", "Warning"),
          mdeItem("callout-error", function (editor) { insertCallout(editor, "is-error"); }, "alert-octagon-outline", "Error"),
          mdeItem("code", EasyMDE.toggleCodeBlock, "code-tags", "Code block"),
        ],
      },
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
        // Asset rights are granted separately from page rights, so someone editing this page may have no
        // write:assets/manage:assets at all — they can still browse and insert what's already there. Pass
        // what they hold so the picker omits Upload/Edit rather than offering controls that 403.
        WikiKtAssetBrowser.open({
          imagesOnly: true,
          title: "Insert Image",
          confirmLabel: "Insert",
          canUpload: !edForm || edForm.dataset.canUploadAssets === "true",
          canManage: !edForm || edForm.dataset.canManageAssets === "true",
        })
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
      mde("table", EasyMDE.drawTable, "table", "Table"),
      "|",
      // Source-pane controls. The preview controls that follow are pushed to the toolbar's far right
      // by CSS (margin-left:auto on the show/hide toggle — see site.css), so each group sits above
      // the pane it governs: source controls on the left, preview controls over the preview pane.
      {
        // Three-way view mode (see applyViewMode below). A dropdown rather than a cycling button:
        // with three states a single button gives no clue which one comes next, and this way the
        // current mode is visible in the open menu instead of having to be inferred from the icon.
        // Ordered most-styled first, matching the admin setting's list.
        name: "view-mode",
        className: "mdi mdi-format-font",
        title: "Editor view",
        noDisable: true,
        children: [
          mdeItem("view-formatted", function () { setViewMode('formatted'); }, "format-text", "Formatted"),
          mdeItem("view-basic", function () { setViewMode('basic'); }, "format-letter-case", "Basic"),
          mdeItem("view-plain", function () { setViewMode('plain'); }, "file-code-outline", "Plain text"),
        ],
      },
      // Light/dark for the source surface only — the preview keeps its own, which is the point:
      // a dark editor beside a light preview separates what you're typing from what a reader sees.
      mde("editor-theme", function () { setEditorDark(!editorIsDark()); }, "weather-night", "Dark editor", true),
      mde("spellcheck", function () { setSpellcheck(!spellcheckOn()); }, "spellcheck", "Spell check", true),
      // The preview cluster, right-aligned (Wiki.js-style). No "|" before it: the flex gap is the
      // separation, and a separator would dangle at the end of the left cluster.
      //
      // Light/dark FIRST, show/hide LAST. The show/hide button is the anchor — it's the one that's
      // always available, so it holds the same spot at the toolbar's end whatever the state. Its
      // light/dark companion only applies to a visible preview, so CSS drops it while the preview is
      // closed (see the .mde-preview-theme rules in site.css) and it appears to its left when the
      // preview opens, rather than shifting the anchor button along.
      //
      // Preview surface, independent of the editor's: normally it shows the page in the site theme
      // (what a reader gets), but checking a page against the other theme shouldn't mean re-theming
      // the whole admin session.
      mde("preview-theme", function () { setPreviewDark(!previewIsDark()); }, "invert-colors", "Dark preview", true),
      // The full-pane and split buttons are the same control at different widths: CSS shows whichever
      // suits the breakpoint (the split is impractical on a phone).
      mde("preview", EasyMDE.togglePreview, "eye-outline", "Preview", true),
      mde("side-by-side", function (editor) {
        EasyMDE.toggleSideBySide(editor);
        afterLayoutChange();
      }, "view-split-vertical", "Side-by-side", true),
    ],
    // Rendering goes through the server (/preview), so the preview is async — but EasyMDE calls this on
    // every CodeMirror "update" (every keystroke AND every scroll, since scrolling redraws the viewport)
    // and synchronously writes whatever we return into the pane. Returning HTML-or-placeholder therefore
    // meant a request per keystroke and a "Loading…" flash that threw away the preview's scroll position
    // each time. So: always return null (EasyMDE leaves the pane alone), and let schedulePreview skip
    // no-op renders and debounce the rest — see renderPreviewInto.
    previewRender: function (plainText, preview) {
      schedulePreview(preview, plainText);
      return null;
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
  // The "Insert block" dropdown opens on :focus-within (EasyMDE's own CSS), but EasyMDE sets
  // tabIndex=-1 on every toolbar button, so without this it would be mouse-only. Make the dropdown
  // and its items tabbable: the closed menu is visibility:hidden, so its items stay out of the tab
  // order until it opens. Escape closes it by moving focus back to the editor.
  document.querySelectorAll('.editor-toolbar .easymde-dropdown').forEach(function (dd) {
    dd.tabIndex = 0;
    dd.setAttribute('aria-haspopup', 'true');
    var items = [].slice.call(dd.querySelectorAll('.easymde-dropdown-content button'));
    items.forEach(function (item) { item.tabIndex = 0; });
    dd.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') { e.preventDefault(); easymde.codemirror.focus(); return; }
      var at = items.indexOf(document.activeElement);
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        // Wrap around; from the dropdown button itself (at === -1) ArrowDown enters at the top.
        e.preventDefault();
        var next = at === -1 ? (e.key === 'ArrowDown' ? 0 : items.length - 1)
          : (at + (e.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length;
        items[next].focus();
      } else if ((e.key === 'Enter' || e.key === ' ') && at !== -1) {
        // These items are <button>s nested inside the dropdown <button> (EasyMDE's own structure),
        // and browsers skip the normal Enter/Space activation for a nested button — so fire it here.
        e.preventDefault();
        items[at].click();
      }
    });
  });
  // Expose both chrome bars' rendered heights (each wraps to a second row on narrow screens) as CSS
  // variables: site.css pins the formatting toolbar just below the editor bar with --wk-editor-bar-h,
  // and sizes the split view's two scrolling panes against both. ResizeObserver covers the wrap/unwrap;
  // resize covers browsers without it.
  var editorBar = document.querySelector('.editor-bar');
  var editorToolbar = document.querySelector('.editor-content .editor-toolbar');
  var syncBarHeights = function () {
    if (editorBar) document.documentElement.style.setProperty('--wk-editor-bar-h', editorBar.offsetHeight + 'px');
    if (editorToolbar) document.documentElement.style.setProperty('--wk-editor-toolbar-h', editorToolbar.offsetHeight + 'px');
  };
  syncBarHeights();
  window.addEventListener('resize', syncBarHeights);
  if (window.ResizeObserver) {
    var barObserver = new ResizeObserver(syncBarHeights);
    if (editorBar) barObserver.observe(editorBar);
    if (editorToolbar) barObserver.observe(editorToolbar);
  }

  var cm = easymde.codemirror;
  var form = textarea.closest('form');
  var editorContainer = cm.getWrapperElement().parentNode;

  // ---- Editor view mode ------------------------------------------------------------------------
  // Three ways to show the source, least to most styled:
  //   plain      monospace, flat token colors, syntax markers only     (.editor--plain)
  //   basic      the same plus real bold / italic / bold headings      (.editor--plain .editor--basic)
  //   formatted  full typographic rendering                            (no class)
  // `basic` layers ON TOP of `plain` rather than replacing it, so both share one set of CSS rules and
  // only the emphasis differs — see the .editor--basic block in site.css. Precedence: this browser's
  // dropdown choice, else the site default from Administration > Settings > General.
  var VIEW_KEY = 'wk-editor-view';
  var VIEW_MODES = ['formatted', 'basic', 'plain'];
  var VIEW_LABELS = {
    formatted: 'Formatted',
    basic: 'Basic',
    plain: 'Plain text',
  };
  // indexOf, not a lookup on VIEW_LABELS: a stored value like "constructor" would inherit a truthy
  // hit from Object.prototype and sail through as a valid mode.
  function isViewMode(m) { return VIEW_MODES.indexOf(m) > -1; }

  function applyViewMode(mode) {
    editorContainer.classList.toggle('editor--plain', mode !== 'formatted');
    editorContainer.classList.toggle('editor--basic', mode === 'basic');
    // Radio semantics: exactly one row checked. wk-toggle-on is the same visual cue the standalone
    // toggles use, for the same reason (EasyMDE reclaims its own `.active` — see markToggle).
    VIEW_MODES.forEach(function (m) {
      var item = document.querySelector('.editor-toolbar button.mde-view-' + m);
      if (!item) return;
      item.setAttribute('role', 'menuitemradio');
      item.classList.toggle('wk-toggle-on', m === mode);
      item.setAttribute('aria-checked', m === mode ? 'true' : 'false');
    });
    // The dropdown button is icon-only, so its title is the accessible name — and the only place the
    // current mode shows without opening the menu.
    var dd = document.querySelector('.editor-toolbar .mde-view-mode');
    if (dd) dd.title = 'Editor view: ' + VIEW_LABELS[mode];
  }

  function setViewMode(mode) {
    try { localStorage.setItem(VIEW_KEY, mode); } catch (e) {}
    applyViewMode(mode);
    // The class change itself is picked up by the layout observer further down, which re-measures
    // CodeMirror — the font, weights and heading sizes all move the line heights it caches.
  }

  (function () {
    var stored = null;
    try { stored = localStorage.getItem(VIEW_KEY); } catch (e) {} // private mode
    var fallback = form && form.dataset.editorView;
    applyViewMode(isViewMode(stored) ? stored : (isViewMode(fallback) ? fallback : 'formatted'));
    // That first apply predates the layout observer, so nothing else will re-measure it.
    afterLayoutChange();
  })();

  // Shared state indicator for our own toolbar toggles. Deliberately NOT EasyMDE's `.active`: it
  // reasserts that class on every cursorActivity from its internal state map and removes it from any
  // button it doesn't recognise (only `fullscreen` and `side-by-side` are exempted), so an `.active`
  // we set would vanish the moment you typed. aria-pressed and the title survive that loop, but the
  // visual cue would not.
  function markToggle(name, on, titleOn, titleOff) {
    var btn = document.querySelector('.editor-toolbar button.mde-' + name);
    if (!btn) return;
    btn.classList.toggle('wk-toggle-on', on);
    btn.setAttribute('aria-pressed', on ? 'true' : 'false');
    btn.title = on ? titleOn : titleOff; // icon-only, so the title is also the accessible name
  }

  // ---- Editor surface (light / dark) -----------------------------------------------------------
  // Precedence: this browser's toolbar choice, else the site default from Administration > Settings >
  // General ("auto" = follow the site theme, which is also what keeps a white slab from sitting under
  // a dark toolbar). Only the source pane changes; the preview stays in the site theme so it keeps
  // showing what a reader would actually see.
  var EDITOR_DARK_KEY = 'wk-editor-dark';

  function storedEditorDark() {
    try { return localStorage.getItem(EDITOR_DARK_KEY); } catch (e) { return null; } // private mode
  }
  function siteIsDark() {
    return document.documentElement.getAttribute('data-bs-theme') === 'dark';
  }
  function editorIsDark() { return editorContainer.classList.contains('editor--dark'); }

  function resolveEditorDark() {
    var stored = storedEditorDark();
    if (stored === 'true' || stored === 'false') return stored === 'true';
    var setting = (form && form.dataset.editorTheme) || 'auto';
    return setting === 'dark' || (setting !== 'light' && siteIsDark());
  }

  function applyEditorDark(dark) {
    editorContainer.classList.toggle('editor--dark', dark);
    markToggle('editor-theme', dark, 'Light editor', 'Dark editor');
  }

  function setEditorDark(dark) {
    try { localStorage.setItem(EDITOR_DARK_KEY, dark ? 'true' : 'false'); } catch (e) {}
    applyEditorDark(dark);
  }

  applyEditorDark(resolveEditorDark());
  // With no explicit choice stored, "auto" has to keep tracking the site theme — which can change
  // from another tab, or from the OS when the site theme is itself set to follow the system.
  if (window.MutationObserver) {
    new MutationObserver(function () {
      if (storedEditorDark() === null) applyEditorDark(resolveEditorDark());
    }).observe(document.documentElement, { attributes: true, attributeFilter: ['data-bs-theme'] });
  }

  // ---- Spell check -----------------------------------------------------------------------------
  // The *browser's* checker, through CodeMirror's `spellcheck` option — which is exactly what EasyMDE's
  // nativeSpellcheck sets at construction, so the button just flips the same switch afterwards. It is
  // not EasyMDE's `spellChecker`, a bundled Typo.js implementation that downloads its own English-only
  // dictionary; that stays off. Only takes effect because inputStyle is "contenteditable" — with the
  // default textarea input the browser has nothing visible to check. Remembered per browser.
  var SPELLCHECK_KEY = 'wk-spellcheck';

  function spellcheckOn() { return cm.getOption('spellcheck') === true; }

  function applySpellcheck(on) {
    cm.setOption('spellcheck', on);
    markToggle('spellcheck', on, 'Turn spell check off', 'Turn spell check on');
  }

  function setSpellcheck(on) {
    try { localStorage.setItem(SPELLCHECK_KEY, on ? 'true' : 'false'); } catch (e) {}
    applySpellcheck(on);
  }

  (function () {
    var stored = null;
    try { stored = localStorage.getItem(SPELLCHECK_KEY); } catch (e) {}
    applySpellcheck(stored !== 'false'); // on unless this browser turned it off
  })();

  // ---- Preview surface (light / dark) ----------------------------------------------------------
  // The preview shows the page as a reader would see it, so by default it simply inherits the site
  // theme. Overriding it sets data-bs-theme on the pane itself: Bootstrap scopes its theme variables
  // to any subtree carrying that attribute, so the whole rendered page — text, tables, code blocks,
  // callouts — re-themes without a parallel set of preview-only rules. Remembered per browser, like
  // the editor surface. Both preview panes (split and full) are kept in step.
  var PREVIEW_DARK_KEY = 'wk-preview-dark';

  function previewPanes() { return editorContainer.querySelectorAll('.editor-preview'); }
  function previewIsDark() {
    var pane = previewPanes()[0];
    return !!pane && pane.getAttribute('data-bs-theme') === 'dark';
  }
  // Three states, not two: null means "never chosen", which leaves the attribute off so the pane keeps
  // following the site theme. Read in more than one place (init, and each time a pane opens), hence a
  // helper rather than an inline localStorage read.
  function storedPreviewDark() {
    var v = null;
    try { v = localStorage.getItem(PREVIEW_DARK_KEY); } catch (e) {} // private mode
    return v === 'true' ? true : (v === 'false' ? false : null);
  }

  function applyPreviewDark(dark) {
    previewPanes().forEach(function (pane) {
      if (dark === null) pane.removeAttribute('data-bs-theme');   // back to inheriting the site theme
      else pane.setAttribute('data-bs-theme', dark ? 'dark' : 'light');
    });
    markToggle('preview-theme', dark === true, 'Light preview', 'Dark preview');
  }

  function setPreviewDark(dark) {
    try { localStorage.setItem(PREVIEW_DARK_KEY, dark ? 'true' : 'false'); } catch (e) {}
    applyPreviewDark(dark);
  }

  applyPreviewDark(storedPreviewDark());

  // WikiKT's two brace forms aren't Markdown, so CodeMirror's mode has no idea they're special. A
  // CodeMirror overlay tags them anyway: `{.is-info}` (callout/decoration markers) and
  // `{{fragment:key}}` references get their own token class, coloured in site.css. Non-opaque, so it
  // layers on top of the Markdown highlighting rather than replacing it, and it survives plain view —
  // picking these out of a wall of monospace source is exactly when it helps most.
  cm.addOverlay({
    token: function (stream) {
      if (stream.match(/^\{\{fragment:[a-zA-Z0-9._/-]+}}/)) return 'wk-fragment';
      if (stream.match(/^\{\.[a-zA-Z][\w-]*}/)) return 'wk-decoration';
      // No match here: consume up to the next `{` so the next call gets a fresh candidate.
      while (stream.next() != null && !stream.match(/^\{/, false)) { /* skip */ }
      return null;
    }
  });

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

  // Unsaved-changes guard: warn before leaving the editor while edits are pending. Snapshot-based
  // rather than a sticky "touched" flag, because half the fields here are written programmatically and
  // never fire an input event — tag chips, the path "Browse…" picker, the link/image/fragment inserts —
  // and because undoing an edit should stop the warning rather than leave it armed for the session.
  // cm.save() writes CodeMirror back into the textarea first, so content is part of the snapshot and
  // CodeMirror's line-ending normalisation can't read as a change on a page that was never touched.
  // Covers links (Close, brand, Administration), Logout (a separate form), Back, reload and tab close;
  // the wording of the prompt itself is the browser's and can't be customised.
  var savedState = null;
  function editorState() {
    cm.save();
    // Serialised as JSON pairs rather than concatenated, so no field name or value can run into the
    // next one and make two different sets of fields compare equal.
    var parts = [];
    new FormData(form).forEach(function (value, key) { parts.push([key, value]); });
    return JSON.stringify(parts);
  }
  if (form) {
    savedState = editorState();
    window.addEventListener('beforeunload', function (e) {
      if (savedState === null || editorState() === savedState) return;
      e.preventDefault();
      e.returnValue = ''; // still required by Chrome/Safari to raise the prompt
    });
  }

  // Guarantee the latest editor content is written back to the textarea before submit. Submitting is
  // an intentional save (or "Discard staged update"), so it also stands the guard above down.
  if (form) form.addEventListener('submit', function () { cm.save(); savedState = null; });

  // EasyMDE flips the split class inside its own setTimeout(…, 1), so the panes are still the old size
  // when the toolbar action returns. Re-measure once they've settled: CodeMirror caches its viewport
  // height and won't notice the change on its own, and the preview's scroll anchors move with it.
  // Debounced, because the observer below can fire several times for one toggle.
  var layoutTimer = null;
  function afterLayoutChange() {
    if (layoutTimer) clearTimeout(layoutTimer);
    layoutTimer = setTimeout(function () {
      layoutTimer = null;
      cm.refresh();
      invalidateAnchors();
    }, 20);
  }

  // ---- Live preview rendering ------------------------------------------------------------------
  // Where previewRender (in the EasyMDE options above) hands off. Two jobs: drop the re-renders where
  // nothing changed (EasyMDE asks on scroll as well as on edit), and debounce the rest so typing isn't
  // a POST per keystroke. The pane's contents are only ever replaced by a *finished* render, so it
  // never blanks to a placeholder and never loses its scroll position mid-edit.
  var PREVIEW_DEBOUNCE_MS = 250;
  var previewTimer = null;
  var previewPane = null;   // pane the current text was rendered into (side-by-side vs. the full-pane one)
  var previewText = null;   // text of the render that is showing, or in flight
  var previewSeq = 0;       // discards a slow response that a newer one has overtaken

  function schedulePreview(pane, text) {
    if (pane === previewPane && text === previewText) return;
    var firstFill = pane !== previewPane;
    previewPane = pane;
    previewText = text;
    if (previewTimer) { clearTimeout(previewTimer); previewTimer = null; }
    // Opening a preview pane renders straight away; only ongoing edits wait for the debounce.
    if (firstFill) renderPreviewInto(pane, text);
    else previewTimer = setTimeout(function () { previewTimer = null; renderPreviewInto(pane, text); }, PREVIEW_DEBOUNCE_MS);
  }

  function renderPreviewInto(pane, text) {
    var fmt = formatSelect ? formatSelect.value : 'MARKDOWN';
    var pageLoc = (form && form.dataset.pageLocale) || '';
    var pagePath = (form && form.dataset.pagePath) || '';
    var seq = ++previewSeq;
    if (!pane.firstChild) pane.innerHTML = '<p class="text-secondary">Loading preview…</p>';
    fetch('/preview', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      credentials: 'same-origin',
      body: 'contentFormat=' + encodeURIComponent(fmt) + '&locale=' + encodeURIComponent(pageLoc)
        + '&path=' + encodeURIComponent(pagePath) + '&content=' + encodeURIComponent(text)
    })
      .then(function (r) { return r.text(); })
      .then(function (html) {
        if (seq !== previewSeq) return;
        pane.innerHTML = '<div class="wiki-content">' + html + '</div>';
        // Mermaid fences are rendered in the browser, so a fresh pane needs that pass run over it again
        // (page-mermaid.js; absent if the page didn't include it). A diagram replaces a code block with
        // a taller SVG well after this render returns, shifting every offset below it — so re-anchor
        // when it lands, exactly as the image handler in onPreviewRendered does.
        if (window.wkRenderMermaid) window.wkRenderMermaid(pane).then(invalidateAnchors);
        onPreviewRendered(pane);
      })
      .catch(function () {
        if (seq !== previewSeq) return;
        previewText = null; // let the next edit retry, so a transient failure isn't sticky
        pane.innerHTML = '<p class="text-danger">Preview failed.</p>';
      });
  }

  // ---- Split-view scroll sync ------------------------------------------------------------------
  // Wiki.js-style line following, in place of EasyMDE's proportional sync (disabled via
  // syncSideBySidePreviewScroll above). The server stamps every rendered block with the source line it
  // came from — data-line, emitted for /preview only (MarkdownRenderer.SourceLineAttributeProvider) — so
  // the line at the top of the editor viewport maps to a real offset in the preview, interpolating
  // between the blocks on either side of it. Both directions: dragging the preview scrolls the source.
  var sidePreview = editorContainer.querySelector('.editor-preview-side');
  var anchors = null;   // [{line, top}], ascending in both; rebuilt lazily after a render or a resize
  var SCROLL_EPSILON = 2; // px; also CodeMirror's own threshold for "this scroll is a no-op"

  function previewActive() {
    return !!sidePreview && sidePreview.classList.contains('editor-preview-active-side');
  }

  function onPreviewRendered(pane) {
    if (pane !== sidePreview) return;
    anchors = null;
    // An image shifts every offset below it once it loads, so re-anchor as each one settles.
    pane.querySelectorAll('img').forEach(function (img) {
      if (img.complete) return;
      img.addEventListener('load', invalidateAnchors);
      img.addEventListener('error', invalidateAnchors);
    });
    syncPreviewToEditor();
  }

  function invalidateAnchors() {
    anchors = null;
    syncPreviewToEditor();
  }
  window.addEventListener('resize', invalidateAnchors);

  function buildAnchors() {
    var nodes = sidePreview.querySelectorAll('[data-line]');
    // Offsets within the pane's scrolled content. Measured off getBoundingClientRect rather than
    // offsetTop, which is relative to whichever ancestor happens to be positioned.
    var base = sidePreview.getBoundingClientRect().top - sidePreview.scrollTop;
    var out = [{ line: 0, top: 0 }];
    for (var i = 0; i < nodes.length; i++) {
      var line = parseInt(nodes[i].getAttribute('data-line'), 10);
      var top = nodes[i].getBoundingClientRect().top - base;
      var last = out[out.length - 1];
      // Nested blocks repeat their parent's line (a <ul> and its first <li>) — keep the outermost. The
      // list has to stay strictly ascending in both fields or the interpolation below can run backwards.
      if (isNaN(line) || line <= last.line || top < last.top) continue;
      out.push({ line: line, top: top });
    }
    // Close the range so the last block interpolates against the end of the document, not a cliff.
    var tail = out[out.length - 1];
    if (cm.lineCount() > tail.line && sidePreview.scrollHeight >= tail.top) {
      out.push({ line: cm.lineCount(), top: sidePreview.scrollHeight });
    }
    return out;
  }

  function getAnchors() {
    if (!anchors) anchors = buildAnchors();
    return anchors.length > 1 ? anchors : null;
  }

  // Index of the last anchor whose `field` is <= value (the list is ascending in both fields).
  function anchorIndex(list, field, value) {
    var lo = 0, hi = list.length - 1;
    while (lo < hi) {
      var mid = (lo + hi + 1) >> 1;
      if (list[mid][field] <= value) lo = mid; else hi = mid - 1;
    }
    return lo;
  }

  // Straight-line interpolation from one anchor field to the other, between the pair bracketing `value`.
  function interpolate(list, from, to, value) {
    var i = anchorIndex(list, from, value);
    var a = list[i], b = list[i + 1];
    if (!b || b[from] <= a[from]) return a[to];
    return a[to] + (b[to] - a[to]) * ((value - a[from]) / (b[from] - a[from]));
  }

  // The (fractional) source line sitting at the top of the editor viewport, and its inverse.
  function editorTopLine() {
    var top = cm.getScrollInfo().top;
    var line = cm.lineAtHeight(top, 'local');
    var lineTop = cm.heightAtLine(line, 'local');
    var lineBottom = cm.heightAtLine(line + 1, 'local');
    return lineBottom > lineTop ? line + (top - lineTop) / (lineBottom - lineTop) : line;
  }
  function editorTopForLine(line) {
    var whole = Math.floor(line);
    var lineTop = cm.heightAtLine(whole, 'local');
    var lineBottom = cm.heightAtLine(whole + 1, 'local');
    return lineTop + (lineBottom - lineTop) * (line - whole);
  }

  // Each pane drives the other, minus the scroll event our own write is about to cause: remember the
  // offset written and skip the one event that reports it back. Clamping first is what makes that
  // reliable — an out-of-range write would come back clamped, read as a user scroll, and bounce. (A
  // timer or requestAnimationFrame guard would be shorter, but rAF does not run at all while the tab is
  // hidden, which leaves the guard stuck on and the sync dead for the rest of the session.)
  var expectPreviewTop = null;
  var expectEditorTop = null;

  function clamp(value, max) { return value < 0 ? 0 : (value > max ? max : value); }
  function consumed(expected, actual) {
    return expected !== null && Math.abs(actual - expected) <= SCROLL_EPSILON;
  }

  function syncPreviewToEditor() {
    var list = previewActive() ? getAnchors() : null;
    if (!list) return;
    var top = clamp(interpolate(list, 'line', 'top', editorTopLine()),
      sidePreview.scrollHeight - sidePreview.clientHeight);
    // Writing an offset it already sits at fires no event, so don't leave an expectation stranded.
    if (Math.abs(sidePreview.scrollTop - top) <= SCROLL_EPSILON) { expectPreviewTop = null; return; }
    expectPreviewTop = top;
    sidePreview.scrollTop = top;
  }

  function syncEditorToPreview() {
    var list = previewActive() ? getAnchors() : null;
    if (!list) return;
    var info = cm.getScrollInfo();
    var top = clamp(editorTopForLine(interpolate(list, 'top', 'line', sidePreview.scrollTop)),
      info.height - info.clientHeight);
    if (Math.abs(info.top - top) <= SCROLL_EPSILON) { expectEditorTop = null; return; }
    expectEditorTop = top;
    cm.scrollTo(null, top);
  }

  // Is either preview pane on screen? The split pane announces itself through its own class, but the
  // full-pane one is read off the *toolbar* instead: EasyMDE appends `editor-preview-active` to the
  // pane a tick later (setTimeout 1) while `disabled-for-preview` — the class that dims the other
  // buttons — goes on synchronously, so only the latter is settled when the observer below runs.
  function previewOnScreen() {
    return previewActive() ||
      !!(editorToolbar && editorToolbar.classList.contains('disabled-for-preview'));
  }

  // Light/dark preview only means something while a preview is showing, so it's disabled the rest of
  // the time (site.css greys it out) — it keeps its slot, so the show/hide button next to it never
  // moves. Re-applying the stored preference on the way in is not just for the title markToggle sets:
  // EasyMDE builds the full-pane preview lazily on its first open, so that pane misses the initial
  // apply entirely and would show the site theme until the button was pressed again.
  function syncPreviewThemeToggle() {
    var btn = document.querySelector('.editor-toolbar button.mde-preview-theme');
    if (!btn) return;
    var on = previewOnScreen();
    btn.disabled = !on;
    if (on) applyPreviewDark(storedPreviewDark());
    else btn.title = 'Preview theme — show the preview first';
  }

  // Watch the classes EasyMDE toggles rather than wiring each button: the split view also has a
  // keyboard shortcut (F9), and plain view and the dark surface change the font and colors, all of
  // which need CodeMirror to re-measure and the preview anchors to be rebuilt. The toolbar is watched
  // for the same reason in miniature — `disabled-for-preview` is the only synchronous signal that the
  // full-pane preview opened or closed.
  if (window.MutationObserver) {
    var layoutObserver = new MutationObserver(function () {
      afterLayoutChange();
      rememberSplit();
      syncPreviewThemeToggle();
    });
    layoutObserver.observe(editorContainer, { attributes: true, attributeFilter: ['class'] });
    if (sidePreview) layoutObserver.observe(sidePreview, { attributes: true, attributeFilter: ['class'] });
    if (editorToolbar) layoutObserver.observe(editorToolbar, { attributes: true, attributeFilter: ['class'] });
  }
  syncPreviewThemeToggle(); // opening state; the observer keeps it in step from here

  // The split opens by default and is then remembered per browser — editing next to the rendered page
  // is the common case, and it's the whole point of the line-following sync above. Closing it sticks.
  var SPLIT_KEY = 'wk-split-open';

  // Below this width site.css hides the split button and the layout falls back to page scrolling, so
  // the split is never opened *or* recorded there — otherwise a session on a narrow window would save
  // "closed" and the preview would stay shut next time the same browser is back at desktop width.
  function splitFits() { return window.matchMedia('(min-width: 769px)').matches; }

  function rememberSplit() {
    if (!sidePreview || !splitFits()) return;
    try { localStorage.setItem(SPLIT_KEY, previewActive() ? 'true' : 'false'); } catch (e) {}
  }

  if (sidePreview && splitFits()) {
    var splitPref = null;
    try { splitPref = localStorage.getItem(SPLIT_KEY); } catch (e) {} // private mode: fall through to the default
    if (splitPref !== 'false') EasyMDE.toggleSideBySide(easymde);
  }

  if (sidePreview) {
    cm.on('scroll', function () {
      var expected = expectEditorTop;
      expectEditorTop = null;
      if (!consumed(expected, cm.getScrollInfo().top)) syncPreviewToEditor();
    });
    sidePreview.addEventListener('scroll', function () {
      var expected = expectPreviewTop;
      expectPreviewTop = null;
      if (!consumed(expected, sidePreview.scrollTop)) syncEditorToPreview();
    }, { passive: true });
  }

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
