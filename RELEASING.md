# Making changes and releases

Every change goes through a pull request; releases are tags on `main` with the signed APK attached.

## A change

1. Branch from an up-to-date `main`: `git switch -c <short-name>`.
2. Make the change. Add a line under **## [Unreleased]** in `CHANGELOG.md` (Added / Changed / Fixed / Removed) for anything a user would notice.
3. Check it on the TV: `./deploy.sh`, and for photos, the snapshot hook (README, "Development").
4. Push and open a PR: `git push -u origin HEAD && gh pr create --fill`. CI builds the APK, compiles the Python tools and runs shellcheck.
5. When CI is green: `gh pr merge --squash --delete-branch`, then `git switch main && git pull`.

## A release

1. In a PR (`release/X.Y.Z`): rename **[Unreleased]** to `## [X.Y.Z] - YYYY-MM-DD`, add a fresh empty **[Unreleased]** above it, update the compare links at the bottom, and set `android:versionName="X.Y.Z"` and `android:versionCode` = major×10000 + minor×100 + patch in `app/AndroidManifest.xml`. Merge it.
2. On `main`, run `scripts/release.sh X.Y.Z`. It checks the changelog and version, builds the APK signed with `keys/orbit.jks`, tags `vX.Y.Z`, and creates the GitHub release with the APK and that version's changelog as notes.
3. `./deploy.sh` to put it on the TV.

Version numbers: patch for fixes, minor for new features, major for anything that breaks settings or setup.

**The signing key never goes to GitHub.** CI builds with a throwaway key only to check the build; real APKs are built and signed on your own machine. If `keys/` is lost, restore it from your backup. A new key can't update the installed app.
