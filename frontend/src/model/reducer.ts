// Client copy of com.datagocar.ops.OperationApplier. Keep both in sync.
import { MAX_ROWS, type DataModel, type EnumType, type Operation, type Relationship, type Row, type Table, type View } from './types';

export class InvalidOperation extends Error {}

const blank = (s: string | null | undefined) => !s || s.trim() === '';

function replaceOrAppend<T extends { id: string }>(list: T[], item: T): T[] {
  let replaced = false;
  const result = list.map((x) => {
    if (x.id === item.id) {
      replaced = true;
      return item;
    }
    return x;
  });
  return replaced ? result : [...result, item];
}

function columnExists(model: DataModel, tableId: string, columnId: string): boolean {
  return !!model.tables.find((t) => t.id === tableId)?.columns.some((c) => c.id === columnId);
}

function upsertTable(model: DataModel, table: Table): DataModel {
  if (blank(table.id)) throw new InvalidOperation('Table id is required');
  if (blank(table.name)) throw new InvalidOperation('Table name is required');
  const ids = new Set<string>();
  for (const c of table.columns) {
    if (blank(c.id) || blank(c.name)) throw new InvalidOperation('Every column needs an id and a name');
    if (ids.has(c.id)) throw new InvalidOperation(`Duplicate column id ${c.id}`);
    ids.add(c.id);
  }
  const rows: Row[] = table.rows ?? [];
  if (rows.length > MAX_ROWS) throw new InvalidOperation(`A table can carry at most ${MAX_ROWS} data rows`);
  const rowIds = new Set<string>();
  for (const r of rows) {
    if (blank(r.id) || rowIds.has(r.id)) throw new InvalidOperation('Every data row needs a unique id');
    rowIds.add(r.id);
  }
  const cleaned: Table = {
    ...table,
    kind: table.kind ?? 'ENTITY',
    // Drop row values of columns that no longer exist.
    rows: rows.map((r) =>
      Object.keys(r.values).every((k) => ids.has(k))
        ? r
        : { ...r, values: Object.fromEntries(Object.entries(r.values).filter(([k]) => ids.has(k))) },
    ),
    schema: blank(table.schema) ? 'public' : table.schema.trim(),
    indexes: table.indexes
      .map((i) => ({ ...i, columnIds: i.columnIds.filter((id) => ids.has(id)) }))
      .filter((i) => i.columnIds.length > 0),
  };
  const updated: DataModel = { ...model, tables: replaceOrAppend(model.tables, cleaned) };
  const relationships = updated.relationships
    .map((r) =>
      r.fromTableId !== table.id && r.toTableId !== table.id
        ? r
        : {
            ...r,
            columns: r.columns.filter(
              (p) => columnExists(updated, r.fromTableId, p.fromColumnId) && columnExists(updated, r.toTableId, p.toColumnId),
            ),
          },
    )
    .filter((r) => r.columns.length > 0);
  return { ...updated, relationships };
}

function upsertRelationship(model: DataModel, rel: Relationship): DataModel {
  if (blank(rel.id)) throw new InvalidOperation('Relationship id is required');
  const from = model.tables.find((t) => t.id === rel.fromTableId);
  const to = model.tables.find((t) => t.id === rel.toTableId);
  if (!from) throw new InvalidOperation('Unknown child table');
  if (!to) throw new InvalidOperation('Unknown parent table');
  if (rel.columns.length === 0) throw new InvalidOperation('A relationship needs at least one column pair');
  for (const p of rel.columns) {
    if (!from.columns.some((c) => c.id === p.fromColumnId)) throw new InvalidOperation(`Unknown column in ${from.name}`);
    if (!to.columns.some((c) => c.id === p.toColumnId)) throw new InvalidOperation(`Unknown column in ${to.name}`);
  }
  return { ...model, relationships: replaceOrAppend(model.relationships, rel) };
}

function upsertEnum(model: DataModel, e: EnumType): DataModel {
  if (blank(e.id) || blank(e.name)) throw new InvalidOperation('Enum id and name are required');
  return { ...model, enums: replaceOrAppend(model.enums, { ...e, schema: blank(e.schema) ? 'public' : e.schema }) };
}

function upsertView(model: DataModel, v: View): DataModel {
  if (blank(v.id) || blank(v.name)) throw new InvalidOperation('View id and name are required');
  const definition = (v.definition ?? '').trim().replace(/(\s*;)+$/, '');
  return {
    ...model,
    views: replaceOrAppend(model.views ?? [], { ...v, definition, schema: blank(v.schema) ? 'public' : v.schema.trim() }),
  };
}

export function applyOp(model: DataModel, op: Operation): DataModel {
  switch (op.type) {
    case 'upsertView':
      return upsertView(model, op.view);
    case 'deleteView':
      return { ...model, views: (model.views ?? []).filter((v) => v.id !== op.viewId) };
    case 'moveView':
      return {
        ...model,
        views: (model.views ?? []).map((v) => (v.id === op.viewId ? { ...v, position: { x: op.x, y: op.y } } : v)),
      };
    case 'upsertTable':
      return upsertTable(model, op.table);
    case 'deleteTable':
      return {
        ...model,
        tables: model.tables.filter((t) => t.id !== op.tableId),
        relationships: model.relationships.filter((r) => r.fromTableId !== op.tableId && r.toTableId !== op.tableId),
      };
    case 'moveTable':
      return {
        ...model,
        tables: model.tables.map((t) => (t.id === op.tableId ? { ...t, position: { x: op.x, y: op.y } } : t)),
      };
    case 'upsertRelationship':
      return upsertRelationship(model, op.relationship);
    case 'deleteRelationship':
      return { ...model, relationships: model.relationships.filter((r) => r.id !== op.relationshipId) };
    case 'upsertEnum':
      return upsertEnum(model, op.enumType);
    case 'deleteEnum':
      return { ...model, enums: model.enums.filter((e) => e.id !== op.enumId) };
    case 'renameModel':
      if (blank(op.name)) throw new InvalidOperation('Model name must not be blank');
      return { ...model, name: op.name.trim() };
  }
}

/** Applies ops in order, skipping any that are invalid against the current state. */
export function applyAll(model: DataModel, ops: Operation[]): DataModel {
  return ops.reduce((acc, op) => {
    try {
      return applyOp(acc, op);
    } catch {
      return acc;
    }
  }, model);
}
