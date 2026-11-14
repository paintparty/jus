# Terminal Markdown rendering for Command Central

Researched 2026-11-13 using first-party documentation/source and local checks.

## Recommendation

Choose **Charm's Glamour renderer, initially accessed through Glow's noninteractive CLI**, for the Command Central README pane. It provides a mature-looking Markdown presentation without making jus own a Markdown layout engine. This is an engineering recommendation based on the integration fit and the local checks below, not a comparative visual or performance benchmark.

Glamour renders Markdown to ANSI text with configurable styles and wrapping. Glow is the executable reader built on Glamour. Charm's official Bubble Tea example renders at the available content width and passes the result into a viewport—the same basic separation we need. [Glamour](https://github.com/charmbracelet/glamour), [Glow](https://github.com/charmbracelet/glow), [official viewport example](https://github.com/charmbracelet/bubbletea/blob/main/examples/glamour/main.go).

The main tradeoff is distribution: Glow is installed on this development machine, but making it required would add an executable dependency for other users. A small packaged Go helper using Glamour is a possible later alternative if we need stricter control of options, versions, or packaging. That helper would be new work, not an existing Clojure binding identified by this research.

## Existing implementation

These findings come from this checkout:

- `deps.edn` uses `de.timokramer/charm.clj` 0.2.125; `bb.edn` provides the Babashka installation entry point.
- `projects/readme` reads top-level README files, prefers `.md`/`.markdown`, and limits files to 2 MB. It also accepts plain text, RST, AsciiDoc, and Org filenames.
- `core/readme-page` delegates to `content/page`, which currently reflows the source through `style/helper-lines` on each call. That helper recognizes a few inline formats and URLs; it does not parse document structure.
- `src/charm/render/core.clj` is a local renderer override that writes ANSI strings directly and handles terminal-cell-aware clipping.

