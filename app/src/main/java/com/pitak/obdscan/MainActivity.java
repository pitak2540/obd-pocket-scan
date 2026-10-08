package com.pitak.obdscan;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shell around the web app in assets/index.html. The page owns all diagnostic logic;
 * this class only moves bytes: Bluetooth SPP (ELM327) and USB host (FTDI cable, Tactrix OpenPort 2.0).
 */
public class MainActivity extends Activity {
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String ACTION_USB = "com.pitak.obdscan.USB_PERMISSION";
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private UsbManager usbMgr;

    /** Bumped on every open and close; reader threads stop when it no longer matches theirs. */
    private volatile int session = 0;
    private volatile BluetoothSocket btSock;
    private volatile OutputStream btOut;
    private volatile UsbDeviceConnection usbConn;
    private volatile UsbInterface usbItf;
    private volatile UsbEndpoint epIn, epOut;
    private volatile UsbDevice pendingUsb;
    private volatile boolean pendingFtdi;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        usbMgr = (UsbManager) getSystemService(Context.USB_SERVICE);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);

        IntentFilter f = new IntentFilter(ACTION_USB);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbRx, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(usbRx, f);

        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(usbRx); } catch (Exception ignored) { }
        session++;
        closeAll();
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        js("__nat.onPerm()");
    }

    private final BroadcastReceiver usbRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (ACTION_USB.equals(i.getAction())) {
                final UsbDevice d = pendingUsb;
                if (d == null) return;
                pendingUsb = null;
                if (usbMgr.hasPermission(d)) {
                    final int my = session;
                    final boolean ftdi = pendingFtdi;
                    io.execute(new Runnable() { @Override public void run() { openUsb(d, ftdi, my); } });
                } else {
                    js("__nat.onOpen(false,'USB_DENIED')");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(i.getAction())) {
                if (usbConn != null) { session++; closeAll(); js("__nat.onLost()"); }
            }
        }
    };

    private void js(final String code) {
        ui.post(new Runnable() { @Override public void run() { if (web != null) web.evaluateJavascript(code, null); } });
    }

    private static String toHex(byte[] b, int off, int len) {
        char[] out = new char[len * 2];
        for (int i = 0; i < len; i++) {
            int v = b[off + i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 15];
        }
        return new String(out);
    }

    private static byte[] fromHex(String h) {
        int n = h.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    private synchronized void closeAll() {
        BluetoothSocket s = btSock;
        btSock = null;
        btOut = null;
        if (s != null) { try { s.close(); } catch (IOException ignored) { } }
        UsbDeviceConnection c = usbConn;
        UsbInterface itf = usbItf;
        usbConn = null;
        usbItf = null;
        epIn = null;
        epOut = null;
        if (c != null) {
            try { if (itf != null) c.releaseInterface(itf); } catch (Exception ignored) { }
            try { c.close(); } catch (Exception ignored) { }
        }
    }

    private void openBt(String addr, int my) {
        BluetoothSocket s = null;
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice d = a.getRemoteDevice(addr);
            try {
                s = d.createRfcommSocketToServiceRecord(SPP);
                s.connect();
            } catch (IOException first) {                    // many clone adapters only accept an insecure link
                try { if (s != null) s.close(); } catch (IOException ignored) { }
                s = d.createInsecureRfcommSocketToServiceRecord(SPP);
                s.connect();
            }
            if (my != session) { s.close(); return; }
            final InputStream in = s.getInputStream();
            btOut = s.getOutputStream();
            btSock = s;
            js("__nat.onOpen(true,'')");
            final int mine = my;
            new Thread(new Runnable() { @Override public void run() {
                byte[] buf = new byte[1024];
                try {
                    while (mine == session) {
                        int n = in.read(buf);
                        if (n < 0) break;
                        if (n > 0) js("__nat.onData('" + toHex(buf, 0, n) + "')");
                    }
                } catch (IOException ignored) { }
                if (mine == session) { session++; closeAll(); js("__nat.onLost()"); }
            } }, "bt-reader").start();
        } catch (Exception e) {
            try { if (s != null) s.close(); } catch (IOException ignored) { }
            if (my == session) js("__nat.onOpen(false," + JSONObject.quote(String.valueOf(e.getMessage())) + ")");
        }
    }

    private void openUsb(UsbDevice d, final boolean ftdi, int my) {
        try {
            UsbDeviceConnection c = usbMgr.openDevice(d);
            if (c == null) { js("__nat.onOpen(false,'USB_OPEN')"); return; }
            UsbInterface found = null;
            UsbEndpoint in = null, out = null;
            for (int i = 0; i < d.getInterfaceCount() && found == null; i++) {
                UsbInterface itf = d.getInterface(i);
                UsbEndpoint ei = null, eo = null;
                for (int e = 0; e < itf.getEndpointCount(); e++) {
                    UsbEndpoint ep = itf.getEndpoint(e);
                    if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                    if (ep.getDirection() == UsbConstants.USB_DIR_IN) ei = ep; else eo = ep;
                }
                if (ei != null && eo != null) { found = itf; in = ei; out = eo; }
            }
            if (found == null || !c.claimInterface(found, true)) { c.close(); js("__nat.onOpen(false,'USB_NO_ENDPOINT')"); return; }
            if (my != session) { c.releaseInterface(found); c.close(); return; }
            usbItf = found;
            epIn = in;
            epOut = out;
            usbConn = c;
            js("__nat.onOpen(true,'')");
            final int mine = my;
            final UsbDeviceConnection conn = c;
            final UsbEndpoint rx = in;
            new Thread(new Runnable() { @Override public void run() {
                int ps = Math.max(8, rx.getMaxPacketSize());
                byte[] buf = new byte[ps * 16];
                byte[] data = new byte[buf.length];
                while (mine == session) {
                    int n = conn.bulkTransfer(rx, buf, buf.length, 200);
                    if (n <= 0) continue;                    // timeout or nothing yet
                    if (!ftdi) { js("__nat.onData('" + toHex(buf, 0, n) + "')"); continue; }
                    int m = 0;                               // FTDI: drop the 2 status bytes that start every packet
                    for (int o = 0; o < n; o += ps)
                        for (int k = o + 2; k < Math.min(o + ps, n); k++) data[m++] = buf[k];
                    if (m > 0) js("__nat.onData('" + toHex(data, 0, m) + "')");
                }
            } }, "usb-reader").start();
        } catch (Exception e) {
            js("__nat.onOpen(false," + JSONObject.quote(String.valueOf(e.getMessage())) + ")");
        }
    }

    /** Called from the page as window.Android. */
    private final class Bridge {
        @JavascriptInterface
        public String btList() {
            try {
                if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    ui.post(new Runnable() { @Override public void run() { requestPermissions(new String[] { Manifest.permission.BLUETOOTH_CONNECT }, 1); } });
                    return "PERM";
                }
                BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
                if (a == null) return "NONE";
                if (!a.isEnabled()) return "OFF";
                JSONArray arr = new JSONArray();
                for (BluetoothDevice d : a.getBondedDevices()) {
                    JSONObject o = new JSONObject();
                    String name = d.getName();
                    o.put("name", name == null ? "" : name);
                    o.put("addr", d.getAddress());
                    arr.put(o);
                }
                return arr.toString();
            } catch (Exception e) {
                return "[]";
            }
        }

        @JavascriptInterface
        public void btConnect(final String addr) {
            closeAll();
            final int my = ++session;
            io.execute(new Runnable() { @Override public void run() { openBt(addr, my); } });
        }

        @JavascriptInterface
        public String usbList() {
            JSONArray arr = new JSONArray();
            try {
                for (UsbDevice d : usbMgr.getDeviceList().values()) {
                    JSONObject o = new JSONObject();
                    o.put("id", d.getDeviceId());
                    o.put("vid", d.getVendorId());
                    o.put("pid", d.getProductId());
                    String name = "";
                    try { String p = d.getProductName(); if (p != null) name = p; } catch (Exception ignored) { }
                    o.put("name", name);
                    arr.put(o);
                }
            } catch (Exception ignored) { }
            return arr.toString();
        }

        @JavascriptInterface
        public void usbOpen(int id, final boolean ftdi) {
            closeAll();
            final int my = ++session;
            UsbDevice dev = null;
            for (UsbDevice d : usbMgr.getDeviceList().values()) if (d.getDeviceId() == id) dev = d;
            if (dev == null) { js("__nat.onOpen(false,'USB_EMPTY')"); return; }
            if (!usbMgr.hasPermission(dev)) {
                pendingUsb = dev;
                pendingFtdi = ftdi;
                Intent i = new Intent(ACTION_USB);
                i.setPackage(getPackageName());
                int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
                usbMgr.requestPermission(dev, PendingIntent.getBroadcast(MainActivity.this, 0, i, flags));
                return;
            }
            final UsbDevice d = dev;
            io.execute(new Runnable() { @Override public void run() { openUsb(d, ftdi, my); } });
        }

        /** Control transfer with no data stage (FTDI setup requests). Returns the result code, negative on failure. */
        @JavascriptInterface
        public int usbControl(int requestType, int request, int value, int index) {
            UsbDeviceConnection c = usbConn;
            if (c == null) return -1;
            return c.controlTransfer(requestType, request, value, index, null, 0, 1000);
        }

        @JavascriptInterface
        public void write(String hex) {
            final byte[] data = fromHex(hex);
            final int my = session;
            io.execute(new Runnable() { @Override public void run() {
                if (my != session) return;
                try {
                    OutputStream o = btOut;
                    UsbDeviceConnection c = usbConn;
                    UsbEndpoint ep = epOut;
                    if (o != null) { o.write(data); o.flush(); }
                    else if (c != null && ep != null) {
                        int off = 0;
                        while (off < data.length) {
                            int n = c.bulkTransfer(ep, data, off, data.length - off, 1000);
                            if (n <= 0) throw new IOException("usb write");
                            off += n;
                        }
                    }
                } catch (Exception e) {
                    if (my == session) { session++; closeAll(); js("__nat.onLost()"); }
                }
            } });
        }

        @JavascriptInterface
        public void close() {
            session++;
            closeAll();
        }

        @JavascriptInterface
        public void copy(final String text) {
            ui.post(new Runnable() { @Override public void run() {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("OBD Pocket Scan", text));
            } });
        }
    }
}
