const { app, BrowserWindow, dialog, session, ipcMain } = require('electron');
const path = require('path');

/* Chromium blocks Web Serial / WebUSB device pickers inside Electron unless we provide one. */
async function pick(win, title, items) {
  if (!items.length) return null;
  const r = await dialog.showMessageBox(win, {
    type: 'question', title, message: title, noLink: true,
    buttons: [...items.map(i => i.label), 'ยกเลิก'], cancelId: items.length,
  });
  return r.response < items.length ? items[r.response].id : null;
}

function create() {
  const win = new BrowserWindow({
    width: 1100, height: 800, backgroundColor: '#03070E', icon: path.join(__dirname, 'icon.png'),
    autoHideMenuBar: true, webPreferences: { contextIsolation: true, preload: path.join(__dirname, 'preload.js') },
  });
  const ses = win.webContents.session;
  ses.setPermissionCheckHandler(() => true);
  ses.setDevicePermissionHandler(() => true);
  ses.setPermissionRequestHandler((wc, perm, cb) => cb(true));
  win.webContents.on('select-serial-port', async (e, ports, cb) => {
    e.preventDefault();
    cb(await pick(win, 'เลือกพอร์ตอุปกรณ์ (COM)', ports.map(p => ({
      id: p.portId, label: (p.displayName || p.portName) + ' [' + p.portName + ']' + (p.vendorId ? ' ' + (+p.vendorId).toString(16) + ':' + (+p.productId).toString(16) : ''),
    }))) || '');
  });
  ses.on('select-usb-device', async (e, details, cb) => {
    e.preventDefault();
    const id = await pick(win, 'เลือกอุปกรณ์ USB', details.deviceList.map(d => ({
      id: d.deviceId, label: (d.productName || 'USB') + ' ' + d.vendorId.toString(16) + ':' + d.productId.toString(16),
    })));
    cb(id || undefined);
  });
  win.loadFile(path.join(__dirname, 'index.html'));
}
/* J2534 (Windows only, needs the 32-bit build to load 32-bit vendor DLLs). If koffi cannot load, the bridge just reports no devices. */
let j2534 = null;
try { j2534 = require('./j2534.js').api; } catch (e) { console.error('J2534 unavailable:', e.message); }
for (const n of ['list', 'load', 'open', 'close', 'version', 'vbatt', 'connect', 'disconnect', 'filter', 'write', 'read', 'fastInit', 'fiveBaud'])
  ipcMain.handle('j2534:' + n, (e, ...a) => j2534 ? j2534[n](...a) : (n === 'list' ? [] : { ok: false, rc: -1, msg: 'J2534 ไม่พร้อมใช้งานในแอปนี้' }));
app.whenReady().then(create);
app.on('window-all-closed', () => app.quit());
