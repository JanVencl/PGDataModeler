/** Random id. crypto.randomUUID only exists in secure contexts (https / localhost), so fall back. */
export function newId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

export const TABLE_COLORS = ['#4f6bed', '#16a34a', '#d97706', '#db2777', '#0891b2', '#7c3aed', '#64748b'];
const USER_COLORS = ['#e11d48', '#2563eb', '#059669', '#d97706', '#7c3aed', '#0d9488', '#c026d3'];

export function colorFor(name: string): string {
  let h = 0;
  for (const ch of name) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  return USER_COLORS[h % USER_COLORS.length];
}

const USER_KEY = 'datagocar.userName';

export function loadUserName(): string {
  try {
    return localStorage.getItem(USER_KEY) ?? '';
  } catch {
    return '';
  }
}

export function saveUserName(name: string): void {
  try {
    localStorage.setItem(USER_KEY, name);
  } catch {
    /* storage unavailable: name lasts for this page only */
  }
}

export const PG_TYPES = [
  'bigint', 'integer', 'smallint', 'numeric(19,4)', 'double precision', 'real', 'boolean',
  'text', 'varchar(255)', 'char(1)', 'uuid', 'date', 'timestamptz', 'timestamp', 'time', 'interval',
  'jsonb', 'json', 'bytea', 'inet', 'cidr', 'text[]', 'bigint[]', 'tsvector',
];
