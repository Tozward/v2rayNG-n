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
| Settings | Keep old persisted keys readable. The `sing` alias is a retirement migration, not a new default for HEV/Xray. All enabled locales use the final native label. |
| Native build | One combined Go AAR/runtime, immutable core/dependency pins, all declared ABIs, original HEV source revision and complete native notices. |

These upstream touches are required for the custom update/native feature's
correctness and confidentiality. Prefer an equivalent upstream correction when
available; retain the regression rather than mechanically carrying duplicate
implementations. The dependency's ST-FD/ST-API/ST-PERF ledger remains authoritative
for its separate resource, ownership, vector and jumbo patches.

The core's custom SOCKS-UDS protocol also receives negotiation, cancellation,
buffer/EOF and frame-boundary corrections. The dependency removes superseded
internal download-handoff branches; its public API and performance parameters
remain unchanged. Details are in the core audit and dependency ledger.

## Validation method

The release build verifies both distributions, all four ABIs, unit/Kotlin checks,
assembly, lint, native notices, focused native races and descriptors. Disposable
Android24/35 emulators exercise VPN backends and settings touch/keyboard/D-pad
selection, legacy decoding and persistence. A separate opt-in arm64 audit APK has
application ID `com.v2ray.ang.audit`; it is explicitly excluded from release assets.
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
