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
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class MainActivityV17 extends Activity {
    private static final String VERSION = "0.17";
    private static final int REQ = 1701;
    private static final long SCAN_MS = 15000;
    private static final long DISCOVERY_DELAY_MS = 1200;
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID NUS_RX = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9f");
    private static final UUID NUS_TX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9f");
    private static final byte[] CAPTURED = new byte[]{
            (byte) 0xDF, 0x00, 0x06, 0x01, 0x05, 0x10, 0x06, 0x00, 0x01, 0x00
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private final StringBuilder log = new StringBuilder();

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic nusRx;
    private BluetoothGattCharacteristic nusTx;
    private boolean connected;
    private boolean connecting;
    private boolean servicesReady;

    private TextView statusView;
    private TextView logView;
    private ScrollView logScroll;
    private Button searchButton;
    private Button capturedButton;
    private Button readButton;
    private Button disconnectButton;

    private final Runnable scanTimeout = () -> {
        stopScan();
        if (!connected && !connecting) setStatus("G28 não encontrado");
    };

    private final Runnable discoverRunnable = () -> {
        BluetoothGatt current = gatt;
        if (current == null || !connected) return;
        append("DISCOVERY após atraso de " + DISCOVERY_DELAY_MS + "ms");
        boolean started;
        try { started = current.discoverServices(); }
        catch (Exception e) { append("ERRO discoverServices: " + e.getMessage()); return; }
        append("discoverServices=" + started);
        if (!started) {
            setStatus("Falha ao iniciar descoberta GATT");
            releaseGatt(current, false);
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = safeName(device);
            if ((name == null || name.isEmpty()) && result.getScanRecord() != null) {
                name = result.getScanRecord().getDeviceName();
            }
            append("SCAN " + (name == null ? "(sem nome)" : name) + " | " + safeAddress(device) + " | RSSI " + result.getRssi());
            if (name != null && name.toUpperCase(Locale.US).contains("G28")) {
                stopScan();
                connect(device);
            }
        }

        @Override public void onScanFailed(int errorCode) {
            connecting = false;
            append("SCAN FAILED " + errorCode);
            setStatus("Falha no scanner: " + errorCode);
            updateUi();
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt current, int status, int newState) {
            append("STATE status=" + status + " state=" + newState);
            if (current != gatt) {
                append("Callback de GATT antigo ignorado");
                try { current.close(); } catch (Exception ignored) {}
                return;
            }

            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true;
                connecting = false;
                servicesReady = false;
                setStatus("Conectado. Aguardando estabilizar...");
                handler.removeCallbacks(discoverRunnable);
                handler.postDelayed(discoverRunnable, DISCOVERY_DELAY_MS);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                append("Desconexão GATT; liberando sessão");
                releaseGatt(current, false);
            }
            updateUi();
        }

        @Override public void onServicesDiscovered(BluetoothGatt current, int status) {
            if (current != gatt) return;
            append("SERVICES status=" + status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setStatus("Falha na descoberta GATT: " + status);
                releaseGatt(current, false);
                return;
            }
            nusRx = null;
            nusTx = null;
            for (BluetoothGattService service : current.getServices()) {
                for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                    if (NUS_RX.equals(c.getUuid())) nusRx = c;
                    if (NUS_TX.equals(c.getUuid())) nusTx = c;
                }
            }
            append("NUS RX=" + (nusRx != null) + " | TX=" + (nusTx != null));
            if (nusRx == null || nusTx == null) {
                setStatus("Nordic UART não encontrado");
                updateUi();
                return;
            }
            enableNotify();
        }

        @Override public void onDescriptorWrite(BluetoothGatt current, BluetoothGattDescriptor descriptor, int status) {
            if (current != gatt) return;
            append("CCCD status=" + status);
            servicesReady = status == BluetoothGatt.GATT_SUCCESS;
            setStatus(servicesReady ? "G28 pronto | Nordic UART ativo" : "Falha ao ativar notificações: " + status);
            updateUi();
        }

        @Override public void onCharacteristicChanged(BluetoothGatt current, BluetoothGattCharacteristic c) {
            if (current == gatt) handleNotify(c, c.getValue());
        }

        @Override public void onCharacteristicChanged(BluetoothGatt current, BluetoothGattCharacteristic c, byte[] value) {
            if (current == gatt) handleNotify(c, value);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        buildUi();
        append("Orbis Watch Lab v" + VERSION + " iniciado");
        ensurePermissions();
    }

    private void buildUi() {
        int p = dp(14);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setText("ORBIS WATCH LAB v" + VERSION);
        title.setTextSize(25);
        title.setTypeface(null, 1);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setText("Pronto para procurar o G28");
        statusView.setTextSize(17);
        statusView.setPadding(0, dp(8), 0, dp(8));
        root.addView(statusView);

        searchButton = button("PROCURAR G28", v -> startScan());
        root.addView(searchButton, fullButtonParams());

        capturedButton = button("TESTAR COMANDO CAPTURADO", v -> sendPacket("CAPTURADO", CAPTURED));
        root.addView(capturedButton, fullButtonParams());

        readButton = button("LER ESTADO DF 00", v -> sendPacket("LER ESTADO", new byte[]{(byte) 0xDF, 0x00}));
        root.addView(readButton, fullButtonParams());

        disconnectButton = button("DESCONECTAR", v -> disconnect());
        root.addView(disconnectButton, fullButtonParams());

        TextView warning = new TextView(this);
        warning.setText("v0.17: sem negociação de MTU; descoberta GATT atrasada e limpeza automática após desconexão.");
        warning.setTextSize(14);
        warning.setPadding(0, dp(8), 0, dp(8));
        root.addView(warning);

        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logScroll = new ScrollView(this);
        logScroll.addView(logView);
        root.addView(logScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        updateUi();
    }

    private void startScan() {
        if (!ensurePermissions()) return;
        if (connected || connecting || gatt != null) { toast("Já existe conexão ativa"); return; }
        if (adapter == null || !adapter.isEnabled()) { toast("Ative o Bluetooth"); return; }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { toast("Scanner BLE indisponível"); return; }
        connecting = true;
        servicesReady = false;
        setStatus("Procurando G28...");
        append("Busca BLE iniciada");
        scanner.startScan(scanCallback);
        handler.postDelayed(scanTimeout, SCAN_MS);
        updateUi();
    }

    private void stopScan() {
        handler.removeCallbacks(scanTimeout);
        if (scanner != null && hasScan()) {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        }
        scanner = null;
    }

    private void connect(BluetoothDevice device) {
        if (!hasConnect() || gatt != null) return;
        connecting = true;
        setStatus("Conectando a " + safeAddress(device));
        append("CONNECT " + safeAddress(device));
        try {
            gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (Exception e) {
            append("ERRO connectGatt: " + e.getMessage());
            connecting = false;
            gatt = null;
        }
        updateUi();
    }

    private void enableNotify() {
        if (gatt == null || nusTx == null || !hasConnect()) return;
        try {
            boolean local = gatt.setCharacteristicNotification(nusTx, true);
            append("setCharacteristicNotification=" + local);
            BluetoothGattDescriptor descriptor = nusTx.getDescriptor(CCCD);
            if (descriptor == null) {
                append("CCCD não encontrado");
                setStatus("CCCD não encontrado");
                return;
            }
            byte[] value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            if (Build.VERSION.SDK_INT >= 33) {
                int result = gatt.writeDescriptor(descriptor, value);
                append("writeDescriptor result=" + result);
            } else {
                descriptor.setValue(value);
                append("writeDescriptor iniciado=" + gatt.writeDescriptor(descriptor));
            }
            setStatus("Nordic UART encontrado. Ativando notificações...");
        } catch (Exception e) {
            append("ERRO NOTIFY " + e.getMessage());
            setStatus("Erro ao ativar notificações");
        }
    }

    private void sendPacket(String label, byte[] data) {
        if (!servicesReady || !connected || gatt == null || nusRx == null || !hasConnect()) {
            toast("Aguarde: G28 ainda não está pronto");
            return;
        }
        append("TX " + label + " | " + hex(data));
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                int result = gatt.writeCharacteristic(nusRx, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                append("TX result=" + result);
            } else {
                nusRx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                nusRx.setValue(data);
                append("TX iniciado=" + gatt.writeCharacteristic(nusRx));
            }
        } catch (Exception e) {
            append("ERRO TX " + e.getMessage());
        }
    }

    private void handleNotify(BluetoothGattCharacteristic c, byte[] value) {
        append((NUS_TX.equals(c.getUuid()) ? "RX NUS-TX | " : "RX " + c.getUuid() + " | ") + hex(value));
    }

    private void releaseGatt(BluetoothGatt current, boolean callDisconnect) {
        handler.removeCallbacks(discoverRunnable);
        if (current != null) {
            if (callDisconnect) try { current.disconnect(); } catch (Exception ignored) {}
            try { current.close(); } catch (Exception ignored) {}
        }
        if (current == gatt) gatt = null;
        connected = false;
        connecting = false;
        servicesReady = false;
        nusRx = null;
        nusTx = null;
        setStatus("Desconectado — pronto para tentar novamente");
        updateUi();
    }

    private void disconnect() {
        stopScan();
        releaseGatt(gatt, true);
    }

    private boolean ensurePermissions() {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQ);
            return false;
        }
        return true;
    }

    private boolean hasScan() { return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED; }
    private boolean hasConnect() { return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED; }
    private String safeName(BluetoothDevice d) { if (!hasConnect()) return null; try { return d.getName(); } catch (Exception e) { return null; } }
    private String safeAddress(BluetoothDevice d) { if (!hasConnect()) return "?"; try { return d.getAddress(); } catch (Exception e) { return "?"; } }

    private Button button(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(listener);
        return button;
    }

    private LinearLayout.LayoutParams fullButtonParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(58));
        lp.setMargins(0, dp(3), 0, dp(3));
        return lp;
    }

    private void append(String message) {
        String line = clock.format(new Date()) + "  " + message;
        log.append(line).append('\n');
        runOnUiThread(() -> {
            logView.setText(log.toString());
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void setStatus(String text) { runOnUiThread(() -> statusView.setText(text)); }

    private void updateUi() {
        runOnUiThread(() -> {
            searchButton.setEnabled(!connected && !connecting && gatt == null);
            capturedButton.setEnabled(servicesReady);
            readButton.setEnabled(servicesReady);
            disconnectButton.setEnabled(gatt != null || connected || connecting);
        });
    }

    private static String hex(byte[] value) {
        if (value == null || value.length == 0) return "(vazio)";
        StringBuilder out = new StringBuilder();
        for (byte b : value) {
            if (out.length() > 0) out.append(' ');
            out.append(String.format(Locale.US, "%02X", b & 0xFF));
        }
        return out.toString();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String text) { runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show()); }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        releaseGatt(gatt, true);
        super.onDestroy();
    }
}
