# Distribution: build, sign and sideload through Light's pipeline

The Light SDK now ships the whole path from a tool repo to a tool on a phone: a containerised
**builder** (`builder/`) that extracts an allowlisted slice of the repo and produces an unsigned
APK plus a `recipe.json`; a **signer** (`signer/`) that writes a trust statement into the APK and
signs it with a per-tool key and Light's source stamp; a device-side **install policy**
(`sdk/trust/`) that decides whether an APK may be installed; and the **Tool Manager** sideloading
route for developers (`docs/sideloading/`). LightDrome adopts that pipeline as-is rather than
carrying its own release mechanics.

## What the pipeline sees of this repo

Only three things reach Light's builder: `tool/lighttool.toml`, `tool/build.gradle.kts` and
`tool/src/main/**` (Kotlin, `res/`, `assets/`). Everything else in the fork — the ADRs, the
`CONTEXT.md` glossary, and **the whole `tool/src/test/` tree** — stays local. Consequences:

- **Tests run here, not in Light's build.** `assembleRelease` never resolves `testImplementation`,
  so the test-only dependencies (`ktor-client-mock`, `kotlinx-coroutines-test`) are fine under
  the builder's `--offline` run even though its image never warmed them. The plugin's
  dependency sweep tolerates unresolvable configurations and only checks coordinates against the
  allowlist, which both satisfy via the `io.ktor` / `org.jetbrains.kotlinx` prefixes.
- **Policy is checked locally before pushing.** `python3 -m lightbuilder prepare` from `builder/`
  against the repo root reproduces the extraction the builder performs (see `builder/README.md`);
  a release candidate must pass it with no `error.json`.
- **The tool root may hold nothing but the two files above.** In particular, a
  `stamp-cert-sha256` file at `tool/` is a forged source-stamp marker and aborts the build.

## Identity, versions and signing

- `id = "io.github.duncangj.lightdrome"` is the permanent identity: the signer's registry maps it to
  one APK signing key, and the install policy refuses a second key for the same id. It never changes.
- `versionCode` increases on **every** published build; the trust statement and the manifest must
  agree, and the trust bundle may raise a `minVersionCode` floor that strands anything older.
  `versionName` stays strict `major.minor.patch`.
- The release signing config in `tool/build.gradle.kts` is the shared SDK dev keystore, for local
  installs only. The builder passes `-DlightSdk.unsigned=true` and the plugin clears it; Light's
  signer adds the real signatures afterwards. Nothing in this repo holds a production key.
- Release builds stay minified (R8). The builder's reproducibility is "diffs are inspectable",
  not bit-exact, and R8 does not change that class.

## Developer distribution

The `uploadTool` Gradle task that arrived with the SDK pushes the debug APK over the LAN into the
device's APK inbox and waits for the install:

```sh
./gradlew :tool:uploadTool -Pdevice.ip=<lp3-ip> -Pdevice.token=<hex key>
# emulator: adb forward tcp:54449 tcp:54449, then omit device.ip
```

One-time setup is the Tool Manager's developer branch: upload the dev keystore's SHA-256
(`docs/sideloading/light_debug_signing_key.txt`) as a Developer Key, and a 64-hex-character HMAC
key under Authentication. Debug installs are therefore trusted *only* on the developer's own phone;
anyone else gets the tool through the signed route above.

## Considered and not adopted

- **`tool-manager-provider` capability.** It lets a tool expose directories to the browser-based
  Tool Manager. LightDrome moves no files in either direction — the library is streamed from the
  user's Navidrome and the [[Rolling cache]] is opportunistic and private — so there is nothing to
  offer. Revisit if **pinned downloads** (CONTEXT.md) arrive and users want to seed them from a PC.
- **A LightDrome-specific CI release job.** The upstream `pr-check.yml` already runs the plugin and
  module checks; publishing is Light's job once the tool is submitted.
