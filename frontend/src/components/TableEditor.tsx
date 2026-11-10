import { useState } from 'react';
import type { Column, DataModel, Identity, Index, Operation, Relationship, Table } from '../model/types';
import { newId, TABLE_COLORS } from '../util';
import { CommitInput } from './CommitInput';
import { DataGrid } from './DataGrid';

interface Props {
  table: Table;
  model: DataModel;
  dispatch: (op: Operation) => void;
  onDeleted: () => void;
}

const METHODS = ['btree', 'hash', 'gin', 'gist', 'brin'];

export function newColumn(name: string, type = 'text'): Column {
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
  };
}

const orNull = (s: string) => (s.trim() === '' ? null : s);

export function TableEditor({ table, model, dispatch, onDeleted }: Props) {
  const [refTarget, setRefTarget] = useState('');
  const [tab, setTab] = useState<'structure' | 'data'>(table.kind === 'LOOKUP' ? 'data' : 'structure');
  const update = (patch: Partial<Table>) => dispatch({ type: 'upsertTable', table: { ...table, ...patch } });
  const updateColumn = (id: string, patch: Partial<Column>) =>
    update({ columns: table.columns.map((c) => (c.id === id ? { ...c, ...patch } : c)) });
  const updateIndex = (id: string, patch: Partial<Index>) =>
    update({ indexes: table.indexes.map((i) => (i.id === id ? { ...i, ...patch } : i)) });

  const moveColumn = (index: number, delta: number) => {
    const target = index + delta;
    if (target < 0 || target >= table.columns.length) return;
    const columns = [...table.columns];
    [columns[index], columns[target]] = [columns[target], columns[index]];
    update({ columns });
  };

  const addColumn = () => {
    let n = table.columns.length + 1;
    while (table.columns.some((c) => c.name === `column_${n}`)) n++;
    update({ columns: [...table.columns, newColumn(`column_${n}`)] });
  };

  /** Adds FK column(s) matching the parent's primary key, an index on them, and the relationship. */
  const addReference = (parentId: string) => {
    const parent = model.tables.find((t) => t.id === parentId);
    if (!parent) return;
    const pk = parent.columns.filter((c) => c.primaryKey);
    if (pk.length === 0) {
      alert(`${parent.name} has no primary key to reference.`);
      return;
    }
    const added = pk.map((p) => {
      const base = pk.length === 1 && p.name === 'id' ? `${parent.name}_id` : `${parent.name}_${p.name}`;
      let name = base;
      for (let i = 2; table.columns.some((c) => c.name === name); i++) name = `${base}${i}`;
      return { ...newColumn(name, p.type), nullable: false };
    });
    const index: Index = { id: newId(), name: null, columnIds: added.map((c) => c.id), unique: false, method: 'btree', where: null };
    dispatch({
      type: 'upsertTable',
      table: { ...table, columns: [...table.columns, ...added], indexes: [...table.indexes, index] },
    });
    const rel: Relationship = {
      id: newId(),
      name: null,
      fromTableId: table.id,
      toTableId: parent.id,
      columns: added.map((c, i) => ({ fromColumnId: c.id, toColumnId: pk[i].id })),
      onDelete: 'NO_ACTION',
      onUpdate: 'NO_ACTION',
    };
    dispatch({ type: 'upsertRelationship', relationship: rel });
    setRefTarget('');
  };

  return (
    <div className="panel">
      <h3>Table</h3>
      <div className="form-grid">
        <label>Name</label>
        <CommitInput value={table.name} onCommit={(v) => v.trim() && update({ name: v.trim() })} />
        <label>Schema</label>
        <CommitInput value={table.schema} onCommit={(v) => update({ schema: v.trim() || 'public' })} />
        <label>Comment</label>
        <CommitInput value={table.comment ?? ''} onCommit={(v) => update({ comment: orNull(v) })} />
        <label>Color</label>
        <div className="swatches">
          {TABLE_COLORS.map((c) => (
            <button
              key={c}
              className={`swatch${(table.color ?? TABLE_COLORS[0]) === c ? ' active' : ''}`}
              style={{ background: c }}
              onClick={() => update({ color: c })}
              aria-label={`Color ${c}`}
            />
          ))}
        </div>
        <label>Kind</label>
        <label className="check" title="Reference data (gender, day of week, roles…): rows are kept in the model and inserted by the exports">
          <input
            type="checkbox"
            checked={table.kind === 'LOOKUP'}
            onChange={(e) => update({ kind: e.target.checked ? 'LOOKUP' : 'ENTITY' })}
          />
          Lookup table (reference data)
        </label>
      </div>

      <div className="tabs section-tabs">
        <button className={tab === 'structure' ? 'tab active' : 'tab'} onClick={() => setTab('structure')}>
          Structure
        </button>
        <button className={tab === 'data' ? 'tab active' : 'tab'} onClick={() => setTab('data')}>
          Data ({table.rows.length})
        </button>
      </div>

      {tab === 'data' && <DataGrid table={table} update={update} />}

      {tab === 'structure' && (
      <>
      <h3>Columns</h3>
      <div className="columns">
        {table.columns.map((c, i) => (
          <div className="column-card" key={c.id}>
            <div className="column-line">
              <CommitInput className="col-name-input" value={c.name} onCommit={(v) => v.trim() && updateColumn(c.id, { name: v.trim() })} />
              <CommitInput className="col-type-input" value={c.type} list="pg-types" onCommit={(v) => updateColumn(c.id, { type: v.trim() || 'text' })} />
              <label className="check" title="Primary key">
                <input
                  type="checkbox"
                  checked={c.primaryKey}
                  onChange={(e) => updateColumn(c.id, { primaryKey: e.target.checked, nullable: e.target.checked ? false : c.nullable })}
                />
                PK
              </label>
              <label className="check" title="NOT NULL">
                <input type="checkbox" checked={!c.nullable} onChange={(e) => updateColumn(c.id, { nullable: !e.target.checked })} />
                NN
              </label>
              <label className="check" title="Unique">
                <input type="checkbox" checked={c.unique} onChange={(e) => updateColumn(c.id, { unique: e.target.checked })} />
                UQ
              </label>
            </div>
            <div className="column-line secondary">
              <select
                value={c.identity}
                title="Identity"
                onChange={(e) => updateColumn(c.id, { identity: e.target.value as Identity })}
              >
                <option value="NONE">no identity</option>
                <option value="ALWAYS">identity always</option>
                <option value="BY_DEFAULT">identity by default</option>
              </select>
              <CommitInput
                placeholder="default (SQL)"
                value={c.defaultValue ?? ''}
                onCommit={(v) => updateColumn(c.id, { defaultValue: orNull(v) })}
              />
              <button className="icon" title="Move up" onClick={() => moveColumn(i, -1)}>↑</button>
              <button className="icon" title="Move down" onClick={() => moveColumn(i, 1)}>↓</button>
              <button
                className="icon danger"
                title="Delete column"
                onClick={() => update({ columns: table.columns.filter((x) => x.id !== c.id) })}
              >
                ✕
              </button>
            </div>
            <div className="column-line secondary">
              <CommitInput placeholder="comment" value={c.comment ?? ''} onCommit={(v) => updateColumn(c.id, { comment: orNull(v) })} />
            </div>
          </div>
        ))}
      </div>
      <div className="row-actions">
        <button onClick={addColumn}>+ Column</button>
        <select value={refTarget} onChange={(e) => addReference(e.target.value)}>
          <option value="">+ Reference to…</option>
          {model.tables.map((t) => (
            <option key={t.id} value={t.id}>
              {t.schema === 'public' ? '' : `${t.schema}.`}
              {t.name}
            </option>
          ))}
        </select>
      </div>

      <h3>Indexes</h3>
      {table.indexes.map((ix) => (
        <div className="column-card" key={ix.id}>
          <div className="column-line">
            <CommitInput placeholder="name (auto)" value={ix.name ?? ''} onCommit={(v) => updateIndex(ix.id, { name: orNull(v) })} />
            <select value={ix.method} onChange={(e) => updateIndex(ix.id, { method: e.target.value })}>
              {METHODS.map((m) => (
                <option key={m}>{m}</option>
              ))}
            </select>
            <label className="check">
              <input type="checkbox" checked={ix.unique} onChange={(e) => updateIndex(ix.id, { unique: e.target.checked })} />
              unique
            </label>
            <button className="icon danger" title="Delete index" onClick={() => update({ indexes: table.indexes.filter((x) => x.id !== ix.id) })}>
              ✕
            </button>
          </div>
          <div className="chips" title="Index columns in order; removing the last column deletes the index">
            {[...ix.columnIds.map((id) => table.columns.find((c) => c.id === id)!).filter(Boolean),
              ...table.columns.filter((c) => !ix.columnIds.includes(c.id))].map((c) => {
              const on = ix.columnIds.includes(c.id);
              return (
                <button
                  key={c.id}
                  className={`chip${on ? ' on' : ''}`}
                  onClick={() =>
                    updateIndex(ix.id, { columnIds: on ? ix.columnIds.filter((x) => x !== c.id) : [...ix.columnIds, c.id] })
                  }
                >
                  {on ? `${ix.columnIds.indexOf(c.id) + 1}. ` : ''}
                  {c.name}
                </button>
              );
            })}
          </div>
          <CommitInput placeholder="WHERE (partial index, optional)" value={ix.where ?? ''} onCommit={(v) => updateIndex(ix.id, { where: orNull(v) })} />
        </div>
      ))}
      <div className="row-actions">
        <button
          disabled={table.columns.length === 0}
          onClick={() =>
            update({
              indexes: [
                ...table.indexes,
                { id: newId(), name: null, columnIds: [table.columns[0].id], unique: false, method: 'btree', where: null },
              ],
            })
          }
        >
          + Index
        </button>
      </div>

      <h3>Check constraints</h3>
      {table.checks.map((ck) => (
        <div className="column-line" key={ck.id}>
          <CommitInput
            placeholder="name (auto)"
            value={ck.name ?? ''}
            onCommit={(v) => update({ checks: table.checks.map((x) => (x.id === ck.id ? { ...x, name: orNull(v) } : x)) })}
          />
          <CommitInput
            placeholder="expression, e.g. price >= 0"
            value={ck.expression}
            onCommit={(v) => update({ checks: table.checks.map((x) => (x.id === ck.id ? { ...x, expression: v } : x)) })}
          />
          <button className="icon danger" onClick={() => update({ checks: table.checks.filter((x) => x.id !== ck.id) })}>
            ✕
          </button>
        </div>
      ))}
      <div className="row-actions">
        <button onClick={() => update({ checks: [...table.checks, { id: newId(), name: null, expression: '' }] })}>+ Check</button>
      </div>
      </>
      )}

      <div className="danger-zone">
        <button
          className="danger"
          onClick={() => {
            if (confirm(`Delete table ${table.name} and its relationships?`)) {
              dispatch({ type: 'deleteTable', tableId: table.id });
              onDeleted();
            }
          }}
        >
          Delete table
        </button>
      </div>
    </div>
  );
}
