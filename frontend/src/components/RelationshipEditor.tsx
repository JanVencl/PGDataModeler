import type { DataModel, FkAction, Operation, Relationship } from '../model/types';
import { CommitInput } from './CommitInput';

interface Props {
  rel: Relationship;
  model: DataModel;
  dispatch: (op: Operation) => void;
  onDeleted: () => void;
}

const ACTIONS: FkAction[] = ['NO_ACTION', 'RESTRICT', 'CASCADE', 'SET_NULL', 'SET_DEFAULT'];

export function RelationshipEditor({ rel, model, dispatch, onDeleted }: Props) {
  const from = model.tables.find((t) => t.id === rel.fromTableId);
  const to = model.tables.find((t) => t.id === rel.toTableId);
  if (!from || !to) return null;
  const update = (patch: Partial<Relationship>) => dispatch({ type: 'upsertRelationship', relationship: { ...rel, ...patch } });

  return (
    <div className="panel">
      <h3>Foreign key</h3>
      <p className="muted">
        <b>{from.name}</b> (child) → <b>{to.name}</b> (parent)
      </p>
      <div className="form-grid">
        <label>Name</label>
        <CommitInput
          placeholder="auto"
          value={rel.name ?? ''}
          onCommit={(v) => update({ name: v.trim() === '' ? null : v.trim() })}
        />
        <label>On delete</label>
        <select value={rel.onDelete} onChange={(e) => update({ onDelete: e.target.value as FkAction })}>
          {ACTIONS.map((a) => (
            <option key={a} value={a}>
              {a.replace('_', ' ')}
            </option>
          ))}
        </select>
        <label>On update</label>
        <select value={rel.onUpdate} onChange={(e) => update({ onUpdate: e.target.value as FkAction })}>
          {ACTIONS.map((a) => (
            <option key={a} value={a}>
              {a.replace('_', ' ')}
            </option>
          ))}
        </select>
      </div>

      <h3>Columns</h3>
      {rel.columns.map((p, i) => (
        <div className="column-line" key={i}>
          <select
            value={p.fromColumnId}
            onChange={(e) =>
              update({ columns: rel.columns.map((x, j) => (j === i ? { ...x, fromColumnId: e.target.value } : x)) })
            }
          >
            {from.columns.map((c) => (
              <option key={c.id} value={c.id}>
                {from.name}.{c.name}
              </option>
            ))}
          </select>
          <span>→</span>
          <select
            value={p.toColumnId}
            onChange={(e) => update({ columns: rel.columns.map((x, j) => (j === i ? { ...x, toColumnId: e.target.value } : x)) })}
          >
            {to.columns.map((c) => (
              <option key={c.id} value={c.id}>
                {to.name}.{c.name}
              </option>
            ))}
          </select>
          <button
            className="icon danger"
            disabled={rel.columns.length === 1}
            onClick={() => update({ columns: rel.columns.filter((_, j) => j !== i) })}
          >
            ✕
          </button>
        </div>
      ))}
      <div className="row-actions">
        <button
          disabled={from.columns.length === 0 || to.columns.length === 0}
          onClick={() => update({ columns: [...rel.columns, { fromColumnId: from.columns[0].id, toColumnId: to.columns[0].id }] })}
        >
          + Column pair
        </button>
      </div>
      <div className="danger-zone">
        <button
          className="danger"
          onClick={() => {
            dispatch({ type: 'deleteRelationship', relationshipId: rel.id });
            onDeleted();
          }}
        >
          Delete foreign key
        </button>
      </div>
    </div>
  );
}
