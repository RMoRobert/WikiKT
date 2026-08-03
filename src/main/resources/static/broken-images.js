// Marks images that failed to load inside rendered page content, so a dead image is visible as a
// missing image (a dashed placeholder box, styled in site.css) instead of its alt text quietly
// blending into the prose — or, when the alt is empty, leaving no trace at all that anything was
// there. Loaded on the page view, revision view, and the editor's side-by-side preview.
//
// A capture-phase listener on the document rather than per-image handlers: `error` does not bubble,
// but it does capture, and one document-level listener also covers images that appear later — the
// editor preview replaces its whole pane on every re-render.
(function () {
  var CONTENT = '.wiki-content, .editor-preview-side, .editor-preview';

  function mark(img) {
    if (img.tagName === 'IMG' && img.closest(CONTENT)) img.classList.add('wk-img-broken');
  }

  document.addEventListener('error', function (e) { mark(e.target); }, true);

  // Images that already failed before this script ran: a finished load with no intrinsic width is a
  // broken one. (Deferred to DOMContentLoaded only if the parser is still going; the script sits at
  // the end of <body>, so usually the content is already there.)
  function sweep() {
    document.querySelectorAll(CONTENT).forEach(function (root) {
      root.querySelectorAll('img').forEach(function (img) {
        if (img.complete && img.naturalWidth === 0) mark(img);
      });
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', sweep);
  } else {
    sweep();
  }
})();
