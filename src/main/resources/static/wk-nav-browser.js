// Wiki sidebar: the static⇄tree view switch and the drill-down page browser.
// Data comes from partials/sidebar.hbs (model built in WikiRouting.navModel): a per-sidebar tree JSON
// blob plus the current page path. No network calls — the whole (permission-filtered) tree is embedded.
(function () {
  'use strict';

  // --- Main Menu ⇄ Browse picker (both mode); remembers the choice per browser in the wk-nav-view cookie. ---
  // The picker is a Bootstrap dropdown. Its trigger icon is fixed (it names the control, not the
  // selection); which view is current lives in the menu's .active item and in the trigger's
  // title/aria-label, plus the label the trigger shows when there's no Home button beside it.
  function switchInit(side) {
    if (side.dataset.navSwitchWired) return;
    side.dataset.navSwitchWired = '1';
    var items = Array.prototype.slice.call(side.querySelectorAll('.wk-nav-view-menu [data-nav-view]'));
    var panes = Array.prototype.slice.call(side.querySelectorAll('.wk-nav-pane'));
    var trigger = side.querySelector('[data-nav-view-trigger]');
    if (!items.length || !trigger) return;
    var triggerLabel = trigger.querySelector('.wk-nav-view-label');

    function show(view) {
      panes.forEach(function (p) { p.classList.toggle('is-active', p.getAttribute('data-nav-view') === view); });
      items.forEach(function (it) {
        var on = it.getAttribute('data-nav-view') === view;
        // .active is Bootstrap's selected-item style and what the CSS hangs the check mark on;
        // aria-current is the matching state for screen readers. Set together so they can't drift.
        it.classList.toggle('active', on);
        if (on) it.setAttribute('aria-current', 'true'); else it.removeAttribute('aria-current');
        if (!on) return;
        // Same wording partials/sidebar.hbs renders server-side — keep the two in step.
        var label = 'Navigation view mode: ' + it.getAttribute('data-nav-label');
        if (triggerLabel) triggerLabel.textContent = it.getAttribute('data-nav-label');
        trigger.setAttribute('title', label);
        trigger.setAttribute('aria-label', label);
      });
      try { document.cookie = 'wk-nav-view=' + view + '; path=/; max-age=31536000; SameSite=Lax'; } catch (e) {}
    }
    items.forEach(function (it) {
      it.addEventListener('click', function () { show(it.getAttribute('data-nav-view')); });
    });
  }

  function icon(name) {
    var i = document.createElement('i');
    i.className = 'mdi mdi-' + name;
    i.setAttribute('aria-hidden', 'true');
    return i;
  }

  // --- Drill-down browser: shows one folder level at a time. ---
  function treeInit(pane) {
    if (pane.dataset.navTreeWired) return;
    pane.dataset.navTreeWired = '1';
    var body = pane.querySelector('[data-tree-body]');
    var dataEl = pane.querySelector('[data-tree-data]');
    if (!body || !dataEl) return;
    var roots;
    try { roots = JSON.parse(dataEl.textContent || '[]'); } catch (e) { roots = []; }
    var currentPath = pane.getAttribute('data-current-path') || '';

    // The node at an exact folder path (null for the pathless root or a missing path).
    function nodeAt(path) {
      if (!path) return null;
      var segs = path.split('/');
      var list = roots, node = null;
      for (var i = 0; i < segs.length; i++) {
        var prefix = segs.slice(0, i + 1).join('/');
        node = null;
        for (var j = 0; j < list.length; j++) {
          if (list[j].path === prefix) { node = list[j]; break; }
        }
        if (!node) return null;
        list = node.children || [];
      }
      return node;
    }
    function childrenAt(path) {
      if (!path) return roots;
      var n = nodeAt(path);
      return n ? (n.children || []) : null;
    }
    function labelAt(path) {
      if (!path) return 'All pages';
      var n = nodeAt(path);
      return n ? n.label : path;
    }

    // Folders (nodes with children) first, then leaf pages; each alphabetized — mirrors SiteNavTree's order.
    function sortNodes(list) {
      return list.slice().sort(function (a, b) {
        var af = (a.children && a.children.length) ? 0 : 1;
        var bf = (b.children && b.children.length) ? 0 : 1;
        if (af !== bf) return af - bf;
        return a.label.toLowerCase().localeCompare(b.label.toLowerCase());
      });
    }

    function render(folder) {
      var list = childrenAt(folder);
      if (list === null) { folder = ''; list = roots; }   // stale/removed path → fall back to the root
      body.innerHTML = '';

      if (folder) {
        var parent = folder.split('/').slice(0, -1).join('/');
        var up = document.createElement('button');
        up.type = 'button';
        up.className = 'wk-tree-up';
        up.appendChild(icon('arrow-up'));
        up.appendChild(document.createTextNode(' ' + labelAt(folder)));
        up.addEventListener('click', function () { render(parent); });
        body.appendChild(up);
      }

      if (!list.length) {
        var empty = document.createElement('p');
        empty.className = 'wk-tree-empty';
        empty.textContent = folder ? 'This section has no pages.' : 'No pages yet.';
        body.appendChild(empty);
        return;
      }

      sortNodes(list).forEach(function (n) {
        var hasKids = !!(n.children && n.children.length);
        var row = document.createElement('div');
        row.className = 'wk-tree-row';

        // The folder icon is the affordance: the whole row is one target. A section that has its own
        // page opens it on click (and this browser then shows its children — see the initial `start`
        // below); a pure container folder has no page, so clicking it just drills in.
        var main;
        if (n.hasPage) {
          main = document.createElement('a');
          main.className = 'wk-tree-link';
          main.href = n.url;
          if (n.path === currentPath) { main.classList.add('active'); main.setAttribute('aria-current', 'page'); }
        } else {
          main = document.createElement('button');
          main.type = 'button';
          main.className = 'wk-tree-link wk-tree-folder';
          main.addEventListener('click', function () { render(n.path); });
        }
        main.appendChild(icon(hasKids ? 'folder-outline' : 'file-document-outline'));
        main.appendChild(document.createTextNode(' ' + n.label));
        row.appendChild(main);
        body.appendChild(row);
      });
    }

    // Open on the current page's folder: its own contents when it's a section, else its parent's.
    var start = (function () {
      var self = nodeAt(currentPath);
      if (self && self.children && self.children.length) return currentPath;
      return currentPath ? currentPath.split('/').slice(0, -1).join('/') : '';
    })();
    render(start);
  }

  function init() {
    document.querySelectorAll('.wiki-sidebar').forEach(switchInit);
    document.querySelectorAll('.wk-nav-pane--tree[data-nav-tree]').forEach(treeInit);
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();
