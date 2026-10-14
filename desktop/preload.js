const { contextBridge, ipcRenderer } = require('electron');
const names = ['list', 'load', 'open', 'close', 'version', 'vbatt', 'connect', 'disconnect', 'filter', 'write', 'read', 'fastInit', 'fiveBaud'];
const bridge = {};
for (const n of names) bridge[n] = (...a) => ipcRenderer.invoke('j2534:' + n, ...a);
contextBridge.exposeInMainWorld('J2534', bridge);
