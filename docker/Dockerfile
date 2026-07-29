# qits-dns as a GraalVM-native container IMAGE — the same shape as qits-ci's, qits-gateway's and
# qits-workspace-daemon's docker/Dockerfile: a Mandrel builder stage compiles the binary, a minimal
# runtime stage carries only it.
#
# This file is not how the binary is built; it is how the binary is SHIPPED. `.sdkmanrc` names a
# GraalVM (25.0.2-graalce), so `sdk env && ./mvnw package -Dnative` produces `service/target/qits-dns`
# with no container involved. What this adds is the image around it — and a way for a machine with no
# GraalVM to get a binary at all, since the builder stage brings its own native-image.
#
# Build from the repo root:
#
#     docker build -t qits/dns:latest -f docker/Dockerfile .
#
# --- this service listens on TWO front doors, and both have to be published --------------------
# Unlike every other qits service, this one is not only HTTP:
#
#   8080/tcp        the record-management API (and the OpenAPI document). Internal network only —
#                   qits-gateway does NOT route to it in this plan, and callers address the service
#                   directly at http://qits-dns:8080/dns/api/...
#   8053/udp+tcp    the DNS wire protocol, facing the OPEN INTERNET. This is the port a registrar's
#                   delegation ultimately reaches.
#
# DNS traffic does not pass through qits-gateway and must not be made to: the gateway proxies HTTP,
# and DNS is not HTTP. This container is a SIBLING of the gateway behind the same public IP —
# 443 to the gateway, 53 to here.
#
# The listener binds 8053 rather than 53 because binding below 1024 needs privileges this process
# should not hold, so the deployment publishes BOTH protocols:
#
#   docker run -p 53:8053/udp -p 53:8053/tcp ...
#
# Both, not just UDP. An authoritative server that refuses TCP fails every truncation retry — and
# truncation is not exotic here: dnsjava drops a whole RRset that will not fit, so a name with many
# A records answers UDP with TC=1 and *zero* records, and the client's TCP retry is the only way it
# ever gets the answer. Some resolvers also probe TCP outright. Alternatively grant
# NET_BIND_SERVICE and set QITS_DNS_PORT=53.
#
# --- this image does NOT run with an empty environment, and that is not a packaging bug ----------
# `docker run --rm qits/dns:latest` dies before either listener with
#
#   FlywaySqlUnableToConnectToDbException: ... A file path that is implicitly relative to the current
#   working directory is not allowed in the database URL "jdbc:h2:file:?/.qits/data/dns/h2/dns"
#
# That `?` is `${user.home}`. UID 1001 has no /etc/passwd entry in ubi-minimal, and a native image
# resolves user.home through getpwuid(2) rather than $HOME — so exporting HOME does not fix it, and
# the `${user.home}`-rooted H2 default the `dns` jar ships has nowhere to land.
#
# An /etc/passwd line for 1001 would make it boot with no environment at all. IT IS DELIBERATELY NOT
# HERE, and re-adding it is not a fix. The H2-on-local-disk arrangement is a PROTOTYPE — one file
# database, single writer, sitting next to the process — and not a committed data architecture. An
# image that comes up healthy on an empty environment resolves that path into the container's own
# writable layer: it presents the prototype as decided, and it silently discards every zone and every
# record on the next redeploy. For this service that failure is worse than for its siblings — losing
# qits-ci's history loses a record of what happened, while losing this one's takes every deployed
# environment's hostname off the internet at once, with the server still answering (NXDOMAIN, from an
# empty database) so that nothing anywhere reports an outage.
#
# So a deployment mounts a volume at /data (which this image owns, see the runtime stage) and names
# the database:
#
#   docker run -v qits-dns-data:/data \
#     -e QUARKUS_DATASOURCE_DNS_JDBC_URL=jdbc:h2:file:/data/dns/h2/dns \
#     -p 53:8053/udp -p 53:8053/tcp ...
#
# Three more have defaults that are wrong QUIETLY — nothing will tell you they are unset:
#
#   QITS_DNS_NS_NAMES     Comma-separated public NS hostnames (ns1.qits.eu,...); the first is also
#                         the SOA's mname. Blank ships, and blank means SOA/NS synthesis is OFF.
#   QITS_DNS_HOSTMASTER   The SOA's rname as a hostname. Blank, same effect — the two go together.
#
#                         A server with these blank still answers A/AAAA/CNAME, so it looks healthy.
#                         What it does not answer is SOA, which means resolvers cannot negative-cache
#                         and come back for every nonexistent name forever. It degrades as load and
#                         latency rather than as an outage. One boot log line names the missing key
#                         and that is the entire warning.
#
#   QITS_DNS_TOKEN        Blank means the record-management API's write verbs are unauthenticated,
#                         on a service whose writes change what a public nameserver answers.
#
# See README.md, "Deploying it", for the whole surface, including the registrar-side glue records.
#
# --- stage `build`: native-compile ----------------------------------------------------------------
# The builder JDK MUST be 25: native-image cannot read bytecode newer than its own JDK, and this
# project targets release 25.
FROM quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
USER root
WORKDIR /src

