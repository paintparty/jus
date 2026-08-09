<!-- <h1><span>&#x262F;&#xFE0E;</span> <i>jus</i></h1> -->

# &#x262F;&#xFE0E; &nbsp;jus

**A TUI app for Clojure dialects.**

<br>

Built on [Babashka](https://babashka.org/) + [Charm](https://github.com/TimoKramer/charm.clj), with help from [rewrite-clj](https://github.com/clj-commons/rewrite-clj) + [cljfmt](https://github.com/weavejester/cljfmt) 

Project Wizard dispatches to [deps-new](https://github.com/seancorfield/deps-new)

<br>


<p align="center">
  <img src="resources/screens/motion/main-menu_light-mode.gif#gh-light-mode-only"></img>
  <img src="resources/screens/motion/main-menu_dark-mode.gif#gh-dark-mode-only"></img>
</p>


**Launch a wizard to scaffold a new Clojure [deps.edn](https://clojure.org/reference/deps_edn) project:**

<p align="center">
  <img src="resources/screens/motion/project-wizard_light-mode.gif#gh-light-mode-only"></img>
  <img src="resources/screens/motion/project-wizard_dark-mode.gif#gh-dark-mode-only"></img>
</p>

<br>

**Launch an interactive REPL for a given clj dialect:**

<p align="center">
  <img src="resources/screens/motion/launch-repl_light-mode.gif#gh-light-mode-only"></img>
  <img src="resources/screens/motion/launch-repl_dark-mode.gif#gh-dark-mode-only"></img>
</p>

<br>

**Explore Clojure community resources:**

<p align="center">
  <img src="resources/screens/motion/community-resources_light-mode.gif#gh-light-mode-only"></img>
  <img src="resources/screens/motion/community-resources_dark-mode.gif#gh-dark-mode-only"></img>
</p>

**Select and run tasks:**

<p align="center">
  <img src="resources/screens/motion/jus-tasks_light-mode.gif#gh-light-mode-only"></img>
  <img src="resources/screens/motion/jus-tasks_dark-mode.gif#gh-dark-mode-only"></img>
</p>


<br>

## Requirements
Babashka `v1.13.219`

<br>

## Installation
```
bbin install io.github.paintparty/jus
```

<br>

## Usage
Launch the TUI app:
```
jus
```
<br>

If your project has a `bb.edn` file with `tasks` defined, you can interactively browse and select tasks:
```
jus tasks
```

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

Please file an issue for discussion before starting or issuing a PR.

If you have a feed that you want featured in the Community Resources section,
please add it to one or more of the aggregators or curated lists that are
featured in various resource menus, for example:
   
- [Planet Clojure](http://planet.clojure.in)
- [Excellent Clojure](https://gist.github.com/ssrihari/0bf159afb781eef7cc552a1a0b17786f)
- [Awesome Clojure](https://github.com/razum2um/awesome-clojure)


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
