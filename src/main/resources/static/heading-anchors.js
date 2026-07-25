// Section-heading anchors: give every content heading (h2–h6) a stable id and a small link icon so
// any section can be linked to directly. The icon is a plain <a href="#id"> — clicking it jumps to
// the section and updates the address bar, and the browser's own right-click / long-press "Copy
// link" gives the full URL. Loaded anywhere .wiki-content is rendered (page + revision views).
//
// This is ALSO the single owner of heading-id assignment: the table-of-contents script in view.hbs
// runs AFTER this (its <script> tag comes later) and simply reuses the ids set here via
// window.wkEnsureHeadingId, so the slug algorithm lives in exactly one place.
(function () {
  var content = document.querySelector('.wiki-content');
  if (!content) return;

  // Heading anchor slugs follow Wiki.js (uslug): keep letters/numbers/marks and - _ ~, turn
  // separators into spaces, and DROP every other punctuation mark (so "can't" -> "cant", not
  // "can-t"). Then collapse runs of space/hyphen to a single hyphen and lower-case. Unicode
  // property escapes stand in for uslug's code-point tables, so non-ASCII headings slug sanely too.
  function slugify(text) {
    var s = text;
    try { s = s.normalize('NFKC'); } catch (e) { /* old engines: skip normalization */ }
    s = s
      .replace(/\p{Z}/gu, ' ')                  // unicode separators -> space
      .replace(/[^\p{L}\p{N}\p{M}\s\-_~]/gu, '') // drop punctuation/symbols (apostrophes, commas, ...)
      .replace(/^\s+|\s+$/g, '')                 // trim
      .replace(/[\s\-]+/g, '-')                  // collapse space/hyphen runs to one hyphen
      .toLowerCase();
    // CSS ids can't start with a digit; Wiki.js prefixes such slugs with "h-".
    if (/^[0-9]/.test(s)) s = 'h-' + s;
    return s || 'section';
  }

  var used = Object.create(null); // slug -> count, for de-duplicating repeated headings on the page

  // Assign a unique id to a heading (idempotent — keeps an author-supplied id) and return it. Exposed
  // so the TOC reuses the identical ids without re-implementing the slug rules.
  function ensureHeadingId(h) {
    if (h.id) { used[h.id] = (used[h.id] || 0) + 1; return h.id; }
    var base = slugify((h.textContent || '').trim());
    var id = base;
    while (used[id] || document.getElementById(id)) { id = base + '-' + (used[base] = (used[base] || 1) + 1); }
    h.id = id;
    used[id] = 1;
    return id;
  }
  window.wkEnsureHeadingId = ensureHeadingId;

  content.querySelectorAll('h2, h3, h4, h5, h6').forEach(function (h) {
    var text = (h.textContent || '').trim();
    if (!text) return; // empty headings can't be slugged meaningfully and have nothing to link to
    var id = ensureHeadingId(h);

    var a = document.createElement('a');
    a.className = 'wk-heading-anchor';
    a.href = '#' + id;
    a.setAttribute('aria-label', 'Link to this section');
    a.title = 'Link to this section';
    a.innerHTML = '<i class="mdi mdi-link-variant" aria-hidden="true"></i>';
    h.appendChild(a);
  });
})();
