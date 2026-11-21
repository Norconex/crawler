#!/bin/bash
# Commits the working tree's modified tracked files to the pull request's branch
# through GitHub's GraphQL API (createCommitOnBranch), so GitHub signs the commit
# itself and it shows as verified. A plain "git push" from a runner cannot do that,
# and the branch protection requires verified signatures on every commit.
#
# Expects: GH_TOKEN, REPO (owner/name), BRANCH, HEAD_SHA (the branch's current head)
# and MESSAGE (the commit's first line). Does nothing when no file was modified.
set -euo pipefail

mapfile -t files < <(git diff --name-only)
if [ "${#files[@]}" -eq 0 ]; then
  echo "No file was modified, nothing to commit."
  exit 0
fi

# File contents go through files and pipes, never command-line arguments, which
# are limited in size.
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
for f in "${files[@]}"; do
  base64 -w0 "$f" | jq -R -c --arg path "$f" '{path: $path, contents: .}' >> "$work/additions.ndjson"
done

cat > "$work/query.graphql" <<'QUERY'
mutation($input: CreateCommitOnBranchInput!) {
  createCommitOnBranch(input: $input) { commit { oid url } }
}
QUERY

jq -n -c --rawfile query "$work/query.graphql" --slurpfile additions "$work/additions.ndjson" \
      --arg repo "$REPO" --arg branch "$BRANCH" --arg head "$HEAD_SHA" --arg message "$MESSAGE" \
  '{query: $query, variables: {input: {
      branch: {repositoryNameWithOwner: $repo, branchName: $branch},
      expectedHeadOid: $head,
      message: {headline: $message},
      fileChanges: {additions: $additions}}}}' \
  | gh api graphql --input -
