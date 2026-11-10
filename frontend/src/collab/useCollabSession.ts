import { useEffect, useState, useSyncExternalStore } from 'react';
import { colorFor } from '../util';
import { CollabSession } from './session';

export function useCollabSession(modelId: string, userName: string) {
  const [session] = useState(() => new CollabSession(modelId, userName, colorFor(userName)));
  useEffect(() => {
    session.connect();
    return () => {
      void session.disconnect();
    };
  }, [session]);
  const state = useSyncExternalStore(session.subscribe, session.getSnapshot);
  return { session, state };
}
