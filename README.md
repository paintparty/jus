<!-- <h1><span>&#x262F;&#xFE0E;</span> <i>jus</i></h1> -->

<!-- ### &#x262F;&#xFE0E;
<h1><img height="50px" valign="center" align="left" src="resources/graphics/jus-logo-1.svg"> jus</h1> -->

# ◒ &nbsp;jus

**A TUI app for Clojure dialects.**

<br>

Scaffold new projects, run tasks, launch REPLs, and explore community resources.

<br>

Built on [Babashka](https://babashka.org/) + [Charm](https://github.com/TimoKramer/charm.clj), with help from [rewrite-clj](https://github.com/clj-commons/rewrite-clj) + [cljfmt](https://github.com/weavejester/cljfmt). 

Project Wizard dispatches to [deps-new](https://github.com/seancorfield/deps-new).

When a selected REPL needs missing tools, Jus asks before installing them
through in-1.
Cancel is selected by default; Escape returns to the REPL menu.
Approval closes the TUI, shows installation progress, and starts the REPL.
If in-1 is unavailable, the confirmation also discloses its download from
https://in-1.cc.

Tools are kept in `$XDG_CACHE_HOME/jus/in-1/local`, falling back to
`~/.cache/jus/in-1/local`.
Existing commands on PATH take precedence over the private cache.
Cached commands are available only to Jus's child processes; no shell startup
files or project configuration are changed.
Failed installations stop the launch and can be retried by selecting the REPL
again.

For a Makes-managed development install, set `JUS-SOURCE` to the absolute path
of this checkout and choose a distinct `JUS-VERSION` to avoid reusing a
published installation:

```bash
in-1 jus JUS-SOURCE="$PWD" JUS-VERSION=dev
```

The default Makes installation still uses the published Jus tag.
Use a fresh development version, or uninstall the development installation,
when testing further checkout changes.

<br>

<p align="center">
  <img src="resources/screens/motion/main-menu_light-mode.gif#gh-light-mode-only"></img>
  <img src="resources/screens/motion/main-menu_dark-mode.gif#gh-dark-mode-only"></img>
</p>

<br>

## Features

• &nbsp;  **Select and run bb tasks:**

<p align="center">
  <img src="resources/screens/motion/jus-tasks_light-mode.gif#gh-light-mode-only"></img>
  <img src="resources/screens/motion/jus-tasks_dark-mode.gif#gh-dark-mode-only"></img>
</p>

<br>

• &nbsp;  **Launch the ***New Project Wizard*** to scaffold a new Clojure [deps.edn](https://clojure.org/reference/deps_edn) project. More project types coming soon.**

<p align="center">
  <img src="resources/screens/motion/project-wizard_light-mode.gif#gh-light-mode-only"></img>
  <img src="resources/screens/motion/project-wizard_dark-mode.gif#gh-dark-mode-only"></img>
</p>

<br>

• &nbsp;  **Launch an interactive REPL for a given dialect:**

<p align="center">
  <img src="resources/screens/motion/launch-repl_light-mode.gif#gh-light-mode-only"></img>
  <img src="resources/screens/motion/launch-repl_dark-mode.gif#gh-dark-mode-only"></img>
</p>

<br>

• &nbsp;  **Explore Clojure community resources:**

<p align="center">
  <img src="resources/screens/motion/community-resources_light-mode.gif#gh-light-mode-only"></img>
  <img src="resources/screens/motion/community-resources_dark-mode.gif#gh-dark-mode-only"></img>
</p>


<br>

## Status
Early days.

<br>

## Requirements
[Java](https://clojure.org/guides/install_clojure#java) <br>
[Clojure](https://clojure.org/guides/install_clojure) <br>
[Babashka`v1.13.219`](https://github.com/babashka/babashka#installation) <br>
[bbin](https://github.com/babashka/bbin)

<br>

## Installation

You can install jus via `in-1` or `bbin`

### Install with `in-1`

[in-1](https://in-1.cc) is a very simple way to install lots of things quickly and with no prerequisites.

> Note: `in-1` works for Bash, Zah and Fish. See [the install page](https://in-1.cc/install/) specifics.

To try `jus` without installing it permanently, just run:

```
source <(curl -sL in-1.cc) jus    # Bash or Zsh
curl -sL in-1.cc | source - jus   # Fish shell
```

The `jus` command will be installed in `/tmp/in-1/bin/jus` and then `/tmp/in-1/bin` will be added to your `PATH`. That means `jus` is only available in the shell you installed it into.

To install `jus` (more permanently) in `$HOME/.local/bin/jus`, run:

```
source <(curl -sL in-1.cc) --local in-1   # First install ~/.local/bin/in-1
in-1 jus                                  # Then install jus (or anything else) in ~/.local/bin/jus
```

> Note: `--local` is just shorthand for `PREFIX=$HOME/.local`, which you can use to install to some other place. Just be sure that `$PREFIX/bin` is in your `PATH`.

#### Uninstall with `in-1`

To uninstall `jus`, just run `in-1 --uninstall jus`.

<br>

### Install with `bbin`
If not already installed,
 [follow these instructions](https://github.com/babashka/bbin#installation) to install `bbin`.

Then install jus:

```
bbin install io.github.paintparty/jus
```

#### Uninstall with `bbin`
```
bbin uninstall jus
```
<br>

## Usage
Launch the TUI:
```
jus
```
<br>

If your project has a `bb.edn` file with `tasks` defined, you can interactively browse and select tasks:
```
jus tasks
```
Shorthand for `jus tasks`
```
jus t
```

<br>

## Roadmap 
Based on usage patterns and subject to community feedback going forward, here is a short list of features being considered:

- Support a [Quickstart/Fiddle wizard flow](https://github.com/paintparty/jus/issues/6)

- Support idiomatic new project templates for additional dialects. Currently, the new project wizard produces a deps.edn project structure and bb.edn for JVM clojure projects. In order of priority, I would like to add support for:
1 Babashka
2 CLJS Browser
3 CLJS Node
4 Squint
5 nbb
6 Jolt

- Leverage the TUI to expose the functionality of existing deps.edn project utility libs such as [neil](https://github.com/babashka/neil), similar to how jus currently dispatches to [deps-new](https://github.com/seancorfield/deps-new)

- A search UI for discovery of libs/functions à la [The Clojure Toolbox](https://www.clojure-toolbox.com/), [clojure.land](https://clojure.land/), or [Cloogle](https://cloogle.phronemophobic.com/doc-search.html)

- Creation of a floating modal-panel to leverage for contextual menus and alerts (info, errors, warnings, and query results).

- Lightweight "My Projects" directory/dashboard

- Automated "recent news" aggregator feed for people who want to limit their exposure to the UI of Slack, Reddit, etc.

- Interactive discovery, via pregenerated menu (at TUI app lauch), of relevant info for Clojure development:
  - Java/JDK version and install locations
  - Clojure version and install location
  - Clojure CLI tools installation status
  - Similar info about other dialects such as Babashka
  - If something that corresponds to one of these slots is not installed, the app could feature a CTA UI that would prompt the user to confirm installation process, which would exit the TUI and initiate the install.


<br>

## Development

### Install locally with bbin

Use this when developing from a checkout. The installed command continues to
use this local source tree, so Clojure source edits are available the next time
you run it.

1. Install [bbin](https://github.com/babashka/bbin) and make sure its bin
   directory is on your `PATH`:

   ```sh
   brew install babashka/brew/bbin
   echo 'export PATH="$PATH:$HOME/.local/bin"' >> ~/.zshrc
   exec zsh
   ```

2. Clone this repository and enter it:

   ```sh
   git clone git@github.com:paintparty/jus.git jus
   cd jus
   ```

3. Install this checkout under a development-safe command name:

   ```sh
   bbin install . --as jus-local
   ```

   bbin reads this project's `:bbin/bin` configuration, which launches
   `jus.tui.core` with `-m`. The `--as` name avoids replacing a separately
   installed `jus` command.

4. Run it:

   ```sh
   jus-local
   ```

5. Remove the local command when finished:

   ```sh
   bbin uninstall jus-local
   ```

To install the checkout as the normal command instead, use
`bbin install . --as jus` and run `jus`.

<br>

## Contributing
Issues for bugs, improvements, or features are very welcome.

Please file an issue for discussion before starting or submitting a PR.

If you want to submit a PR against an open issue, start by dropping a message in the open issue before doing any work or issuing a PR, in order to initiate communication and make sure everyone is on the same page.

If you have a feed that you want featured in the Community Resources section,
please add it to one or more of the aggregators or curated lists that are
featured in various resource menus, for example:
   
- [Planet Clojure](http://planet.clojure.in)
- [Excellent Clojure](https://gist.github.com/ssrihari/0bf159afb781eef7cc552a1a0b17786f)
- [Awesome Clojure](https://github.com/razum2um/awesome-clojure)


<br>

## License

Copyright © 2026 Jeremiah Coyle

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
