// Header behaviors, loaded right after the fixed navbar markup in partials/header.hbs (every
// page). Independent pieces, each a no-op when its markup is absent: body-padding sync for the
// fixed navbar, the compact-screen sidebar hamburger, the theme switcher (persists via the
// data-persist/data-csrf attributes on .wk-theme-menu), and the live search suggestions combobox.
// The navbar is `position: fixed` to take it out of flow (better behavior for macOS/iOS overscroll),
// site.css reserves `--wikikt-header-h` of body padding for it, correct for the
// default single row. On narrow screens the search box expands into a second row (taller bar), so keep the
// body's top padding pinned to the bar's real height. Mirrors the editor bar's `--wk-editor-bar-h` measuring.
(function () {
  var nav = document.querySelector('.wk-navbar');
  if (!nav) return;
  function sync() { document.body.style.paddingTop = nav.offsetHeight + 'px'; }
  sync();
  if (window.ResizeObserver) new ResizeObserver(sync).observe(nav);
  else window.addEventListener('resize', sync);
})();
// Sidebar hamburger: reveal + wire it only when this page actually has a wiki sidebar (`#wikiSidebar`
// is parsed further down, so defer to DOMContentLoaded). On desktop CSS keeps it hidden; on compact
// screens `.is-active` + the media query show it, and a click opens the sidebar as a pop-over floating
// over the content (`.is-open`). Being a popover, it also closes on an outside click or Esc.
(function () {
  function wire() {
    var btn = document.getElementById('wikiNavToggle');
    var sidebar = document.getElementById('wikiSidebar');
    if (!btn || !sidebar) return;   // no wiki sidebar here (admin/editor) — leave the button hidden
    btn.classList.add('is-active');
    function setOpen(open) {
      sidebar.classList.toggle('is-open', open);
      btn.setAttribute('aria-expanded', String(open));
      // Opening moves focus into the popover (first link/control) so a keyboard user lands on the
      // nav they just revealed instead of being left on the toggle behind it. (No focus trap yet —
      // Tab can still walk out to the page; acceptable for a light-dismiss popover, worth revisiting.)
      if (open) {
        var first = sidebar.querySelector('a[href], button:not([disabled]), input, [tabindex]:not([tabindex="-1"])');
        if (first) first.focus();
      }
    }
    btn.addEventListener('click', function (e) {
      e.stopPropagation();   // don't let the document handler below immediately re-close it
      setOpen(!sidebar.classList.contains('is-open'));
    });
    document.addEventListener('click', function (e) {
      // Outside click is a deliberate move elsewhere — close, but don't yank focus back to the toggle.
      if (sidebar.classList.contains('is-open') && !sidebar.contains(e.target)) setOpen(false);
    });
    document.addEventListener('keydown', function (e) {
      // Escape returns focus to the toggle that opened the popover (standard dismiss behaviour).
      if (e.key === 'Escape' && sidebar.classList.contains('is-open')) { setOpen(false); btn.focus(); }
    });
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', wire);
  else wire();
})();
// Theme switch: apply the chosen mode (wkApplyTheme is defined in head-styles.hbs, pre-paint) and mark
// the active option. A logged-in user's pick is saved to their account (data-persist) so it follows them
// across devices; a guest's pick is remembered per-browser (localStorage).
(function () {
  // There can be more than one theme menu now: the standalone theme dropdown (≥md) and the compact
  // "More" kebab (<md) both carry .wk-theme-menu. Wire every [data-theme] button and mark the active
  // option across all of them so whichever is visible at the current width stays in sync.
  var menus = document.querySelectorAll('.wk-theme-menu');
  if (!menus.length) return;
  function mark() {
    var m = window.__wkThemeMode || 'light';
    menus.forEach(function (menu) {
      menu.querySelectorAll('[data-theme]').forEach(function (b) { b.classList.toggle('active', b.dataset.theme === m); });
    });
  }
  menus.forEach(function (menu) {
    var persist = menu.getAttribute('data-persist');   // account endpoint when logged in, else null
    var csrf = menu.getAttribute('data-csrf') || '';
    menu.querySelectorAll('[data-theme]').forEach(function (b) {
      b.addEventListener('click', function () {
        var m = b.dataset.theme;
        if (window.wkApplyTheme) window.wkApplyTheme(m);
        if (persist) {
          try {
            fetch(persist, { method: 'POST', credentials: 'same-origin',
              headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
              body: '_csrf=' + encodeURIComponent(csrf) + '&theme=' + encodeURIComponent(m) });
          } catch (e) {}
        } else {
          try { localStorage.setItem('wk-theme', m); } catch (e) {}
        }
        mark();
      });
    });
  });
  mark();
})();
(function () {
  var input = document.getElementById('siteSearch');
  var box = document.getElementById('siteSearchResults');
  var statusEl = document.getElementById('siteSearchStatus');
  var form = input && input.closest('form');
  if (!input || !box || !form) return;
  var locale = (form.querySelector('input[name="locale"]') || {}).value || '';
  var timer = null, items = [], active = -1;
  var suppressDismiss = false;  // an aux-click on a result is blurring us; keep the popup open through it
  var refocusing = false;       // we're restoring focus after such a click — don't re-run the search
  var MIN = 2;
  var inflight = null;   // the AbortController of the request in flight, so a newer keystroke can cancel it
  var cache = {};        // query string -> results, for this page view only

  // Keep the combobox's ARIA state in step with the visible popup: aria-expanded reflects whether the
  // listbox is showing, aria-activedescendant names the highlighted option (or nothing), and the
  // off-screen status region announces counts/messages without stealing focus from the input.
  function setExpanded(open) { input.setAttribute('aria-expanded', open ? 'true' : 'false'); }
  function setActiveDescendant(id) {
    if (id) input.setAttribute('aria-activedescendant', id);
    else input.removeAttribute('aria-activedescendant');
  }
  function announce(text) { if (statusEl) statusEl.textContent = text || ''; }

  function hide() {
    box.hidden = true; box.innerHTML = ''; items = []; active = -1;
    setExpanded(false); setActiveDescendant(null); announce('');
  }
  function dismiss() { hide(); input.value = ''; }
  function go(url) { window.location.href = url; }

  // Each row is a real <a href>. A *plain* left-click navigates in this tab (on mousedown, so a slow
  // release or the blur-dismiss can't drop the row first). Middle-click, right-click, and
  // Cmd/Ctrl/Shift/Alt+click fall through to the browser -- new tab/new window/context menu. Those
  // keep you on this page, so we hold the popup open (and its query) instead of dismissing on the blur
  // they cause, letting you open several results in a row. The flag is scoped to that one blur.
  function bindRowNav(li, url) {
    li.addEventListener('mousedown', function (e) {
      if (e.button === 0 && !e.metaKey && !e.ctrlKey && !e.shiftKey && !e.altKey) {
        e.preventDefault();
        go(url);
        return;
      }
      suppressDismiss = true;
      setTimeout(function () { suppressDismiss = false; }, 0);
    });
  }

  // A single non-interactive row (min-length hint or "no results"). Not added to items[], so it is
  // not keyboard-selectable and Enter still submits the form to the full search page.
  function showMessage(text) {
    box.innerHTML = ''; items = []; active = -1;
    setActiveDescendant(null);
    var li = document.createElement('li');
    li.className = 'search-dropdown-msg';
    li.textContent = text;
    box.appendChild(li);
    box.hidden = false;
    setExpanded(true);
    // A message row is status text, not a selectable option, so it carries no role="option"; the live
    // region carries it to a screen reader instead.
    announce(text);
  }

  function render(results, q) {
    box.innerHTML = '';
    items = [];
    if (!results.length) { showMessage('No results found'); return; }
    results.forEach(function (r, i) {
      var li = document.createElement('li');
      li.className = 'search-dropdown-item';
      li.id = 'siteSearchOption-' + i;      // referenced by aria-activedescendant when highlighted
      li.setAttribute('role', 'option');
      li.setAttribute('aria-selected', 'false');
      var a = document.createElement('a');
      a.href = r.url;
      var t = document.createElement('span'); t.className = 'sd-title'; t.textContent = r.title;
      // Prefer the page's short description; fall back to the content snippet.
      var s = document.createElement('span'); s.className = 'sd-snippet'; s.textContent = r.description || r.snippet;
      a.appendChild(t); a.appendChild(s);
      if (r.parentLabel) {
        var p = document.createElement('span'); p.className = 'sd-path'; p.textContent = r.parentLabel;
        a.appendChild(p);
      }
      li.appendChild(a);
      bindRowNav(li, r.url);
      box.appendChild(li);
      items.push(r.url);
    });
    var foot = document.createElement('li');
    foot.className = 'search-dropdown-all';
    foot.id = 'siteSearchOption-' + results.length;
    foot.setAttribute('role', 'option');
    foot.setAttribute('aria-selected', 'false');
    var fa = document.createElement('a');
    fa.href = '/s?q=' + encodeURIComponent(q) + '&locale=' + encodeURIComponent(locale);
    fa.textContent = 'See all results →';
    foot.appendChild(fa);
    bindRowNav(foot, fa.href);
    box.appendChild(foot);
    items.push(fa.href);
    active = -1;
    box.hidden = false;
    setExpanded(true);
    setActiveDescendant(null);
    // Count excludes the "See all results" row; read from the live region by a screen reader.
    announce(results.length + (results.length === 1 ? ' result' : ' results') + ' available');
  }

  function query() {
    var q = input.value.trim();
    if (!q.length) { hide(); return; }
    if (q.length < MIN) { showMessage('Please type at least ' + MIN + ' characters to start search'); return; }

    // Backspacing or retyping a query we already ran: render it without touching the server.
    if (cache[q]) { render(cache[q], q); return; }

    // Abandon the request still in flight for an earlier keystroke. Without this it keeps running
    // server-side, burning a pooled DB connection on a result nobody will ever look at.
    if (inflight) inflight.abort();
    var ctl = new AbortController();
    inflight = ctl;
    fetch('/u/v1/search?q=' + encodeURIComponent(q) + '&locale=' + encodeURIComponent(locale) + '&limit=7',
      { credentials: 'same-origin', signal: ctl.signal })
      .then(function (r) { return r.ok ? r.json() : []; })
      .then(function (results) {
        cache[q] = results;
        if (input.value.trim() === q) render(results, q);
      })
      .catch(function (e) { if (e.name !== 'AbortError') hide(); });   // an abort isn't a failure
  }

  input.addEventListener('input', function () { clearTimeout(timer); timer = setTimeout(query, 300); });
  // Re-run on focus so the menu returns after Escape (or if the field is focused with text already) —
  // but not when we're only restoring focus after an aux-click, where the menu is already up and
  // re-rendering would rebuild the rows mid-click.
  input.addEventListener('focus', function () {
    if (refocusing) { refocusing = false; return; }
    query();
  });
  // Clicking away clears the box AND the text, so there's never orphaned text with no menu. An
  // aux-click on a result blurs us too, but there we keep the popup up and just restore focus (so the
  // normal click-away dismissal still works) rather than closing and clearing the query.
  input.addEventListener('blur', function () {
    if (suppressDismiss) { refocusing = true; setTimeout(function () { input.focus(); }, 0); return; }
    setTimeout(dismiss, 150);
  });
  input.addEventListener('keydown', function (e) {
    // Escape dismisses whenever the menu is open (including a message-only row).
    if (e.key === 'Escape') { if (!box.hidden) { e.preventDefault(); dismiss(); } return; }
    if (box.hidden || !items.length) return;
    var lis = box.children;
    if (e.key === 'ArrowDown') { e.preventDefault(); active = (active + 1) % items.length; }
    else if (e.key === 'ArrowUp') { e.preventDefault(); active = (active - 1 + items.length) % items.length; }
    else if (e.key === 'Enter') { if (active >= 0) { e.preventDefault(); go(items[active]); } return; }
    else return;
    for (var i = 0; i < lis.length; i++) {
      var on = i === active;
      lis[i].classList.toggle('active', on);
      lis[i].setAttribute('aria-selected', on ? 'true' : 'false');
    }
    setActiveDescendant(active >= 0 ? lis[active].id : null);
  });
})();
