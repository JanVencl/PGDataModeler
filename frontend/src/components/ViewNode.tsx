import { memo } from 'react';
import { Handle, Position as HandlePosition, type Node, type NodeProps } from '@xyflow/react';
import type { Participant, View } from '../model/types';
import { VIEW_COLOR } from './ViewEditor';

export type ViewNodeData = {
  view: View;
  reads: string[];
  remote: Participant[];
};

export type ViewFlowNode = Node<ViewNodeData, 'view'>;

const PREVIEW_LINES = 6;

function ViewNodeView({ data, selected }: NodeProps<ViewFlowNode>) {
  const { view, reads, remote } = data;
  const remoteColor = remote[0]?.color ?? undefined;
  const lines = view.definition.split('\n');
  const preview = lines.slice(0, PREVIEW_LINES).join('\n') + (lines.length > PREVIEW_LINES ? '\n…' : '');
  return (
    <div
      className={`table-node view-node${selected ? ' selected' : ''}`}
      style={remoteColor ? { boxShadow: `0 0 0 3px ${remoteColor}` } : undefined}
    >
      {remote.length > 0 && (
        <div className="remote-tag" style={{ background: remoteColor }}>
          {remote.map((r) => r.userName).join(', ')}
        </div>
      )}
      <Handle type="target" position={HandlePosition.Top} id="view-target" className="dep-handle" isConnectable={false} />
      <div className="table-header" style={{ background: view.color ?? VIEW_COLOR }} title={view.comment ?? undefined}>
        {view.schema !== 'public' && <span className="schema">{view.schema}.</span>}
        {view.name}
        <span className="lookup-tag">{view.materialized ? 'materialized view' : 'view'}</span>
      </div>
      <pre className="view-sql">{preview || 'no query yet'}</pre>
      {reads.length > 0 && <div className="view-reads">reads {reads.join(', ')}</div>}
      <Handle type="source" position={HandlePosition.Bottom} id="view-source" className="dep-handle" isConnectable={false} />
    </div>
  );
}

export const ViewNode = memo(ViewNodeView);
