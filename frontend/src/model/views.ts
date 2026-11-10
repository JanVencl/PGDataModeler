// Which tables / views a view's query reads. Client copy of com.datagocar.ddl.ViewDependencies (a FROM / JOIN
// heuristic, not a SQL parser). Keep both in sync.
import type { DataModel, View } from './types';

type Tok = { kind: 'word' | 'ident' | 'sym'; text: string };

const FROM_END = new Set([
  'where', 'group', 'having', 'order', 'limit', 'offset', 'union', 'intersect', 'except', 'window',
  'fetch', 'for', 'select', 'on', 'using',
]);

/** Words, quoted identifiers and symbols; comments, strings and numbers are dropped. */
function tokenize(sql: string): Tok[] {
  const toks: Tok[] = [];
  let i = 0;
  while (i < sql.length) {
    const ch = sql[i];
    if (/\s/.test(ch)) {
      i++;
    } else if (sql.startsWith('--', i)) {
      const end = sql.indexOf('\n', i);
      i = end < 0 ? sql.length : end;
    } else if (sql.startsWith('/*', i)) {
      const end = sql.indexOf('*/', i + 2);
      i = end < 0 ? sql.length : end + 2;
    } else if (ch === "'") {
      i++;
      while (i < sql.length && !(sql[i] === "'" && sql[i + 1] !== "'")) i += sql[i] === "'" ? 2 : 1;
      i++;
    } else if (ch === '$') {
      const m = /^\$[A-Za-z_]*\$/.exec(sql.slice(i));
      if (m) {
        const end = sql.indexOf(m[0], i + m[0].length);
        i = end < 0 ? sql.length : end + m[0].length;
      } else {
        i++;
      }
    } else if (ch === '"') {
      let text = '';
      i++;
      while (i < sql.length) {
        if (sql[i] === '"' && sql[i + 1] === '"') {
          text += '"';
          i += 2;
        } else if (sql[i] === '"') {
          i++;
          break;
        } else {
          text += sql[i++];
        }
      }
      toks.push({ kind: 'ident', text });
    } else if (/[A-Za-z_]/.test(ch)) {
      const m = /^[A-Za-z_][A-Za-z0-9_$]*/.exec(sql.slice(i))!;
      toks.push({ kind: 'word', text: m[0].toLowerCase() });
      i += m[0].length;
    } else if (/[0-9]/.test(ch)) {
      i += /^[0-9.eE]+/.exec(sql.slice(i))![0].length;
    } else {
      toks.push({ kind: 'sym', text: ch });
      i++;
    }
  }
  return toks;
}

/** Dotted names after FROM, JOIN, ONLY / LATERAL, or a comma inside a FROM list. */
export function relationNames(sql: string): string[][] {
  const toks = tokenize(sql);
  const names: string[][] = [];
  const inFrom: boolean[] = [false];
  let expectName = false;
  const isName = (t: Tok | undefined) => !!t && (t.kind === 'word' || t.kind === 'ident');
  for (let i = 0; i < toks.length; i++) {
    const t = toks[i];
    if (t.kind === 'sym' && t.text === '(') {
      inFrom.push(false);
      expectName = false;
      continue;
    }
    if (t.kind === 'sym' && t.text === ')') {
      if (inFrom.length > 1) inFrom.pop();
      expectName = false;
      continue;
    }
    if (t.kind === 'word') {
      if (t.text === 'from' || t.text === 'join') {
        inFrom[inFrom.length - 1] = true;
        expectName = true;
        continue;
      }
      if (FROM_END.has(t.text)) {
        if (t.text !== 'on' && t.text !== 'using') inFrom[inFrom.length - 1] = false;
        expectName = false;
        continue;
      }
      if (expectName && (t.text === 'only' || t.text === 'lateral')) continue;
    }
    if (t.kind === 'sym' && t.text === ',' && inFrom[inFrom.length - 1]) {
      expectName = true;
      continue;
    }
    if (expectName && isName(t)) {
      const name = [t.text];
      while (toks[i + 1]?.kind === 'sym' && toks[i + 1].text === '.' && isName(toks[i + 2])) {
        name.push(toks[i + 2].text);
        i += 2;
      }
      names.push(name);
    }
    expectName = false;
  }
  return names;
}

/** Ids of the tables and views (other than itself) the view reads, in order of first mention. */
export function viewDependencies(model: DataModel, view: View): string[] {
  const ids: string[] = [];
  const add = (id: string) => !ids.includes(id) && ids.push(id);
  for (const name of relationNames(view.definition)) {
    const rel = name[name.length - 1];
    const schema = name.length > 1 ? name[name.length - 2] : null;
    const match = (s: string, n: string) => n === rel && (schema === null || s === schema);
    model.tables.filter((t) => match(t.schema, t.name)).forEach((t) => add(t.id));
    (model.views ?? []).filter((v) => v.id !== view.id && match(v.schema, v.name)).forEach((v) => add(v.id));
  }
  return ids;
}
