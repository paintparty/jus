<!-- <h1><span>&#x262F;&#xFE0E;</span> <i>jus</i></h1> -->

<!-- # &#x262F;&#xFE0E; &nbsp;jus -->
# ◒ &nbsp;jus

**A TUI app for Clojure dialects.**

<br>

Scaffold new projects, run tasks, launch REPLs, and explore community resources.

<br>

Built on [Babashka](https://babashka.org/) + [Charm](https://github.com/TimoKramer/charm.clj), with help from [rewrite-clj](https://github.com/clj-commons/rewrite-clj) + [cljfmt](https://github.com/weavejester/cljfmt). 

Project Wizard dispatches to [deps-new](https://github.com/seancorfield/deps-new).

<br>

<p align="center">
  <img src="resources/screens/motion/main-menu_light-mode.gif#gh-light-mode-only"></img>
  <img src="resources/screens/motion/main-menu_dark-mode.gif#gh-dark-mode-only"></img>
</p>

<br>

## Features

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

• &nbsp;  **Select and run bb tasks:**

<p align="center">
  <img src="resources/screens/motion/jus-tasks_light-mode.gif#gh-light-mode-only"></img>
  <img src="resources/screens/motion/jus-tasks_dark-mode.gif#gh-dark-mode-only"></img>
</p>


<br>

## Requirements
[Java](https://clojure.org/guides/install_clojure#java) <br>
[Clojure](https://clojure.org/guides/install_clojure) <br>
[Babashka`v1.13.219`](https://github.com/babashka/babashka#installation) <br>
[bbin](https://github.com/babashka/bbin)

<br>

## Installation
First, [follow these instructions](https://github.com/babashka/bbin#installation) to install `bbin`, if it is not already installed.

Then install **jus**:

```
bbin install io.github.paintparty/jus
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

<br>

## Roadmap 
Based on usage patterns and subject to community feedback going forward, here is a short list of features being considered:

- Support idiomatic new project templates for various dialects. Currently, the new project wizard produces a deps.edn project structure and bb.edn for JVM clojure projects.

- Leverage the TUI to expose the functionality of existing deps.edn project utility libs such as [neil](https://github.com/babashka/neil), similar to how **jus** currently dispatches to [deps-new](https://github.com/seancorfield/deps-new)

- Discovery of libs/functions ala [The Clojure Toolbox](https://www.clojure-toolbox.com/), [clojure.land](https://clojure.land/), or [Cloogle](https://cloogle.phronemophobic.com/doc-search.html)

- Lightweight "My Projects" directory/dashboard

- Automated "recent news" aggregator feed for people who want to limit their exposure to the UI of Slack, Reddit, etc.


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


I am reporting this account/repository because it appears to be a short-lived, substantially AI-generated SEO/reputation campaign targeting named individuals and a company, rather than a genuine FOSS/free-culture project.

This project look alot like SEO spam. The repository appears designed for search indexing via Codeberg Pages, multilingual sitemaps, crawler-friendly robots.txt, and legal-looking AI-generated articles. It includes accusations about named people and a company, and seems to use Codeberg primarily as hosting for reputational/search visibility rather than shared FOSS.

I understand this is separate from username availability. For transparency, I discovered this user and repo because I am interested in using the “jus” namespace for a legitimate project if Codeberg ever determines the current account violates policy and releases the name. The project, which I just released, is a fully-functioning TUI app for Clojure dialects. It is a beginner-friendly gateway to project creation, the REPL, running repo tasks, and exploring Clojure community resources. 
