import { memo, useMemo } from 'react';
import { BaseEdge, type EdgeProps, getSmoothStepPath, Position, type ReactFlowState, useStore } from '@xyflow/react';
import { type Dir, midpoint, type Rect, roundedPath, routeOrthogonal } from './edgeRouter';

const DIR: Record<Position, Dir> = {
  [Position.Right]: 0,
  [Position.Bottom]: 1,
  [Position.Left]: 2,
  [Position.Top]: 3,
};

/** Nodes farther than this from the edge's end points are ignored, so dragging a table only reroutes nearby edges. */
const WATCH_PAD = 500;

/** Measured node rectangles (flow coordinates) that overlap the given box. */
const rectsNear = (l: number, t: number, r: number, b: number) => (s: ReactFlowState): Rect[] => {
  const out: Rect[] = [];
  for (const n of s.nodeLookup.values()) {
    const w = n.measured?.width;
    const h = n.measured?.height;
    if (!w || !h || n.hidden) continue;
    const { x, y } = n.internals.positionAbsolute;
    if (x > r || x + w < l || y > b || y + h < t) continue;
    out.push({ x, y, w, h });
  }
  return out;
};

const rectsEqual = (a: Rect[], b: Rect[]) =>
  a.length === b.length && a.every((r, i) => r.x === b[i].x && r.y === b[i].y && r.w === b[i].w && r.h === b[i].h);

/** Orthogonal edge that walks around tables and views instead of through them. */
function RoutedEdgeView(props: EdgeProps) {
  const { id, sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, markerEnd, markerStart, style, label } = props;
  const selector = useMemo(
    () =>
      rectsNear(
        Math.min(sourceX, targetX) - WATCH_PAD,
        Math.min(sourceY, targetY) - WATCH_PAD,
        Math.max(sourceX, targetX) + WATCH_PAD,
        Math.max(sourceY, targetY) + WATCH_PAD,
      ),
    [sourceX, sourceY, targetX, targetY],
  );
  const rects = useStore(selector, rectsEqual);

  const { path, labelX, labelY } = useMemo(() => {
    const pts = routeOrthogonal(
      { x: sourceX, y: sourceY },
      DIR[sourcePosition],
      { x: targetX, y: targetY },
      DIR[targetPosition],
      rects,
    );
    if (!pts) {
      const [p, lx, ly] = getSmoothStepPath({ sourceX, sourceY, sourcePosition, targetX, targetY, targetPosition });
      return { path: p, labelX: lx, labelY: ly };
    }
    const m = midpoint(pts);
    return { path: roundedPath(pts), labelX: m.x, labelY: m.y };
  }, [sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition, rects]);

  return (
    <BaseEdge
      id={id}
      path={path}
      markerEnd={markerEnd}
      markerStart={markerStart}
      style={style}
      label={label}
      labelX={labelX}
      labelY={labelY}
      labelStyle={props.labelStyle}
      labelShowBg={props.labelShowBg}
      labelBgStyle={props.labelBgStyle}
      labelBgPadding={props.labelBgPadding}
      labelBgBorderRadius={props.labelBgBorderRadius}
      interactionWidth={props.interactionWidth}
    />
  );
}

export const RoutedEdge = memo(RoutedEdgeView);
