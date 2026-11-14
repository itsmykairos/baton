# Releasing

Artifacts are published to Maven Central under `io.github.kairos-tools`, through the
[Central Portal](https://central.sonatype.com), by the
`com.vanniktech.maven.publish` Gradle plugin.

**Published modules**
- `baton-bom`, `baton-api`, `baton-core`, `baton-testkit`;
- `baton-micrometer`, `baton-opentelemetry`, `baton-slf4j`, `baton-visualize`;
- `baton-spring-boot-starter`.

The examples, benchmarks and integration tests are never published.

## One-time setup

1. **Namespace.** Create the GitHub organization `kairos-tools`. Sign in to the Central
   Portal, add the namespace `io.github.kairos-tools`, and verify it as the portal asks:
   create a temporary public repository with the given name in that organization.
2. **Portal token.** Generate a user token in the Central Portal (Account → Generate
   User Token).
3. **Signing key.** Create a GPG key and publish its public key to a key server, for
   example `keys.openpgp.org`. Then export the private key in ASCII armor:
   `gpg --armor --export-secret-keys <KEY_ID>`.
4. **Repository secrets.** Add these on GitHub (Settings → Secrets → Actions):
   - `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD`: the portal token;
   - `SIGNING_KEY`: the armored private key;
   - `SIGNING_KEY_PASSWORD`: its passphrase.

## Cutting a release

1. Set `version` in `gradle.properties`, for example `0.1.0`.
2. In `CHANGELOG.md`, replace "unreleased" with the date.
3. Run `./gradlew build`. This includes `apiCheck`.
4. Commit and push.
5. Run the **Release** workflow from the Actions tab. It builds, signs and uploads a
   deployment to the Central Portal.
6. Check the deployment on central.sonatype.com and press **Publish**. It reaches Maven
   Central within about 30 minutes.
7. Tag the release (`v0.1.0`), then set the next version, for example `0.2.0-SNAPSHOT`.

**Releasing from your machine** needs the same values as Gradle properties, for example
in `~/.gradle/gradle.properties`:
- `mavenCentralUsername` and `mavenCentralPassword`;
- `signingInMemoryKey` and `signingInMemoryKeyPassword`.

Then run `./gradlew publishToMavenCentral --no-configuration-cache`. Artifacts are
signed only when `signingInMemoryKey` is set, so `./gradlew publishToMavenLocal` works
without a key, for checking what would be published.

## Public API changes

Each published module except `baton-core` (which exports nothing) and the BOM has its
public signatures recorded in `api/<module>.api`. `./gradlew build` runs `apiCheck`,
which fails when the compiled API differs from the recorded file, listing the changed
signatures. If the change is intended, run `./gradlew apiDump` and commit the updated
file, so every API change is visible in review. In `CHANGELOG.md`, note any change that
removes or alters a signature.
