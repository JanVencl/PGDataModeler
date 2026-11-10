import { useEffect, useState } from 'react';
import { Editor } from './components/Editor';
import { ModelList } from './components/ModelList';
import { loadUserName, saveUserName } from './util';

/** Hash routing: #/ is the model list, #/models/<id> opens the editor (links can be shared). */
function currentModelId(): string | null {
  const m = /^#\/models\/([\w-]+)/.exec(window.location.hash);
  return m ? m[1] : null;
}

export function App() {
  const [modelId, setModelId] = useState(currentModelId);
  const [userName, setUserName] = useState(loadUserName);

  useEffect(() => {
    const onHash = () => setModelId(currentModelId());
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  const changeName = (name: string) => {
    setUserName(name);
    saveUserName(name);
  };

  if (modelId && userName.trim()) {
    return <Editor key={modelId} modelId={modelId} userName={userName.trim()} onBack={() => (window.location.hash = '#/')} />;
  }
  return <ModelList userName={userName} onUserName={changeName} onOpen={(id) => (window.location.hash = `#/models/${id}`)} />;
}
