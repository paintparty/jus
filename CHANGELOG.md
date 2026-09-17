# Changelog
[Jus](https://github.com/paintparty/jus): A TUI app for Clojure dialects 

## 0.2.0

### Changed
- Hand off missing REPL installation to copyable in-1 snippets instead of running the installer inside jus.
- Use "*" for loading spinner.
- UX writing updates
- Speed up `jus tasks` reveal animation
- Add Jolt resources
- Add `install:jus-local` task to the `jus` repo's own `bb.edn` (not the template), for streamlined dev-safe install of local dev checkout of `jus`
- Add `install:jus` task to the `jus` repo's own `bb.edn` (not the template), for streamlined override install of local dev checkout of `jus`
- Add `uninstall` versions of both new install tasks to the `jus` repo's own `bb.edn` (not the template), for streamlined uninstallation
- Update contents and formatting of help menu
- Add `jus t` shorthand for `jus tasks`
- Add `babashka/process` dep
- Bump `babashka/fs` dep
- Bump `de.timokramer/charm.clj`dep
- Updated tests
