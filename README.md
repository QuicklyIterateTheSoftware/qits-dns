# qits-dns

The authoritative DNS server for the hostnames the platform hands out to itself.

qits orchestrates building and deploying applications — including its own — and every deployed
environment needs a resolvable name without a human touching a registrar's control panel. This
service is what makes those names resolve:

    qits.eu                                    prod (qits-gateway; everything else is a /path behind it)
    some-fancy-feature.qits-dev.eu             the environment for the epic `some-fancy-feature`
    the-application.some-fancy-feature.qits-dev.eu   one application inside that environment

It is **authoritative-only**. It answers for zones delegated to it at a registrar, out of records
held in its own database, and it offers an HTTP API for other modules to create and delete those
records. It recurses for nobody, transfers to nobody, and signs nothing.

    ./mvnw verify     # a clone of this repo alone builds and tests green — no monorepo, no docker

## Layout

| Module | What |
|---|---|
| `dns/` | The domain and **the resolution contract**: entities, persistence, validation, the snapshot, and the pure function from `(qname, qtype)` to a response. Framework-free — no JAX-RS, no vert.x, no dnsjava. |
| `service/` | The deployable. `wire/` is the UDP and TCP listeners and the dnsjava-backed codec; `api/` is the record-management REST surface. |

The directories are `dns/` and `service/`; the artifactIds are `qits-dns-domain` and
`qits-dns-service`. The mismatch is deliberate — generic coordinates like `eu.wohlben:dns` would
collide in the shared `~/.m2` that every workspace container mounts, so the coordinates are
namespaced while the directories stay short.

## The boundary

**qits-dns answers queries and stores records. It decides nothing about what should point where.**

Whatever wires an epic environment to a hostname — qits-cd on a deploy, teardown on a delete, the
epic orchestration that knows which application got which name — calls the API from outside. This
service has no opinion on the subject and no dependency on any other qits module.

Two protocols, two entirely separate front doors:

- **Port 8053, UDP and TCP** — the DNS wire protocol, facing the open internet, answering queries.
- **Port 8080, HTTP** — the record-management API, on the internal network, behind a static token.

**DNS traffic never passes through qits-gateway.** The gateway proxies HTTP; DNS is not HTTP. This
service is a *sibling container behind the same public IP* — port 443 to the gateway, port 53 to
here — not a gateway route, and it must not become one.

## What the wire serves

The contract lives in one place, `dns/…/control/DnsResolverImpl`, and `AGENTS.md` states it in
full. The shape of it:

Names go three levels deep relative to a zone apex and no deeper — the apex itself,
`feature.qits-dev.eu`, and `app.feature.qits-dev.eu`. At each depth a query matches an explicit
record or an **opt-in wildcard**: `*` (any one label), `*.<label>` (any label under a specific sub),
`*.*` (any two labels). A wildcard answers only if somebody created that row; nothing is implied per
zone. An explicit name always beats a wildcard, and a wildcard's answer carries the **queried** name
as its owner, never a literal `*`.

Types are **A, AAAA and CNAME**, plus the **SOA and NS a delegated zone cannot function without** —
those two are synthesized from configuration and are never rows in the database.

Queries for names outside our zones are REFUSED, as are `ANY`, `AXFR` and `IXFR`. There is no
recursion (RA is always 0) and no DNSSEC.

## The record-management API

Under `/dns/api`, called service-to-service (`http://qits-dns:8080/dns/api/...`). There is no
gateway route to it.

| Verb + path | Meaning |
|---|---|
| `POST /dns/api/zones` | Create a zone `{fqdn}` → 201 |
| `GET /dns/api/zones` | List zones |
| `GET /dns/api/zones/{id}` | One zone with its records |
| `DELETE /dns/api/zones/{id}` | Delete a zone and its records → 204 |
| `POST /dns/api/zones/{id}/records` | Create `{name, type, value, ttl?}` → 201 |
| `PUT /dns/api/zones/{id}/records` | **Replace-by-(name, type)**: `{name, type, values[], ttl?}` swaps every row of that pair atomically → 200 |
| `DELETE /dns/api/records/{id}` | Delete one record → 204 |

`PUT` is the verb an automated deployer actually wants: re-deploying the same epic writes the same
record set again and gets a 200 instead of dancing around a 409.

Write verbs are guarded by `qits.dns.token` (header `X-DNS-Token`); reads are open. A blank token —
the shipped default — makes the guard a no-op, which is what keeps dev and the suite friction-free.

