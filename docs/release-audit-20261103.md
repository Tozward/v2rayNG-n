# Stable native TUN release audit, 2026-11-03

## Scope

Review covers the custom application diff from the fetched 2dust merge base
`57907cf354d9769200ed4717b4430d605abe0952`, native Xray integration from
`7c8dbcb4bbec7f80d16070069e0b2f7bf3aca62c`, and sing-tun dependency changes from
`611d83b7a57b4719be45581eb674942ff561de6c`. The separate core and dependency
repositories retain their own audit and individual patch ledger. Unrelated
official routing/protocol implementations are not rewritten for this release.

Reviewed paths include native configuration/lifecycle, one-reader admission,
root/VPN/proxy-mode precedence, network handover, descriptors, dispatcher/stat
wrappers, Vision framing/readiness, UDP/FakeDNS/source addresses, local-proxy
download routing, self-update integrity/cancellation/installation, geo-file
concurrency, application-list filtering and testpre persistence/import/export.

## Application corrections

| Finding | Correction | Regression |
| --- | --- | --- |
| Running proxy query returned settings instead of the launched listener | Cache a compatible IPv4-loopback HTTP/mixed listener from the successfully launched configuration, invalidate on stop/failure/reload, and acknowledge unavailable/setup states as Unknown. Never silently treat a live daemon as stopped and go direct. | `CoreLocalProxyConfigTest`, device VPN/proxy scenarios |
| Local proxy authentication could be sent in an HTTPS origin request | Metadata uses the secure update client, original requests carry no proxy credentials, and proxy authentication is confined to its exchange. Reject HTTPS-origin 407 requests for proxy credentials after CONNECT. | `UpdateProxyAuthenticationTest`, `HttpUtilProxyAuthenticationTest` |
| Fixed ready APK path could be replaced by a later download | Verified files retain a unique suffix; a returned FileProvider URI is not retargeted to another verified package. Size/digest verification and pending-attempt cleanup remain mandatory. | `UpdateCheckerManagerTest.aLaterVerifiedDownloadCannotReplaceAnEarlierPendingInstall` |
| Unique ready APKs could accumulate after successful updates | Only this package's replacement broadcast starts one finite IO cleanup. Parse ready archives and remove this package's installed/older semantic versions; preserve future/unknown packages, pending downloads and unrelated cache files. | `UpdateCacheCleanupTest` |
| Retired GUI key could become an invalid selection | Offer Xray/HEV/Sing native only; decode preceding `sing` values as `sing_native`, normalize ViewModel input and rollback, preserve other legacy defaults. | `TunBackendTest`, `SettingsViewModelTest`, UI/device tests |

Disabling all compatible local listeners deliberately makes in-app proxied
downloads unavailable while the daemon is running. The app reports failure;
it neither re-enables a disabled listener nor switches nodes or silently bypasses
the VPN. Ordinary settings keep their existing default listener. Custom listeners
need an address, transport and credentials compatible with the existing client.

The remaining old Sing enum/JNI controller is retained for internal compatibility
and controlled comparisons. It is not selectable through the GUI. Removing that
binding entirely is a separate coordinated ABI/build change; do not re-expose it
merely because its serialized alias is retained.

## Intentional upstream touches and future synchronization

