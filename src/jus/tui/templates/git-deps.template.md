# Consuming {{PROJECT}} as a git dependency

This document shows how to use `{{PROJECT_ARTIFACT}}` directly from its Git repository.
`:git/sha` pins the dependency to a commit; `:git/tag` labels the corresponding release.
The examples include an explicit repository URL so they also work with library names
that do not support URL inference.

## Tag + SHA

```clojure
;; deps.edn
{:deps { {{PROJECT_ARTIFACT}}
         {:git/url "{{PROJECT_GIT_URL}}"
          :git/tag {{TAG}}
          :git/sha {{SHA}}}}}
```

## SHA only

```clojure
{{PROJECT_ARTIFACT}}
{:git/url "{{PROJECT_GIT_URL}}"
 :git/sha {{SHA}}}
```

## Resolving the SHA from a tag

Start with the tag and repository URL in `deps.edn`:

```clojure
{{PROJECT_ARTIFACT}}
{:git/url "{{PROJECT_GIT_URL}}"
 :git/tag {{TAG}}}
```

Then resolve the tag and write its SHA into your dependency coordinate:

```sh
clojure -X:deps git-resolve-tags
```

## Refreshing this document

After tagging a release, run this in the library repository:

```sh
bb git-deps-docs
```

This selects the latest tag reachable from the current checkout. To select a
particular release explicitly:

```sh
bb git-deps-docs {{TAG}}
```

The task resolves annotated and lightweight tags to the full commit SHA and
regenerates `docs/git-deps.md` from `docs/git-deps.template.md`. Commit the updated
document after refreshing it.
