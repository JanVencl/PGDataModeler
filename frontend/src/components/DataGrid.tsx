import { useState } from 'react';
import { nextKeyValue, parsePastedRows } from '../model/lookup';
import { MAX_ROWS, type Column, type Row, type Table } from '../model/types';
import { newId } from '../util';
import { CommitInput } from './CommitInput';

interface Props {
  table: Table;
  update: (patch: Partial<Table>) => void;
}

function placeholder(c: Column): string {
  if (c.identity !== 'NONE') return 'auto';
  if (c.defaultValue) return `= ${c.defaultValue}`;
  return c.nullable && !c.primaryKey ? 'NULL' : 'required';
}

/**
 * Seed rows of a table, one input per cell. An empty cell means "no value": the column's DEFAULT
 * (NULL without one). Values are typed as plain text; the export turns them into SQL literals.
 */
export function DataGrid({ table, update }: Props) {
  const [paste, setPaste] = useState<string | null>(null);
  const [pasteNote, setPasteNote] = useState<string | null>(null);
  const rows = table.rows;
  const full = rows.length >= MAX_ROWS;

  const setRows = (next: Row[]) => update({ rows: next });
  const setCell = (rowId: string, columnId: string, value: string) =>
    setRows(
      rows.map((r) => {
        if (r.id !== rowId) return r;
        const values = { ...r.values };
        if (value === '') delete values[columnId];
        else values[columnId] = value;
        return { ...r, values };
      }),
    );
  const move = (index: number, delta: number) => {
    const target = index + delta;
    if (target < 0 || target >= rows.length) return;
    const next = [...rows];
    [next[index], next[target]] = [next[target], next[index]];
    setRows(next);
  };

  const applyPaste = (mode: 'append' | 'replace') => {
    if (paste === null) return;
    const result = parsePastedRows(paste, table);
    if (result.rows.length === 0) {
      setPasteNote('Nothing to add: paste lines of tab- or comma-separated values.');
      return;
    }
    const base = mode === 'replace' ? [] : rows;
    const room = MAX_ROWS - base.length;
    // Give rows without a key value explicit ids continuing after the existing ones (stable across environments).
    const added: Row[] = [];
    for (const r of result.rows.slice(0, room)) {
      const key = nextKeyValue({ ...table, rows: [...base, ...added] });
      const missing = Object.fromEntries(Object.entries(key).filter(([col]) => r.values[col] === undefined));
      added.push({ ...r, values: { ...missing, ...r.values } });
    }
    setRows([...base, ...added]);
    setPaste(null);
    setPasteNote(
      `${Math.min(room, result.rows.length)} row(s) into ${result.columns.map((c) => c.name).join(', ')}` +
        (result.header ? ' (first line used as header)' : '') +
        (result.rows.length > room ? `; ${result.rows.length - room} dropped (limit ${MAX_ROWS})` : '') +
        '.',
    );
  };

  if (table.columns.length === 0) return <p className="muted">Add columns first.</p>;

  return (
    <div>
      <p className="muted small">
        Exported as an idempotent <code>INSERT … ON CONFLICT</code> right after the tables. Empty cell = column default.
      </p>
      <div className="data-grid-wrap">
        <table className="data-grid">
          <thead>
            <tr>
              <th className="rownum">#</th>
              {table.columns.map((c) => (
                <th key={c.id} title={c.type}>
                  {c.name}
                  <span className="th-type">{c.type}</span>
                </th>
              ))}
              <th />
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={r.id}>
                <td className="rownum">{i + 1}</td>
                {table.columns.map((c) => (
                  <td key={c.id}>
                    <CommitInput
                      value={r.values[c.id] ?? ''}
                      placeholder={placeholder(c)}
                      onCommit={(v) => setCell(r.id, c.id, v)}
                    />
                  </td>
                ))}
                <td className="row-tools">
                  <button className="icon" title="Move up" onClick={() => move(i, -1)}>↑</button>
                  <button className="icon" title="Move down" onClick={() => move(i, 1)}>↓</button>
                  <button className="icon danger" title="Delete row" onClick={() => setRows(rows.filter((x) => x.id !== r.id))}>
                    ✕
                  </button>
                </td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr>
                <td className="muted empty-rows" colSpan={table.columns.length + 2}>
                  No rows yet.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      <div className="row-actions">
        <button disabled={full} onClick={() => setRows([...rows, { id: newId(), values: nextKeyValue(table) }])}>
          + Row
        </button>
        <button disabled={full} onClick={() => setPaste(paste === null ? '' : null)}>
          Paste rows…
        </button>
        {rows.length > 0 && (
          <button className="danger" onClick={() => confirm(`Delete all ${rows.length} rows of ${table.name}?`) && setRows([])}>
            Clear
          </button>
        )}
        <span className="muted small">
          {rows.length} / {MAX_ROWS}
        </span>
      </div>
      {pasteNote && <p className="muted small">{pasteNote}</p>}
      {paste !== null && (
        <div className="paste-box">
          <textarea
            autoFocus
            rows={6}
            value={paste}
            placeholder={`Paste from a spreadsheet or CSV. Optional header line, e.g.\n${table.columns.map((c) => c.name).join('\t')}`}
            onChange={(e) => setPaste(e.target.value)}
          />
          <div className="row-actions">
            <button className="primary" onClick={() => applyPaste('append')}>
              Add rows
            </button>
            {rows.length > 0 && <button onClick={() => applyPaste('replace')}>Replace all rows</button>}
            <button onClick={() => setPaste(null)}>Cancel</button>
          </div>
        </div>
      )}
    </div>
  );
}
