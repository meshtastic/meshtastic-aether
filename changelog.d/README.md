# Changelog fragments

A change that needs a changelog entry adds a file here and never edits `CHANGELOG.md`. The release cut folds the
fragments into `## [Unreleased]` and deletes them, so parallel branches never conflict on the changelog.

One file per change, named `<name>.<section>.md`. `<name>` is the PR number or a short slug. `<section>` is one of
`breaking`, `added`, `changed`, `deprecated`, `removed`, `fixed` or `security`, which map to the `### Breaking`,
`### Added` and so on headings of `CHANGELOG.md`.

The file holds one or more `- ` bullets written exactly as they will appear in `CHANGELOG.md`, with continuation
lines indented two spaces and no blank lines between them:

```markdown
- The connect screen opens on the link type you last chose.
```

`scripts/changelog.sh check` validates the files (CI runs it), `scripts/changelog.sh preview` prints `[Unreleased]`
as the cut will fold it, and `scripts/changelog.sh fold` applies it. [`RELEASING.md`](../RELEASING.md) covers the cut.
