import { useEffect, useState, type ReactNode } from 'react';
import { api } from '../api';
import { enumToLookupOps } from '../model/lookup';
import { type MergePlan, planMerge } from '../model/merge';
import type { DataModel, EnumType, ExportFormat, Operation, Position, ValidationIssue } from '../model/types';
import { newId } from '../util';
import { CommitInput } from './CommitInput';

function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);
  return (
    <div className="modal-backdrop" onMouseDown={onClose}>
      <div className="modal" onMouseDown={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h2>{title}</h2>
          <button className="icon" onClick={onClose} aria-label="Close">
            ✕
          </button>
        </div>
        {children}
      </div>
    </div>
  );
}

interface EnumDialogProps {
  model: DataModel;
  dispatch: (op: Operation) => void;
  onClose: () => void;
  lookupPosition: () => Position;
  onConverted: (tableId: string) => void;
}

export function EnumDialog({ model, dispatch, onClose, lookupPosition, onConverted }: EnumDialogProps) {
  const convert = (e: EnumType) => {
    const users = model.tables.filter((t) => t.columns.some((c) => c.type.trim() === e.name || c.type.trim() === `${e.schema}.${e.name}`));
    const msg =
      `Replace enum ${e.name} with a lookup table holding its ${e.values.length} value(s)?` +
      (users.length ? `\n\nColumns of this type in ${users.map((t) => t.name).join(', ')} become smallint foreign keys (renamed to …_id).` : '');
    if (!confirm(msg)) return;
    const ops = enumToLookupOps(model, e.id, lookupPosition());
    ops.forEach(dispatch);
    const created = ops[0];
    if (created?.type === 'upsertTable') onConverted(created.table.id);
  };
  return (
    <Modal title="Enum types" onClose={onClose}>
      <p className="muted">
        Use an enum by typing its name as a column type. Enums suit small, fixed sets; when values need a label, sort
        order or may change, convert the enum to a lookup table.
      </p>
      {model.enums.map((e) => (
        <div className="column-line" key={e.id}>
          <CommitInput
            className="narrow"
            value={e.schema}
            onCommit={(v) => dispatch({ type: 'upsertEnum', enumType: { ...e, schema: v.trim() || 'public' } })}
          />
          <CommitInput value={e.name} onCommit={(v) => v.trim() && dispatch({ type: 'upsertEnum', enumType: { ...e, name: v.trim() } })} />
          <CommitInput
            className="wide"
            placeholder="values, comma separated"
            value={e.values.join(', ')}
            onCommit={(v) =>
              dispatch({
                type: 'upsertEnum',
                enumType: { ...e, values: v.split(',').map((x) => x.trim()).filter((x) => x !== '') },
              })
            }
          />
          <button title="Replace with a lookup table and foreign keys" onClick={() => convert(e)}>
            → Lookup
          </button>
          <button className="icon danger" onClick={() => dispatch({ type: 'deleteEnum', enumId: e.id })}>
            ✕
          </button>
        </div>
      ))}
      <div className="row-actions">
        <button
          onClick={() => {
            let n = model.enums.length + 1;
            while (model.enums.some((e) => e.name === `enum_${n}`)) n++;
            dispatch({ type: 'upsertEnum', enumType: { id: newId(), schema: 'public', name: `enum_${n}`, values: [] } });
          }}
        >
          + Enum
        </button>
      </div>
    </Modal>
  );
}

const FORMATS: { id: ExportFormat; label: string }[] = [
  { id: 'sql', label: 'PostgreSQL DDL' },
  { id: 'liquibase-xml', label: 'Liquibase XML' },
  { id: 'liquibase-sql', label: 'Liquibase formatted SQL' },
  { id: 'json', label: 'DataGocar JSON' },
];

export function ExportDialog({ modelId, version, pending, onClose }: { modelId: string; version: number; pending: number; onClose: () => void }) {
  const [format, setFormat] = useState<ExportFormat>('sql');
  const [text, setText] = useState('');
  const [issues, setIssues] = useState<ValidationIssue[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    let live = true;
    setError(null);
    api
      .exportText(modelId, format)
      .then((t) => live && setText(t))
      .catch((e: Error) => live && setError(e.message));
    return () => {
      live = false;
    };
  }, [modelId, format, version]);

  useEffect(() => {
    api.validation(modelId).then(setIssues).catch(() => setIssues([]));
  }, [modelId, version]);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      setError('Clipboard not available; use Download instead.');
    }
  };

  return (
    <Modal title="Export" onClose={onClose}>
      <div className="tabs">
        {FORMATS.map((f) => (
          <button key={f.id} className={f.id === format ? 'tab active' : 'tab'} onClick={() => setFormat(f.id)}>
            {f.label}
          </button>
        ))}
      </div>
      {issues.length > 0 && (
        <ul className="issues">
          {issues.map((i, k) => (
            <li key={k} className={i.level === 'ERROR' ? 'error' : 'warning'}>
              <b>{i.level === 'ERROR' ? 'Error' : 'Warning'}:</b> {i.message}
            </li>
          ))}
        </ul>
      )}
      {pending > 0 && <p className="muted">Waiting for {pending} change(s) to reach the server…</p>}
      {error && <p className="error-text">{error}</p>}
      <pre className="export-text">{text}</pre>
      <div className="row-actions">
        <button onClick={copy}>{copied ? 'Copied' : 'Copy'}</button>
        <a className="button" href={api.downloadUrl(modelId, format)}>
          Download
        </a>
      </div>
    </Modal>
  );
}

