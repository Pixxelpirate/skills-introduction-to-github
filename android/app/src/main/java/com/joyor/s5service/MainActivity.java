package com.joyor.s5service;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MainActivity extends Activity {

    private static final int PERM_REQ = 100;
    private static final UUID CCC_DESCRIPTOR = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private WebView webView;
    private BluetoothAdapter btAdapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<BluetoothDevice> foundDevices = new ArrayList<>();

    private BluetoothGattCharacteristic txCharacteristic;
    private BluetoothGattCharacteristic rxCharacteristic;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().setStatusBarColor(0xFF1A1D23);

        BluetoothManager btManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (btManager != null) btAdapter = btManager.getAdapter();

        webView = findViewById(R.id.webview);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setDatabaseEnabled(true);
        settings.setTextZoom(100);

        webView.addJavascriptInterface(new BleJsBridge(), "AndroidBle");
        webView.setWebViewClient(new WebViewClient());
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl("file:///android_asset/index.html");
        }

        requestBlePermissions();
    }

    private void requestBlePermissions() {
        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        String[] arr = perms.toArray(new String[0]);
        boolean needed = false;
        for (String p : arr) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) needed = true;
        }
        if (needed) requestPermissions(arr, PERM_REQ);
    }

    private void jsCallback(String fn, String arg) {
        handler.post(() -> webView.evaluateJavascript(
                "if(window." + fn + ")" + fn + "('" + arg.replace("'", "\\'") + "')", null));
    }

    private void jsCallback(String fn, String arg1, String arg2) {
        handler.post(() -> webView.evaluateJavascript(
                "if(window." + fn + ")" + fn + "('" + arg1.replace("'", "\\'") + "','"
                        + arg2.replace("'", "\\'") + "')", null));
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff)).append(' ');
        return sb.toString().trim();
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice dev = result.getDevice();
            for (BluetoothDevice d : foundDevices) {
                if (d.getAddress().equals(dev.getAddress())) return;
            }
            foundDevices.add(dev);
            String name;
            try {
                name = dev.getName();
            } catch (SecurityException e) {
                name = null;
            }
            if (name == null || name.isEmpty()) name = dev.getAddress();
            jsCallback("onBleDeviceFound", dev.getAddress(), name);
        }
    };

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                jsCallback("onBleConnected", "true");
                try { g.discoverServices(); } catch (SecurityException ignored) {}
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                jsCallback("onBleDisconnected", "true");
                gatt = null;
                txCharacteristic = null;
                rxCharacteristic = null;
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            StringBuilder json = new StringBuilder("[");
            List<BluetoothGattService> services = g.getServices();
            for (int i = 0; i < services.size(); i++) {
                BluetoothGattService svc = services.get(i);
                if (i > 0) json.append(",");
                json.append("{\"uuid\":\"").append(svc.getUuid()).append("\",\"chars\":[");
                List<BluetoothGattCharacteristic> chars = svc.getCharacteristics();
                for (int j = 0; j < chars.size(); j++) {
                    BluetoothGattCharacteristic ch = chars.get(j);
                    if (j > 0) json.append(",");
                    int props = ch.getProperties();
                    json.append("{\"uuid\":\"").append(ch.getUuid()).append("\",")
                            .append("\"read\":").append((props & BluetoothGattCharacteristic.PROPERTY_READ) != 0).append(",")
                            .append("\"write\":").append((props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0).append(",")
                            .append("\"notify\":").append((props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0).append("}");

                    if ((props & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 && rxCharacteristic == null) {
                        rxCharacteristic = ch;
                        try {
                            g.setCharacteristicNotification(ch, true);
                            BluetoothGattDescriptor desc = ch.getDescriptor(CCC_DESCRIPTOR);
                            if (desc != null) {
                                desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                                g.writeDescriptor(desc);
                            }
                        } catch (SecurityException ignored) {}
                    }
                    if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 && txCharacteristic == null) {
                        txCharacteristic = ch;
                    }
                }
                json.append("]}");
            }
            json.append("]");
            jsCallback("onBleServicesDiscovered", json.toString());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            byte[] data = ch.getValue();
            if (data != null) {
                jsCallback("onBleData", bytesToHex(data));
            }
        }
    };

    class BleJsBridge {
        @JavascriptInterface
        public boolean isAvailable() {
            return btAdapter != null && btAdapter.isEnabled();
        }

        @JavascriptInterface
        public void startScan() {
            if (btAdapter == null) return;
            foundDevices.clear();
            try {
                scanner = btAdapter.getBluetoothLeScanner();
                if (scanner != null) scanner.startScan(scanCb);
                handler.postDelayed(() -> {
                    try { if (scanner != null) scanner.stopScan(scanCb); } catch (SecurityException ignored) {}
                    jsCallback("onBleScanDone", "true");
                }, 10000);
            } catch (SecurityException e) {
                jsCallback("onBleError", "Bluetooth-Berechtigung fehlt");
            }
        }

        @JavascriptInterface
        public void stopScan() {
            try { if (scanner != null) scanner.stopScan(scanCb); } catch (SecurityException ignored) {}
        }

        @JavascriptInterface
        public void connect(String address) {
            if (btAdapter == null) return;
            BluetoothDevice device = btAdapter.getRemoteDevice(address);
            txCharacteristic = null;
            rxCharacteristic = null;
            try {
                gatt = device.connectGatt(MainActivity.this, false, gattCb, BluetoothDevice.TRANSPORT_LE);
            } catch (SecurityException e) {
                jsCallback("onBleError", "Verbindung fehlgeschlagen: " + e.getMessage());
            }
        }

        @JavascriptInterface
        public void disconnect() {
            if (gatt != null) {
                try {
                    gatt.disconnect();
                    gatt.close();
                } catch (SecurityException ignored) {}
                gatt = null;
                txCharacteristic = null;
                rxCharacteristic = null;
            }
        }

        @JavascriptInterface
        public void sendBytes(String hexStr) {
            if (txCharacteristic == null || gatt == null) return;
            String[] parts = hexStr.trim().split("\\s+");
            byte[] data = new byte[parts.length];
            for (int i = 0; i < parts.length; i++) data[i] = (byte) Integer.parseInt(parts[i], 16);
            txCharacteristic.setValue(data);
            try { gatt.writeCharacteristic(txCharacteristic); } catch (SecurityException ignored) {}
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (SecurityException ignored) {}
        }
        super.onDestroy();
    }
}
