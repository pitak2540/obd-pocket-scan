/* J2534 PassThru access for the Windows desktop app. Runs in Electron's main process (must be the 32-bit build:
   Tactrix and most vendor DLLs are 32-bit). Every call returns { ok, ... } so the page never sees a native exception. */
const koffi = require('koffi');
const { execFile } = require('child_process');

const MSGSZ = 4152, DOFF = 24;          // PASSTHRU_MSG: 6 x uint32 header + 4128 data bytes
const SBA = koffi.struct('SBYTE_ARRAY', { n: 'uint32', p: 'void *' });
let lib = null, F = null, dev = 0;

function reg(key) { return new Promise(res => execFile('reg', ['query', key, '/s'], { windowsHide: true }, (e, out) => res(e ? '' : String(out)))); }
async function list() {
  const blocks = [];
  for (const key of ['HKLM\\SOFTWARE\\PassThruSupport.04.04', 'HKLM\\SOFTWARE\\WOW6432Node\\PassThruSupport.04.04']) {
    let cur = null;
    for (const ln of (await reg(key)).split(/\r?\n/)) {
      if (/^HKEY_/.test(ln)) { cur = {}; blocks.push(cur); continue; }
      const m = /^\s+(\w+)\s+REG_\w+\s+(.*)$/.exec(ln);
      if (m && cur) cur[m[1]] = m[2].trim();
    }
  }
  const seen = new Set();
  return blocks.filter(x => x.FunctionLibrary && !seen.has(x.FunctionLibrary.toLowerCase()) && seen.add(x.FunctionLibrary.toLowerCase()))
    .map(x => ({ name: x.Name || x.FunctionLibrary, dll: x.FunctionLibrary }));
}

function err(rc) {
  let t = '';
  try { const b = Buffer.alloc(256); F.LastErr(b); t = b.toString('latin1').split('\0')[0]; } catch (e) {}
  return { ok: false, rc, msg: 'J2534 error ' + rc + (t ? ': ' + t : '') };
}
const guard = fn => async (...a) => { try { return await fn(...a); } catch (e) { return { ok: false, rc: -1, msg: String((e && e.message) || e) }; } };

function msg(proto, flags, bytes) {
  const b = Buffer.alloc(MSGSZ);
  b.writeUInt32LE(proto, 0); b.writeUInt32LE(flags >>> 0, 8); b.writeUInt32LE(bytes.length, 16);
  Buffer.from(bytes).copy(b, DOFF);
  return b;
}
const bytesOf = (b, o) => Array.from(b.subarray(o + DOFF, o + DOFF + Math.min(b.readUInt32LE(o + 16), 4128)));

