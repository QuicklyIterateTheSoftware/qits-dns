# qits-dns — working notes

Read `README.md` first: it defines the boundary (what the wire serves, what the API owns) and the
deployment surface. This file is the working conventions on top of it, plus the one thing this repo
cannot afford to have two opinions about — the resolution contract in §"The contract" below.

## The two rules that shape everything

**A clone of this repo alone builds and tests green** — no monorepo, no docker, no prior
`mvn install` elsewhere, no credentials. `./mvnw verify` is the gate. Anything that would break that
is not a tradeoff to weigh, it is the thing this repo exists to avoid.

That is why the poms duplicate versions instead of inheriting them, why the wire suite binds real
sockets on an ephemeral port rather than reaching for a fixture DNS server, and why the whole
resolution contract is a pure function over an in-memory snapshot instead of something that needs a
database to be interesting.

**`service/` compiles to a GraalVM native image**, the same rule qits-gateway, qits-ci and
qits-workspace-daemon carry. `.sdkmanrc` names `25.0.2-graalce`, so `sdk env` gives you a
`native-image` and `./mvnw verify -Dnative` produces `service/target/qits-dns`. Three things follow:

- **A missing GraalVM does not fail the build.** Quarkus logs `Cannot find the native-image ...
  Attempting to fall back to container build` and shells out to docker for a 1.8 GB Mandrel image.
  Green either way, so the fallback is easy to be in without noticing. Grep a native build's log for
  that line before believing it proved anything.
- **Every dependency is a decision about what the builder has to be told.** See §"dnsjava" below —
  this repo's one non-Quarkus dependency needed two build-config lines, and finding out cost a
  dedicated spike.
- **So is every config default the app boots with.** The datasource URL carries no `AUTO_SERVER` and
  the note in `dns/src/main/resources/META-INF/microprofile-config.properties` says why (qits-ci's
  binary died at boot on exactly that default while every JVM test stayed green).
  `DnsPackagedSurfaceIT` is the guard, and it relocates `user.home` rather than restating the
  settings, precisely so the shipped values stay the ones under test.

## The contract

**§"The contract" is the specification. `dns/…/control/DnsResolverImpl` is its only implementation,
and a rule appearing anywhere else is a bug.** In particular the wire layer decides nothing: it
hands `DnsResolver` a qname and a *numeric* qtype and encodes whatever comes back. That is why
`resolve(String, int)` takes a raw int — ANY, AXFR and IXFR have no `WireType` member and are
refused *here*, not intercepted by a codec.

**Matching.** Lowercase the qname, drop the trailing dot. Find the longest configured zone that is a
suffix of it, on a label boundary. No zone → **REFUSED**. Then match what is left of the apex:

| labels above apex | tried, in order |
|---|---|
| 0 | `@` |
| 1 (`x`) | `x`, then `*` |
| 2 (`y.x`) | `y.x`, then `*.x`, then `*.*` |
| 3+ | no match → **NXDOMAIN** |

The first name that has *any* rows wins; later patterns are not consulted. A wildcard match is
**expanded** — the answer's owner is the queried name, never a literal `*`, because resolvers
discard answers whose owner does not match the question.

**One deviation from RFC 4592, deliberate.** If only `y.x` exists and `*` is configured, a query for
`x` matches `*` here; real DNS would say `x` exists as an empty non-terminal and block the wildcard.
Somebody opting into `*` means "cover every one-label name", and that is what they get. The line
that does this is commented as deliberate — do not "fix" it.

**One RFC rule kept, and it matters.** If the qname has no rows and matches no wildcard but *is* a
prefix of a name that does have rows (query `x`, only `y.x` configured, no wildcards), the answer is
**NODATA** — NOERROR, empty answer, SOA in authority — and not NXDOMAIN. Resolvers negative-cache
NXDOMAIN for the whole subtree, so getting this wrong poisons `y.x`.

**Answering, once a name matched.** A/AAAA return every matching row. A CNAME is answered regardless
of qtype; if its target is in one of our zones it is chased **once** through this same table (so a
chase may itself land on a wildcard) and the target's A/AAAA rows are appended — a chase landing on
another CNAME stops there, because one hop means one hop. Out-of-zone target: the CNAME alone. A
matched name with no rows of the asked type and no CNAME is NODATA, and so is any qtype this server
does not serve. `SOA` and `NS` at the apex are answered from the synthesized records; `ANY` is
REFUSED (RFC 8482 in spirit — it keeps the amplification surface at zero).

**Every negative answer carries the zone's SOA in the authority section** — without it negative
caching breaks and resolvers hammer us. Except when synthesis is off, where there is no SOA to carry
and the authority section is empty; see below.

**Synthesis is off by default and that is not a degraded state.** `qits.dns.ns-names` and
`qits.dns.hostmaster` ship blank because this repo cannot know its own public names and a default it
invented would be a lie that resolves. Blank means SOA and NS have nothing to answer with, and a
negative answer goes out with an empty authority section rather than not going out at all — refusing
to say a name does not exist because nobody configured a hostmaster address would be absurd. A boot
log line names whichever key is missing. A/AAAA/CNAME are unaffected, which is what lets the entire
test suite and `quarkus:dev` run with no configuration.