# Fetch the Maven distribution first, keyed only on the wrapper files, so a source-only change does
# not re-download it.
COPY mvnw .
COPY .mvn/ .mvn/
RUN ./mvnw --version

# The whole reactor, not a module list. `-am` builds `service`'s dependency from source —
# `qits-dns-domain` is a 1.0.0-SNAPSHOT this repo never publishes (no distributionManagement, no
# <repositories>), so a builder container with an empty ~/.m2 has nowhere else to get it. Copying
# module by module would mean editing this file every time one is added. `.dockerignore` is what
# keeps this cheap — without it this line drags in every target/ dir, and a stale one can shadow what
# this stage compiles.
COPY . .

# -Dnative flips quarkus.native.enabled (the `native` profile lives in service/pom.xml, since only
# that module is an application). Cap native-image RAM so the build is deterministic on small
# machines.
#
# Tests are skipped because `./mvnw verify` on a clone is their gate (AGENTS.md) and re-running them
# here proves the same thing more slowly. Note the profile also flips `skipITs` to false — stopping
# at `package` rather than `verify` is what keeps `DnsPackagedSurfaceIT` out of the image build; it
# wants free ports and a writable home, neither of which this stage is the right place to arrange.
RUN ./mvnw -B -ntp -pl service -am package -Dnative -DskipTests \
      -Dquarkus.native.native-image-xmx=4g

# --- runtime --------------------------------------------------------------------------------------
FROM registry.access.redhat.com/ubi9/ubi-minimal:9.6
WORKDIR /work

# Nothing is installed here, and that is the whole difference from qits-ci's runtime stage. qits-ci
# is host-side by design and needs `git` and a `docker` CLI at runtime; this service shells out to
# nothing at all. It parses bytes, reads its own database and answers. A package added here is a
# package on a host whose UDP port faces the open internet, so it needs a reason that this service
# does not currently have.
#
# `quarkus.package.output-name` names the binary `qits-dns` (no -runner suffix). That name is spelled
# a third time in service/pom.xml as failsafe's `native.image.path`; all three move together, and if
# they drift the native IT launches nothing and passes.
COPY --from=build --chown=1001:root /src/service/target/qits-dns /work/qits-dns
RUN chmod +x /work/qits-dns

# The mount point for the H2 file, created here so it is OWNED. Docker seeds a fresh named volume
# from whatever the image has at the mount path, ownership included; with no such directory it makes
# one root-owned 0755, and the boot then dies on an AccessDeniedException rather than on the config
# error the header documents — which reads like a bug in this image and is not one. Group-writable
# and group-root because a ubi image is meant to survive being assigned an arbitrary UID with GID 0.
# (A bind mount still takes its ownership from the host — nothing in an image can fix that.)
#
# The datasource URL is deliberately NOT set beside it: the prototype must refuse to start until a
# deployment says where its data goes. See the header.
RUN mkdir -p /data/dns && chown -R 1001:root /data && chmod -R 775 /data

# The API, then the DNS listener on both transports. EXPOSE is documentation rather than a control,
# but here it is the documentation that stops a deployment publishing UDP and calling it done.
EXPOSE 8080
EXPOSE 8053/udp
EXPOSE 8053/tcp

USER 1001

# Quarkus binds 127.0.0.1 outside dev mode, which inside a container means nothing can reach the API
# — not the gateway, not a peer on qits-net. The DNS listener has its own host setting
# (qits.dns.host), which already defaults to 0.0.0.0 for the same reason.
ENTRYPOINT ["/work/qits-dns", "-Dquarkus.http.host=0.0.0.0"]
