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

## Later real Edge observation and candidate scope

The actual affected browser is Edge, not the system default browser. Edge is
in the VPN allowlist and has no enabled Edge-specific process routing rule.
Only named networking preferences were read; no browsing history, account
state or full preferences were exported. No explicit secure-DNS override was
found in those preferences, which does not prove every default is disabled.

At 10:38 the user reported another severe stall. After the phone later
returned to TOP_SLEEPING, a synthetic Edge-UID client could neither resolve nor
establish TCP; system policy showed APP_BACKGROUND blocking and none of its
packets reached TUN. Those measurements are excluded from awake-path diagnosis.
The GMS-UID positive control's 20 handshake/data packets did reach TUN.

A later unlocked foreground refresh, with system effective blocking NONE,
was reported normal by the user. Temporary Info logging captured four Google
sessions, two of which sniffed www.google.com and then received the existing
Vision UDP/443 rejection. All 39 rejected UDP/443 sessions in that window are
not assumed to be Google. Logging was restored to warning automatically.

The native UDP adapter had a confirmed handshake-notification omission. The
candidate uses the existing Xray error tracker and Sing handshake APIs, clears
the initial snapshot on upstream response, and preserves the original packet
batching. No Vision policy, preconnect implementation, Sing engine algorithm,
MTU, user flow or DNS choice is changed. The abandoned custom SOCKS-over-UDS
protocol is also absent from the pinned core; native forwarding uses neither
SOCKS nor UDS.

Real Android TUN tests overlaying the same regression on the released core
showed that an explicit asynchronous-error receiver waited 1.5 seconds on old
code and received unreachable in approximately 3–7 ms after the fix, in IPv4
and IPv6. Default UDP readers still did not receive that soft NoRoute error
immediately. Consequently this is a verified adapter-correctness fix, not a
verified claim that Edge's QUIC fallback or every reported first-page stall
is now faster. Browser-specific causation remains incomplete.

Snapshot commit 76975bbd passed [Actions 41567083345](https://github.com/Tozward/v2rayNG-n/actions/runs/41567083345):
218 tests per distribution, no failures/errors/skips, both debug builds and
lint, and Android 24/35 instrumentation. The 2.3.15.5 candidate pins core
93cbbf3e50dec388ed4c7e42cb8dd3f7cf2a8958; its production Go/module files are
identical to reviewed core 4773658556c1fa515e4e5b30a715eb206c5e2b6f (the intervening
commit only applies the upstream formatter to tests). Candidate checks are
recorded after its final build; a successful earlier build is not substituted
for that pinned-artifact validation.
