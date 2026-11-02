# Experimental Sing TUN backend

## Source and scope

This branch integrates the new Go TCP/IP stack from sing-box 1.15, not the
sing-box core, configuration system, routing engine, protocols, or service.
It is an optional VPN backend. Existing HEV/Xray choices and their persisted
legacy preference remain compatible; upgrading does not select Sing TUN.

Audited source:

| Component | Revision |
| --- | --- |
| sing-box reference | `v1.15.0-alpha.13`, `824dbcfb4dd0a95921840b1737b950cde590241e` |
| sing-tun | `611d83b7a57b4719be45581eb674942ff561de6c` |
| sing | `03a6cce64bf744023af9e2691fb11afd405b9061` |
| AndroidLibXrayLite | unchanged gitlink `2ffe5f794a32ba15b122e177b5acffc6f11c4959` |
| Xray-core | unchanged `a4a3dff5b20c2faae567b742c172c7b95e75c083` |

The SagerNet libraries use GPLv3, compatible with this GPLv3 project. Their
module versions and checksums are pinned in `native/singtun/go.mod` and `go.sum`.
The new libraries' copyright notices and the GPLv3 text are bundled as an APK asset.
The 1.15 reference is an alpha release. Upstream performance claims are not
evidence that this Android integration outperforms HEV or consumes less energy.

## Data path and ownership

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
Xray path uses its `constraints` package) and some tool-only dependencies; it
also selects `x/time` 0.15, which is not imported by the Android package graph.

The SOCKS bridge deliberately retains Xray's inbound metadata, FakeDNS,
sniffing, DNS routing, and outbound policy. It adds loopback socket/copy costs;
calling Xray's dispatcher directly would require reproducing those contracts.
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
persisted selection/error rollback, and lifecycle decisions. CI also runs the
full Play Store and F-Droid test/assembly matrix.

Android service/UI runtime checks and comparative throughput, CPU, wakeup,
memory, and energy measurements must be reported separately from build checks.
Keep the backend experimental until those checks pass. Do not infer energy
savings from a successful transfer or synthetic throughput benchmark.
