# DataGocar

A browser tool where several people design a PostgreSQL data model together, live, and export it as
PostgreSQL DDL or a Liquibase changelog.

- **Backend:** Java 21, Spring Boot 3.3 (Web, WebSocket/STOMP, Data JPA), Liquibase, SQLite
- **Frontend:** React 18 + TypeScript, Vite, React Flow (`@xyflow/react`) for the ER canvas, `@stomp/stompjs`

## Features (MVP)

- ER diagram canvas: tables with columns, PK / FK / UQ badges, drag to move, drag column → column to create a foreign key
- Table editor: name, schema, comment, color; columns (type, PK, NOT NULL, UNIQUE, identity, default, comment, order);
  indexes (multi-column, unique, btree/hash/gin/gist/brin, partial `WHERE`); check constraints
- "+ Reference to…" adds the FK column(s) matching the parent's primary key, an index and the constraint in one step
- Foreign keys: composite column pairs, `ON DELETE` / `ON UPDATE` actions
- PostgreSQL enum types, multiple schemas
- **Views and materialized views:** "+ View" adds a node holding the query (SQL editor in the side panel). Dashed
  arrows show the tables / views it reads (found in its FROM / JOIN clauses). Exported after all tables, in dependency
  order: `CREATE OR REPLACE VIEW` (Liquibase `createView` with `replaceIfExists` and `runOnChange`) or
  `CREATE MATERIALIZED VIEW` (Liquibase `sql` with a `DROP` rollback)
- **Lookup tables (reference data):** "+ Lookup table" creates `id / code / name / sort_order / active`; the table's
  **Data** tab holds its rows (type, or paste from a spreadsheet / CSV, max 1000 rows per table). Exports insert them
  as an idempotent `INSERT ... ON CONFLICT (key) DO UPDATE` right after the tables (Liquibase: `runOnChange` changesets
  with a `DELETE` rollback) and move identity sequences past the seeded ids. An enum can be converted to a lookup
  table: its columns become `smallint` FKs and seed values / defaults are remapped. Any table may carry seed rows
- **Live collaboration:** every change is broadcast instantly; presence avatars; tables and FKs selected by others are
  outlined in their color; offline edits are re-sent after reconnect
- **Import plain SQL:** upload or paste a PostgreSQL DDL script (hand-written or `pg_dump --schema-only`): tables,
  columns, PK / UNIQUE / CHECK / FK constraints, enums, indexes and comments become a new model; `serial` and
  `nextval()` columns become identity columns; `INSERT ... VALUES` rows become seed data (and the table a lookup table);
  `CREATE [MATERIALIZED] VIEW` and their comments are imported (query text kept as written; a view column-name list is
  dropped with a note); functions, triggers, grants, `COPY` data etc. are skipped and listed as notes
- **Export:** PostgreSQL DDL, Liquibase XML changelog, Liquibase formatted-SQL changelog, JSON (re-importable)
- **Design checks:** missing PK, FK type mismatch, FK not referencing a key, unindexed FK columns, duplicate names,
  reserved words, names over 63 chars, identity on non-integer, `serial`/`timestamp` hints; seed rows: values that do
  not fit the column type / length / enum, missing NOT NULL values, duplicate keys, child rows whose FK value is
  missing from the parent's rows, lookup tables without rows; views without a query, not starting with
  SELECT / WITH / VALUES / TABLE, reading no known table (e.g. after a rename), or clashing with a table name
- Operation history per model (`GET /api/models/{id}/history`)

## Run it

Prerequisites: JDK 21, Maven 3.9+, Node 20+. No database server is needed: models are stored in an SQLite file.

```bash
cd backend
mvn spring-boot:run                  # API + WebSocket on :8080, creates ./data/datagocar.db via Liquibase

cd ../frontend
npm install
npm run dev                          # http://localhost:5173 (proxies /api and /ws to :8080)
```

Open the app in two browser windows with different names to see collaboration.

**Single deployable:** `cd frontend && npm run build` writes the bundle to `backend/src/main/resources/static`,
then `cd backend && mvn package` produces one JAR serving UI + API on :8080.

