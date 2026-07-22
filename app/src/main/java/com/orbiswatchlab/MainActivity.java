package com.orbiswatchlab;

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
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 1001;
    private static final long SCAN_TIMEOUT_MS = 15000;
    private static final long CONNECT_TIMEOUT_MS = 12000;
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothDevice lastDevice;
    private TextView statusView;
    private TextView logView;
    private ScrollView logScroll;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuffer = new StringBuilder();
    private boolean connected;
    private boolean manualDisconnect;
    private boolean forceMode;
    private int connectionAttempt;

    private final Runnable connectionTimeout = () -> {
        if (!connected && gatt != null) {
            appendLog("TIMEOUT: nenhum retorno GATT em " + CONNECT_TIMEOUT_MS + " ms");
            setStatus("Timeout ao conectar");
            closeGattOnly();
            if (forceMode && lastDevice != null && connectionAttempt < 3) {
                handler.postDelayed(() -> connect(lastDevice, true), 1200);
            }
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = safeName(device);
            if ((name == null || name.equals("(sem nome)")) && result.getScanRecord() != null) {
                String advertisedName = result.getScanRecord().getDeviceName();
                if (advertisedName != null) name = advertisedName;
            }
            appendLog("SCAN: " + name + " | " + safeAddress(device) + " | RSSI " + result.getRssi());
            if (name != null && name.toUpperCase(Locale.US).contains("G28")) {
                lastDevice = device;
                stopScan();
                connect(device, false);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            appendLog("SCAN FAILED: código=" + errorCode);
            setStatus("Falha no scanner: " + errorCode);
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt currentGatt, int status, int newState) {
            handler.removeCallbacks(connectionTimeout);
            appendLog("CALLBACK STATE: status=" + status + " (" + statusName(status) + ") state=" + newState + " (" + stateName(newState) + ")");

            if (newState == BluetoothGatt.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected = true;
                gatt = currentGatt;
                setStatus("G28 conectado. Descobrindo serviços...");
                if (hasConnectPermission()) {
                    boolean started = currentGatt.discoverServices();
                    appendLog("discoverServices()=" + started);
                }
                return;
            }

            if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                connected = false;
                setStatus("Desconectado: " + statusName(status));
                try { currentGatt.close(); } catch (Exception ignored) { }
                if (gatt == currentGatt) gatt = null;

                if (!manualDisconnect && forceMode && lastDevice != null && connectionAttempt < 3) {
                    appendLog("Reconexão automática agendada...");
                    handler.postDelayed(() -> connect(lastDevice, true), 1500);
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt currentGatt, int status) {
            appendLog("CALLBACK SERVICES: status=" + status + " (" + statusName(status) + ")");
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setStatus("Falha ao descobrir serviços");
                return;
            }

            List<BluetoothGattService> services = currentGatt.getServices();
            appendLog("TOTAL DE SERVIÇOS: " + services.size());
            for (BluetoothGattService service : services) {
                appendLog("SERVIÇO: " + service.getUuid());
                for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                    int p = c.getProperties();
                    appendLog("  CHAR: " + c.getUuid() + " | props=" + propertiesText(p));
                    if ((p & BluetoothGattCharacteristic.PROPERTY_READ) != 0 && hasConnectPermission()) {
                        appendLog("  readCharacteristic()=" + currentGatt.readCharacteristic(c));
                    }
                    if ((p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                            (p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                        enableNotification(currentGatt, c);
                    }
                }
            }
            setStatus("Conectado e monitorando o G28");
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt currentGatt, BluetoothGattCharacteristic c) {
            appendLog("RX NOTIFY " + c.getUuid() + ": " + bytesToHex(c.getValue()));
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt currentGatt, BluetoothGattCharacteristic c, int status) {
            appendLog("RX READ " + c.getUuid() + " status=" + statusName(status) + " valor=" + bytesToHex(c.getValue()));
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt currentGatt, BluetoothGattCharacteristic c, int status) {
            appendLog("TX CALLBACK " + c.getUuid() + " status=" + statusName(status));
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt currentGatt, BluetoothGattDescriptor d, int status) {
            appendLog("CCCD " + d.getCharacteristic().getUuid() + " status=" + statusName(status));
        }

        @Override
        public void onMtuChanged(BluetoothGatt currentGatt, int mtu, int status) {
            appendLog("MTU: " + mtu + " status=" + statusName(status));
        }

        @Override
        public void onPhyRead(BluetoothGatt currentGatt, int txPhy, int rxPhy, int status) {
            appendLog("PHY READ: tx=" + txPhy + " rx=" + rxPhy + " status=" + statusName(status));
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt currentGatt, int rssi, int status) {
            appendLog("RSSI GATT: " + rssi + " status=" + statusName(status));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildInterface();
        initializeBluetooth();
        requestRequiredPermissions();
        appendLog("Orbis Watch Lab v0.4 iniciado | Android " + Build.VERSION.RELEASE + " API " + Build.VERSION.SDK_INT);
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(14));

        TextView title = new TextView(this);
        title.setText("ORBIS WATCH LAB v0.4");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setText("Inicializando Bluetooth...");
        statusView.setTextSize(16);
        statusView.setPadding(0, dp(10), 0, dp(10));
        root.addView(statusView);

        LinearLayout row1 = new LinearLayout(this);
        Button scan = button("PROCURAR G28", v -> startScan());
        Button force = button("FORÇAR GATT", v -> forceGatt());
        row1.addView(scan, weight());
        row1.addView(force, weight());
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        Button disconnect = button("DESCONECTAR", v -> disconnectGatt());
        Button export = button("COMPARTILHAR LOG", v -> shareLog());
        row2.addView(disconnect, weight());
        row2.addView(export, weight());
        root.addView(row2);

        logScroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setText("LOG ORBIS\n");
        logView.setTextSize(12);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logScroll.addView(logView);
        root.addView(logScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private Button button(String text, android.view.View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(listener);
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    }

    private void initializeBluetooth() {
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (manager == null) { setStatus("BluetoothManager indisponível"); return; }
        adapter = manager.getAdapter();
        if (adapter == null) { setStatus("Este celular não possui Bluetooth"); return; }
        if (!adapter.isEnabled()) { setStatus("Ative o Bluetooth do celular"); return; }
        scanner = adapter.getBluetoothLeScanner();
        setStatus("Pronto para procurar o G28");
    }

    private void requestRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_PERMISSIONS);
            }
        } else if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_PERMISSIONS);
        }
    }

    private boolean hasScanPermission() {
        return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void startScan() {
        forceMode = false;
        manualDisconnect = false;
        connectionAttempt = 0;
        if (!hasScanPermission()) { requestRequiredPermissions(); setStatus("Autorize o Bluetooth"); return; }
        if (scanner == null && adapter != null) scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { setStatus("Scanner BLE indisponível"); return; }
        appendLog("Iniciando busca BLE...");
        setStatus("Procurando o G28...");
        scanner.startScan(scanCallback);
        handler.postDelayed(() -> { stopScan(); if (lastDevice == null) setStatus("G28 não encontrado"); }, SCAN_TIMEOUT_MS);
    }

    private void forceGatt() {
        forceMode = true;
        manualDisconnect = false;
        connectionAttempt = 0;
        if (lastDevice != null) {
            appendLog("FORÇAR GATT usando último G28: " + safeAddress(lastDevice));
            connect(lastDevice, true);
        } else {
            appendLog("FORÇAR GATT: primeiro localizando o G28...");
            startScan();
            forceMode = true;
        }
    }

    private void stopScan() {
        if (scanner != null && hasScanPermission()) {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) { }
        }
    }

    private void connect(BluetoothDevice device, boolean forced) {
        if (!hasConnectPermission()) { requestRequiredPermissions(); return; }
        stopScan();
        closeGattOnly();
        lastDevice = device;
        connected = false;
        connectionAttempt++;
        setStatus("Conectando ao G28... tentativa " + connectionAttempt);
        appendLog("CONNECT tentativa " + connectionAttempt + " | modo=" + (forced ? "FORÇADO" : "NORMAL") + " | " + safeAddress(device));

        try {
            if (forced && Build.VERSION.SDK_INT >= 26) {
                appendLog("connectGatt: TRANSPORT_LE + PHY_LE_1M");
                gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK);
            } else if (Build.VERSION.SDK_INT >= 23) {
                appendLog("connectGatt: TRANSPORT_LE");
                gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            } else {
                appendLog("connectGatt: modo legado");
                gatt = device.connectGatt(this, false, gattCallback);
            }
            handler.removeCallbacks(connectionTimeout);
            handler.postDelayed(connectionTimeout, CONNECT_TIMEOUT_MS);
        } catch (Exception e) {
            appendLog("EXCEÇÃO connectGatt: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            setStatus("Erro ao iniciar conexão");
        }
    }

    private void enableNotification(BluetoothGatt currentGatt, BluetoothGattCharacteristic c) {
        if (!hasConnectPermission()) return;
        boolean local = currentGatt.setCharacteristicNotification(c, true);
        appendLog("NOTIFY local " + c.getUuid() + " = " + local);
        BluetoothGattDescriptor descriptor = c.getDescriptor(CCCD);
        if (descriptor == null) { appendLog("CCCD ausente em " + c.getUuid()); return; }
        byte[] value = (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
        if (Build.VERSION.SDK_INT >= 33) {
            appendLog("writeDescriptor=" + currentGatt.writeDescriptor(descriptor, value));
        } else {
            descriptor.setValue(value);
            appendLog("writeDescriptor=" + currentGatt.writeDescriptor(descriptor));
        }
    }

    private void disconnectGatt() {
        manualDisconnect = true;
        forceMode = false;
        handler.removeCallbacks(connectionTimeout);
        stopScan();
        if (gatt != null && hasConnectPermission()) {
            try { gatt.disconnect(); } catch (Exception ignored) { }
        }
        closeGattOnly();
        connected = false;
        setStatus("Desconectado");
        appendLog("Conexão encerrada pelo usuário.");
    }

    private void closeGattOnly() {
        if (gatt != null) {
            try { gatt.close(); } catch (Exception ignored) { }
            gatt = null;
        }
    }

    private void shareLog() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "Orbis Watch Lab - log G28");
        intent.putExtra(Intent.EXTRA_TEXT, logBuffer.toString());
        startActivity(Intent.createChooser(intent, "Compartilhar log do G28"));
    }

    private String safeName(BluetoothDevice device) {
        if (!hasConnectPermission()) return "(sem permissão)";
        try { String name = device.getName(); return name == null ? "(sem nome)" : name; }
        catch (Exception e) { return "(nome indisponível)"; }
    }

    private String safeAddress(BluetoothDevice device) {
        if (!hasConnectPermission()) return "(endereço protegido)";
        try { return device.getAddress(); } catch (Exception e) { return "(endereço indisponível)"; }
    }

    private String bytesToHex(byte[] data) {
        if (data == null || data.length == 0) return "(vazio)";
        StringBuilder b = new StringBuilder();
        for (byte value : data) b.append(String.format(Locale.US, "%02X ", value & 0xFF));
        return b.toString().trim();
    }

    private String propertiesText(int p) {
        StringBuilder b = new StringBuilder();
        if ((p & BluetoothGattCharacteristic.PROPERTY_READ) != 0) b.append("READ ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) b.append("WRITE ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) b.append("WRITE_NR ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) b.append("NOTIFY ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) b.append("INDICATE ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_BROADCAST) != 0) b.append("BROADCAST ");
        return b.length() == 0 ? "0x" + Integer.toHexString(p) : b.toString().trim();
    }

    private String statusName(int status) {
        if (status == BluetoothGatt.GATT_SUCCESS) return "SUCCESS";
        if (status == 8) return "TIMEOUT/CONNECTION_LOST";
        if (status == 19) return "REMOTE_TERMINATED";
        if (status == 22) return "LOCAL_TERMINATED";
        if (status == 62) return "FAIL_ESTABLISH";
        if (status == 133) return "GATT_ERROR_133";
        return "STATUS_" + status;
    }

    private String stateName(int state) {
        if (state == BluetoothGatt.STATE_CONNECTED) return "CONNECTED";
        if (state == BluetoothGatt.STATE_CONNECTING) return "CONNECTING";
        if (state == BluetoothGatt.STATE_DISCONNECTED) return "DISCONNECTED";
        if (state == BluetoothGatt.STATE_DISCONNECTING) return "DISCONNECTING";
        return "STATE_" + state;
    }

    private void setStatus(String message) {
        runOnUiThread(() -> statusView.setText(message));
    }

    private void appendLog(String message) {
        String time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        String line = time + "  " + message;
        synchronized (logBuffer) { logBuffer.append(line).append('\n'); }
        runOnUiThread(() -> {
            logView.append(line + "\n");
            logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        manualDisconnect = true;
        handler.removeCallbacksAndMessages(null);
        stopScan();
        closeGattOnly();
        super.onDestroy();
    }
}
