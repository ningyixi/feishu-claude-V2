import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'
import './App.css'
import './ProductUI.css'
import './GlassMaterial.css'
import { applyPreferences } from './glass/preferences'
import { startGlass } from './glass/controller'
import { FloatingUIProvider } from './components/ui/FloatingUI'

applyPreferences()
const stopGlass = startGlass()
if (import.meta.hot) import.meta.hot.dispose(stopGlass)

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <FloatingUIProvider><App /></FloatingUIProvider>
  </React.StrictMode>
)
