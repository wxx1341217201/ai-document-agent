import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './app/App';
import './styles/index.css';

async function enableMocking() {
  if (import.meta.env.VITE_USE_MSW !== 'true') {
    return;
  }

  const { worker } = await import('./test/mocks/browser');
  await worker.start({
    onUnhandledRequest: 'bypass',
  });
}

void enableMocking().then(() => {
  ReactDOM.createRoot(document.getElementById('root')!).render(
    <React.StrictMode>
      <App />
    </React.StrictMode>,
  );
});
