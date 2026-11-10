import { memo } from 'react';
import { Handle, Position as HandlePosition, type Node, type NodeProps } from '@xyflow/react';
import type { Participant, Table } from '../model/types';
import { TABLE_COLORS } from '../util';

export type TableNodeData = {
  table: Table;
  fkColumnIds: Set<string>;
  remote: Participant[];
};

export type TableFlowNode = Node<TableNodeData, 'table'>;

function TableNodeView({ data, selected }: NodeProps<TableFlowNode>) {
  const { table, fkColumnIds, remote } = data;
  const color = table.color ?? TABLE_COLORS[0];
  const remoteColor = remote[0]?.color ?? undefined;
  return (
    <div
      className={`table-node${selected ? ' selected' : ''}${table.kind === 'LOOKUP' ? ' lookup' : ''}`}
      style={remoteColor ? { boxShadow: `0 0 0 3px ${remoteColor}` } : undefined}
    >
      {remote.length > 0 && (
        <div className="remote-tag" style={{ background: remoteColor }}>
          {remote.map((r) => r.userName).join(', ')}
        </div>
      )}
      <Handle type="target" position={HandlePosition.Top} id="view-target" className="dep-handle" isConnectable={false} />
      <div className="table-header" style={{ background: color }} title={table.comment ?? undefined}>
        {table.schema !== 'public' && <span className="schema">{table.schema}.</span>}
        {table.name}
        {table.kind === 'LOOKUP' && (
          <span className="lookup-tag" title={`Lookup table with ${table.rows.length} data row(s)`}>
            lookup · {table.rows.length}
          </span>
        )}
      </div>
      {table.columns.length === 0 && <div className="col-row empty">no columns</div>}
      {table.columns.map((c) => {
        const notNull = !c.nullable || c.primaryKey || c.identity !== 'NONE';
        return (
          <div className="col-row" key={c.id} title={c.comment ?? undefined}>
            <Handle type="target" position={HandlePosition.Left} id={`t-${c.id}`} />
            <span className="col-keys">
              {c.primaryKey && <span className="badge pk">PK</span>}
              {fkColumnIds.has(c.id) && <span className="badge fk">FK</span>}
              {c.unique && !c.primaryKey && <span className="badge uq">UQ</span>}
            </span>
            <span className={`col-name${c.primaryKey ? ' strong' : ''}`}>{c.name}</span>
            <span className="col-type">
              {c.type}
              {notNull ? '' : '?'}
            </span>
            <Handle type="source" position={HandlePosition.Right} id={`s-${c.id}`} />
          </div>
        );
      })}
    </div>
  );
}

export const TableNode = memo(TableNodeView);
