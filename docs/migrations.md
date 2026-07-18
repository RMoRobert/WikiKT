# Database migrations

WikiKT manages its schema with a small R2DBC-native migration runner ( not Flyway/Liquibase/etc.)
We chose this because the app is fully R2DBC + Exposed, while these other options are JDBC-only, which would
mean a second, JDBC connection stack, dialect-specific SQL, and broken in-memory H2 tests. The runner
uses the app's own R2DBC pool, applies migrations through Exposed (portable across H2 and Postgres),
and a test guards against schema/code drift.

## Where things live

| Thing | Location |
|-------|----------|
| Migration list + runner | `src/main/kotlin/com/wikikt/service/MigrationService.kt` |
| Applied-history table | `SchemaMigrationsTable` in `src/main/kotlin/com/wikikt/db/Tables.kt` |
| Drift guard test | `src/test/kotlin/com/wikikt/MigrationDriftTest.kt` |
| Where it runs | `createAppContext()` calls `MigrationService(database).migrate()` at startup |

Each migration is one entry in the `MIGRATIONS` list. V1 is the **baseline**: a single
create-if-not-exists of every current table (see "How baselining works" below). New schema changes
are appended as V2, V3, etc.:

```kotlin
val MIGRATIONS: List<Migration> = listOf(
    Migration(1, "baseline") {
        SchemaUtils.create(UsersTable, GroupsTable, /* ...every table... */, PageSearchIndexTable)
    },
    // add new migrations here, with the next version number
)
```

`apply` runs inside a transaction. Prefer Exposed (`SchemaUtils.create(...)`) so it works on both H2
and Postgres; drop to `exec("...")` only for changes Exposed can't express.

## How to add a migration

### 1. Change the Exposed table definition
Edit (or add) the `Table` object in `db/Tables.kt` as usual — this is what the running app queries
against.

### 2. Add a `Migration` for the change
Append to the `MIGRATIONS` list with the **next** version number.

**New table** (preferred form — idempotent):
```kotlin
Migration(2, "api_keys") {
    SchemaUtils.create(ApiKeysTable)
}
```

**Alter an existing table** (Exposed can't express most ALTERs, so use raw SQL):
```kotlin
Migration(3, "pages.published") {
    exec("ALTER TABLE pages ADD COLUMN IF NOT EXISTS published BOOLEAN NOT NULL DEFAULT TRUE")
}
```
Make sure any raw SQL works on **both H2 and Postgres**. If it can't, branch on the dialect inside
the migration.

> **Adding a column to an existing table — use `ADD COLUMN IF NOT EXISTS`.** When you add a column
> to a `Table` object, the V1 baseline's `SchemaUtils.create` will create that column on *fresh*
> databases (it uses the current definition), while the ALTER is what adds it to *existing* ones.
> So a plain `ADD COLUMN` would fail on fresh DBs ("column already exists"). `IF NOT EXISTS` makes
> the migration a no-op there and the real change on existing DBs. Both H2 and Postgres support it.

**Replace / drop a table** (say a new `WidgetsTable` supersedes an old `gadgets` table):
```kotlin
Migration(2, "widgets") {
    SchemaUtils.create(WidgetsTable)
    exec("DROP TABLE IF EXISTS gadget_tags") // child first (its FK references the parent)
    exec("DROP TABLE IF EXISTS gadgets")
}
```
Two gotchas when *dropping* a table:
> - **Drop child tables before parents** (a table is a "child" if it has a foreign key into another),
>   or Postgres rejects the drop. Use `DROP TABLE IF EXISTS` for idempotency.
> - **Keep the dropped table's Kotlin `object` defined** in `db/Tables.kt` if any *shipped* migration
>   still references it (via `SchemaUtils.create(...)`) — deleting the object would break that
>   migration's compile, and you must never edit a shipped migration. Mark it dead with a comment and
>   remove it from `MigrationDriftTest`'s table list (the drift test only checks the tables you pass
>   it, so a dropped-and-unlisted table is correctly absent from both sides). Once no shipped migration
>   references it (e.g. after a baseline consolidation), the dead object can be deleted outright.

### 3. Let the drift test write the SQL for you (optional but handy)
After changing a table, run the drift test:

```
./gradlew test --tests com.wikikt.MigrationDriftTest
```

If your table no longer matches the migrations, it **fails and prints the exact SQL** needed to
reconcile them — paste that into your new `Migration` (after reviewing it). When it passes again,
your tables and migrations are in sync.

### 4. Run it
`./gradlew run` (or any startup) applies pending migrations in version order and records them in
`schema_migrations`. Already-applied versions are skipped.

## Hard rules

- **Never edit a migration that has shipped.** Its checksum isn't enforced, but changing it means
  existing databases won't pick up the change. Add a new migration instead.
- **Never add new tables to V1 (`baseline`).** Once V1 has shipped to a real database it is frozen;
  new tables get their own migration, or fresh and existing databases will diverge.
- **Keep migrations ordered and gap-free** by version number.
- **Review generated SQL.** `MigrationUtils` can emit destructive `DROP` statements and does not
  reliably detect column *type* changes on Postgres (only H2). Read what you paste.

## How baselining works

V1 uses `SchemaUtils.create` (create-if-not-exists). So a database that already had the tables
(from before the runner existed) is baselined cleanly: V1 runs as a no-op and is simply recorded,
without touching existing data. Fresh databases get the tables created. Either way you end up at V1
and pending migrations apply from there.

## The drift test, and its one quirk

`MigrationDriftTest` boots a fresh in-memory H2, runs all migrations, then uses
`MigrationUtils.statementsRequiredForDatabaseMigration(...)` to assert the live schema matches the
Exposed table definitions.

It filters out `DROP INDEX ... FK_*` statements: **H2 auto-creates an index for every foreign key**,
which Exposed doesn't declare, so MigrationUtils always reports them as "unmapped." They're not real
drift (Postgres doesn't auto-index FKs). Genuine differences — missing/changed tables, columns, or
*declared* indexes — are still asserted on.
