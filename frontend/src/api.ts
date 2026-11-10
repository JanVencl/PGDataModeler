import type { DataModel, ExportFormat, ModelSnapshot, ModelSummary, SqlImportResult, SqlMergeResult, ValidationIssue } from './model/types';

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
  });
  if (!res.ok) {
    let detail = res.statusText;
    try {
      detail = (await res.json()).detail ?? detail;
    } catch {
      /* not JSON */
    }
    throw new Error(detail);
  }
  if (res.status === 204) return undefined as T;
  const type = res.headers.get('content-type') ?? '';
  return (type.includes('json') ? res.json() : res.text()) as Promise<T>;
}

export const api = {
  listModels: () => request<ModelSummary[]>('/api/models'),
  createModel: (name: string) => request<ModelSummary>('/api/models', { method: 'POST', body: JSON.stringify({ name }) }),
  importModel: (doc: DataModel) =>
    request<ModelSummary>('/api/models/import', { method: 'POST', body: JSON.stringify(doc) }),
  importSql: (sql: string, name: string) =>
    request<SqlImportResult>(`/api/models/import-sql?name=${encodeURIComponent(name)}`, {
      method: 'POST',
      body: sql,
      headers: { 'Content-Type': 'text/plain' },
    }),
  /** Merged preview of a DDL script applied to an existing model; nothing is saved. */
  mergeSql: (id: string, sql: string, existing: 'replace' | 'keep') =>
    request<SqlMergeResult>(`/api/models/${id}/merge-sql?existing=${existing}`, {
      method: 'POST',
      body: sql,
      headers: { 'Content-Type': 'text/plain' },
    }),
  deleteModel: (id: string) => request<void>(`/api/models/${id}`, { method: 'DELETE' }),
  snapshot: (id: string) => request<ModelSnapshot>(`/api/models/${id}`),
  validation: (id: string) => request<ValidationIssue[]>(`/api/models/${id}/validation`),
  exportText: async (id: string, format: ExportFormat): Promise<string> => {
    const res = await fetch(`/api/models/${id}/export?format=${format}`);
    if (!res.ok) throw new Error(`Export failed: ${res.status}`);
    return res.text();
  },
  downloadUrl: (id: string, format: ExportFormat) => `/api/models/${id}/export?format=${format}&download=true`,
};
