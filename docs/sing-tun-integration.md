# Experimental Sing TUN backends

## Source and scope

This branch integrates the new Go TCP/IP stack from sing-box 1.15, not the
sing-box core, configuration system, routing engine, protocols, or service.
The optional VPN choices include the existing SOCKS-backed Sing TUN and a separate
native Sing TUN. Existing HEV/Xray choices and their persisted legacy preference
remain compatible; upgrading does not select either experimental backend.

| Saved key | Backend | Local SOCKS bridge |
| --- | --- | --- |
| `xray` | Existing Xray TUN | No |
| `hev` | Existing HEV TUN | Yes |
| `sing` | Existing Sing TUN (experimental) | Yes |
| `sing_native` | Sing native TUN (experimental) | No |

The saved `sing` key keeps its existing behavior. No preference migration or
default change selects `sing_native`. Proxy-only and root mode retain their
existing paths; this choice controls VPN mode only.
Root mode takes priority over the saved VPN setting, matching the launcher.
Core TUN generation and process metadata require VPN mode with root mode disabled;
root/proxy mode keeps its SOCKS path even when `sing_native` is saved. An explicit
custom `sing-tun` outside that VPN mode is rejected before core startup because
those services supply no Android TUN descriptor.

Audited source:

| Component | Revision |
| --- | --- |
| sing-box reference | `v1.15.0-alpha.13`, `824dbcfb4dd0a95921840b1737b950cde590241e` |
| sing-tun upstream | `611d83b7a57b4719be45581eb674942ff561de6c` |
| sing-tun integration fork | `3855ec8dcf9cb1eb3036d334b1ecd5be89b14334` |
| sing | `03a6cce64bf744023af9e2691fb11afd405b9061` |
| AndroidLibXrayLite | unchanged gitlink `2ffe5f794a32ba15b122e177b5acffc6f11c4959` |
| Xray-core integration fork | `eb5310947c46520eeba7981b8711feb5f8ed9b1a` |
| Xray upstream baseline for the native integration | `7c8dbcb4bbec7f80d16070069e0b2f7bf3aca62c` |

The SagerNet libraries use GPLv3, compatible with this GPLv3 project. Their
module versions and checksums are pinned in `native/singtun/go.mod` and `go.sum`.
The new libraries' copyright notices and the GPLv3 text are bundled as an APK asset.
The 1.15 reference is an alpha release. Upstream performance claims are not
evidence that this Android integration outperforms HEV or consumes less energy.

## SOCKS-backed Sing TUN path

```
Android VpnService fd -> sing-tun NewGo -> authenticated loopback SOCKS5
                     -> existing Xray routing/DNS/outbound -> selected node
```

`NewGo` selects the new stack directly. The adapter does not select the older
system/mixed/gVisor stacks and does not enable the `with_gvisor` build tag.
Xray's own existing dependencies are retained. Go module checksums include
upstream optional/test dependencies; a module's presence does not mean that its
code or background workers run in the APK.
An Android/arm64 package dependency listing contains neither `sagernet/gvisor`
nor `sagernet/sing-box`. Shared Go module selection raises `x/exp` (the existing
Xray path uses its `constraints` and `slices` packages) and some tool-only dependencies; it
also selects `x/time` 0.15, which is not imported by the Android package graph.

The SOCKS bridge retains Xray's inbound metadata, FakeDNS, sniffing, DNS routing,
and outbound policy. It adds loopback socket/copy costs.
After the SOCKS handshake, TCP sockets use `GoConn.Splice` and UDP sockets use
`UDPNatConn.Splice` with sing's SOCKS packet offload codec. This hands socket I/O
to the new stack's event loop instead of parking a pair of copying goroutines
per flow. The UDP association's control TCP connection stays alive until that
flow closes. Unsupported sockets/platforms fall back to buffered forwarding.
The adapter's dialer accepts loopback addresses only. Remote destinations occur
only in SOCKS requests. TCP/UDP are supported; ICMP forwarding is not supplied
by SOCKS5, as with the existing HEV path.

