# Releasing

Artifacts are published to Maven Central under `io.github.kairos-tools`, through the
[Central Portal](https://central.sonatype.com), by the
`com.vanniktech.maven.publish` Gradle plugin.

**Published modules**
- `baton-bom`, `baton-api`, `baton-core`, `baton-testkit`;
- `baton-micrometer`, `baton-opentelemetry`, `baton-slf4j`, `baton-visualize`;
- `baton-spring-boot-starter`.

The examples, benchmarks and integration tests are never published.

## Versions

The version comes from git tags, through the axion-release Gradle plugin. Nothing in the
repository holds it.

| Where the build runs | Version |
|---|---|
| On the commit tagged `v0.1.0-RC1` | `0.1.0-RC1` |
| On `main` after `v0.1.0-RC1` | `0.1.0-RC2-SNAPSHOT` |
| On `main` after `v0.1.0` | `0.1.1-SNAPSHOT` |
| On `main` before any tag | `0.1.0-SNAPSHOT` |
| On another branch | the same, with the branch name added, for example `0.1.0-feat-v4-SNAPSHOT` |

`./gradlew cV` prints the current version.

- `X.Y.Z-RC<n>` is a release candidate. Maven and Gradle both sort a candidate before
  its final version.
- `X.Y.Z` is the final release.
- To make snapshots after a final release read the next minor, run
  `./gradlew markNextVersion -Prelease.version=0.2.0` once. This pushes a marker tag.

**A version published to Maven Central is permanent:** it can't be deleted or replaced.
A broken candidate is followed by the next one (`-RC2`), never re-published.

## One-time setup

1. **Namespace.**
   - Sign in to the Central Portal and add the namespace `io.github.kairos-tools`.
   - Verify it as the portal asks: create a temporary public repository with the name
     it gives in the `kairos-tools` GitHub organization, then delete that repository
     once the namespace is verified.
2. **Portal token.** Generate a user token in the Central Portal (Account → Generate
   User Token).
3. **Signing key.**
   ```bash
   gpg --quick-gen-key "Baton release <you@example.com>" ed25519 sign 2y
   gpg --keyserver keys.openpgp.org --send-keys <KEY_ID>
   ```
   Central checks signatures against public key servers, so publish the key before the
   first release.
4. **Repository secrets.** Set them from a terminal, so the values never appear
   anywhere else:
   ```bash
   gh secret set MAVEN_CENTRAL_USERNAME --repo kairos-tools/baton
   gh secret set MAVEN_CENTRAL_PASSWORD --repo kairos-tools/baton
   gpg --armor --export-secret-keys <KEY_ID> | gh secret set SIGNING_KEY --repo kairos-tools/baton
   gh secret set SIGNING_KEY_PASSWORD --repo kairos-tools/baton
   ```
5. **GitHub Pages.** Settings → Pages → Source: **GitHub Actions**. The Docs workflow
   then publishes the site from `main`.

## Cutting a release

1. **Get `main` ready.**
   - Work from a phase branch reaches `main` through a pull request merged with **Create
     a merge commit**. Squash and rebase merges rewrite commit ids, which would break
     `.git-blame-ignore-revs`.
   - Make the install snippets in the README, `docs/getting-started.md` and
     `docs/spring-boot.md` use the version you're about to release.
   - For a final release, replace "unreleased" in `CHANGELOG.md` with the date.
2. **Run the Release workflow** from the Actions tab, on `main`, with the version, for
   example `0.1.0-RC1`. It:
   1. checks that it runs on `main`, that the version looks like `X.Y.Z` or `X.Y.Z-RCn`,
      that it isn't tagged yet, and that all four secrets are set;
   2. creates the tag `v<version>` on the runner, so the build's version is exactly the
      release version;
   3. builds, signs and uploads a deployment to the Central Portal;
   4. only if the upload succeeded, pushes the tag and creates a **draft** GitHub
      Release with generated notes, marked as a pre-release for an RC.

   If a step fails before the upload, nothing is tagged, and you can run it again.
3. **Publish:** check the deployment on central.sonatype.com (every module listed,
   validation passed) and press **Publish**. It reaches Maven Central within about 30
   minutes.
4. **Check it from a clean build:** a throwaway project that uses only `mavenCentral()`
   and the BOM at the new version should resolve the modules and run a pipeline.
5. **Publish the draft GitHub Release,** after editing its notes from `CHANGELOG.md` if
   you like.

**Releasing from your machine** needs the same values as Gradle properties, for example
in `~/.gradle/gradle.properties`:
- `mavenCentralUsername` and `mavenCentralPassword`;
- `signingInMemoryKey` and `signingInMemoryKeyPassword`.

Then tag the commit and publish from it:

```bash
./gradlew createRelease -Prelease.forceVersion=0.1.0-RC1 -Prelease.localOnly
./gradlew publishToMavenCentral --no-configuration-cache
git push origin v0.1.0-RC1   # only once the upload succeeded
```

Artifacts are signed only when `signingInMemoryKey` is set, so
`./gradlew publishToMavenLocal` works without a key, for checking what would be
published. With a key it signs too: each published file then has a `.asc` signature
next to it, which `gpg --verify` can check.

## Public API changes

Each published module except `baton-core` (which exports nothing) and the BOM has its
public signatures recorded in `api/<module>.api`. `./gradlew build` runs `apiCheck`,
which fails when the compiled API differs from the recorded file, listing the changed
signatures. If the change is intended, run `./gradlew apiDump` and commit the updated
file, so every API change is visible in review. In `CHANGELOG.md`, note any change that
removes or alters a signature.
