# Exporting to Wiki.js 2.x

WikiKT can hand its content back to Wiki.js. **Administration → Storage and backup → Export for
Wiki.js** downloads a ZIP whose contents are laid out exactly the way Wiki.js's disk storage module
expects, so a migration is unzip-and-import (from **Local File Storage** in Wiki.js) with no
conversion step in between, i.e., after the WikiKT export (which can perform some Wiki.js-friendly
conversion). This can also be used by the **Git** storage method for import.

This option is provided as a convenience for those who want to migrate to Wiki.js. This
project has no affiliation with, endorsement by, or guarantee of compatilibty with Wiki.js
or the project maintainer or any sponsors. Some features may work differently
on both platforms, and manual review of all pages is stil recommended after export.
(If you are looking to migrate *from* Wiki.js, we suggest importing content using the
Git sync option in WikiKT, pointing to a Git repo used for export or bidirectional sync in
Wiki.js, as WikiKT is designed to be compatible with this content for import as-is..)

> This is a **content** export. To move a wiki between WikiKT instances -- with accounts, groups,
> permissions, history, and settings -- use a WikiKT full backup instead. To migrate content only, use
> a content export, Git sync, or other option.

## Importing into Wiki.js

1. Unzip the archive into a folder on the Wiki.js server (e.g. `/wiki-import`). The ZIP root *is* the
   storage folder — don't nest it inside another directory.
2. In Wiki.js: **Administration → Storage → Local File System**. Set **Path** to that folder and save.
3. Still on that page, run the **Import Everything** action.
4. Alternatively, add to Git repo after step 1, then point Wiki.js to that using **Git** (instead 
   of **Local File System**) with appropriate configuration to import instead of the above two steps.

Wiki.js walks every file under the folder: `.md`, `.html` and `.adoc` become pages, and other
fuies become an asset. The ZIP contains nothing but pages and images and should work well
for this kind of import.

## Archive layout

```
en/home.md                 page   -> locale "en", path "home"
en/guides/setup.md         page   -> locale "en", path "guides/setup"
de/hallo.html              page   -> locale "de", path "hallo"
en/img/logo.png            asset  -> asset folder "en/img", served at /en/img/logo.png
```

All exported pages locale-prefixed. Assets keep the same `{locale}/{path}` position,
which is where Wiki.js puts them relative to its  storage root (so an `en`
page's `/en/img/logo.png` still resolves after the import).

Front-matter is exactly the seven keys Wiki.js uses as of the time of this writing,
in the same order we see in its own files:

```yaml
---
title: Setup
description: How to set up
published: true
date: 2026-08-01T09:15:00Z
tags: guide, howto
editor: admin
dateCreated: 2027-07-02T11:00:00Z
---
```

## What the export rewrites

WikiKT extensions are converted to Wiki.js friendly formats on export, the goal being that no
WikiKT-only syntax is left sitting exported pages as unknown text, lost data, etc.

| WikiKT | In the export                                                                                                                                                                                                    |
|---|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `{{fragment:key}}` | The fragment's text, expanded in place (recursively). Markdown pages only (WikiKT itself doesn't support fragments in HTML).                                                                                     |
| `:mdi-home:` icon shortcode | `<i class="mdi mdi-home" aria-hidden="true"></i>`. Wiki.js bundles the same Material Design Icons font and allows inline HTML, so the icon should still render.                                                  |
| `![{alt}](…)`, `<img alt="{alt}">` | The asset's stored alt text (empty when it has none), as default alt text from asset editor is WikiKT feature                                                                                                    |
| `/logo.png` (locale-relative asset link) | `/{locale}/logo.png` for the locale that actually serves those bytes, including WikiKT's default-locale fallback. Links that don't name a known asset (page links, external URLs, anchors) are left as authored. |
| Page infobox | Exports as you select for the **Infoboxes** option: an `infobox:` front-matter block, with or without a Markdown (or HTML) table at the top of the page — or nothing. See below.                    |

### Infoboxes

Wiki.js 2.x lacks a feature like WikiKT infobxes, so the export offers three solutions for export. The
first two both keep the WikiKT `infobox:` YAML block in the front-matter — Wiki.js should ignore it, and
a later import back into WikiKT would restore the infobox exactly (if not dropped by Wiki.js in
meantime) — and differ only in whether the data is *also* rendered where a Wiki.js reader can see it:

- **Insert as table at top of page** (default): the front-matter block, plus a `### Template name`
  heading and a two-column table of the filled fields, at the top of the page where the card sits in
  WikiKT. Section headings become a bold label row; `|` is escaped and newlines become `<br>`.
- **Keep as front-matter only**: machine-readable but invisible in Wiki.js.
- **Omit**: ignore infobox data.

One wart worth knowing: on a WikiKT → Wiki.js → WikiKT round trip, a page exported as a **table** comes
back with both its restored infobox *and* the table still in the body. Prefer front-matter only if you
expect to come back.

## What does not export

Revision history, users/groups, navigation menus, per-page custom CSS/JS,
scheduled publishing, robots override, and site admin settings are not
exported; the focus is on page content. Do not use this feature as a WikiKT-only
export or backup option; use the built-in WikiKT content or full backup instead.

Similalry, when doing a round-trip export and re-import from Wiki.js, you may find
changes, like unpublished pages become published, timestamps and authorship being
reset, and other minor differences,

### Behaviour differences to expect on the Wiki.js side

- **Unpublished pages come back published.** Wiki.js's importer ignores the `published` front-matter
  key and marks everything it imports as published. The export writes the flag correctly, but Wiki.js
  won't honour it — untick **Include unpublished pages** if drafts must not go live there.
- **Page timestamps are reset.** `date`/`dateCreated` are written but ignored on import; imported pages
  get fresh timestamps and the importing user as their author.
- **Asset alt text becomes literal.** Wiki.js has no per-asset alt-text store, so what the export
  resolved into each `![…]` is what readers get from then on.
- **Rendering is close but not identical.** Everything WikiKT renders, Wiki.js also renders: tables,
  strikethrough, task lists, footnotes, sub/sup (`~x~`, `^x^`), `:emoji:` shortcodes, `{.is-info}` and
  friends, `{.tabset}` headings, and ` ```mermaid ` diagrams (both draw them client-side from the same
  fence, so they travel unchanged in either direction). So a page that looks right here looks right
  there — modulo Wiki.js drawing emoji as Twemoji images rather than the font glyphs WikiKT uses.

  The gap runs the *other* way, and matters when content comes back: Wiki.js additionally supports
  `==mark==`, abbreviations, general `{.attrs}` decorations, KaTeX, PlantUML and Kroki, none of
  which WikiKT renders. A page authored in Wiki.js using those imports fine but shows the raw syntax.
- **Sanitizing differs.** Wiki.js sanitizes with DOMPurify and (by default) allows inline HTML; WikiKT
  uses jsoup with its own allowlist. Raw HTML that WikiKT strips today may survive there, and vice
  versa.

## For developers

If you're looking to modify the export, take a look at:

- `service/WikiJsExportService.kt`: the exporter, including every body rewrite above.
- `service/InfoboxService.kt#plainInfoboxes`: infobox data flattened for a plain table.
- `service/PageFileFormat.kt`: the *inbound* format (WikiKT's own git-sync/backup files), which is the
  same front-matter plus WikiKT extension keys.
- `routing/BackupRouting.kt`: `POST /a/backup/export/wikijs`.
- `WikiJsExportTest`: layout, front-matter, rewrites, and infobox modes.