`charm.clj` is a Clojure implementation inspired by Bubble Tea, using JLine; it is not a bridge to Go libraries. Glamour therefore needs a subprocess/helper boundary here. Its published components include a viewport but do not advertise a Markdown renderer. [charm.clj documentation](https://github.com/TimoKramer/charm.clj), [dependencies](https://github.com/TimoKramer/charm.clj/blob/main/deps.edn).

## Options

| Option | Strengths | Cost or limitation for jus | Verdict |
| --- | --- | --- | --- |
| **Glamour / Glow** | Styled ANSI output, configurable wrapping/themes; GFM parser; tables, tasks, nested lists, fenced code and links. | Separate executable; HTML has limited text fallback. | **First choice.** |
| **Python Rich** | Markdown renderable and CLI; syntax-highlighted code, tables, emphasis, links. | Python plus package management; no clear integration advantage over Glow. | Strong alternative, especially for a Python application. |
| **Textual Markdown / MarkdownViewer** | Widgets with links, document navigation and optional table of contents. | Interaction depends on Textual's widget/event system; captured output does not embed the widget. | Useful design reference. |
| **Ratatui `tui-markdown`** | Markdown into styled Ratatui `Text`, with code highlighting and document extensions. | Rust-specific output types; project describes itself as experimental. | Relevant within a Ratatui app. |
| **Termimad** | Wrapping, table layout, styling and scrollable areas. | Deliberately partial Markdown; documentation lists no syntax coloring, links, or ordered lists. | Poor match for arbitrary READMEs. |
| **mdcat** | Standalone renderer; maintained fork advertises richer terminal graphics and document features. | Graphics introduce terminal compatibility and pane lifecycle questions. | Revisit if images become a requirement. |
| **Nextjournal Markdown + custom renderer** | Structured Clojure AST, already available in Babashka; full control over presentation. | We would own block/inline layout, table sizing, styling, highlighting, links, and width handling. | Best alternative if avoiding an executable dependency is the priority. |

Sources and specific qualifications:

- **Glamour:** source enables Goldmark GFM and definition-list extensions. Renderer handles table/task/strike nodes. Code highlighting and custom styling are documented. [Parser and options](https://github.com/charmbracelet/glamour/blob/main/glamour.go), [ANSI node handling](https://github.com/charmbracelet/glamour/blob/main/ansi/renderer.go), [README](https://github.com/charmbracelet/glamour).
- **Rich:** documented CLI is `python -m rich.markdown README.md`. Current implementation enables tables and strikethrough, but does not enable a task-list plugin. Images receive a textual fallback. [Documentation](https://rich.readthedocs.io/en/stable/markdown.html), [implementation](https://github.com/Textualize/rich/blob/main/rich/markdown.py).
- **Textual:** [Markdown](https://textual.textualize.io/widgets/markdown/), [MarkdownViewer](https://textual.textualize.io/widgets/markdown_viewer/).
- **Ratatui:** `tui-markdown` supports configurable table width and code highlighting; HTML is literal and images are textual in its documented implementation. [Scope/status](https://github.com/joshka/tui-markdown/blob/main/tui-markdown/README.md), [rendering implementation](https://github.com/joshka/tui-markdown/blob/main/tui-markdown/src/lib.rs).
- **Termimad:** its documented feature table explicitly distinguishes its subset from general Markdown. [Project documentation](https://github.com/Canop/termimad).
- **mdcat:** the original repository is archived and points to a maintained fork. Use the fork when evaluating it. [Original project's notice](https://github.com/swsnr/mdcat), [maintained fork](https://github.com/BIRSAx2/mdcat).
- **Nextjournal:** parser produces nested Clojure data and supports CommonMark with GFM extensions. Babashka added it as a built-in in 1.12.201, before jus's documented minimum 1.13.222. JVM execution would still need the library dependency. [Parser documentation](https://github.com/nextjournal/markdown), [Babashka changelog](https://github.com/babashka/babashka/blob/master/CHANGELOG.md#112201-2025-06-12).

## Local checks

Installed versions: **Glow 2.1.2**, **Babashka 1.13.229**. These checks ran locally; other renderers were assessed from sources rather than installed and benchmarked.

Rendered this repository's README with `glow -s ascii -w 72 README.md`. HTML comments, logo tags and screenshot tags disappeared; prose and Markdown document structure remained readable. The ASCII theme deliberately retains some Markdown-like decoration, so it is not the best theme for judging the desired appearance.

Also passed a fixture on stdin with `glow -s dark -w 40 -` and `glow -s dark -w 72 -`, capturing stdout. Both completed successfully and retained ANSI styling despite stdout being a pipe. The fixture exercised headings, emphasis, strikethrough, inline code, nested/task lists, a long table cell, a Clojure fence, links, HTML, comments, and an image.

Observed behavior:

- Tasks became checkboxes, tables laid out and wrapped at the requested width, and nested lists retained indentation.
- Code fences became code blocks; a long code line wrapped in the narrower pane. Highlighting colors were not visually assessed in a live terminal.
- HTML block text survived, but inline HTML emphasis lost its styling.
- `<br>` disappeared without inserting a newline: `here.<br>Next` became `here.Next`.
- Comments disappeared. A Markdown image became an alt-text/path placeholder rather than terminal graphics.
- The two small-fixture subprocess calls took roughly 39 and 43 ms on this machine. This is only a startup-cost indication, not a benchmark or a result for 2 MB documents.

Verified `(require '[nextjournal.markdown :as md])` and `md/parse` directly in installed Babashka without adding a dependency. The result contained heading, strong, link, and HTML-block AST nodes.

## HTML expectations

Glamour sanitizes raw HTML into text; it is not an HTML layout engine. Its element handling and strict sanitizer explain the local observations. Markdown images use a text/link representation. [HTML element handling](https://github.com/charmbracelet/glamour/blob/main/ansi/elements.go), [sanitizer](https://github.com/charmbracelet/glamour/blob/main/ansi/context.go), [image rendering](https://github.com/charmbracelet/glamour/blob/main/ansi/image.go).

For a polished README viewer, define a small fallback policy: preserve meaningful breaks and text, suppress comments/decorative markup, and show useful image labels/links where available. If implementing preprocessing, make it aware of Markdown/code boundaries so HTML examples inside code fences remain intact. Tables, badges, `<details>`, SVG logos, CSS and image positioning should not be assumed to work like GitHub's browser rendering.

## Proposed integration

1. Put rendering behind a small interface accepting source, available width, theme, and document location, returning already-laid-out ANSI lines.
2. Start with Glow's noninteractive stdin/stdout mode and an explicit style/width. Glow switches to a non-TTY style when output is captured unless the style flag is explicit. Avoid its pager/TUI mode inside our app. [CLI source](https://github.com/charmbracelet/glow/blob/main/main.go).
3. Run rendering asynchronously on load and width/theme changes; cache results by source and layout inputs. Scrolling should slice cached lines without launching another process or reparsing the document.
4. Adapt `content/page` to accept rendered lines without sending them back through `helper-lines`. Use terminal-cell width for borders/padding and preserve ANSI/OSC-8 state when slicing pages. Check links that cross page boundaries.
5. Choose a compact custom style consistent with jus's neutral design, accounting for both the pane's padding and the renderer's own margins. Do not query terminal background from a captured subprocess; pass the chosen theme explicitly.
6. Keep non-Markdown README formats on the existing text path. Retain readable text if the renderer is absent or fails; document/install the executable if we choose to require it.
7. Before shipping, verify actual pane rendering, terminal resize, scroll limits, emoji/CJK widths, relative links, code/table overflow, no-color styling, and representative large READMEs. Agree on HTML fallbacks using the existing jus README as one fixture.

Glamour's current upstream examples use `charm.land/glamour/v2`; current source options must not be assumed to exist in the Glamour version bundled with installed Glow 2.1.2. Pin the selected executable/helper version and verify the features actually exposed by it. [Current example imports](https://github.com/charmbracelet/bubbletea/blob/main/examples/glamour/main.go).

No application implementation was changed as part of this research.