| Owner | Local contract to preserve when merging |
| --- | --- |
| `CoreServiceManager` / `CoreVpnService` | The daemon owns runtime truth. A listener is advertised only after successful core startup; setup/reload/failure/stop cannot masquerade as a stopped daemon. Port metadata carries no credentials across IPC. |
| `CoreConfigManager.runningHttpProxyPort` | Interpret the launched HTTP/mixed schema, address/auth and raw transport, including Go's case rules. Recheck this parser if upstream changes HTTP-in-SOCKS support, account fields, listener types or transport framing. |
| `HttpUtil` | Two inherited redundant origin `Proxy-Authorization` additions were removed because the existing proxyAuthenticator already supplies proxy authentication. The authentication callback now rejects tunneled origin challenges; custom-header debug logs record names, not secret values. No subscription/header/redirect architecture was replaced. |
| `UpdateCheckerManager` | One cancellation/response lifetime owns each request. Proxy credentials remain lazy and secret, verified APK paths immutable, and route decisions come from daemon acknowledgement. |
| `BootReceiver` | On `MY_PACKAGE_REPLACED` only, own one `goAsync` cleanup and finish its pending result in `finally`. Leave the inherited boot/locked-boot/autostart decision path unchanged. Cache maintenance must not depend on autostart being enabled. |
| Settings | Keep old persisted keys readable. The `sing` alias is a retirement migration, not a new default for HEV/Xray. All enabled locales use the final native label. |
| Native build | One combined Go AAR/runtime, immutable core/dependency pins, all declared ABIs, original HEV source revision and complete native notices. |

These upstream touches are required for the custom update/native feature's
correctness and confidentiality. Prefer an equivalent upstream correction when
available; retain the regression rather than mechanically carrying duplicate
implementations. The dependency's ST-FD/ST-API/ST-PERF ledger remains authoritative
for its separate resource, ownership, vector and jumbo patches.

