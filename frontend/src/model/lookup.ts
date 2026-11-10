// Lookup tables (reference data): presets, row helpers and enum -> lookup conversion.
import { newId, TABLE_COLORS } from '../util';
import type { Column, DataModel, Index, Operation, Position, Relationship, Row, Table } from './types';

export const LOOKUP_COLOR = TABLE_COLORS[6];

function column(name: string, type: string, patch: Partial<Column> = {}): Column {
  return {
    id: newId(),
    name,
    type,
    nullable: true,
    primaryKey: false,
    unique: false,
    defaultValue: null,
    identity: 'NONE',
    comment: null,
    ...patch,
  };
}

function uniqueTableName(model: DataModel, base: string): string {
  let name = base;
  for (let n = 2; model.tables.some((t) => t.name === name); n++) name = `${base}_${n}`;
  return name;
}

/** id / code / name / sort_order / active: the usual shape of a reference-data table. */
export function newLookupTable(model: DataModel, position: Position, name = 'lookup', values: string[] = []): Table {
  const id = column('id', 'smallint', { primaryKey: true, nullable: false, identity: 'ALWAYS' });
  const code = column('code', 'varchar(50)', { nullable: false, unique: true, comment: 'Stable key used by code' });
  const label = column('name', 'varchar(255)', { nullable: false });
  const sort = column('sort_order', 'integer');
  const active = column('active', 'boolean', { nullable: false, defaultValue: 'true' });
  return {
    id: newId(),
    schema: 'public',
    name: uniqueTableName(model, name),
    comment: null,
    columns: [id, code, label, sort, active],
    indexes: [],
    checks: [],
    position,
    color: LOOKUP_COLOR,
    kind: 'LOOKUP',
    rows: values.map((v, i) => ({
      id: newId(),
      values: { [id.id]: String(i + 1), [code.id]: v, [label.id]: humanize(v), [sort.id]: String((i + 1) * 10) },
    })),
  };
}

function humanize(code: string): string {
  const s = code.replace(/[_-]+/g, ' ').trim().toLowerCase();
  return s.charAt(0).toUpperCase() + s.slice(1);
}

const INTEGER = /^(smallint|integer|int|int2|int4|int8|bigint)$/i;

/** Next value for a single integer primary key (max + 1), so new rows get stable explicit ids. */
export function nextKeyValue(table: Table): Record<string, string> {
  const pk = table.columns.filter((c) => c.primaryKey);
  if (pk.length !== 1 || !INTEGER.test(pk[0].type.trim())) return {};
  const max = table.rows.reduce((m, r) => {
    const n = Number(r.values[pk[0].id]);
    return Number.isInteger(n) && n > m ? n : m;
  }, 0);
  return { [pk[0].id]: String(max + 1) };
}

/** Splits one CSV / TSV line; double quotes may wrap a cell ("" is a literal quote). */
function splitLine(line: string, sep: string): string[] {
  const cells: string[] = [];
  let cur = '';
  let quoted = false;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (quoted) {
      if (ch === '"' && line[i + 1] === '"') {
        cur += '"';
        i++;
      } else if (ch === '"') {
        quoted = false;
      } else {
        cur += ch;
      }
    } else if (ch === '"' && cur === '') {
      quoted = true;
    } else if (ch === sep) {
      cells.push(cur);
      cur = '';
    } else {
      cur += ch;
    }
  }
  cells.push(cur);
  return cells.map((c) => c.trim());
}

export interface PasteResult {
  rows: Row[];
  columns: Column[];
  header: boolean;
}

/**
 * Rows pasted from a spreadsheet (tab separated) or CSV. A first line naming the table's columns is used as the
 * header; otherwise cells map to the columns in order (skipping identity columns when the paste is narrow enough).
 */
