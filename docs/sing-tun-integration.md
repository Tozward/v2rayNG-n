# Sing native TUN integration

## Source and scope

This branch integrates the new Go TCP/IP stack from sing-box 1.15, not the
sing-box core, configuration system, routing engine, protocols, or service.
The VPN choices are Xray, HEV and Sing native TUN. Existing HEV/Xray choices
and their persisted legacy preference remain compatible. The retired Sing SOCKS
selection key upgrades to Sing native TUN; upgrading does not change HEV/Xray.

| Saved key | Backend | Local SOCKS bridge |
| --- | --- | --- |
| `xray` | Existing Xray TUN | No |
| `hev` | Existing HEV TUN | Yes |
| `sing` | Retired selection alias for Sing native TUN | No |
| `sing_native` | Sing native TUN | No |

The saved `sing` key remains readable and resolves to `sing_native`; it is not
a fourth GUI choice. The old enum/controller is retained for internal compatibility
and controlled comparisons, not for new selection. Proxy-only and root mode retain their
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
| sing-tun integration fork | `2c2f8b289bf652b432506e0a6fe50e6ac6bd1fa2` |
| sing | `03a6cce64bf744023af9e2691fb11afd405b9061` |
| AndroidLibXrayLite | unchanged gitlink `2ffe5f794a32ba15b122e177b5acffc6f11c4959` |
| Xray-core integration fork | `e965fbf73b280483598d7d2ad4b406c00464fcbc` |
| Xray upstream baseline for the native integration | `7c8dbcb4bbec7f80d16070069e0b2f7bf3aca62c` |

The SagerNet libraries use GPLv3, compatible with this GPLv3 project. Their
module versions and checksums are pinned in `native/singtun/go.mod` and `go.sum`.
The new libraries' copyright notices and the GPLv3 text are bundled as an APK asset.
The 1.15 reference is an alpha release. Upstream performance claims are not
evidence that this Android integration outperforms HEV or consumes less energy.

## Retired SOCKS-backed Sing TUN implementation

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
The final release audit records actual runtime checks separately from the GUI
label. Do not infer energy savings from a successful transfer or synthetic
throughput benchmark. See `release-audit-20261103.md` for the release evidence
and intentional upstream synchronization boundaries.

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

## Candidate .3 audit corrections

Candidate `2.3.15.3-native-sing-tun.3` / versionCode767 includes two re-audit
corrections before production testing. New native upload handoff rejects TCP
write masks and unknown transports, preserving the ordinary writer's behavior;
a real fragment mask configured for packets 2-20 reproduces the former bypass.
The dependency also cancels and reclaims a blocked directional upload when
`CloseRead` follows native FIN/ACK completion (ST-API-004). Android's existing
context cancellation uses `Close`, which already handles the whole stream.
Existing Vision readiness, download unwrapping, backend preferences, GUI MTU
and TCP kernel-splice behavior are unchanged by these corrections.

The core runtime pin is `f9a1f8e4a9cdf7dc0a939b7732f339550bc8e0cb`; dependency pin
`395f9425830a9991450bd0b26b54ecd5be80e647`. The core benchmark now additionally
compares freedom with the same verified application TLS1.3 fixture as Vision.
The historical plain-TCP freedom column is not a matched estimate of Vision's
protocol or native-integration overhead.

### Candidate .3 validation and matched TLS analysis

