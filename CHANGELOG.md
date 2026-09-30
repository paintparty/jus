# Changelog
[Jus](https://github.com/paintparty/jus): A TUI app for Clojure dialects 

## 0.3.0

### Changes

#### Interactive Repl Launcher
- Add more dialects (total of 20) with in-1 installer [#21](https://github.com/paintparty/jus/issues/21)
- Navigate user back to just after repl session [#17](https://github.com/paintparty/jus/issues/17)
- Hand off missing REPL installation to copyable in-1 snippets.

#### New Project Wizard
- Auto-navigate user to newly created project dir after successful New Project Wizard flow exits TUI [#19](https://github.com/paintparty/jus/issues/19)
- Show composite path for new project location below menu list [#22](https://github.com/paintparty/jus/issues/22)

#### Community Resources
- Add official clj Cheatsheat to community resources[#25](https://github.com/paintparty/jus/issues/25)
- Add various jolt and jank commons links to resources [#18](https://github.com/paintparty/jus/issues/18)

#### UI
- Add padding block + inline padding start to all menu lists by default. [#26](https://github.com/paintparty/jus/issues/26)
- Fix "down more / up more" scrolling [#13](https://github.com/paintparty/jus/issues/14)

#### CLI
- Add `--version` option [#14](https://github.com/paintparty/jus/issues/14)

#### Deps
- Bump deps-new to `0.14.1`. Minimum bb version for generated projects is `1.13.222`. [#24](https://github.com/paintparty/jus/issues/24)

#### Misc
- Add "About" page [#11](https://github.com/paintparty/jus/issues/11)


### Removed
- Extract the interactive Babashka task picker into the independently installable [`bbtl`](https://github.com/paintparty/bbtl) application. [#22](https://github.com/paintparty/jus/issues/23). <br>Running `jus tasks` now directs the user to install `bbtl`:
```
To list and run bb.edn tasks, install bbtl:
bbin install io.github.paintparty/bbtl
```

## 0.2.0

### Changed
- Use "*" for loading spinner.
- UX writing updates
- Add Jolt to resources
- Add `install:jus-local` task to the `jus` repo's own `bb.edn` (not the template), for streamlined dev-safe install of local dev checkout of `jus`
- Add `install:jus` task to the `jus` repo's own `bb.edn` (not the template), for streamlined override install of local dev checkout of `jus`
- Add `uninstall` versions of both new install tasks to the `jus` repo's own `bb.edn` (not the template), for streamlined uninstallation
- Update contents and formatting of help menu
- Add `jus t` shorthand for `jus tasks`
- Add `babashka/process` dep
- Bump `babashka/fs` dep
- Bump `de.timokramer/charm.clj`dep
- Updated tests
