# Release Workflow & Changelog Best Practices

This document outlines best practices for maintaining changelogs and releasing Norconex Crawler v4.

## Overview

Changelogs are created on **release branches only**. After a release is tagged, the changelog is preserved in git history at that tag and is **not kept in the main branch**. This avoids duplication while maintaining a permanent record.

The process combines:

1. **Lightweight PR contribution** — Contributors provide context in PR descriptions (optional for PRs with no user impact).
2. **Release-time curation** — At release time, changelogs are drafted from merged PRs and lightly edited.
3. **Automated validation** — The release workflow enforces that a changelog file exists and has content.
4. **Clean history** — After release, the changelog is deleted from the release branch to keep main branch clean.

## Release Process

### Before Release

1. **Create a release branch** from `main` (e.g., `release/4.0.x`).
2. **Review and merge PRs** into the release branch as usual.
3. **When ready to release:**
   - Create a changelog file at `changelogs/changelog-X.Y.Z.md` (where `X.Y.Z` is your target version).
   - Use the template in `TEMPLATE.md` as a guide.
   - Categorize merged PRs into the appropriate sections (Added, Improved, Fixed, etc.).
   - Keep language user-focused and concise.

### During Release

1. Go to **Actions → Release → Run workflow**.
2. Select the release branch.
3. Enter the target version (e.g., `4.0.0`).
4. The workflow validates:
   - The changelog file exists (`changelogs/changelog-4.0.0.md`).
   - The file has content (not empty).
   - The version in POMs is currently a SNAPSHOT.
   - The version matches the branch major version.
5. If validation passes, the workflow:
   - Sets the version in all POMs.
   - Pushes the commit, triggering the main CI/CD workflow.
6. The CI/CD workflow then builds, tags, publishes, and bumps to the next SNAPSHOT version.

### After Release

1. **Changelog is preserved** — The changelog file is now part of git history at the release tag.
   - Users can access it in GitHub at that tag.
   - Users can access it via `git show v4.0.0:changelogs/changelog-4.0.0.md`.
   - GitHub Releases can mirror or link to it.

2. **Delete the changelog file** from the release branch before merging back to main:
   - This keeps the main branch clean and prevents duplication.
   - Next release will create a fresh changelog file.

3. **Merge release branch back to main:**
   - The changelog file should not be included.
   - Only production code and version bumps go to main.

## File Organization

```
.github/
  PULL_REQUEST_TEMPLATE.md        ← Contributor Agreement (DCO) and release notes reminder
  workflows/
    release.yaml                   ← Enforces changelog validation
changelogs/
  README.md                        ← Directory guide
  TEMPLATE.md                      ← Template for new changelog files
  RELEASE_CHANGELOG_GUIDE.md       ← This file

# After each release, the changelog is on the release branch and in git history.
# Example: v4.0.0 tag contains the changelog at that commit.
# The file is deleted before merging back to main.
```

## Changelog File Naming

Changelog files **must** be named `changelog-{VERSION}.md` to prevent accidental reuse and enable workflow validation:

- ✅ `changelog-4.0.0.md`
- ✅ `changelog-4.0.1.md`
- ✅ `changelog-4.1.0.md`
- ❌ `CHANGELOG.md` (too generic, allows reuse)
- ❌ `changelog-latest.md` (vague, unclear)

This naming convention is enforced by the release workflow. If you forget to create the file, the release will fail with a clear error message.

## Changelog Content Best Practices

### Structure

Use these categories:

- **Added** — New features, configuration options, APIs.
- **Improved** — Performance improvements, behavior enhancements, UI/UX polish.
- **Fixed** — Bug fixes, correctness issues.
- **Deprecated** — Features scheduled for removal.
- **Removed** — Removed APIs or features (typically for major releases).
- **Breaking Changes** — Incompatible changes. **Always include migration guidance.**
- **Upgrade Notes** — Special instructions for this release (database migrations, plugin compatibility, etc.).

### Language Guidelines

1. **User-focused:** Write for end users, not developers.
   - ✅ "Added parallel crawling with configurable thread pool"
   - ❌ "Refactored CrawlerThread to use ExecutorService"

2. **Concise:** One bullet per change, 1-2 lines max.
   - ✅ "Improved URL parsing performance by 30%"
   - ❌ "Refactored URL parsing with optimized regex. Also removed old regex cache. Now uses LRU."

3. **Actionable:** Include relevant configuration or API names.
   - ✅ "Fixed issue #456: Query parameters in URLs are now properly encoded"
   - ❌ "Fixed encoding bug"

4. **No internal details unless user-relevant:**
   - ✅ "Fixed memory leak in long-running crawls"
   - ❌ "Fixed null pointer in crawlerCache.cleanup()"

### Example Changelog

```markdown
# Version 4.0.2

Release Date: 2026-05-15

## Added

- Added `connectionPool.enableKeepAlive` configuration option for HTTP persistence
- Added support for SOCKS5 proxy configuration

## Improved

- Improved import performance by 40% through batched document indexing
- Improved error messages for missing or invalid configuration files

## Fixed

- Fixed issue #789: Duplicate document IDs in distributed crawls
- Fixed memory leak in filter pipeline after recent refactor
- Fixed URL encoding for parameters containing special characters

## Upgrade Notes

- If using custom committers, recompile against the updated `CommitterPipeline` interface (minor API change, backward compatible at runtime).
```

## Preventing Empty or Forgotten Changelogs

The release workflow has several layers of protection:

1. **File existence check:** Release fails if `changelogs/changelog-X.Y.Z.md` does not exist.
2. **Content validation:** Release fails if the file is empty or contains only whitespace.
3. **Versioned filename:** The filename must match the release version, preventing accidental reuse.

If the release fails due to a missing or empty changelog:

```
ERROR: Changelog file not found: changelogs/changelog-4.0.2.md
You must create a changelog file with the version in its name to prevent reuse.
Example: changelogs/changelog-4.0.2.md
```

**Fix:** Create the changelog file, commit it to the release branch, and re-run the release workflow.

## Release Branch Cleanup Checklist

After a successful release, before merging the release branch back to main:

- [ ] Changelog file has been created on the release branch ✓
- [ ] Release workflow validated and passed ✓
- [ ] CI/CD workflow created the git tag ✓
- [ ] GitHub Releases page created ✓
- **[ ] Delete `changelogs/changelog-X.Y.Z.md` from the release branch** ← Important!
- [ ] Merge release branch back to main (without the changelog file)
- [ ] Verify main branch does not contain the changelog file

## Git History Reference

If you need to reference a historical changelog, git history preserves it at each release tag:

```bash
# View changelog for v4.0.1
git show v4.0.1:changelogs/changelog-4.0.1.md

# List all releases
git tag -l | grep '^v'
```

## Future Enhancements (Optional)

These are not required now but could be valuable later:

1. **Release Drafter GitHub Action:** Automatically draft changelog from merged PRs using labels (e.g., `type:feature`, `type:fix`).
2. **PR template enforcement:** GitHub branch protection rule requiring the PR template to be filled out.
3. **Changelog mirror to docs:** Auto-copy release notes into the website documentation.
4. **GitHub Release auto-populate:** Post changelog to GitHub Releases automatically during CI/CD.
5. **Automated file cleanup:** GitHub Action to delete the changelog file after successful release (if desired).

## Questions?

- See `TEMPLATE.md` for a changelog template and examples.
- Check `.github/PULL_REQUEST_TEMPLATE.md` for PR guidelines.