[Android build 41472886839](https://github.com/Tozward/v2rayNG-n/actions/runs/41472886839)
passes at source `32bda978aceb88fac08dec81933ea053c01bc5f0`: native adapter
race/vet and fd checks, four-ABI combined AAR, both distributions' unit tests,
Kotlin compilation, debug/release assembly and lint, plus Android API24/API35
VPN TCP/UDP checks at MTU1280/1500/9000. No physical phone was operated by this audit.

[Core 41472283828](https://github.com/Tozward/Xray-core-socks-uds/actions/runs/41472283828)
and [dependency 41471918840](https://github.com/Tozward/sing-tun/actions/runs/41471918840)
pass their Linux and Windows jobs. The macOS cloud jobs were not acquired by a
hosted runner and were cancelled for arm64 capacity constraints, so those cloud
jobs did not pass. Local macOS arm64 tests using the final dependency pass the
core's complete focused tagged race suite and the dependency's directional,
FD, jumbo/vector and retained-upload-close race regressions.

[Matched TLS control 41473742626](https://github.com/Tozward/Xray-core-socks-uds/actions/runs/41473742626)
passes 9 cases / 27 uninstrumented rows. On one EPYC 9V74 runner, MTU9000 / GOMAXPROCS=4,
the plain/direct-TLS/Vision duplex medians are 1927.61/1328.02/1333.56 MB/s. Direct
TLS/Vision whole-process CPU is 18.18/22.00ms per 8MiB duplex operation. Their
throughput ranges overlap; Vision is not established as faster. Matched one-way
Vision upload/download remain 19.1% / 6.8% slower. The benchmark includes both
application TLS endpoints and a simulated local Vision server; these CPU totals
are not Android VPN-process power use. The runner differs from the earlier
EPYC 7763 matrix, so the absolute values are not a cross-version speedup.

Both native directions carry the full bulk data. Residual upload copying is in
the fixture's ordinary Vision server/freedom path, a separate optimization
candidate. The current client integration does not need an architecture rewrite
to explain the old unmatched plain-TCP/Vision table. See the
[complete audit](https://github.com/Tozward/Xray-core-socks-uds/blob/e3a337d9a4e86d9b0ad761ab2571c78705155444/docs/native-sing-tun.md#production-test-audit-follow-up).

## Candidate .4 follow-up

Candidate `2.3.15.3-native-sing-tun.4` / versionCode768 fixes the optional native
handoff's unsupported Unix datagram boundary. An unconnected Unix datagram has
no remote address; both directional entry points now decline without touching
the native stream, closing the original descriptor or consuming queued data.
The preceding implementation reproduces a nil-address panic. Connected Unix
datagrams continue to decline. Normal TCP and connected Unix stream eligibility,
Vision framing/readiness, write masks, statistics and backend defaults retain
their existing paths.

The dependency is `9af120bbf52457f6c946e4d63587c2bfed57e07e`; its splice read
batch is 128KiB (ST-PERF-002). The TCP window, transmit capacity and shared-memory
budgets retain their preceding values. A new socket regression covers reads
above 64KiB, a smaller 32KiB capacity, backpressure/resume, EOF and slab release.
Both Sing native directional and SOCKS-backed full handoff use the new limit;
HEV and ordinary GoConn reads/writes are unchanged.

[Batch experiment 41500278151](https://github.com/Tozward/Xray-core-socks-uds/actions/runs/41500278151)
passes 195 samples at MTU9000 on one Linux Intel Xeon Platinum8573C runner,
using five randomized repetitions and identical TLS workloads. Median paired
changes from 64KiB are +9.93% plain TCP download throughput, -3.82% whole-fixture
Vision download CPU, and +6.97% throughput for four concurrent Vision downloads.
These are controlled Linux results, not Android battery measurements. Gains
depend on workload: single direct-TLS download has a -2.47% paired throughput
median, while small-request tail latency and observed scheduling gaps are mixed.
The larger 256KiB candidate has repeatable latency tradeoffs and is not selected.
See the [full comparison](https://github.com/Tozward/Xray-core-socks-uds/blob/d276f910aabb31c1303e56293ca108c17d9b9aa7/docs/native-sing-tun.md#follow-up-bounded-socket-read-batches).

The benchmark's remaining Vision server upload copier belongs to the remote
node. Changing the local simulated server would not improve a phone connected
to an unchanged official Xray node, so this candidate leaves server forwarding
unchanged.

### Candidate .4 validation

[Android build 41502856334](https://github.com/Tozward/v2rayNG-n/actions/runs/41502856334)
passes at source `7f5ea105a4cadb51ff56871bcf27ed052c8a7b18`: adapter races/vet,
FD repeats, all four native ABIs, both distributions' unit tests, Kotlin compilation,
assembly/lint and signed release packages. Android API24 and API35 VPN TCP/UDP
checks pass at MTU1280/1500/9000. No physical phone was operated. The Playstore
arm64 APK is 32934142 bytes, 612 bytes larger than .3, with SHA256
`029fc296c05ed591e0e84ac3b7edfc5ba4b8c205d88cd18d55ac4d1864afd5c8`.

[Core 41502724582](https://github.com/Tozward/Xray-core-socks-uds/actions/runs/41502724582)
and [dependency 41502596650](https://github.com/Tozward/sing-tun/actions/runs/41502596650)
pass their Linux and Windows jobs, including the dependency's privileged kernel
memory-link cases and the core's real-TUN/Vision repeats and 60-row matrix.
Local macOS arm64 focused core/dependency race checks pass with the final pins.
**Not run:** cloud macOS jobs did not acquire hosted runners (GitHub arm64 capacity
constraints); local real-utun memory-link cases lack permission and are covered
by the passing Linux privileged job instead. These cloud workflow totals are not
successful macOS runtime validation. Android throughput and energy measurements
remain for physical-device testing.
