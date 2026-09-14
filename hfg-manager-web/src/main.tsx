import React from 'react';import{createRoot}from'react-dom/client';import{BrowserRouter}from'react-router-dom';import'../node_modules/@douyinfe/semi-ui/dist/css/semi.min.css';import'./styles.css';import{App}from'./App';
createRoot(document.getElementById('root')!).render(<React.StrictMode><BrowserRouter><App/></BrowserRouter></React.StrictMode>);
