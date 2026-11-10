import { useEffect, useState } from 'react';
import { api } from '../api';
import type { DataModel, ModelSummary, SqlImportResult } from '../model/types';

interface Props {
  userName: string;
  onUserName: (name: string) => void;
  onOpen: (id: string) => void;
}

export function ModelList({ userName, onUserName, onOpen }: Props) {
  const [models, setModels] = useState<ModelSummary[]>([]);
  const [name, setName] = useState('');
  const [error, setError] = useState<string | null>(null);

  const reload = () => api.listModels().then(setModels).catch((e: Error) => setError(e.message));
  useEffect(() => {
    void reload();
  }, []);

  const create = async () => {
    try {
      const m = await api.createModel(name.trim() || 'Untitled model');
      onOpen(m.id);
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const [sqlOpen, setSqlOpen] = useState(false);
  const [sqlText, setSqlText] = useState('');
  const [sqlResult, setSqlResult] = useState<SqlImportResult | null>(null);
  const [busy, setBusy] = useState(false);

  const importFile = async (file: File) => {
    try {
      const doc = JSON.parse(await file.text()) as DataModel;
      const m = await api.importModel(doc);
      onOpen(m.id);
    } catch (e) {
      setError(`Import failed: ${(e as Error).message}`);
    }
  };

  /** Imports a DDL script; opens the model straight away unless there are warnings worth reading first. */
  const importSql = async (sql: string, modelName: string) => {
    setError(null);
    setSqlResult(null);
    setBusy(true);
    try {
      const result = await api.importSql(sql, modelName);
      if (result.warnings.length === 0) {
        onOpen(result.model.id);
        return;
      }
      setSqlResult(result);
      setSqlOpen(false);
      setSqlText('');
      void reload();
    } catch (e) {
      setError(`SQL import failed: ${(e as Error).message}`);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="home">
      <h1>PGDataModeler</h1>
      <p className="muted">Collaborative PostgreSQL data-model designer</p>

      <section className="card">
        <label>
          Your name (shown to collaborators)
          <input value={userName} onChange={(e) => onUserName(e.target.value)} placeholder="e.g. Jan" />
        </label>
      </section>

      <section className="card">
        <h2>New model</h2>
        <div className="row-actions">
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Model name" onKeyDown={(e) => e.key === 'Enter' && create()} />
          <button className="primary" disabled={!userName.trim()} onClick={create}>
            Create
          </button>
          <label className={`button${userName.trim() ? '' : ' disabled'}`}>
            Import JSON
            <input
              type="file"
              accept=".json,application/json"
              hidden
              disabled={!userName.trim()}
              onChange={(e) => e.target.files?.[0] && importFile(e.target.files[0])}
            />
          </label>
          <label className={`button${userName.trim() && !busy ? '' : ' disabled'}`} title="PostgreSQL DDL or pg_dump --schema-only output">
            Import SQL file
            <input
              type="file"
              accept=".sql,.ddl,.txt,.pgsql,text/plain,application/sql"
              hidden
              disabled={!userName.trim() || busy}
              onChange={async (e) => {
                const file = e.target.files?.[0];
                e.target.value = '';
                if (file) await importSql(await file.text(), name.trim() || file.name.replace(/\.[^.]+$/, ''));
              }}
            />
          </label>
          <button disabled={!userName.trim()} onClick={() => setSqlOpen((o) => !o)}>
            {sqlOpen ? 'Cancel' : 'Paste SQL'}
          </button>
        </div>
        {sqlOpen && (
          <div className="sql-import">
            <textarea
              value={sqlText}
              onChange={(e) => setSqlText(e.target.value)}
              placeholder={'CREATE TABLE customer (\n  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,\n  email varchar(255) NOT NULL UNIQUE\n);'}
              rows={12}
              spellCheck={false}
            />
            <div className="row-actions">
              <button className="primary" disabled={!sqlText.trim() || busy} onClick={() => importSql(sqlText, name.trim() || 'Imported SQL')}>
                {busy ? 'Importing…' : 'Import'}
              </button>
              <span className="muted">Tables, enums, constraints, indexes and comments are imported; other statements are skipped.</span>
            </div>
          </div>
        )}
        {sqlResult && (
          <div className="sql-import">
            <p>
              Imported <b>{sqlResult.model.name}</b> with {sqlResult.warnings.length} note(s):
            </p>
            <ul className="issues">
              {sqlResult.warnings.map((w, k) => (
                <li key={k} className="warning">
                  {w}
                </li>
              ))}
            </ul>
            <div className="row-actions">
              <button className="primary" onClick={() => onOpen(sqlResult.model.id)}>
                Open model
              </button>
              <button onClick={() => setSqlResult(null)}>Dismiss</button>
            </div>
          </div>
        )}
      </section>

      <section className="card">
        <h2>Models</h2>
        {error && <p className="error-text">{error}</p>}
        {models.length === 0 && <p className="muted">No models yet.</p>}
        <ul className="model-list">
          {models.map((m) => (
            <li key={m.id}>
              <button className="link" disabled={!userName.trim()} onClick={() => onOpen(m.id)}>
                {m.name}
              </button>
              <span className="muted">
                v{m.version} · {new Date(m.updatedAt).toLocaleString()}
              </span>
              <button
                className="icon danger"
                title="Delete model"
                onClick={async () => {
                  if (confirm(`Delete model "${m.name}" for everyone?`)) {
                    await api.deleteModel(m.id).catch((e: Error) => setError(e.message));
                    void reload();
                  }
                }}
              >
                ✕
              </button>
            </li>
          ))}
        </ul>
        {!userName.trim() && <p className="muted">Enter your name to open a model.</p>}
      </section>
    </div>
  );
}