Android owns addresses, routes, per-app selection, permissions, and the original
descriptor. The adapter duplicates that descriptor with close-on-exec, uses
nonblocking I/O, and owns only the duplicate. External-configuration mode keeps
sing-tun from undoing Android networking on close. GSO, automatic routes, DNS
hijacking, and kernel configuration are disabled. Android's VpnService fd has no
virtio network header, so upstream Linux GSO benchmark results do not transfer
directly to this implementation.

## Native Sing TUN path

```
Android VpnService fd -> Xray sing-tun inbound -> sing-tun NewGo
                     -> Xray TUN handler/dispatcher -> selected outbound
```

`CoreConfigManager` uses the existing `tun` routing tag with portless protocol
`sing-tun`. Its generated settings contain `name`, `mtu`, `userLevel`, `address`
and `tcpLocalJumbo`. Addresses are the Android client IPv4 `/30` prefix and, when
enabled, IPv6 `/126` prefix. `tcpLocalJumbo` is enabled for these local VPN replies;
the SOCKS-backed Sing controller enables the same stack option. The GUI MTU still
controls the VPN interface and negotiated MSS. Eligible plain Linux/Android TUN
queues may coalesce local TCP payloads up to 61,440 bytes, with normal checksums
and no virtio/GSO metadata. Loss, retransmission, validated packet-too-big or a
rejected oversized write disables aggregation for that flow. Other addresses,
platforms and UDP keep their existing packet sizing. The core CLI option defaults
to false, and explicit custom inbound settings, including false or an absent
option, are preserved. The core also accepts `gso` and `multiQueue`; the app leaves
them disabled.
It retains the existing sniffing, route-only, DNS/FakeDNS, routing and process
metadata path. Native mode does not force a local SOCKS listener, and
`CoreVpnService` starts no separate tun2socks controller for it.

The daemon passes the Android descriptor through the existing core startup API.
Android owns the original descriptor; the core owns its duplicate and closes it
before the service releases the original. Android still configures routes,
addresses, DNS, app selection and outbound socket protection. The native core
does not configure host routing or run another Android service.

TCP initially uses `GoConn.Read` and a borrowed `WriteVector` batch, which include
copies into existing retransmission storage. Each raw direction is handed over
independently: download waits for protocol and prefetched TLS/padding data to
drain; upload waits for a successful final Vision command-2 write and header
flush. Sniff/timed-read caches must be empty. `SpliceDownload` and `SpliceUpload`
then connect native storage directly to the raw socket without an Xray buffer
conversion on those directions. EOF and half-close are independent, and the
original outbound socket remains owned by Xray. UDP enters the same dispatcher with
per-destination routing and FakeDNS reply restoration. There is no added SOCKS
session or forwarding pipe in this path and no end-to-end zero-copy claim.

