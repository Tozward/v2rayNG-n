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

## Candidate verification and phone installation

The final candidate is built from application source
`9ba74a7abe87efc4d5140f1df5b81182b1fc3f66` with native core
`93cbbf3e50dec388ed4c7e42cb8dd3f7cf2a8958` and unchanged Sing dependency
`2c2f8b289bf652b432506e0a6fe50e6ac6bd1fa2`.
[Application Actions 41573264155](https://github.com/Tozward/v2rayNG-n/actions/runs/41573264155)
passed both distributions' 218 tests (no failures, errors or skips), Kotlin
compilation, debug/release assembly, both lint runs, required native files for
all four ABIs, and Android API24/API35 instrumentation.
[Core Actions 41572930421](https://github.com/Tozward/Xray-core-socks-uds/actions/runs/41572930421)
passed format/protobuf, ordinary/native dependency boundaries, three-platform
contracts/races, real Linux descriptor/Vision/preconnect/ICMP regressions and
the existing matched-path benchmark. Public release creation was not invoked.

The Playstore arm64 signed APK has SHA256
`b84a684c74fcf32fb4408e80ddb10377e3e5f2548061e6752155401f04cd6e09`.
Its embedded Go build information confirms the new core pin and unchanged
Sing pin; all three required native libraries are present. The extra
`sagernet_fork` tag used in some standalone diagnostics selects exactly the
same package source inputs as `with_sing_tun` alone in this dependency graph.

The phone accepted the same-signature update and reports version2.3.15.5,
versionCode4000770. Its existing VPN authorization was reused through the
existing Android-owned service entry, without lockscreen/security-policy
changes or a node parameter. The installed-APK probe (explicit asynchronous
error subscription) changed from a 2.002-second timeout before the update to
a local unreachable after 2.447 ms afterwards. A subsequent HTTPS204 request
succeeded, showing that rejection did not stop the tunnel. This still does
not establish Edge's fallback latency.

The current settings remain Sing native TUN, MTU9000, testpre5, localDNS and
FakeDNS on, IPv6/HTTP append/mux/fragment off. Warning logging is restored,
there are no enabled process routing rules, and all disposable UID0 test
routing rules/interfaces were removed. Node credentials and complete personal
configuration remained on the phone; recorded browser events and packet-header
summaries contain no addresses, cookies or page content.

Only this evidence section was appended after the candidate build. Production
files and module locks remain identical to the compiled source above; no
additional binary rebuild is needed for this documentation update.

## Long-running candidate and formal-release review

At 21:28 CST on 2026-11-03, the installed 2.3.15.5 daemon was still PID12699,
with elapsed time 09:27:08 and CPU time 00:03:39. This is process continuity,
not a battery-energy measurement. The named settings above remained unchanged.
No VPN restart, node/backend change, temporary logging change, security-policy
change or personal-browser operation was needed for this follow-up.

The retained current-daemon logs covered approximately 21:11–21:27, with 136
Info entries and no Warning/Error/Fatal entries or pre-connect failures. The
ring buffers do not preserve all nine hours, so absence of errors is claimed
only for that retained interval. Historical crash-buffer references to the
APK include standalone app_process diagnostics; they are not counted as a
crash of the continuously running application daemon. A later targeted
classification found 113 such references in standalone app_process reports
and one with incomplete old context; all 114 predate this daemon's start.

The complete app path was reread: persisted editor/imported testpre value,
MMKV ProfileItem, CoreOutboundBuilder's settings.testpre, libv2ray JSON loading,
the pinned VLESS account, and the original outbound preconnection pool. The
pool and TCP/TLS/REALITY dial code match upstream 7c8dbcb4. Native upload
handoff changes are later, after taking a preconnection and committing the
required Vision transition and buffered data. Both backends share this
outbound. No new application or core production changes were required.

Upstream's design is lazy: the first Process starts five workers, each offers
one already-dialed connection, then waits 200ms after handing it off before
replenishing. Connections expire after two minutes, checked when taken. This
prepares the connection to the proxy server, not target-site DNS/TLS/HTTP.
Cold starts, stale pools, bursts and original Vision UDP443 rejection can
still add work; these are not proven causes of a particular reported delay.
The previously recorded upstream Close/send race boundary remains unchanged.

Six fixed public HTTPS204 requests, without cookies or browser state, all
succeeded on the existing daemon. Three VPN-allowlisted GMS-UID requests
through the complete native TUN took 444.042, 422.561 and 493.557ms; DNS was
4.835–11.509ms. Three independent requests through the daemon's local HTTP
entry took 484.512, 481.073 and 458.589ms. The latter bypass TUN and are not
presented as native-path timings. Android's public CA directory was explicitly
provided to the standalone Go client; earlier diagnostic attempts without
that directory failed client TLS verification and are excluded from latency
and application-failure conclusions.

A phone-local 26.4-second socket observation, exporting only aggregate counts,
saw six selected-node established sockets before requests, six new sockets
appearing as requests progressed, and six established sockets again after
completion. This supports continued pool consumption/replenishment alongside
the source audit; six total sockets are not claimed to be six idle pool slots,
nor does this read the private channel's exact occupancy. No endpoint,
credential, payload, account state or browsing history was exported.

Focused REALITY first-request tests were rerun three times with testpre0/5:
18 connections and 54 small requests passed, as did pending-preconnect Close
in both feature orders. Focused native handoff/buffering/cancellation tests
across four packages passed three race-enabled repetitions. The real-TUN
handoff evidence and the complete candidate build gates above remain from the
same unchanged production source. No new throughput or battery claim is made.

The formal v2.3.15.5 release reuses signed build41573264155, source9ba74a7a.
The follow-up changes only this audit record; no rebuild or phone reinstall
is needed. Existing 2.3.15.5 candidate installations and formal assets have
the same version and binary. The occasional remaining browser-first-load
delay has not been reproduced or assigned a complete cause by these checks.