**TTL** defaults to `qits.dns.ttl-seconds` and a record's own TTL overrides it. It is resolved once,
when the snapshot is built; nothing downstream reads configuration again.

## The snapshot

**The hot path never touches H2.** `ZoneSnapshot` is an immutable map from zone fqdn to that zone's
records plus its synthesized SOA/NS material. It is built at boot and rebuilt *whole* after every
mutating API call, then swapped in with one volatile write. So the UDP event loop never blocks on a
datasource and a query burst costs zero database load.

Two invariants:

- **A rebuild happens after the transaction commits, never inside it.** A failed write must not
  publish. The control services are `@Transactional` and each mutating method's Javadoc says its
  caller owes a rebuild; the JAX-RS layer is where that call lives.
- **A rebuild re-reads committed state rather than patching the previous snapshot**, so concurrent
  writers converge on whichever rebuild runs last. Rebuilding everything per write is the deliberate
  trade at this data size — hundreds of rows, not millions — where the simple thing is also the one
  that cannot leave a stale entry behind.

## Package and module conventions

`eu.wohlben.qits.dns.*`, split across two maven modules with disjoint sub-packages so there is no
split package:

- `dns/` — `entity`, `persistence`, `dto`, `mapper`, `control`, `error`, and `resolve` (the contract's
  types). **Framework-free in the sense that matters: no JAX-RS, no vert.x, no dnsjava.** The
  resolver operates on its own types, which is what makes the contract testable as pure functions
  with no socket, no database and no clock. Entities are Panache; mappers are MapStruct
  `@Mapper(componentModel = "jakarta")`.
- `service/` — `api` (the JAX-RS routes, the token filter, the exception mapper) and `wire` (the UDP
  and TCP listeners, the `DnsCodec` seam and its dnsjava implementation). Both need a runtime stack
  the domain module deliberately does not have.

The directories are `dns/` and `service/`; the artifactIds are `qits-dns-domain` and
`qits-dns-service`. Generic coordinates like `eu.wohlben:dns` would collide in the shared `~/.m2`
every workspace container mounts.

## Validation lives in one place

`control/DnsNames` owns every rule about what a zone fqdn, a record name and a record value may be,
and the API calls it. **Do not restate a rule as a bean-validation annotation on a DTO** — two
copies of a rule is one copy that is wrong, and the interesting rules here (no CNAME at the apex, a
CNAME admits no siblings, a zone may not be a suffix of another zone) are not expressible as
annotations anyway.

Record names are exactly one of six shapes — `@`, `l`, `l.l`, `*`, `*.l`, `*.*` — and that grammar
plus the matching table above are the two places the two-label depth limit lives. Extending to
deeper names is mechanical in both; the stated requirement stops at two, so this does.

## dnsjava

`dnsjava:dnsjava` (**not** `org.dnsjava:dnsjava`, which is dead at 2.0.6 from 2010), used strictly
for `Message`/`Name`/`Record` parse and encode, behind the `DnsCodec` seam.

**Never import `Lookup`, `Resolver`, `SimpleResolver`, `ResolverConfig` or `org.xbill.DNS.spi`** —
not in `src/main`, and not in `src/test` either. That is where the jar's `ServiceLoader` and
system-configuration machinery lives, and it is actively hostile to a native image:

- `Lookup.<clinit>` reads the **build machine's** `/etc/resolv.conf` and freezes it into the binary.
  This was caught in the spike with a WSL host's nameserver about to be baked in.
- `ResolverConfig` reaches `AndroidResolverConfigProvider` and `WindowsResolverConfigProvider`,
  which reference `android.net.ConnectivityManager` and `com.sun.jna.Pointer`. Neither is on the
  classpath, and Quarkus passes a blanket `--link-at-build-time` with no application-level opt-out,
  so that is a hard build failure.

`service/src/main/resources/application.properties` carries two lines about this, and both are
load-bearing:

- `--initialize-at-run-time=org.xbill.DNS.Header`, because `Header` holds a
  `private static final Random random = new SecureRandom()` and GraalVM refuses a `Random` instance
  in the image heap. We never call the no-arg constructor that reads it, but `<clinit>` runs on any
  first touch of the class.
- `quarkus.class-loading.removed-resources` dropping dnsjava's two `META-INF/services` files, which
  severs the whole resolver subtree in one line. It is also the mechanical enforcement of the rule
  above: import `Lookup` and the native build fails loudly instead of shipping the builder's DNS
  config. (Note the escaped `\:` — a colon is a key/value separator in a `.properties` file.)

Three dnsjava behaviours the wire layer is built around, all measured rather than assumed:

