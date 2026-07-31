// Page-view behaviors, loaded at the end of <body> on page/view.hbs (after heading-anchors.js — the
// TOC builder reuses the heading ids that script assigns). Independent pieces, each a no-op when its
// markup is absent: the table-of-contents builder (markup only rendered when tocEnabled), the share
// box (e-mail / copy-URL / print), and the infobox field-help popovers.
(function () {
  var toc = document.getElementById('pageToc');
  var content = document.querySelector('.wiki-content');
  if (!toc || !content) return;
  var list = toc.querySelector('.page-toc-list');
  var count = 0;
  // Headings are demoted one level on render (the page title is the sole h1), so the content's top
  // two levels are h2/h3. Scanning these also excludes the title h1 from the table of contents.
  // H3s nest under the preceding H2; an H2 that gains children grows a chevron that expands/collapses
  // its sub-list (created lazily on the first child so childless H2s stay plain).
  var currentTop = null;      // current top-level <li> (an H2) that H3s attach to
  var currentSublist = null;  // its child <ul>, created on the first H3
  // Every entry gets the same chevron in a fixed-width gutter so titles line up; only sections that
  // turn out to have children swap theirs for a real (clickable) toggle below.
  function buildRow(a) {
    var row = document.createElement('div');
    row.className = 'toc-row';
    var gutter = document.createElement('span');
    gutter.className = 'toc-gutter';
    gutter.innerHTML = '<i class="mdi mdi-chevron-right toc-bullet" aria-hidden="true"></i>';
    row.appendChild(gutter);
    row.appendChild(a);
    return row;
  }
  content.querySelectorAll('h2, h3').forEach(function (h) {
    var text = (h.textContent || '').trim();
    if (!text) return;
    // ids are assigned by heading-anchors.js (loaded just above); fall back to it directly in case
    // that script is ever absent, so the same slug rules always apply.
    var id = h.id || (window.wkEnsureHeadingId ? window.wkEnsureHeadingId(h) : null);
    if (!id) return;
    var a = document.createElement('a');
    a.href = '#' + id;
    a.textContent = text;

    if (h.tagName === 'H3' && currentTop) {
      if (!currentSublist) {
        // First child: swap the parent's static chevron for a toggle, and open a sub-list.
        var toggle = document.createElement('button');
        toggle.type = 'button';
        toggle.className = 'toc-toggle';
        toggle.setAttribute('aria-expanded', 'true');
        toggle.setAttribute('aria-label', 'Toggle subheadings');
        toggle.innerHTML = '<i class="mdi mdi-chevron-right" aria-hidden="true"></i>';
        currentTop.classList.add('toc-has-children', 'is-expanded');
        var parentGutter = currentTop.querySelector('.toc-gutter');
        parentGutter.innerHTML = '';
        parentGutter.appendChild(toggle);
        currentSublist = document.createElement('ul');
        currentSublist.className = 'toc-sublist';
        currentTop.appendChild(currentSublist);
      }
      var subLi = document.createElement('li');
      subLi.className = 'toc-h3';
      subLi.appendChild(buildRow(a));
      currentSublist.appendChild(subLi);
    } else {
      // Top-level entry (an H2, or a stray H3 with no preceding H2).
      var li = document.createElement('li');
      li.className = 'toc-' + h.tagName.toLowerCase();
      li.appendChild(buildRow(a));
      list.appendChild(li);
      currentTop = (h.tagName === 'H2') ? li : null;
      currentSublist = null;
    }
    count++;
  });
  if (count > 0) toc.hidden = false;

  // A chevron click toggles its section (and only that — the title link still navigates).
  list.addEventListener('click', function (e) {
    var btn = e.target.closest('.toc-toggle');
    if (!btn) return;
    e.preventDefault();
    var li = btn.closest('li');
    btn.setAttribute('aria-expanded', String(li.classList.toggle('is-expanded')));
  });

  // Collapse toggle — only interactive on compact screens (CSS makes it inert on desktop, where
  // the list is always shown).
  var toggleBtn = document.getElementById('pageTocToggle');
  toggleBtn.addEventListener('click', function () {
    var open = toc.classList.toggle('is-open');
    toggleBtn.setAttribute('aria-expanded', String(open));
  });

  // Compact screens: move the TOC under the page title (it reads as part of the article there);
  // desktop: back to the top of the side column. Collapse state resets to the breakpoint default
  // (mobile collapsed, desktop expanded).
  var aside = document.querySelector('.page-aside');
  var articleHeader = document.querySelector('.wiki-content > header');
  // Below 1200px — NOT the 768px single-column point — because with the side column in the row,
  // mid-width screens squeezed the article between the nav and the TOC. Must match the CSS blocks.
  var mq = window.matchMedia('(max-width: 1199.98px)');
  function place() {
    var compact = mq.matches && articleHeader;
    if (compact) articleHeader.after(toc);
    else aside.insertBefore(toc, aside.firstChild);
    toc.classList.toggle('is-open', !compact);
    toggleBtn.setAttribute('aria-expanded', String(!compact));
  }
  if (mq.addEventListener) mq.addEventListener('change', place); else mq.addListener(place);
  place();
})();

