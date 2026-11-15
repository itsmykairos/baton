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

- `X.Y.Z-SNAPSHOT` between releases. The Release workflow refuses snapshots.
- `X.Y.Z-RC<n>` for release candidates, such as `0.1.0-RC1`. Maven and Gradle both sort
  a candidate before its final version.
- `X.Y.Z` for the final release.

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

1. **On `main`, set the release version** in `gradle.properties`, for example
   `0.1.0-RC1` or `0.1.0`.
   - For a final release, replace "unreleased" in `CHANGELOG.md` with the date.
   - Make the install snippets in the README, `docs/getting-started.md` and
     `docs/spring-boot.md` use the new version.
   - Work from a phase branch reaches `main` through a pull request merged with **Create
     a merge commit**. Squash and rebase merges rewrite commit ids, which would break
     `.git-blame-ignore-revs`.
2. **Check:** `./gradlew build`, which includes `apiCheck`, then commit and push.
3. **Run the Release workflow** from the Actions tab, on `main`.
   - It first checks that it runs on `main`, that the version isn't a snapshot, and that
     all four secrets are set. Then it builds, signs and uploads a deployment to the
     Central Portal.
4. **Publish:** check the deployment on central.sonatype.com (every module listed,
   validation passed) and press **Publish**. It reaches Maven Central within about 30
   minutes.
5. **Check it from a clean build:** a throwaway project that uses only `mavenCentral()`
   and the BOM at the new version should resolve the modules and run a pipeline.
6. **Tag it, and create the GitHub Release:**
   ```bash
   git tag v0.1.0-RC1 && git push origin v0.1.0-RC1
   gh release create v0.1.0-RC1 --prerelease --title "Baton 0.1.0-RC1" --notes-file <notes>
   ```
   Leave out `--prerelease` for a final release.
7. **Set the next version** on `main`:
   - after a candidate, back to the snapshot of the same version (`0.1.0-SNAPSHOT`);
   - after a final release, the next minor (`0.2.0-SNAPSHOT`), with a new "unreleased"
     section in `CHANGELOG.md`.

**Releasing from your machine** needs the same values as Gradle properties, for example
in `~/.gradle/gradle.properties`:
- `mavenCentralUsername` and `mavenCentralPassword`;
- `signingInMemoryKey` and `signingInMemoryKeyPassword`.

Then run `./gradlew publishToMavenCentral --no-configuration-cache`.

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