- **`toWire(int)` sets TC in the returned bytes but never in the caller's `Message`.** Reading
  `getHeader().getFlag(Flags.TC)` after encoding always says false. Detect truncation by encoding
  with `toWire(max, false)` and catching `MessageSizeExceededException` — and note that overload's
  second argument is `truncate`, not `throwOnFail`, which reads backwards at a call site.
- **Truncation is RRset-atomic**, so an RRset that alone exceeds the budget is dropped *entirely*.
  100 A records under one owner truncated to 512 bytes yields **zero** answers, not as many as fit.
  That is the common case for a DNS server, and it is correct behaviour — the client retries over
  TCP, which is why refusing TCP is not an option.
- **Case echo is free, but only if you reuse the parsed `Name`.** `Name.toString()` preserves the
  parsed spelling while `equals`/`hashCode` are case-insensitive, so copying the question record
  into the response and using its `Name` as the answer's owner echoes the caller's capitalisation
  exactly. Re-deriving a canonical name from zone data throws that away.

Also: **never put an `InetAddress` in a `static final`** — native-image rejects `Inet4Address` in
the image heap. `ARecord(Name, int, long, byte[])` takes raw address bytes, which sidesteps it.

## Untrusted input

**This is the only qits module exposed below HTTP, and its UDP socket parses hostile bytes off the
open internet.** Keep that in your head when you touch `wire/`:

- **Bytes that do not parse are dropped and counted, never answered.** Replying FORMERR to garbage
  is free amplification surface. FORMERR is reserved for a message we understood well enough to know
  was malformed (a parseable query with QDCOUNT ≠ 1); an opcode that is not QUERY gets NOTIMP.
- **Responses stay small.** ANY, AXFR and IXFR are REFUSED, out-of-zone is REFUSED, and the
  amplification factor is about 1. Anything that would make a response large in reply to a small
  query is a security change, not a feature.
- **UDP responses are capped** at `min(the client's advertised EDNS0 payload, 1232)`, or 512 with no
  OPT. The DO bit is ignored; we sign nothing.
- **One malformed packet must never take the socket down.** The listener catches, counts and keeps
  serving.

The record-management API is the other untrusted surface, and its guard is `qits.dns.token` on the
write verbs. Note what a write there does: it changes what a public nameserver answers.

## Schema changes

`dns/src/main/resources/db/dns/migration/`, hand-written, its own lineage on its own datasource —
keep appending to it. The `dns_record` value column is named **`rdata`** in SQL because `value` is a
reserved word in H2 2.x and the migration simply does not run with it; the entity field and the JSON
key stay `value` via `@Column(name = "rdata")`. Do not "tidy" that back, and do not reach for
`NON_KEYWORDS=VALUE` in the JDBC URL — connection-string magic is the same class of decision as the
`AUTO_SERVER` that broke qits-ci's binary.

Adding TXT is the enum, `DnsNames`' value validation, and one migration for the `type` check
constraint. No matching change.

## Tests

- **Plain JUnit 5, no Mockito.** The resolver suite builds `ZoneData`/`StoredRecord`/`ZoneSnapshot`
  by hand and asserts pure functions; the wire suite binds real sockets and scripts the resolver
  with a CDI `@Alternative`. Between them there is nothing left that a mock would be for.
- App-level config lives in `service/src/main/resources/application.properties` — this module is the
  deployable, and Quarkus merges that file into the test config rather than letting
  `src/test/resources/application.properties` shadow it. **Never re-declare an app-level setting in
  test resources**: a suite green because the *test* copy is right proves nothing about what ships.
  The test files carry only genuine overrides — in-memory H2, and `qits.dns.port=0`.
- **`qits.dns.port=0` in the test config is load-bearing.** The suite binds real UDP and TCP sockets;
  a fixed 8053 would fight a developer's own running server and any parallel run. The wire server
  exposes the port it actually bound.
- **`DnsPackagedSurfaceIT` is the only test that runs the packaged artifact** — the fast-jar under
  `-DskipITs=false`, the binary under `-Dnative`. It is not a second boundary test and behaviour does
  not belong in it: it asserts the handful of things a `@QuarkusTest` structurally cannot see because
  they only exist once the app is built — the routes' build-time prefixes, the shipped datasource URL,
  Flyway's migration surviving as a classpath resource, and that the UDP listener answers from the
  binary. That last one is what keeps the dnsjava-in-native finding true instead of merely once-true.
- A `Failed to start quarkus` / `Port already bound: 8081` failure is the known Quarkus flake —
  `@QuarkusTest` restarts racing for the test port. Re-run first. `DnsPackagedSurfaceIT` is
  deliberately outside that race: failsafe passes it `quarkus.http.test-port=0`.

## Adding a dependency on another context

Don't. This context has no compile-time dependency on any other qits module and should not grow one.
Records arrive over its own API or not at all, and there is nothing in another module's database it
could want — a zone is not a repository, an epic or a workspace, and the name it serves is a string
somebody else decided on.

Never add a JPA relation to another context's entity. The FK inside `dns_record` is fine; it lives
in this module's own physical database.
