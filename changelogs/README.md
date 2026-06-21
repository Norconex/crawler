# Release Changelogs

Changelogs are created on **release branches only** and preserved in git history at each release tag. They are not kept in the main branch to avoid duplication.

## Quick Overview

1. Create `changelog-{VERSION}.md` on the release branch before triggering the release.
2. The release workflow validates the file exists and has content.
3. After release, the changelog is permanently recorded in git history at that tag.
4. Delete the changelog file from the release branch before merging back to main.
5. Next release: create a fresh changelog file on the next release branch.

## File Naming

Changelog files **must** be named `changelog-{VERSION}.md`:

- ✅ `changelog-4.0.0.md`, `changelog-4.0.1.md`
- ❌ `CHANGELOG.md` (generic — allows accidental reuse)

The release workflow enforces this naming so that each file is tied to exactly one release version.

## Further Reading

- **[RELEASE_CHANGELOG_GUIDE.md](RELEASE_CHANGELOG_GUIDE.md)** — Full release process, content guidelines, language examples, and cleanup checklist.
- **[TEMPLATE.md](TEMPLATE.md)** — Blank template for new changelog files.