Every mutation bumps the zone's SOA serial and rebuilds the resolver's snapshot **after the
transaction commits**, so a failed write never publishes and a successful one takes effect on the
next query with no restart.

The document is at `/dns/q/openapi`, browsable at `/dns/q/swagger-ui`.

## Deploying it

### Pointing a domain at this server

A registrar's NS record holds a **hostname**, not an address — `ns1.qits.eu`, not `203.0.113.7`.
The address rides along as a **glue A record**, which the registrar asks for whenever the NS name
lives inside a zone it is delegating. That pair together is what "point the domain at our server"
means, and it is why `qits.dns.ns-names` is a list of hostnames.

So, once per delegated zone:

1. Register the NS hostnames (`ns1.qits.eu`, …) and give the registrar their glue addresses.
2. At the registrar for `qits-dev.eu`, set its NS records to those hostnames.
3. Set `QITS_DNS_NS_NAMES` and `QITS_DNS_HOSTMASTER` on this service — **both**, or SOA/NS
   synthesis stays off and the delegation is broken in the quiet way (see below).
4. Create the zone over the API and write its records.

### Ports

The listener defaults to **8053**, not 53, because binding below 1024 needs privileges this process
should not hold. Publish both protocols:

    docker run -p 53:8053/udp -p 53:8053/tcp ...

**Both.** An authoritative server that refuses TCP fails every truncation retry, and resolvers do
probe it outright. Alternatively grant `NET_BIND_SERVICE` and set `QITS_DNS_PORT=53`.

### What a deployment must set

| Env | Why it is not defaulted |
|---|---|
| `QUARKUS_DATASOURCE_DNS_JDBC_URL` | The shipped default is `${user.home}`-rooted and a container has no home to resolve. See `docker/Dockerfile`'s header — the image refuses to start rather than silently storing zones on an ephemeral layer. |
| `QITS_DNS_NS_NAMES` | This repo cannot know its own public nameserver hostnames. Blank ⇒ synthesis off. |
| `QITS_DNS_HOSTMASTER` | The SOA's rname. Blank ⇒ synthesis off. |
| `QITS_DNS_TOKEN` | Blank ⇒ the write API is unauthenticated. A write here changes what a public nameserver answers. |

**Blank `ns-names`/`hostmaster` is a working server with a broken delegation.** A/AAAA/CNAME are
still answered — which is exactly why dev and the test suite need no configuration — but the zone
answers no SOA, so negative answers cannot be cached and resolvers come back for every nonexistent
name. It fails as latency and load, not as an outage. A boot log line says which of the two is
missing; that line is the only warning you get.

## Checking it by hand

With a zone `qits-dev.eu` created and `@ A`, `* CNAME`, `*.* CNAME` and an explicit `app.feature A`
written through the API:

```bash
dig @127.0.0.1 -p 8053 qits-dev.eu A               # the apex rows, AA flagged
dig @127.0.0.1 -p 8053 anything.qits-dev.eu        # the * CNAME, with the in-zone chase applied
dig @127.0.0.1 -p 8053 x.y.qits-dev.eu             # the *.* CNAME
dig @127.0.0.1 -p 8053 app.feature.qits-dev.eu     # the explicit A, beating the wildcard
dig @127.0.0.1 -p 8053 other.tld                   # REFUSED
dig @127.0.0.1 -p 8053 a.b.c.qits-dev.eu           # NXDOMAIN, SOA in authority
dig +tcp @127.0.0.1 -p 8053 qits-dev.eu A          # the same answers over TCP
```

Delete the records over the API and re-query: the answers change on the next query, no restart.

## What is deliberately *not* here

- **The callers.** Nothing in this repo decides what points where.
- **Recursion.** Out-of-zone queries are REFUSED, never forwarded.
- **Zone transfers, NOTIFY, secondaries, DNSSEC.** AXFR and IXFR are REFUSED.
- **TXT records.** The first extension anyone will want — ACME DNS-01 is how `*.qits-dev.eu`
  eventually gets a wildcard certificate — and it rides in cleanly: the enum, the value validation,
  and the migration for the `type` check constraint. No matching change. Deliberately not in v1.
- **Rate limiting.** Recorded as a risk rather than built: the responses are tiny (amplification
  factor ≈ 1), garbage is dropped rather than answered, and ANY is refused. Revisit before pointing
  anything third-party at this.
- **An apex CNAME.** DNS forbids it (RFC 1034 §3.6.2) and so does the API. Give the apex A/AAAA rows
  with the same targets instead. ALIAS-style flattening — resolving the target server-side when the
  snapshot is built — is the extension if callers ever need apex-follows-CNAME.