`GoConn.Splice` and the directional APIs are engine-owned socket I/O, not Linux
`splice(2)`. Existing HEV and SOCKS-backed Sing choices remain available for
comparison, including Xray's existing TCP kernel-splice path. The
[matched Linux measurements](https://github.com/Tozward/Xray-core-socks-uds/blob/43050801/docs/native-sing-tun.md#verified-bidirectional-handoff-and-local-aggregation)
cover 51 cases and 153 uninstrumented rows. At matched MTU9000, native
bidirectional handoff with local aggregation improves duplex throughput and CPU
against HEV in that run. Vision download alone is close to SOCKS+jumbo and its CPU
is slightly higher than HEV. These are Linux controls; Android energy and WAN
performance require target-device measurements.

In a custom VPN configuration, one explicit `tun` or `sing-tun` inbound takes
precedence over the saved backend and is preserved. If neither exists, a core
TUN is added only for a selected core backend. More than one core TUN inbound is
rejected before core startup so the descriptor has one reader. The daemon uses
the resolved config choice for both descriptor passing and external-reader
selection, without changing the saved preference. A reader change during an
in-place network reload requires a full VPN restart.

The combined AAR is built with `with_sing_tun`; the core fork and dependency
replacement are pinned in `native/singtun/go.mod`/`go.sum`. Go does not inherit a
dependency module's replacements, so the Android root module declares both
forks. The APK contains GPL-licensed SagerNet code and its notices whether either
experimental choice is selected. See the core fork's
[native integration guide](https://github.com/Tozward/Xray-core-socks-uds/blob/43050801/docs/native-sing-tun.md)
for the dependency patch ledger, socket ownership and update procedure.

## Lifecycle and packaging

Setup and shutdown run in the VPN service's owned IO scopes. Stop first blocks
new setup, cancels/joins setup, closes the native stack and forwarding sockets,
waits for relay handlers, then releases the original descriptor after core
shutdown. Native start/stop/reset are serialized. Failed startup releases its
duplicate and stack. Network handover resets stale native flow/NAT state after
Xray replaces its local listener. No periodic Java polling, JNI packet callback,
or additional standalone service is introduced. Unselected Sing TUN creates no
stack, forwarding sockets, or maintenance workers.

Both the existing Xray wrapper and this adapter are bound in one gomobile
invocation and share one `libgojni.so`/Go runtime per ABI. The upstream submodule
is not modified. CI checks both Java bindings and all four native ABIs, plus
the existing HEV JNI and root executable payloads in both distributions.

## Validation

Native tests cover authenticated SOCKS TCP/UDP forwarding, exact destination and
payload preservation, IPv4/IPv6, DNS-port UDP, MTU validation, cancellation during
a stalled handshake, idempotent stop, and refusal to dial remote addresses.
The disposable Linux CI runner additionally exercises a real kernel TUN fd,
network reset, stop/restart, duplicate start rejection, and caller fd ownership.
JVM regressions cover legacy backend preference mapping, explicit selection,
persisted selection/error rollback, native prefixes/settings, custom TUN
preservation/duplicate rejection and reload-reader decisions. The Android smoke
scenario exercises all four choices, TCP/UDP, duplicate start, restart, setup
failure, repeated stop, stop during setup and explicit
custom TUN overrides. CI also runs the full Play Store and F-Droid test/assembly
matrix; configured scenarios are not runtime evidence until their run passes.

**Not run / known limitation:** stop/revoke racing a pending shared service
restart. The pre-existing command receiver is unregistered during teardown,
before the delayed replacement starts, so an explicit stop in that interval can
miss the pending restart. This task does not change that restart mechanism.

**Not run:** physical rooted-Android validation with `sing_native` saved and the
VPN setting still enabled: root SOCKS startup, duplicate start, setup failure,
restart, repeated stop and cleanup. The configuration decision has JVM regression
coverage; it does not establish rooted-device lifecycle behavior.

Android service/UI runtime checks and comparative throughput, CPU, wakeup,
memory, and energy measurements must be reported separately from build checks.
Keep the backend experimental until those checks pass. Do not infer energy
savings from a successful transfer or synthetic throughput benchmark.

## Candidate .2 verification

Candidate `2.3.15.3-native-sing-tun.2` / versionCode766 is built from
`04845fb7a4a1d86510cc92fc8aee8cfeff0599d3` in
[run 41449202010](https://github.com/Tozward/v2rayNG-n/actions/runs/41449202010).
Both distributions pass unit tests, Kotlin compilation, assembly and lint;
the combined AAR and signed APKs include all four declared ABIs. Android API24
and API35 emulator checks pass TCP/UDP VPN traffic at MTU1280/1500/9000.
The Playstore arm64 APK is 32933998 bytes, 26663 bytes larger than candidate .1.
Its SHA256 is `8c11616c9fb5b2fcc94fbddc8247e86c09f4887918a52b87f75d62c0ca7aa5e4`.

The runtime core pin remains `eb5310947c46520eeba7981b8711feb5f8ed9b1a`.
Core verification `cdd71da026a0deb6ef0d5130d266a8218979c724` adds only test fixes,
with identical production source; documentation records the resulting evidence.
No AAR/APK rebuild is needed for those test/documentation-only changes.
Xiaomi 14 Pro Android16 throughput and battery use have not been measured by
this work. Existing backend preferences are retained; select Sing native TUN
and reconnect the VPN to exercise the new path.
