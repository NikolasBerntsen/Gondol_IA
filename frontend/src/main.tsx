// Tipografías Góndola UI empaquetadas (sin CDN: el contenedor web funciona sin internet).
import '@fontsource-variable/bricolage-grotesque';
import '@fontsource-variable/figtree';
import '@fontsource-variable/jetbrains-mono';
import './index.css';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';

const container = document.getElementById('root');
if (!container) throw new Error('No se encontró el elemento #root.');

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
