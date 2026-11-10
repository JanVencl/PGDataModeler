// Mirrors com.pgdatamodeler.model.* and com.pgdatamodeler.ops.ModelOperation on the backend.

export type Identity = 'NONE' | 'ALWAYS' | 'BY_DEFAULT';
export type FkAction = 'NO_ACTION' | 'RESTRICT' | 'CASCADE' | 'SET_NULL' | 'SET_DEFAULT';

export interface Column {
  id: string;
  name: string;
  type: string;
  nullable: boolean;
  primaryKey: boolean;
  unique: boolean;
  defaultValue: string | null;
  identity: Identity;
  comment: string | null;
}

export interface Index {
  id: string;
  name: string | null;
  columnIds: string[];
  unique: boolean;
  method: string;
  where: string | null;
}

export interface CheckConstraint {
  id: string;
  name: string | null;
  expression: string;
}

export interface Position {
  x: number;
  y: number;
}

/** LOOKUP tables hold reference data (genders, days of week, company roles) that the exports insert. */
export type TableKind = 'ENTITY' | 'LOOKUP';

/**
 * One seed-data row. values: column id -> value as typed ("42", "true", "Female"). A column without a value
 * gets its DEFAULT (NULL when it has none).
 */
export interface Row {
  id: string;
  values: Record<string, string>;
}

/** Most rows a table may carry (Table.MAX_ROWS on the backend). */
export const MAX_ROWS = 1000;

export interface Table {
  id: string;
  schema: string;
  name: string;
  comment: string | null;
  columns: Column[];
  indexes: Index[];
  checks: CheckConstraint[];
  position: Position;
  color: string | null;
  kind: TableKind;
  rows: Row[];
}

export interface ColumnPair {
  fromColumnId: string;
  toColumnId: string;
}

/** Foreign key: fromTable (child) references toTable (parent). */
export interface Relationship {
  id: string;
  name: string | null;
  fromTableId: string;
  toTableId: string;
  columns: ColumnPair[];
  onDelete: FkAction;
  onUpdate: FkAction;
}

export interface EnumType {
  id: string;
  schema: string;
  name: string;
  values: string[];
}

/** A SQL view or materialized view. definition is the query after AS (no trailing semicolon). */
export interface View {
  id: string;
  schema: string;
  name: string;
  comment: string | null;
  materialized: boolean;
  definition: string;
  position: Position;
  color: string | null;
}

export interface DataModel {
  name: string;
  tables: Table[];
  relationships: Relationship[];
  enums: EnumType[];
  views: View[];
}

export type Operation =
  | { type: 'upsertTable'; table: Table }
  | { type: 'deleteTable'; tableId: string }
  | { type: 'moveTable'; tableId: string; x: number; y: number }
  | { type: 'upsertRelationship'; relationship: Relationship }
  | { type: 'deleteRelationship'; relationshipId: string }
  | { type: 'upsertEnum'; enumType: EnumType }
  | { type: 'deleteEnum'; enumId: string }
  | { type: 'upsertView'; view: View }
  | { type: 'deleteView'; viewId: string }
  | { type: 'moveView'; viewId: string; x: number; y: number }
  | { type: 'renameModel'; name: string };

export interface OperationEvent {
  modelId: string;
  version: number;
  status: 'APPLIED' | 'REJECTED';
  clientOpId: string | null;
  author: string | null;
  op: Operation;
  error: string | null;
}

export interface ModelSnapshot {
  id: string;
  version: number;
  document: DataModel;
}

export interface ModelSummary {
  id: string;
  name: string;
  version: number;
  updatedAt: string;
}

/** Response of POST /api/models/import-sql: the new model and what could not be imported. */
export interface SqlImportResult {
  model: ModelSummary;
  warnings: string[];
}

export interface SqlMergeResult {
  document: DataModel;
  warnings: string[];
}

export interface Participant {
  sessionId: string;
  userName: string;
  color: string | null;
  selectedId: string | null;
}

export interface ValidationIssue {
  level: 'ERROR' | 'WARNING';
  objectId: string;
  message: string;
}

export type ExportFormat = 'sql' | 'liquibase-xml' | 'liquibase-sql' | 'json';
