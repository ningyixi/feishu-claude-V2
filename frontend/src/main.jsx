import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App'
import './App.css'
import './ProductUI.css'
import { FloatingUIProvider } from './components/ui/FloatingUI'

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <FloatingUIProvider><App /></FloatingUIProvider>
  </React.StrictMode>
)
