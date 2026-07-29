-- The authoritative-DNS schema: one dns_zone per delegated domain, one dns_record per configured
-- name+type+value under it. This is CONFIGURATION, not a cache — every row got here because the
-- management API put it here, and the resolver answers from a snapshot rebuilt out of these tables
-- rather than reading them on the hot path.
--
-- The wildcard opt-ins are ORDINARY ROWS. `name = '*'` (any one label), `'*.<l>'` (any label under
-- a specific sub) and `'*.*'` (any two labels) are values in the same column as `@` and
-- `app.feature`, so "opt in per domain" is the presence of a row and there are no flags on the
-- zone. A wildcard that nobody inserted resolves to nothing; nothing is implied per zone.
--
-- THE SOA AND NS RECORDS ARE NOT ROWS HERE, deliberately. A delegated zone cannot function without
-- them, but they are synthesized at snapshot-build time from qits.dns.ns-names, qits.dns.hostmaster
-- and the zone's own serial — deployment facts this repo does not know and must not invent. Storing
-- them would mean the API could create an apex NS pointing anywhere, and a zone whose delegation
-- silently disagrees with the server actually answering for it. The database holds only what the
-- API creates.
--
-- `serial` is a plain bump-on-write counter rather than the conventional YYYYMMDDnn encoding: it is
-- only ever read out of a SOA answer, there are no secondaries to compare it, and monotonic is the
-- whole contract.

create table dns_zone (
    id         varchar(255) not null primary key,
    fqdn       varchar(253) not null unique,   -- lowercase, no trailing dot, e.g. 'qits-dev.eu'
    serial     bigint       not null,          -- SOA serial; bumped on every write in the zone
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null
);

create table dns_record (
    id         varchar(255) not null primary key,
    zone_id    varchar(255) not null,
    name       varchar(255) not null,          -- '@' | '<l>' | '<l>.<l>' | '*' | '*.<l>' | '*.*'
    type       varchar(8)   not null check (type in ('A', 'AAAA', 'CNAME')),
    -- The record's payload: an IPv4 literal for A, an IPv6 literal for AAAA, a hostname for CNAME.
    --
    -- NOT NAMED `value`, which is what the feature plan's §4 wrote and what the entity field is
    -- still called. VALUE is a RESERVED WORD in H2 2.x, so `value varchar(253)` fails the migration
    -- outright with `expected "identifier"` — the plan's SQL was never run. The alternatives were
    -- quoting it forever (every future migration and every hand-written query pays, and the quoting
    -- has to reach into @Column too) or adding NON_KEYWORDS=VALUE to the JDBC URL in three places,
    -- which is exactly the sort of connection-string magic the AUTO_SERVER lesson in
    -- META-INF/microprofile-config.properties is about. `rdata` is RFC 1035's own name for this
    -- field, so the rename costs nothing in clarity and the API's JSON still says `value`.
    rdata      varchar(253) not null,
    ttl        int,                            -- null -> qits.dns.ttl-seconds
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    constraint uq_dns_record unique (zone_id, name, type, rdata),
    -- A real FK, unlike the string-id columns the qits modules carry across context boundaries:
    -- both tables are inside dns's OWN physical database, so there is nothing for it to span. The
    -- "string ids, never FK" rule is about pointing at another module's tables, and dns points at
    -- none.
    constraint fk_dns_record_zone foreign key (zone_id) references dns_zone
);

-- The resolver's snapshot rebuild reads every record of a zone, and the API's read surface reads a
-- single (zone, name) — one index covers both because zone_id leads it.
create index idx_dns_record_zone_name on dns_record (zone_id, name);
