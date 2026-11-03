# First-connection audit, 2026-11-03

The reported version is v2.3.15.4, with Sing native TUN, MTU 9000,
VLESS Vision over TCP/REALITY, testpre 5, local DNS and FakeDNS enabled,
route-only sniffing enabled, mux/fragment/IPv6 disabled. Settings were read
on the rooted phone without exporting node configuration or credentials.

## Controlled upstream comparison

Two static Linux/arm64 command-line cores run on the same Xiaomi 14 Pro:

- Official baseline: `7c8dbcb4bbec7f80d16070069e0b2f7bf3aca62c`, the upstream
  base of the installed custom core, with its original module files.
- Installed custom source: `e965fbf73b280483598d7d2ad4b406c00464fcbc`, with
  the installed dependency pin `2c2f8b289bf652b432506e0a6fe50e6ac6bd1fa2`.

Both use Go 1.27.3 and identical build flags; only the custom core enables
its native tags. A phone-only helper constructs the same selected outbound
in memory and sends JSON through child stdin. Both listen only on loopback
and use an independent test port. No personal configuration is saved or
transferred. Three interleaved fresh-process rounds each issue three new
HTTPS connections to Google's public generate_204 endpoint, with the same
HTTP client and testpre value.

| Total request time | Official | Installed custom |
| --- | ---: | ---: |
| Fresh-process first request, median of 3 | 957.698 ms | 852.162 ms |
| Subsequent requests, median of 6 | 446.535 ms | 447.922 ms |
| First request after 130 s idle, one pair | 1066.486 ms | 1051.301 ms |
| Next request after that idle | 423.448 ms | 421.230 ms |

The two idle processes use separate ports and remain otherwise untouched.
This isolates shared core transport changes. It does **not** compare official
and custom native TUN engines, replace the installed AAR, or measure browser
QUIC/page rendering. The first slower requests seen in earlier uncontrolled
probes are not evidence of a custom-core regression by themselves.

The complete installed VPN path was also compared twice, restoring Sing after
each HEV test. Six controlled TCP requests per backend all succeeded. Total
ranges were HEV 0.674–1.019 s and Sing 0.430–1.580 s; DNS was 2–8 ms on Sing.
A separate no-cookie client verified the public Google homepage over both
HTTP/1.1 and negotiated HTTP/2: 0.553 s and 0.610 s including full bodies.
These observations do not reproduce the reported persistent multi-second
browser stall or identify its cause. Background phone traffic can warm the
installed core, so those requests are not claimed as isolated cold starts.

## Integration issues found separately

- Configuration generation reread backend/mode settings while choosing its
  template, configuring its inbound/DNS, and producing runtime metadata. A
  setting change during a build could mark a configuration native without a
  corresponding native reader. One build now captures these related settings
  once and passes the snapshot through the existing generation path. Explicit
  custom inbounds still win; VPN, proxy-only, root and speedtest paths remain.
- Native inbound shutdown waited for Xray-owned dispatchers. A pending VLESS
  preconnection can require the outbound owner's Close to release it; waiting
  in an earlier inbound Close prevents Core.Close from reaching that owner.
  The core integration fix cancels flows and closes its stack/device without
  waiting for a foreign dispatcher. Regression tests exercise both feature
  orders, concurrent close and late TCP/UDP I/O.

Neither issue has been established as the reported browser-delay cause. No
upstream VLESS preconnection, Vision framing or sing-tun engine algorithm is
changed by these fixes. The on-device test restores the original Sing backend,
leaves node/DNS/per-app settings intact and does not alter lockscreen policy.
