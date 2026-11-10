import { useEffect, useRef, useState } from 'react';

interface Props {
  value: string;
  onCommit: (value: string) => void;
  placeholder?: string;
  list?: string;
  className?: string;
  title?: string;
  autoFocus?: boolean;
}

/**
 * Text input that keeps a local draft while focused and commits on blur / Enter (Escape reverts).
 * Committing per field rather than per keystroke keeps the operation stream small for collaborators.
 */
export function CommitInput({ value, onCommit, placeholder, list, className, title, autoFocus }: Props) {
  const [draft, setDraft] = useState(value);
  const focused = useRef(false);
  const cancelled = useRef(false);

  useEffect(() => {
    if (!focused.current) setDraft(value);
  }, [value]);

  return (
    <input
      className={className}
      value={draft}
      placeholder={placeholder}
      list={list}
      title={title}
      autoFocus={autoFocus}
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
        if (e.key === 'Enter') e.currentTarget.blur();
        if (e.key === 'Escape') {
          cancelled.current = true;
          e.currentTarget.blur();
        }
      }}
    />
  );
}