export function parsePastedRows(text: string, table: Table): PasteResult {
  const lines = text.split(/\r?\n/).filter((l) => l.trim() !== '');
  if (lines.length === 0) return { rows: [], columns: [], header: false };
  const sep = lines[0].includes('\t') ? '\t' : lines[0].includes(';') && !lines[0].includes(',') ? ';' : ',';
  const grid = lines.map((l) => splitLine(l, sep));
  const byName = new Map(table.columns.map((c) => [c.name.toLowerCase(), c]));
  const header = grid[0].every((h) => byName.has(h.toLowerCase()));
  let columns: Column[];
  if (header) {
    columns = grid[0].map((h) => byName.get(h.toLowerCase())!);
  } else {
    // Without a header, a paste no wider than the non-identity columns fills those (ids are generated or
    // added later); a wider one starts at the first column.
    const width = Math.max(...grid.map((r) => r.length));
    const nonIdentity = table.columns.filter((c) => c.identity === 'NONE');
    columns = width <= nonIdentity.length ? nonIdentity.slice(0, width) : table.columns.slice(0, width);
  }
  const rows = grid.slice(header ? 1 : 0).map((cells) => {
    const values: Record<string, string> = {};
    columns.forEach((c, i) => {
      const v = cells[i];
      if (v !== undefined && v !== '') values[c.id] = v;
    });
    return { id: newId(), values };
  });
  return { rows, columns, header };
}

/**
 * Replaces an enum type with a lookup table: one row per value, every column of that enum type becomes a
 * smallint FK (with an index) to the new table, and existing seed values are mapped to the new ids.
 */
export function enumToLookupOps(model: DataModel, enumId: string, position: Position): Operation[] {
  const e = model.enums.find((x) => x.id === enumId);
  if (!e) return [];
  const typeNames = new Set([e.name, `${e.schema}.${e.name}`]);
  const lookup = { ...newLookupTable(model, position, e.name, e.values), schema: e.schema };
  const [idCol, codeCol] = lookup.columns;
  const idByValue = new Map(lookup.rows.map((r) => [r.values[codeCol.id], r.values[idCol.id]]));
  const ops: Operation[] = [{ type: 'upsertTable', table: lookup }];
  for (const t of model.tables) {
    const enumCols = t.columns.filter((c) => typeNames.has(c.type.trim()));
    if (enumCols.length === 0) continue;
    const renamed = new Map(enumCols.map((c) => [c.id, c.name.endsWith('_id') ? c.name : `${c.name}_id`]));
    const columns = t.columns.map((c) =>
      renamed.has(c.id)
        ? {
            ...c,
            name: renamed.get(c.id)!,
            type: 'smallint',
            defaultValue: c.defaultValue ? mapDefault(c.defaultValue, idByValue) : null,
          }
        : c,
    );
    const rows = t.rows.map((r) => {
      const values = { ...r.values };
      for (const c of enumCols) {
        if (values[c.id] !== undefined) values[c.id] = idByValue.get(values[c.id]) ?? values[c.id];
      }
      return { ...r, values };
    });
    const indexes: Index[] = [
      ...t.indexes,
      ...enumCols
        .filter((c) => !t.indexes.some((i) => i.columnIds[0] === c.id))
        .map((c) => ({ id: newId(), name: null, columnIds: [c.id], unique: false, method: 'btree', where: null })),
    ];
    ops.push({ type: 'upsertTable', table: { ...t, columns, rows, indexes } });
    for (const c of enumCols) {
      const rel: Relationship = {
        id: newId(),
        name: null,
        fromTableId: t.id,
        toTableId: lookup.id,
        columns: [{ fromColumnId: c.id, toColumnId: idCol.id }],
        onDelete: 'NO_ACTION',
        onUpdate: 'NO_ACTION',
      };
      ops.push({ type: 'upsertRelationship', relationship: rel });
    }
  }
  ops.push({ type: 'deleteEnum', enumId });
  return ops;
}

/** 'paid' or 'paid'::order_status -> the new id; anything else is dropped. */
function mapDefault(sql: string, idByValue: Map<string, string>): string | null {
  const m = /^'((?:[^']|'')*)'(::.*)?$/.exec(sql.trim());
  if (!m) return null;
  return idByValue.get(m[1].replace(/''/g, "'")) ?? null;
}
