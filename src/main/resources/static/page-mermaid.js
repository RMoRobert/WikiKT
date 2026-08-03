// Mermaid diagrams: ```mermaid fences rendered client-side into SVG -- the same approach Wiki.js takes,
// so diagrams move between the two unchanged. There is no server-side half to this: commonmark already
// emits the fence as <pre><code class="language-mermaid">, and HtmlSanitizer already keeps that class.
//
// Load order matters: this runs BEFORE highlight.js. The wrapping below is synchronous, so by the time
// hljs.highlightAll() runs every diagram source is marked `nohighlight` and left alone. Mermaid itself
// (~3.5 MB) is fetched lazily, and only on a page that actually has a diagram on it.
//
// The source <pre> stays in the DOM inside the wrapper and is only hidden once its SVG is in, so no JS,
// a blocked CDN, or a syntax error all degrade to the code the author wrote rather than to a blank.
(() => {
  // document.currentScript is only meaningful while this script is executing -- read the URL now, not
  // later from inside the lazy loader.
  const script = document.currentScript;
  const src = script && script.dataset.mermaidSrc;
  const integrity = (script && script.dataset.mermaidIntegrity) || '';

  const CONFIG = {
    startOnLoad: false,
    // Diagram sources are page content, and page authors are not necessarily admins: 'strict' keeps
    // mermaid's own DOMPurify pass on and disables `click` directives -- the one part of the syntax
    // that attaches behaviour or links. mermaid's built-in maxTextSize/maxEdges caps stay at their
    // defaults, which is what bounds the work one diagram can ask a reader's browser to do.
    securityLevel: 'strict',
    // Never draw mermaid's own "syntax error" graphic; a failed diagram falls back to its source below.
    suppressErrorRendering: true,
  };

  let loading = null;   // shared promise, so N blocks on a page trigger one fetch
  let seq = 0;          // unique element ids for mermaid.render
  let configured = '';  // the (theme, font) mermaid was last initialize()d with

  const loadMermaid = () => {
    if (loading) return loading;
    loading = new Promise((resolve, reject) => {
      if (!src) { reject(new Error('mermaid: no source configured')); return; }
      const tag = document.createElement('script');
      tag.src = src;
      // Set together or not at all: an integrity check on a cross-origin script needs CORS.
      if (integrity) { tag.integrity = integrity; tag.crossOrigin = 'anonymous'; }
      tag.onload = () => (window.mermaid ? resolve(window.mermaid) : reject(new Error('mermaid: loaded but absent')));
      tag.onerror = () => reject(new Error('mermaid: could not load ' + src));
      document.head.appendChild(tag);
    });
    return loading;
  };

  // Bootstrap scopes its theme to any subtree carrying data-bs-theme, so the nearest one wins. That is
  // what makes the editor's independently-dark preview pane (applyPreviewDark in page-edit.js) come out
  // dark while the site around it stays light.
  const themeFor = (el) => {
    const scope = el.closest('[data-bs-theme]');
    return scope && scope.getAttribute('data-bs-theme') === 'dark' ? 'dark' : 'default';
  };

  const draw = (mermaid, wrap) => {
    const source = wrap.querySelector('pre > code');
    if (!source) return Promise.resolve();
    // Mermaid bakes colours and fonts into the SVG at render time and its config is global, so it needs
    // re-initializing whenever the next block wants different ones.
    const theme = themeFor(wrap);
    const font = getComputedStyle(wrap).fontFamily;
    const key = theme + '|' + font;
    if (key !== configured) {
      configured = key;
      mermaid.initialize(Object.assign({}, CONFIG, { theme: theme, fontFamily: font }));
    }
    const id = 'wk-mermaid-' + (++seq);
    // Wrapped in a promise so a synchronous throw fails this one diagram like a rejection would.
    return Promise.resolve()
      .then(() => mermaid.render(id, source.textContent))
      .then((result) => {
        wrap.querySelector('.wk-mermaid-svg').innerHTML = result.svg;
        wrap.dataset.wkTheme = theme;
        wrap.classList.remove('is-failed');
        wrap.classList.add('is-rendered');
      }, (err) => {
        wrap.classList.remove('is-rendered');
        wrap.classList.add('is-failed');
        console.warn('mermaid: diagram could not be rendered', err);
      })
      .then(() => {
        // Mermaid measures text in a temporary element it normally cleans up itself; make sure a failed
        // render doesn't leave one behind in the body.
        const temp = document.getElementById('d' + id);
        if (temp) temp.remove();
      });
  };

  // One diagram at a time: the config carrying the theme is global, so overlapping renders would race.
  const drawAll = (wraps) => {
    if (!wraps.length) return Promise.resolve();
    return loadMermaid().then(
      (mermaid) => wraps.reduce((chain, wrap) => chain.then(() => draw(mermaid, wrap)), Promise.resolve()),
      (err) => {
        wraps.forEach((wrap) => wrap.classList.add('is-failed'));
        console.warn(err.message);
      },
    );
  };

  // Wraps each ```mermaid block in a container holding an (initially empty) SVG target plus the original
  // <pre>, and returns the wrappers still to draw. Idempotent: blocks that already have one are skipped.
  const prepare = (root) => {
    const pending = [];
    root.querySelectorAll('pre > code.language-mermaid').forEach((code) => {
      const pre = code.parentElement;
      if (pre.parentElement && pre.parentElement.classList.contains('wk-mermaid')) return;
      // Keep hljs.highlightAll() off the fallback source. Adding `nohighlight` is not enough on its
      // own: hljs reads the `language-` class first and would warn about an unknown language before
      // ever looking at the rest, so the marker this file matched on is swapped out for it.
      code.classList.remove('language-mermaid');
      code.classList.add('nohighlight');
      const wrap = document.createElement('div');
      wrap.className = 'wk-mermaid';
      const target = document.createElement('div');
      target.className = 'wk-mermaid-svg';
      pre.replaceWith(wrap);
      wrap.append(target, pre);
      pending.push(wrap);
    });
    return pending;
  };

  // Exported for the editor's live preview, which replaces its pane's HTML on every render (see
  // renderPreviewInto in page-edit.js). Defaults to the whole document, which is the page-view case.
  window.wkRenderMermaid = (root) => drawAll(prepare(root || document));

  window.wkRenderMermaid(document);

  // The theme can change after a diagram is drawn -- the header's light/dark switch, or the editor's
  // preview-only toggle -- and the colours are baked into the SVG, so redraw whatever no longer matches.
  // Nothing to do until mermaid has actually been loaded by a diagram somewhere.
  let redrawTimer = null;
  const redrawStale = () => {
    if (!loading) return;
    drawAll(Array.from(document.querySelectorAll('.wk-mermaid.is-rendered'))
      .filter((wrap) => wrap.dataset.wkTheme !== themeFor(wrap)));
  };
  new MutationObserver(() => {
    clearTimeout(redrawTimer);
    redrawTimer = setTimeout(redrawStale, 50);
  }).observe(document.documentElement, { attributes: true, attributeFilter: ['data-bs-theme'], subtree: true });
})();