Update-cache cleanup recognizes only `cache/updates/update-*.apk` and the preceding
`update.apk`. It compares parsed `versionName` with the running installed build,
using the same semantic ordering as update discovery. ABI/distribution offsets
make `versionCode` unsuitable for this purpose. Unknown versions/packages and
future install candidates remain intact. A five-second work budget is checked
between archive parses; no recurring task, startup scan or installation-URI
handoff cleanup is added. Android may evict cache files under storage pressure,
but [its storage guidance](https://developer.android.com/training/data-storage/app-specific#internal-remove-cache)
requires applications to maintain their own cache rather than rely on eviction.

The suspected universal-to-ARM64 downgrade was checked against the current
Gradle mapping. Play Store ABI/universal outputs all use `4,000,000 + base`;
F-Droid uses its separate package and `5,000,000 + 100 * base + ABI offset`, where
offsets range from 0 to 4. A newer base therefore remains installable across ABI
choices. Keep the existing exact ABI/distribution asset selection; do not add
universal-package detection for this disproved version-code issue.

The core's custom SOCKS-UDS protocol also receives negotiation, cancellation,
buffer/EOF and frame-boundary corrections. The dependency removes superseded
internal download-handoff branches; its public API and performance parameters
remain unchanged. Details are in the core audit and dependency ledger.

## Validation method

The release build verifies both distributions, all four ABIs, unit/Kotlin checks,
assembly, lint, native notices, focused native races and descriptors. Disposable
Android24/35 emulators exercise VPN backends and settings touch/keyboard/D-pad
selection, legacy decoding and persistence. A separate opt-in arm64 audit APK has
application ID `com.v2ray.ang.audit.r<Actions run ID>`; it is explicitly excluded from release assets.
It refuses to modify the normal application's storage or run probes with the
wrong UID. Its VPN allowlist includes only shell UID, and its SOCKS fixture binds
loopback and echoes only a reserved synthetic endpoint without dialing it.

The locked rooted Xiaomi 14 Pro can run these bounded tests without unlocking or
reading personal profiles. Core fixtures use independent disposable TUN links
and UID0-only test-prefix policies, not default routes or system DNS. The Android
IPv6 fixture explicitly routes its own prefix in main, where Android's policy
otherwise differs from a Linux CI host. Every temporary rule is removed in
`finally`; interface-owned routes disappear with the closed interface.

Core Vision tests use local TLS fixtures and verify real protocol-ready handoff.
The separate application SOCKS echo test measures framework integration and
generic fallback forwarding, not Vision's optimized throughput. Report those
workloads separately. Probe/process CPU includes the named processes, not an
automatic conversion to electrical energy. The phone is charging and hardware
power-monitor availability is queried rather than assumed. No global
batterystats reset, private app database export or personal-traffic trace is used.

## Boundaries

**Not run:** physical rooted-app mode requiring a new KernelSU grant and global
root-routing rules; this audit does not alter root authorization or risk personal
routes to obtain that result. Pure root/config decisions retain regression coverage.
**Not run:** TalkBack speech and spoken focus order where the disposable image
does not include that service; accessible names/actions/state are inspected.
Mac real-utun and Windows native-device support must not be inferred from
cross-compilation. Record actual platform results with the final release runs.

Two documented upstream behaviors remain outside the custom correction scope:
the pre-existing restart/stop broadcast gap and notification polling when a core
starts/reloads after the screen is already off. Their source comparison shows
they were not introduced by this integration. The audit does not claim that
unmodified upstream code or all possible device/network conditions are defect-free.

## Final validation provenance

Production application sources have not changed since `719aa6deff901432f43d620a05317ad52f0c866b`.
Subsequent commits repair test synchronization, test-process execution, CI compatibility and artifact
isolation only.
The final compiled source is `06b70872ee92f3457e23b537c85735d39ba87497`; its [release validation
run](https://github.com/Tozward/v2rayNG-n/actions/runs/41546863209) includes both distributions and
all declared ABIs. The complete run succeeds, including its build and both emulator jobs.

The earlier accessibility-node clicker could target Compose bounds outside a scroll viewport. It was
replaced with the project's fixed-BOM official Compose test APIs, including real touch injection,
semantic role/selection checks and real keyboard/D-pad key events. The test dependency is
`androidTestImplementation` only. The physical audit uses the official API31+ shell stdin/stdout
interface: `executeShellCommand(String)` does not parse shell expressions. Earlier ART probe
terminations and the broken shell wrapper produced no valid physical application measurements and
are retained locally as failed test-tool attempts. They are not product passes or proof of a TUN
regression.

Each physical audit uses a reserved, run-specific package to avoid different hosted runners' debug
certificates colliding. Earlier scratch packages are stopped and disabled, their VPN app-op reset,
and their files/data retained. No normal application profile is read or replaced by this procedure.
The fixed-target Go probe runs as UID2000; it has no arbitrary URL or node selection. The private
loopback fixture accepts only the reserved synthetic destination and never forwards to the Internet.

The [core
audit](https://github.com/Tozward/Xray-core-socks-uds/blob/fea2b64546ac918637c0a03b947b8c6ce9459e73/docs/release-audit-20261103.md)
records the actual platform checks, native ownership/Vision review, known-vulnerability scan limits
and all matched physical-kernel performance medians. The [dependency patch
ledger](https://github.com/Tozward/sing-tun/blob/2c2f8b289bf652b432506e0a6fe50e6ac6bd1fa2/docs/xray-integration-patches.md)
records separate ST-FD/ST-API/ST-PERF patches and their upstream-first retirement criteria. The
Android integration remains one combined Go runtime rather than a complete sing-box core.

The preceding `f408d0e6` build passed all 212 unit tests in each distribution and both lint tasks.
Its API24 emulator passed the TUN and typed UI tests; the subsequent cache shell check lost its
variable because the emulator action starts each inline line in a separate shell. That check now
runs from `tools/ci/android_backend_smoke.sh` in one shell, with an explicit disposable-emulator
guard. The preceding API35 task failed downloading Google's emulator archive before running any
application check. Those run failures do not waive the final validation gate. The physical audit APK
is from the successful isolated-package job in that preceding run; production and AndroidTest
sources are identical to the final run.

The final ARM64 release library has pinned Go1.27.3/Android/arm64/CGO/`with_sing_tun` metadata and
the audited Xray/sing-tun replacements. Gradle removes non-loadable ELF metadata: the packaged `.so`
hash differs from the AAR, while all 28 allocated ELF sections' contents, addresses, sizes and flags
are identical. The HEV library hash matches the library used in physical-kernel comparisons. Runtime
notices are included. The local verification JSON records both binary hashes and section-level
equivalence.

### Locked physical application results

The isolated Android16/arm64 audit on Xiaomi 14 Pro passes all assertions in 590.4 seconds:
Xray/HEV/native at MTU1280/9000/1500, TCP/UDP/256KiB payload integrity, duplicate starts, restart
and stop, invalid setup recovery, stop during foreground setup, legacy `sing` decoding, and
proxy-only actual listener port/authentication/recovery. The test VPN is stopped; scratch packages
are disabled, their VPN app-op reset, the verified loopback fixture stopped, and no UID0 test policy
remains. Files/data are retained.

The following are one sequential framework-integration measurement per backend at MTU1500 against a
private SOCKS echo, without a cryptographic outbound. Latency uses 500 samples after 20 warmups;
bulk is aggregate bidirectional echo over approximately three seconds. These numbers do not measure
Vision handoff, Internet throughput or a sustained-flow ceiling.

| Backend | P50 latency ms | P95 latency ms | Aggregate MB/s | Idle sampled seconds | Daemon CPU seconds | Idle FD first/last |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| xray | 0.676 | 0.979 | 107.90 | 157.82 | 0.17 | 141/141 |
| hev | 0.759 | 0.965 | 123.43 | 163.11 | 0.09 | 146/148 |
| sing_native | 0.450 | 0.723 | 111.14 | 157.97 | 0.05 | 145/145 |

Each requested idle interval is 180 seconds with speed notification disabled; the table excludes the
settling interval and uses actual first/last sample timestamps. CPU is the daemon's process-wide
user+system time at 100Hz; ADB samples every five seconds. Raw context-switch counters describe the
main thread only, not device wakeups. The combined runtime is reused between backends, so sequential
RSS values are not fresh-start memory comparisons. Native RSS stays at 224684KiB and FDs at145
during its sampled idle window; this finite observation does not establish absence of every
long-lived leak.

The phone remains locked/Dozing and charging. Android returns zero supported hardware power
monitors. No device governor, wake/suspend policy, battery statistics, personal profiles or traffic
history is changed/read. These CPU/latency observations are not electrical-energy or battery-life
measurements. Physical application root mode, real-node WAN/mobile handover and a multi-hour battery
discharge/production soak remain **Not run**; the first would require an additional root-app grant
and global routing changes, and the latter need a separate controlled network/battery experiment.

The intermediate `b04cc849` run passes API35 completely, including actual package replacement and
cache retention. API24 passes both instrumentation tests but cannot exec a standalone `test` binary.
The final CI-only correction invokes that POSIX builtin through `/system/bin/sh`; it neither changes
the application nor bypasses an assertion. Both intermediate release APKs' DEX, native libraries and
runtime assets compare byte-identical. These intermediate runs are not publication sources.

### Publication gate passed

Actions 41546863209 completes successfully at source `06b70872`. Both distributions pass 212 tests with
zero failures, errors or skips, compile/debug assembly and lint; all four native ABIs and ten signed
release APKs are produced. API24 and API35 each pass both TUN/UI instrumentation tests, real
touch/Enter/Space/D-pad selection and reopened persistence, plus actual same-package installation
replacement: installed-version APK cache is reclaimed, while pending, unknown and unrelated cache
files remain. No test was disabled to obtain this result.

The final ARM64 release APK's DEX, native libraries and runtime assets are byte-identical to the
corresponding earlier audited signed build (28 entries), and production/AndroidTest sources match
the isolated physical audit's source. Its SHA256 is
`a8abf54457badb379e0d88732d0a64280917ce15dd155d11e198e3da149c434e`. The formal release is
`v2.3.15.4`; publication reuses the successful run's signed APKs without recompilation.
Audit/emulator APKs are explicitly excluded. The standalone core is
[v26.10.31-native-tun.1](https://github.com/Tozward/Xray-core-socks-uds/releases/tag/v26.10.31-native-tun.1).
Local raw reports and verification JSON are retained under
`artifacts/native-sing-tun/final-release/`.
