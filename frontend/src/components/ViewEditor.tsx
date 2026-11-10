import type { DataModel, Operation, View } from '../model/types';
import { viewDependencies } from '../model/views';
import { TABLE_COLORS } from '../util';
import { CommitInput } from './CommitInput';
import { CommitTextArea } from './CommitTextArea';

export const VIEW_COLOR = '#0f766e';
const VIEW_COLORS = [VIEW_COLOR, ...TABLE_COLORS];

interface Props {
  view: View;
  model: DataModel;
  dispatch: (op: Operation) => void;
  onDeleted: () => void;
  onSelect: (kind: 'table' | 'view', id: string) => void;
}

const orNull = (s: string) => (s.trim() === '' ? null : s);

export function ViewEditor({ view, model, dispatch, onDeleted, onSelect }: Props) {
  const update = (patch: Partial<View>) => dispatch({ type: 'upsertView', view: { ...view, ...patch } });
  const reads = viewDependencies(model, view);

  return (
    <div className="panel">
      <h3>{view.materialized ? 'Materialized view' : 'View'}</h3>
      <div className="form-grid">
        <label>Name</label>
        <CommitInput value={view.name} onCommit={(v) => v.trim() && update({ name: v.trim() })} />
        <label>Schema</label>
        <CommitInput value={view.schema} onCommit={(v) => update({ schema: v.trim() || 'public' })} />
        <label>Comment</label>
        <CommitInput value={view.comment ?? ''} onCommit={(v) => update({ comment: orNull(v) })} />
        <label>Color</label>
        <div className="swatches">
          {VIEW_COLORS.map((c) => (
            <button
              key={c}
              className={`swatch${(view.color ?? VIEW_COLOR) === c ? ' active' : ''}`}
              style={{ background: c }}
              onClick={() => update({ color: c })}
              aria-label={`Color ${c}`}
            />
          ))}
        </div>
        <label>Kind</label>
        <label
          className="check"
          title="A materialized view stores its result; refresh it with REFRESH MATERIALIZED VIEW"
        >
          <input type="checkbox" checked={view.materialized} onChange={(e) => update({ materialized: e.target.checked })} />
          Materialized
        </label>
      </div>

      <h3>Query</h3>
      <CommitTextArea
        className="sql-editor"
        rows={14}
        value={view.definition}
        placeholder={'SELECT ...\nFROM ...'}
        onCommit={(v) => update({ definition: v })}
      />
      <p className="muted small">
        The query after <code>AS</code>. Saved when you leave the field or press Ctrl+Enter. Exported as{' '}
        {view.materialized ? (
          <code>CREATE MATERIALIZED VIEW</code>
        ) : (
          <>
            <code>CREATE OR REPLACE VIEW</code> (Liquibase re-runs it when the query changes)
          </>
        )}
        , after all tables and after the views it reads.
      </p>

      <h3>Reads</h3>
      {reads.length === 0 ? (
        <p className="muted small">No table or view of this model found in the FROM / JOIN clauses.</p>
      ) : (
        <div className="chips">
          {reads.map((id) => {
            const t = model.tables.find((x) => x.id === id);
            const v = model.views.find((x) => x.id === id);
            const obj = t ?? v!;
            return (
              <button key={id} className="chip" onClick={() => onSelect(t ? 'table' : 'view', id)}>
                {obj.schema === 'public' ? '' : `${obj.schema}.`}
                {obj.name}
                {v ? ' (view)' : ''}
              </button>
            );
          })}
        </div>
      )}

      <div className="danger-zone">
        <button
          className="danger"
          onClick={() => {
            if (confirm(`Delete view ${view.name}?`)) {
              dispatch({ type: 'deleteView', viewId: view.id });
              onDeleted();
            }
          }}
        >
          Delete view
        </button>
      </div>
    </div>
  );
}
