import type { DataModel, Operation } from './types';

/** JSON with sorted keys and undefined treated as null, so equal objects compare equal whoever built them. */
function stable(v: unknown): string {
  if (v === undefined || v === null) return 'null';
  if (Array.isArray(v)) return `[${v.map(stable).join(',')}]`;
  if (typeof v === 'object') {
    const o = v as Record<string, unknown>;
    return `{${Object.keys(o)
      .filter((k) => o[k] !== undefined && o[k] !== null)
      .sort()
      .map((k) => `${JSON.stringify(k)}:${stable(o[k])}`)
      .join(',')}}`;
  }
  return JSON.stringify(v);
}

export interface MergePlan {
  ops: Operation[];
  newTables: string[];
  changedTables: string[];
  newViews: string[];
  changedViews: string[];
  newEnums: string[];
  changedEnums: string[];
  addedFks: number;
  changedFks: number;
  removedFks: number;
}

const qualified = (o: { schema: string; name: string }) => (o.schema === 'public' ? o.name : `${o.schema}.${o.name}`);

/**
 * Operations that turn `current` into `merged` (the server's merge preview). Objects are matched by id; nothing
 * is deleted except foreign keys whose columns the merge removed.
 */
export function planMerge(current: DataModel, merged: DataModel): MergePlan {
  const plan: MergePlan = {
    ops: [],
    newTables: [],
    changedTables: [],
    newViews: [],
    changedViews: [],
    newEnums: [],
    changedEnums: [],
    addedFks: 0,
    changedFks: 0,
    removedFks: 0,
  };

  const enums = new Map(current.enums.map((e) => [e.id, e]));
  for (const e of merged.enums) {
    const old = enums.get(e.id);
    if (old && stable(old) === stable(e)) continue;
    (old ? plan.changedEnums : plan.newEnums).push(qualified(e));
    plan.ops.push({ type: 'upsertEnum', enumType: e });
  }

  const tables = new Map(current.tables.map((t) => [t.id, t]));
  for (const t of merged.tables) {
    const old = tables.get(t.id);
    if (old && stable(old) === stable(t)) continue;
    (old ? plan.changedTables : plan.newTables).push(qualified(t));
    plan.ops.push({ type: 'upsertTable', table: t });
  }

  const views = new Map((current.views ?? []).map((v) => [v.id, v]));
  for (const v of merged.views ?? []) {
    const old = views.get(v.id);
    if (old && stable(old) === stable(v)) continue;
    (old ? plan.changedViews : plan.newViews).push(qualified(v));
    plan.ops.push({ type: 'upsertView', view: v });
  }

  const keptFks = new Set(merged.relationships.map((r) => r.id));
  for (const r of current.relationships) {
    if (keptFks.has(r.id)) continue;
    plan.removedFks++;
    plan.ops.push({ type: 'deleteRelationship', relationshipId: r.id });
  }
  const rels = new Map(current.relationships.map((r) => [r.id, r]));
  for (const r of merged.relationships) {
    const old = rels.get(r.id);
    if (old && stable(old) === stable(r)) continue;
    if (old) plan.changedFks++;
    else plan.addedFks++;
    plan.ops.push({ type: 'upsertRelationship', relationship: r });
  }
  return plan;
}
