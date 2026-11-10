import { useEffect, useRef, useState } from 'react';

interface Props {
  value: string;
  onCommit: (value: string) => void;
  placeholder?: string;
  className?: string;
  rows?: number;
}

/** Multi-line twin of CommitInput: commits on blur or Ctrl/Cmd+Enter, Escape reverts. Tab inserts two spaces. */
export function CommitTextArea({ value, onCommit, placeholder, className, rows = 10 }: Props) {
  const [draft, setDraft] = useState(value);
  const focused = useRef(false);
  const cancelled = useRef(false);

  useEffect(() => {
    if (!focused.current) setDraft(value);
  }, [value]);

  return (
    <textarea
      className={className}
      value={draft}
      rows={rows}
      placeholder={placeholder}
      spellCheck={false}
      onFocus={() => {
        focused.current = true;
      }}
      onChange={(e) => setDraft(e.target.value)}
      onBlur={() => {
        focused.current = false;
        if (cancelled.current) {
          cancelled.current = false;
          setDraft(value);
          return;
        }
        if (draft !== value) onCommit(draft);
      }}
      onKeyDown={(e) => {
        if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
          e.preventDefault();
          e.currentTarget.blur();
        } else if (e.key === 'Escape') {
          cancelled.current = true;
          e.currentTarget.blur();
        } else if (e.key === 'Tab' && !e.shiftKey) {
          e.preventDefault();
          const el = e.currentTarget;
          const { selectionStart: start, selectionEnd: end } = el;
          const next = draft.slice(0, start) + '  ' + draft.slice(end);
          setDraft(next);
          requestAnimationFrame(() => el.setSelectionRange(start + 2, start + 2));
        }
      }}
    />
  );
}
