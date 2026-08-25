# CraftGenie local development environment

Runs OneDev against **PostgreSQL in Docker** instead of the bundled HSQLDB, with
**pgAdmin** already pointed at the database.

Everything is driven from [`../cg-dev.sh`](../cg-dev.sh).

```bash
./cg-dev.sh run          # PostgreSQL + pgAdmin up, OneDev configured and started
./cg-provision.sh        # create the CraftGenie project tree, AI users, links, queries
```

OneDev completes its own setup from `.env` on first start, so there is no wizard to click
through - it comes up with an admin account already created.

| | |
|---|---|
| OneDev | <http://localhost:6610> |
| pgAdmin | <http://localhost:6680> — no login prompt, server pre-registered |
| PostgreSQL | `localhost:6432`, database `onedev` |

## Why not HSQLDB

The bundled HSQLDB is configured with `sql.ignore_case=true`, so string comparison is
case-insensitive. PostgreSQL is case-sensitive. Developing on HSQLDB and shipping on
PostgreSQL hides a whole class of query and uniqueness bugs until production.

OneDev also runs PostgreSQL through a **custom dialect**
(`io.onedev.server.persistence.PostgreSQLDialect`, which remaps `BLOB` to `bytea`).
Custom persistence code that nothing exercises locally is exactly the code that breaks.

## Why OneDev is not in docker-compose

`../dev.sh run` hot-loads recompiled classes into the running JVM. Containerising OneDev
would cost that, and the dev loop is the point. Only the dependencies are containerised.

## Commands

```
./cg-dev.sh run          up + configure + start OneDev
./cg-dev.sh up           start PostgreSQL + pgAdmin, wait until ready
./cg-dev.sh down         stop containers, keep data
./cg-dev.sh status       containers, database, and which DB OneDev points at
./cg-dev.sh psql         psql shell on the OneDev database
./cg-dev.sh pgadmin      print the pgAdmin URL and open a browser
./cg-dev.sh logs [svc]   follow container logs
./cg-dev.sh test         run the CraftGenie integration tests (throwaway PostgreSQL)
./cg-dev.sh configure    rewrite the sandbox Hibernate config for PostgreSQL
./cg-dev.sh reset-db     drop and recreate the database        (destructive)
./cg-dev.sh destroy      remove containers and data volumes    (destructive)
```

While OneDev runs, use `./dev.sh build` in another terminal to hot-load Java changes.

## Configuration

`.env` is created from `.env.example` on first run and is gitignored. Change ports there
if something on your machine already holds them — the defaults avoid the usual collisions
(PostgreSQL is on **6432**, not 5432).

## How the pieces fit

- **`.runtime/`** (gitignored) holds `pgpass` and `servers.json`, both generated from `.env`
  so credentials have a single source of truth.
- **`configure`** rewrites `server-product/target/sandbox/conf/hibernate.properties`. It starts
  from OneDev's own `server-product/system/conf/hibernate.properties`, comments out whichever
  database was active, and appends the PostgreSQL block — so upstream changes to the shared
  Hibernate settings carry over by themselves. The file lives under `target/`, so **no tracked
  OneDev file is ever modified**.
- **The JDBC driver needs no setup.** OneDev ships `postgresql-*.jar` in
  `server-product/system/site/lib/`, which `Bootstrap` adds to the classpath.

## Security

Every published port binds to `127.0.0.1`. That is what makes the rest of this defensible:
pgAdmin runs in desktop mode (`SERVER_MODE=False`, no master password) because a sign-in
wall on a developer's own machine buys nothing — but only while nothing else can reach it.
Compose publishes to `0.0.0.0` when the prefix is left off, so this is one easy edit away
from an open administrative console on the database.

A host firewall is not the backstop it appears to be. Docker publishes a port by writing
rules into the `DOCKER` iptables chain, which is consulted *before* the `INPUT` chain most
firewall tooling manages, so an unprefixed port is commonly reachable on a machine whose
firewall reads as closed. `ComposeExposureTest` fails the build if a port loses its prefix.

The credentials in `.env.example` are local-development values. **Do not carry any of this
to a shared or reachable deployment.**

## Integration tests

`../craftgenie-it` boots a real PostgreSQL through Testcontainers and runs OneDev's own
entity model against it:

```bash
./cg-dev.sh test
```

- **`SchemaGenerationTest`** builds the schema from `server-core`'s entities through
  OneDev's dialect and naming strategy, then checks every table is prefixed and that
  `BLOB` columns really did become `bytea`. If the custom dialect stops being applied,
  stock Hibernate maps them to `oid` and this fails.
- **`DialectDivergenceTest`** pins the HSQLDB/PostgreSQL differences that motivate this
  whole setup: `WHERE name = 'alice'` matches `'Alice'` on HSQLDB and does not on
  PostgreSQL; a `UNIQUE` column accepts both spellings on PostgreSQL and rejects them on
  HSQLDB; ordering differs under the C collation. Documentation with a build failure
  attached.

- **`ComposeExposureTest`** fails the build if a published port in the compose file
  loses its `127.0.0.1:` prefix.
- **`LocalSourceTest`** checks these tests are running against *this* server-core.

That last one guards something quiet. `server-core` is a test-scoped sibling, but
selecting only `craftgenie-it` still resolves it — OneDev publishes it to its own Maven
repository, which the parent pom declares. Run the tests without building it first and
they go green having exercised **upstream's** jar, so a local change to an entity, the
dialect or the naming strategy is not covered by the tests written to cover it. Nothing
fails; the coverage is simply imaginary. `./cg-dev.sh test` builds it into the reactor
first, which is why it uses two Maven invocations rather than `-am` — `-am` would also
run `server-core`'s 79 tests, two of which fail upstream on a current JDK.

Tests skip themselves cleanly when Docker is unavailable, so they are safe in the
default `mvn test`.

## Provisioning

`../cg-provision.sh` applies the CraftGenie conventions to a running server: the
`<group>/<customer>/{backend,ui}` project tree, the `cg-implementer` / `cg-reviewer` /
`cg-architect` AI users, the *Backend counterpart* ↔ *UI counterpart* issue link,
cross-repo saved queries, and a webhook on the customer project (inherited by both
repositories). Every step is idempotent; `--dry-run` shows what would change.