(function () {
  // Share box: e-mail (mailto with the page title + URL), copy-to-clipboard, and print.
  var url = window.location.href;
  var emailLink = document.getElementById('shareEmail');
  if (emailLink) {
    emailLink.href = 'mailto:?subject=' + encodeURIComponent(document.title) +
      '&body=' + encodeURIComponent(url);
  }
  var copyBtn = document.getElementById('shareCopyUrl');
  if (copyBtn) {
    var fallbackCopy = function () {
      var ta = document.createElement('textarea');
      ta.value = url; ta.style.position = 'fixed'; ta.style.opacity = '0';
      document.body.appendChild(ta); ta.focus(); ta.select();
      try { document.execCommand('copy'); } catch (e) { /* no-op */ }
      document.body.removeChild(ta);
    };
    copyBtn.addEventListener('click', function () {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(url).then(null, fallbackCopy);
      } else { fallbackCopy(); }
    });
  }
  var printBtn = document.getElementById('printPage');
  if (printBtn) printBtn.addEventListener('click', function () { window.print(); });
})();

(function () {
  // Infobox field help: an infobox label whose template supplied help text is rendered as a
  // .wk-infobox-help button (InfoboxService.labelHtml) carrying the explanation in data-bs-content.
  // Turn each into a Bootstrap popover — the trigger ('hover focus', set in the markup) covers pointer
  // hover, keyboard focus, and touch alike, since tapping a button focuses it and tapping away blurs
  // it. container:'body' lifts the popup out of the infobox card, which floats and would otherwise
  // clip it or be widened by it. No-op without infobox help on the page; if the Bootstrap bundle is
  // missing the buttons keep their title attribute and degrade to native tooltips.
  var helps = document.querySelectorAll('.wk-infobox-help');
  if (!helps.length || !window.bootstrap || !window.bootstrap.Popover) return;
  Array.prototype.forEach.call(helps, function (el) {
    new window.bootstrap.Popover(el, {
      container: 'body',
      // Two corrections to Popper's defaults, both about narrow screens, where the card is full width
      // and its labels sit right against the left edge. Left to itself Popper answers a label with no
      // room to its left by flipping the popup to the SIDE of it, which then runs off the right edge
      // (its default overflow guard only works along the placement's own axis, so a side placement is
      // free to overflow horizontally). Restricting the fallback to bottom keeps the popup above or
      // below the label where it belongs, and altAxis then holds it inside the viewport, 8px in.
      popperConfig: function (config) {
        config.modifiers = (config.modifiers || []).concat([
          { name: 'flip', options: { fallbackPlacements: ['bottom'] } },
          { name: 'preventOverflow', options: { altAxis: true, padding: 8 } },
        ]);
        return config;
      },
    });
  });
})();
