# {{PROJECT}}

**{{PROJECT_DESC}}**<br>

<br/>
<br/>

[Preamble (FIX ME)]

<br>

## Development Status

| Period           | Stage        | Release |
| ---------------- | ------------ | ------- |
| {{YEAR}} {{MONTH}}       | Experimental |         |
| tbd | Alpha        | 0.1.0 (Unreleased)   |

<br />

## Quick comparison (FIX ME)
Comparison with other libraries that you may be familiar with:
|  | {{PROJECT}} | [libfoo↗](https://example.com) | [libar↗](https://example.com) |
|---|---|---|---|
| Feature A | ✓ | ✕ | ✕ |
| Feature B | ✓ | ✕ | ✓ |
| Feature C | ✓ | ✕ | ✕ |


<br />

## Requirements

Requirements go here (Fix me)

<br />

## Setup

Add as a dependency to your project:

Deps:

```clojure
{{PROJECT_ARTIFACT}} {:mvn/version "0.1.0"}
```

Leiningen:

```clojure
[{{PROJECT_ARTIFACT}} "0.1.0"]
```

Require it:

```Clojure
(require '[{{PROJECT_CORE_NS}}])
```

Or import into your namespace:

```Clojure
(ns myns.core
  (:require
    [{{PROJECT_CORE_NS}}]))
```

### Consuming via git

Using a tool without Maven support? See
[Consuming {{PROJECT}} as a git dependency](docs/git-deps.md).

After tagging a release, refresh that document with `bb git-deps-docs` and commit
the result. Use `bb git-deps-docs v0.1.0` to select a particular release tag.

<br />


## Documentation

**[API](docs/api.md)**<br>
The public api.


<br />

## Contributing

Issues for bugs, improvements, or features are very welcome.

Please file an issue for discussion before starting or submitting a PR.

If you want to submit a PR against an open issue, start by dropping a message in the open issue before doing any work or issuing a PR, in order to initiate human communication, and make sure everyone is on the same page.

<br />

## License

Copyright © {{YEAR}} {{DEVELOPER}}

{{LICENSE}}
