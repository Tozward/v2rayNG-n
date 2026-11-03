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