const api = {
  list: guard(async () => list()),
  load: guard(async dll => {
    if (lib) { try { lib.unload(); } catch (e) {} lib = null; }
    lib = koffi.load(dll);
    const fn = (name, args) => lib.func('__stdcall', name, 'int32', args);
    F = {
      Open: fn('PassThruOpen', ['void *', 'void *']), Close: fn('PassThruClose', ['uint32']),
      Connect: fn('PassThruConnect', ['uint32', 'uint32', 'uint32', 'uint32', 'void *']), Disconnect: fn('PassThruDisconnect', ['uint32']),
      Read: fn('PassThruReadMsgs', ['uint32', 'void *', 'void *', 'uint32']), Write: fn('PassThruWriteMsgs', ['uint32', 'void *', 'void *', 'uint32']),
      Filter: fn('PassThruStartMsgFilter', ['uint32', 'uint32', 'void *', 'void *', 'void *', 'void *']),
      Ioctl: fn('PassThruIoctl', ['uint32', 'uint32', 'void *', 'void *']),
      Version: fn('PassThruReadVersion', ['uint32', 'void *', 'void *', 'void *']), LastErr: fn('PassThruGetLastError', ['void *']),
    };
    return { ok: true };
  }),
  open: guard(async () => {
    const b = Buffer.alloc(4), rc = F.Open(null, b);
    if (rc) return err(rc);
    dev = b.readUInt32LE(0); return { ok: true };
  }),
  close: guard(async () => { if (F && dev) F.Close(dev); dev = 0; return { ok: true }; }),
  version: guard(async () => {
    const fw = Buffer.alloc(80), dl = Buffer.alloc(80), ap = Buffer.alloc(80), rc = F.Version(dev, fw, dl, ap);
    if (rc) return err(rc);
    const s = b => b.toString('latin1').split('\0')[0];
    return { ok: true, fw: s(fw), dll: s(dl), api: s(ap) };
  }),
  vbatt: guard(async () => {
    const o = Buffer.alloc(4), rc = F.Ioctl(dev, 3, null, o);   // READ_VBATT (device id in the channel slot)
    return rc ? err(rc) : { ok: true, mv: o.readUInt32LE(0) };
  }),
  connect: guard(async (proto, flags, baud) => {
    const b = Buffer.alloc(4), rc = F.Connect(dev, proto, flags >>> 0, baud, b);
    return rc ? err(rc) : { ok: true, ch: b.readUInt32LE(0) };
  }),
  disconnect: guard(async ch => { const rc = F.Disconnect(ch); return rc ? err(rc) : { ok: true }; }),
  filter: guard(async (ch, proto, flags, mask, pat) => {
    const id = Buffer.alloc(4), rc = F.Filter(ch, 1, msg(proto, flags, mask), msg(proto, flags, pat), null, id);   // 1 = PASS_FILTER
    return rc ? err(rc) : { ok: true };
  }),
  write: guard(async (ch, proto, flags, bytes, timeout) => {
    const n = Buffer.alloc(4); n.writeUInt32LE(1, 0);
    const rc = F.Write(ch, msg(proto, flags, bytes), n, timeout);
    return rc ? err(rc) : { ok: true };
  }),
  read: guard(async (ch, proto, max, timeout) => {
    const buf = Buffer.alloc(MSGSZ * max);
    for (let i = 0; i < max; i++) buf.writeUInt32LE(proto, i * MSGSZ);
    const n = Buffer.alloc(4); n.writeUInt32LE(max, 0);
    const rc = F.Read(ch, buf, n, timeout), cnt = Math.min(n.readUInt32LE(0), max), msgs = [];
    for (let i = 0; i < cnt; i++) msgs.push({ rx: buf.readUInt32LE(i * MSGSZ + 4), ts: buf.readUInt32LE(i * MSGSZ + 12), data: bytesOf(buf, i * MSGSZ) });
    if (rc && !cnt && rc !== 9 && rc !== 16) return err(rc);     // 9 = timeout, 16 = buffer empty: just nothing to read
    return { ok: true, msgs };
  }),
  fastInit: guard(async (ch, proto, bytes) => {
    const out = Buffer.alloc(MSGSZ); out.writeUInt32LE(proto, 0);
    const rc = F.Ioctl(ch, 5, msg(proto, 0, bytes), out);        // FAST_INIT
    return rc ? err(rc) : { ok: true, data: bytesOf(out, 0) };
  }),
  fiveBaud: guard(async (ch, bytes) => {
    const inB = koffi.alloc('uint8', 16), outB = koffi.alloc('uint8', 16), inS = koffi.alloc(SBA, 1), outS = koffi.alloc(SBA, 1);
    koffi.encode(inB, 'uint8', bytes, bytes.length);
    koffi.encode(inS, SBA, { n: bytes.length, p: inB });
    koffi.encode(outS, SBA, { n: 0, p: outB });
    const rc = F.Ioctl(ch, 4, inS, outS);                        // FIVE_BAUD_INIT
    if (rc) return err(rc);
    const o = koffi.decode(outS, SBA);
    return { ok: true, data: Array.from(koffi.decode(outB, 'uint8', Math.min(o.n, 16))) };
  }),
};
module.exports = { api, list, msg, bytesOf };
