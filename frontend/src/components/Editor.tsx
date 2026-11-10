import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  applyNodeChanges,
  Background,
  type Connection,
  Controls,
  type Edge,
  type EdgeChange,
  MarkerType,
  MiniMap,
  type NodeChange,
  ReactFlow,
  ReactFlowProvider,
  useReactFlow,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { useCollabSession } from '../collab/useCollabSession';
import type { Participant, Relationship, Table, View } from '../model/types';
import { viewDependencies } from '../model/views';
import { newId, PG_TYPES, TABLE_COLORS } from '../util';
import { CommitInput } from './CommitInput';
import { EnumDialog, ExportDialog, ImportSqlDialog } from './Dialogs';
import { RelationshipEditor } from './RelationshipEditor';
import { RoutedEdge } from './RoutedEdge';
import { newLookupTable } from '../model/lookup';
import { newColumn, TableEditor } from './TableEditor';
import { TableNode, type TableFlowNode } from './TableNode';
import { VIEW_COLOR, ViewEditor } from './ViewEditor';
import { ViewNode, type ViewFlowNode } from './ViewNode';

type Selection = { kind: 'table' | 'relationship' | 'view'; id: string } | null;
type FlowNode = TableFlowNode | ViewFlowNode;

const nodeTypes = { table: TableNode, view: ViewNode };
const edgeTypes = { routed: RoutedEdge };
const DEP_EDGE_PREFIX = 'dep:';

interface Props {
  modelId: string;
  userName: string;
  onBack: () => void;
}

export function Editor(props: Props) {
  return (
    <ReactFlowProvider>
      <EditorInner {...props} />
    </ReactFlowProvider>
  );
}