interface ImportSqlDialogProps {
  modelId: string;
  model: DataModel;
  pending: number;
  dispatch: (op: Operation) => void;
  onClose: () => void;
  onApplied: (newTableIds: string[]) => void;
}

/** Merges a DDL script into the open model: preview the changes, then apply them as ordinary operations. */
export function ImportSqlDialog({ modelId, model, pending, dispatch, onClose, onApplied }: ImportSqlDialogProps) {
  const [sql, setSql] = useState('');
  const [existing, setExisting] = useState<'replace' | 'keep'>('replace');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [preview, setPreview] = useState<{ plan: MergePlan; warnings: string[]; merged: DataModel } | null>(null);

  const runPreview = async () => {
    setBusy(true);
    setError(null);
    try {
      const result = await api.mergeSql(modelId, sql, existing);
      setPreview({ plan: planMerge(model, result.document), warnings: result.warnings, merged: result.document });
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const apply = () => {
    if (!preview) return;
    preview.plan.ops.forEach(dispatch);
    const known = new Set(model.tables.map((t) => t.id));
    onApplied(preview.merged.tables.filter((t) => !known.has(t.id)).map((t) => t.id));
  };

  const list = (label: string, names: string[]) =>
    names.length > 0 && (
      <li>
        {label} ({names.length}): {names.slice(0, 12).join(', ')}
        {names.length > 12 ? `, … ${names.length - 12} more` : ''}
      </li>
    );

  return (
    <Modal title="Import SQL into this model" onClose={onClose}>
      {!preview ? (
        <div className="sql-import">
          <p className="muted">
            PostgreSQL DDL: CREATE TABLE / TYPE … AS ENUM / [MATERIALIZED] VIEW, ALTER TABLE, CREATE INDEX, COMMENT ON, INSERT.
            Statements may refer to tables already in the model. You will see the changes before anything is applied.
          </p>
          <textarea
            rows={16}
            value={sql}
            placeholder="CREATE TABLE invoice (&#10;  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,&#10;  order_id bigint NOT NULL REFERENCES orders&#10;);"
            onChange={(e) => setSql(e.target.value)}
            autoFocus
          />
          <div className="row-actions">
            <label className="button">
              Load file…
              <input
                type="file"
                hidden
                accept=".sql,.ddl,.txt,.pgsql,text/plain,application/sql"
                onChange={async (e) => {
                  const file = e.target.files?.[0];
                  e.target.value = '';
                  if (file) setSql(await file.text());
                }}
              />
            </label>
            <span className="muted">Tables, views and enums that already exist:</span>
            <select value={existing} onChange={(e) => setExisting(e.target.value as 'replace' | 'keep')}>
              <option value="replace">replace with the script's definition</option>
              <option value="keep">keep as they are</option>
            </select>
          </div>
          {pending > 0 && <p className="muted">Waiting for {pending} change(s) to reach the server…</p>}
          {error && <p className="error-text">{error}</p>}
          <div className="row-actions">
            <button className="primary" disabled={!sql.trim() || busy || pending > 0} onClick={runPreview}>
              {busy ? 'Reading…' : 'Preview changes'}
            </button>
            <button onClick={onClose}>Cancel</button>
          </div>
        </div>
      ) : (
        <div className="sql-import">
          {preview.plan.ops.length === 0 ? (
            <p>The script does not change anything in this model.</p>
          ) : (
            <ul className="merge-summary">
              {list('New tables', preview.plan.newTables)}
              {list('Changed tables', preview.plan.changedTables)}
              {list('New views', preview.plan.newViews)}
              {list('Changed views', preview.plan.changedViews)}
              {list('New enums', preview.plan.newEnums)}
              {list('Changed enums', preview.plan.changedEnums)}
              {preview.plan.addedFks + preview.plan.changedFks + preview.plan.removedFks > 0 && (
                <li>
                  Foreign keys: {preview.plan.addedFks} new
                  {preview.plan.changedFks > 0 && `, ${preview.plan.changedFks} changed`}
                  {preview.plan.removedFks > 0 && `, ${preview.plan.removedFks} removed`}
                </li>
              )}
            </ul>
          )}
          {preview.warnings.length > 0 && (
            <>
              <p className="muted">Notes ({preview.warnings.length}):</p>
              <ul className="issues">
                {preview.warnings.map((w, k) => (
                  <li key={k} className="warning">
                    {w}
                  </li>
                ))}
              </ul>
            </>
          )}
          <div className="row-actions">
            <button className="primary" disabled={preview.plan.ops.length === 0} onClick={apply}>
              Apply {preview.plan.ops.length} change(s)
            </button>
            <button onClick={() => setPreview(null)}>Back</button>
            <button onClick={onClose}>Cancel</button>
          </div>
        </div>
      )}
    </Modal>
  );
}