Configuration (env vars): `DATAGOCAR_DB_PATH` (SQLite file, default `./data/datagocar.db`, relative to the working directory);
`datagocar.export.liquibase-author` sets the changeset author (or pass `&author=` on export).

## Architecture

```
frontend (React)                                    backend (Spring Boot)
┌─────────────────────────┐   STOMP /ws            ┌──────────────────────────────────┐
│ Editor / TableEditor    │ ── /app/models/{id}/ops ─▶ CollaborationController          │
│   dispatch(op)          │                        │   CollaborationService            │
│ CollabSession           │                        │     lock model row, apply op,     │
│   confirmed + pending   │ ◀─ /topic/models/{id} ── │     version+1, log op, broadcast │
│   reducer.ts (= Java)   │                        │   OperationApplier (pure Java)    │
│                         │ ── REST /api/models ──▶ │ ModelController, ExportController │
└─────────────────────────┘                        │ ddl/: Postgres, Liquibase XML/SQL │
                                                   │ SQLite: design_model (JSON text), │
                                                   │             model_operation       │
                                                   └──────────────────────────────────┘
```

### Collaboration protocol

The whole design is one JSON document (`DataModel`) stored as JSON text with a version number.
Clients never send the document, only **operations**: `upsertTable`, `deleteTable`, `moveTable`,
`upsertRelationship`, `deleteRelationship`, `upsertEnum`, `deleteEnum`, `upsertView`, `deleteView`, `moveView`,
`renameModel`.

1. The server applies operations one at a time per model (in-process lock; SQLite serializes writes), assigns consecutive versions,
   stores the document and the operation log, and broadcasts `{version, status, clientOpId, op}`.
2. Each client keeps `confirmed` (server state, built only from broadcasts applied in version order) and
   `pending` (its own unconfirmed ops). It shows `confirmed + pending`, so local edits are instant.
3. A version gap triggers a reload of the snapshot. On (re)connect the client subscribes first, loads the
   snapshot, replays buffered events and re-sends pending ops. Operations replace whole objects and are idempotent,
   so re-sending is safe and all clients converge (last writer wins per table / FK / enum).

`OperationApplier.java` and `frontend/src/model/reducer.ts` implement the same rules: keep them in sync.

### REST API

| Method | Path | |
|---|---|---|
| GET / POST | `/api/models` | list / create `{name}` |
| POST | `/api/models/import` | create from an exported JSON document |
| POST | `/api/models/import-sql[?name=]` | create from a PostgreSQL DDL script (`text/plain` body); returns `{model, warnings}` |
| GET / DELETE | `/api/models/{id}` | snapshot `{id, version, document}` / delete |
| POST | `/api/models/{id}/operations` | submit `{clientOpId, author, op}` over HTTP (scripts, CI) |
| GET | `/api/models/{id}/export?format=sql\|liquibase-xml\|liquibase-sql\|json[&download=true]` | export |
| GET | `/api/models/{id}/validation` | design checks |
| GET | `/api/models/{id}/history?limit=100` | operation log |

## Tests

`mvn test` runs the unit tests for the operation rules, JSON shape, SQL / Liquibase generators and validator.

## Known limits and next steps

- **No authentication yet:** users type a display name. Add Spring Security (OAuth2/OIDC login) and per-model
  sharing roles (owner / editor / viewer), then take the author from the principal instead of the message.
- **Single instance:** the simple STOMP broker is in-memory. For several instances use
  `enableStompBrokerRelay` with RabbitMQ and move storage from the local SQLite file to a shared database.
- **Migrations between versions:** export currently produces the full schema. Next: diff two versions
  (from the operation log or saved tags) into incremental `ALTER` SQL / Liquibase changesets.
- Undo/redo (invert operations), reverse-engineering from an existing database, indexes on materialized views,
  renaming tables inside view queries, sequences,
  generated columns, column-level conflict merge, auto-layout, PNG/SVG export of the diagram.
