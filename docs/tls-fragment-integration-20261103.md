# Bounded TLS fragmentation compatibility integration

The user approved integrating the bounded correction after the preconnection
study. Application 2.3.15.6 is prepared as a candidate; this does not publish a
new formal release or replace the existing 2.3.15.5 release assets.

## Exact dependency boundary

- Core: `93e7cbb2fc8dd9693b2f523038f78368c1e12aa6`, pseudo-version
  `v0.0.0-20261103151910-93e7cbb2fc8d`.
- Core module checksum: `h1:anR1PR5bu27XhKWgwajMlXc7DzKDFWmNhNLeclDbtLo=`.
- Sing remains `2c2f8b289bf652b432506e0a6fe50e6ac6bd1fa2`.
- AndroidLibXrayLite and HEV source pins, runtime settings, configuration
  generation and GUI are unchanged. No other module is upgraded.

The core's only production modification from the preceding pin is five added
lines in SniffTLS: an incomplete plaintext TLS record whose declared length is
at most 16384 uses the existing NeedMoreData result. Larger declarations keep
the previous classification. This preserves sniffing across multiple reads
without a larger buffer, extra parser or new native API.

The correction affects shared TCP sniffing, including HEV/local proxy entry
points. It does not alter preconnection pooling, Vision framing, socket
handoff, DNS choices, QUIC parsing or the Sing engine. It does not reassemble a
ClientHello spanning distinct TLS records. Legitimate additional fragments
require more of the existing reading/timer work; zero cost is not claimed.

## Upstream maintenance

Core [XR-COMPAT-001](https://github.com/Tozward/Xray-core-socks-uds/blob/93e7cbb2fc8dd9693b2f523038f78368c1e12aa6/docs/upstream-compatibility-patches.md)
records user authorization, exact modified code, bounds, regression names and
retirement conditions. When upstream provides an equivalent correction, prefer
it after those regressions pass, record its revision and remove the local hunk.
The dependency's independent ST-FD/ST-API/ST-PERF ledger remains unchanged.

## Source verification

The committed source passes focused three-repetition race tests for the TLS
header/prefix/length classifications, malformed input, EOF, stall/cancellation,
and real Go ClientHello 32/128-byte chunks through TimeoutWrapperReader. The
seven-package native race suite and scoped vet also pass locally.

A fresh Linux/arm64 binary compiled directly from the integrated source,
without overlays, passes 20 strict phone repetitions: 80 original-stack and 80
native-handoff connections. Every response requires the sniffed domain to
select the Vision outbound; the default outbound rejects traffic. Temporary
UID0 test routes are removed after the test. These are synthetic routing
regressions, not evidence that every intermittent browser delay is eliminated.

The native core cache key includes the exact application module files. The
new pin requires a new combined AAR and APK build; previous 2.3.15.5 APKs are
not reused. Candidate build and installed-artifact evidence are recorded after
their own checks finish.

## Candidate artifact verification

[Application build41663427972](https://github.com/Tozward/v2rayNG-n/actions/runs/41663427972),
source `fd57fad7e66668416975acc2913de960c4a48a9e`, passed both distributions'
218 unit tests (zero failures, errors and skips), compilation, debug/release
assembly, lint, four-ABI native-input checks and Android API24/API35 VPN
instrumentation. Formal release creation was not invoked.

The Playstore arm64 APK is32928570 bytes, SHA256
`96419d07084afb26c7d1ffabc14e14da8229993786732d2d66087fffa244680a`.
Its embedded Go build information reports the exact core93e7cbb2 pin/checksum,
unchanged Sing2c2f8b28 pin, GOOSandroid, GOARCHarm64 and with_sing_tun build tag.
The native-library names match the preceding candidate. This APK is736 bytes
larger than the preceding Playstore arm64 candidate; no new dependency is added.

Core verification source705e3365 changes only tests, CI and documentation from
the pinned93e7cbb2. All production Go source and module files compare equal.
[Core run41665416255](https://github.com/Tozward/Xray-core-socks-uds/actions/runs/41665416255)
passed Linux (including real-TUN race tests, genuine protocol controls and the
existing benchmark), Windows and formatting. Its hosted macOS job was still
queued at this record; local macOS ordinary/native builds, default/tagged race
regressions and scoped vet passed. The pending hosted job is not reported as a
completed CI check.

The first Linux job41662649266 exposed concurrent accesses to the unchanged
Vision filter counter. Source comparison matches upstream7c8dbcb4, but a pure
official macOS control did not independently reproduce that runtime race. The
new routing component regression therefore uses verified TLS/direct forwarding
under race detection, while genuine Vision remains a mandatory separate
non-race protocol control. This does not claim a race-free upstream Vision
implementation; no additional Vision repair was included in this approval.

Fresh phone checks of these separated controls pass160 further connections.
The phone continues to run its original2.3.15.5 application and VPN process;
the candidate was not installed and no node or user setting was changed. All
disposable interfaces and priority9998 test rules were removed. The public
latest release remains2.3.15.5.

Only this evidence section was appended after the candidate build. Executable
sources and module locks remain identical to that build; no rebuild is needed
for this documentation addition.

## Release readiness and per-app policy decision (2026-11-04)

The previously pending hosted macOS job in core run41665416255 completed
successfully. All required jobs in that workflow are now green. Application
executable sources remain identical to successful build41663427972.

Android resolves VpnService per-app package lists to UID ranges when a VPN is
established. Uninstall/reinstall can change a selected package's UID without
changing its persisted selection; restarting the VPN reapplies the selection.
See https://developer.android.com/develop/connectivity/vpn#per-app .
An automatic package-event refresh was considered, but the user explicitly
chose manual restart over maintaining an additional event listener. Its
uncommitted prototype was withdrawn in full; no receiver, worker, polling,
per-app behavior change or new dependency is included in this release.