function EditorInner({ modelId, userName, onBack }: Props) {
  const { session, state } = useCollabSession(modelId, userName);
  const model = state.model;
  const dispatch = useCallback((op: Parameters<typeof session.dispatch>[0]) => session.dispatch(op), [session]);
  const [selection, setSelection] = useState<Selection>(null);
  const [dialog, setDialog] = useState<'enums' | 'export' | 'import' | null>(null);
  const [nodes, setNodes] = useState<FlowNode[]>([]);
  const nodesRef = useRef(nodes);
  nodesRef.current = nodes;
  const { screenToFlowPosition, fitView } = useReactFlow();

  useEffect(() => session.select(selection?.id ?? null), [session, selection]);

  // Others' selections, keyed by object id (own tabs are filtered by name).
  const remoteBySelection = useMemo(() => {
    const map = new Map<string, Participant[]>();
    for (const p of state.participants) {
      if (p.userName === userName || !p.selectedId) continue;
      map.set(p.selectedId, [...(map.get(p.selectedId) ?? []), p]);
    }
    return map;
  }, [state.participants, userName]);

  const fkColumnsByTable = useMemo(() => {
    const map = new Map<string, Set<string>>();
    for (const r of model?.relationships ?? []) {
      const set = map.get(r.fromTableId) ?? new Set<string>();
      r.columns.forEach((p) => set.add(p.fromColumnId));
      map.set(r.fromTableId, set);
    }
    return map;
  }, [model]);

  // View id -> ids of the tables / views its query reads.
  const viewDeps = useMemo(() => {
    const map = new Map<string, string[]>();
    for (const v of model?.views ?? []) map.set(v.id, viewDependencies(model!, v));
    return map;
  }, [model]);

  // Model → React Flow nodes, preserving React Flow's own measurements and in-progress drags.
  useEffect(() => {
    if (!model) return;
    setNodes((prev) => {
      const byId = new Map(prev.map((n) => [n.id, n]));
      const nameOf = (id: string) => model.tables.find((t) => t.id === id)?.name ?? model.views.find((v) => v.id === id)?.name ?? '?';
      const tables = model.tables.map((t): TableFlowNode => {
        const old = byId.get(t.id) as TableFlowNode | undefined;
        return {
          ...(old ?? {}),
          id: t.id,
          type: 'table',
          position: old?.dragging ? old.position : t.position,
          data: { table: t, fkColumnIds: fkColumnsByTable.get(t.id) ?? new Set(), remote: remoteBySelection.get(t.id) ?? [] },
          selected: selection?.kind === 'table' && selection.id === t.id,
        };
      });
      const views = (model.views ?? []).map((v): ViewFlowNode => {
        const old = byId.get(v.id) as ViewFlowNode | undefined;
        return {
          ...(old ?? {}),
          id: v.id,
          type: 'view',
          position: old?.dragging ? old.position : v.position,
          data: { view: v, reads: (viewDeps.get(v.id) ?? []).map(nameOf), remote: remoteBySelection.get(v.id) ?? [] },
          selected: selection?.kind === 'view' && selection.id === v.id,
        };
      });
      return [...tables, ...views];
    });
  }, [model, selection, fkColumnsByTable, remoteBySelection, viewDeps]);

  const edges = useMemo<Edge[]>(
    () =>
      (model?.relationships ?? []).map((r): Edge => {
        const remote = remoteBySelection.get(r.id);
        const selected = selection?.kind === 'relationship' && selection.id === r.id;
        return {
          id: r.id,
          source: r.fromTableId,
          target: r.toTableId,
          sourceHandle: `s-${r.columns[0].fromColumnId}`,
          targetHandle: `t-${r.columns[0].toColumnId}`,
          type: 'routed',
          selected,
          markerEnd: { type: MarkerType.ArrowClosed },
          style: remote ? { stroke: remote[0].color ?? undefined, strokeWidth: 3 } : selected ? { strokeWidth: 3 } : undefined,
          label: r.columns.length > 1 ? `${r.columns.length} cols` : undefined,
        };
      }).concat(
        // Dashed, read-only edges from each view to what it reads.
        [...viewDeps].flatMap(([viewId, ids]) =>
          ids.map((id) => ({
            id: `${DEP_EDGE_PREFIX}${viewId}:${id}`,
            source: viewId,
            target: id,
            sourceHandle: 'view-source',
            targetHandle: 'view-target',
            type: 'routed',
            selectable: false,
            deletable: false,
            className: 'dep-edge',
            markerEnd: { type: MarkerType.Arrow },
          })),
        ),
      ),
    [model, selection, remoteBySelection, viewDeps],
  );

  const onNodesChange = useCallback(
    (changes: NodeChange<FlowNode>[]) => {
      const keep: NodeChange<FlowNode>[] = [];
      const isView = (id: string) => nodesRef.current.find((n) => n.id === id)?.type === 'view';
      for (const c of changes) {
        if (c.type === 'remove') {
          dispatch(isView(c.id) ? { type: 'deleteView', viewId: c.id } : { type: 'deleteTable', tableId: c.id });
          continue;
        }
        if (c.type === 'select') continue; // selection is ours
        if (c.type === 'position' && c.dragging === false) {
          const pos = c.position ?? nodesRef.current.find((n) => n.id === c.id)?.position;
          if (pos) {
            const x = Math.round(pos.x);
            const y = Math.round(pos.y);
            dispatch(isView(c.id) ? { type: 'moveView', viewId: c.id, x, y } : { type: 'moveTable', tableId: c.id, x, y });
          }
        }
        keep.push(c);
      }
      setNodes((nds) => applyNodeChanges(keep, nds) as FlowNode[]);
    },
    [dispatch],
  );

  const onEdgesChange = useCallback(
    (changes: EdgeChange[]) => {
      for (const c of changes) {
        if (c.type === 'remove' && !c.id.startsWith(DEP_EDGE_PREFIX)) dispatch({ type: 'deleteRelationship', relationshipId: c.id });
      }
    },
    [dispatch],
  );

  const onConnect = useCallback(
    (conn: Connection) => {
      // Only column-to-column connections make foreign keys (view handles are for dependency edges).
      if (!conn.sourceHandle?.startsWith('s-') || !conn.targetHandle?.startsWith('t-')) return;
      const rel: Relationship = {
        id: newId(),
        name: null,
        fromTableId: conn.source,
        toTableId: conn.target,
        columns: [{ fromColumnId: conn.sourceHandle.slice(2), toColumnId: conn.targetHandle.slice(2) }],
        onDelete: 'NO_ACTION',
        onUpdate: 'NO_ACTION',
      };
      dispatch({ type: 'upsertRelationship', relationship: rel });
      setSelection({ kind: 'relationship', id: rel.id });
    },
    [dispatch],
  );

  const addTable = () => {
    if (!model) return;
    let n = model.tables.length + 1;
    while (model.tables.some((t) => t.name === `table_${n}`)) n++;
    const center = screenToFlowPosition({ x: window.innerWidth * 0.4, y: window.innerHeight * 0.4 });
    const table: Table = {
      id: newId(),
      schema: 'public',
      name: `table_${n}`,
      comment: null,
      columns: [{ ...newColumn('id', 'bigint'), primaryKey: true, nullable: false, identity: 'ALWAYS' }],
      indexes: [],
      checks: [],
      position: { x: Math.round(center.x), y: Math.round(center.y) },
      color: TABLE_COLORS[model.tables.length % TABLE_COLORS.length],
      kind: 'ENTITY',
      rows: [],
    };
    dispatch({ type: 'upsertTable', table });
    setSelection({ kind: 'table', id: table.id });
  };

  const canvasCenter = () => {
    const c = screenToFlowPosition({ x: window.innerWidth * 0.4, y: window.innerHeight * 0.4 });
    return { x: Math.round(c.x), y: Math.round(c.y) };
  };

  const addView = () => {
    if (!model) return;
    let n = (model.views?.length ?? 0) + 1;
    while (model.views.some((v) => v.name === `view_${n}`)) n++;
    const first = model.tables[0];
    const from = first ? `${first.schema === 'public' ? '' : `${first.schema}.`}${first.name}` : 'some_table';
    const view: View = {
      id: newId(),
      schema: 'public',
      name: `view_${n}`,
      comment: null,
      materialized: false,
      definition: `SELECT *\nFROM ${from}`,
      position: canvasCenter(),
      color: VIEW_COLOR,
    };
    dispatch({ type: 'upsertView', view });
    setSelection({ kind: 'view', id: view.id });
  };

  const addLookupTable = () => {
    if (!model) return;
    const table = newLookupTable(model, canvasCenter());
    dispatch({ type: 'upsertTable', table });
    setSelection({ kind: 'table', id: table.id });
  };

  const selectedTable = selection?.kind === 'table' ? model?.tables.find((t) => t.id === selection.id) : undefined;
  const selectedRel = selection?.kind === 'relationship' ? model?.relationships.find((r) => r.id === selection.id) : undefined;
  const selectedView = selection?.kind === 'view' ? model?.views.find((v) => v.id === selection.id) : undefined;

  if (!model) {
    return (
      <div className="loading">
        {state.lastError ?? 'Connecting…'}
        <button onClick={onBack}>Back</button>
      </div>
    );
  }

  return (
    <div className="editor">
      <header className="topbar">
        <button className="link" onClick={onBack}>
          ← Models
        </button>
        <CommitInput
          className="model-name"
          value={model.name}
          onCommit={(v) => v.trim() && dispatch({ type: 'renameModel', name: v.trim() })}
        />
        <span className={`status ${state.status}`} title={`Version ${state.version}`}>
          {state.status === 'online' ? (state.pendingCount > 0 ? 'Saving…' : 'Saved') : state.status === 'offline' ? 'Offline – reconnecting' : 'Connecting…'}
        </span>
        <div className="spacer" />
        <div className="participants">
          {state.participants.map((p) => (
            <span key={p.sessionId} className="avatar" style={{ background: p.color ?? '#888' }} title={p.userName}>
              {p.userName.slice(0, 2).toUpperCase()}
            </span>
          ))}
        </div>
        <button onClick={addTable}>+ Table</button>
        <button onClick={addLookupTable} title="Reference data table (id, code, name, sort_order, active) with rows">
          + Lookup table
        </button>
        <button onClick={addView} title="SQL view or materialized view">
          + View
        </button>
        <button onClick={() => setDialog('enums')}>Enums ({model.enums.length})</button>
        <button onClick={() => setDialog('import')} title="Add or update tables, views and enums from a PostgreSQL DDL script">
          Import SQL
        </button>
        <button className="primary" onClick={() => setDialog('export')}>
          Export
        </button>
      </header>

      <div className="workspace">
        <div className="canvas">
          <ReactFlow<FlowNode, Edge>
            nodes={nodes}
            edges={edges}
            nodeTypes={nodeTypes}
            edgeTypes={edgeTypes}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            onNodeClick={(_, n) => setSelection({ kind: n.type === 'view' ? 'view' : 'table', id: n.id })}
            onEdgeClick={(_, e) => !e.id.startsWith(DEP_EDGE_PREFIX) && setSelection({ kind: 'relationship', id: e.id })}
            onPaneClick={() => setSelection(null)}
            deleteKeyCode={['Delete']}
            fitView
            minZoom={0.1}
          >
            <Background gap={20} />
            <Controls />
            <MiniMap pannable zoomable />
          </ReactFlow>
        </div>
        <aside className="sidebar">
          {selectedTable && (
            <TableEditor key={selectedTable.id} table={selectedTable} model={model} dispatch={dispatch} onDeleted={() => setSelection(null)} />
          )}
          {selectedRel && (
            <RelationshipEditor key={selectedRel.id} rel={selectedRel} model={model} dispatch={dispatch} onDeleted={() => setSelection(null)} />
          )}
          {selectedView && (
            <ViewEditor
              key={selectedView.id}
              view={selectedView}
              model={model}
              dispatch={dispatch}
              onDeleted={() => setSelection(null)}
              onSelect={(kind, id) => setSelection({ kind, id })}
            />
          )}
          {!selectedTable && !selectedRel && !selectedView && (
            <div className="panel muted">
              <h3>{model.name}</h3>
              <p>
                {model.tables.length} tables · {model.views.length} views · {model.relationships.length} foreign keys ·{' '}
                {model.enums.length} enums
              </p>
              <ul className="hints">
                <li>Click a table or a foreign key to edit it.</li>
                <li>Drag from a column's right dot to a column's left dot on another table to create a foreign key (child → parent).</li>
                <li>Or use “+ Reference to…” in the table panel to add the FK column, index and constraint in one step.</li>
                <li>Select and press Delete to remove a table, view or foreign key.</li>
                <li>“+ View” adds a SQL view; dashed arrows show which tables and views its query reads.</li>
                <li>
                  Reference data (gender, day of week, company roles): “+ Lookup table”, fill its Data tab and point other
                  tables at it with “+ Reference to…”. Exports insert the rows. Enums can be converted in the Enums dialog.
                </li>
              </ul>
            </div>
          )}
        </aside>
      </div>

      {state.lastError && (
        <div className="toast" onClick={() => session.clearError()}>
          {state.lastError} <span className="muted">(click to dismiss)</span>
        </div>
      )}
      {dialog === 'enums' && (
        <EnumDialog
          model={model}
          dispatch={dispatch}
          onClose={() => setDialog(null)}
          lookupPosition={canvasCenter}
          onConverted={(tableId) => {
            setDialog(null);
            setSelection({ kind: 'table', id: tableId });
          }}
        />
      )}
      {dialog === 'import' && (
        <ImportSqlDialog
          modelId={modelId}
          model={model}
          pending={state.pendingCount}
          dispatch={dispatch}
          onClose={() => setDialog(null)}
          onApplied={(newTableIds) => {
            setDialog(null);
            if (newTableIds.length > 0) {
              // Bring the imported tables into view once React Flow has measured them.
              setTimeout(() => fitView({ nodes: newTableIds.map((id) => ({ id })), padding: 0.2, duration: 400 }), 150);
            }
          }}
        />
      )}
      {dialog === 'export' && (
        <ExportDialog modelId={modelId} version={state.version} pending={state.pendingCount} onClose={() => setDialog(null)} />
      )}
      <datalist id="pg-types">
        {[...PG_TYPES, ...model.enums.map((e) => (e.schema === 'public' ? e.name : `${e.schema}.${e.name}`))].map((t) => (
          <option key={t} value={t} />
        ))}
      </datalist>
    </div>
  );
}
