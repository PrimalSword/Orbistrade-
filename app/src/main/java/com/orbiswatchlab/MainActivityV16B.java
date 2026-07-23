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
import android.content.ClipData;
import android.content.ClipboardManager;
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
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class MainActivityV16B extends Activity {
    private static final String VERSION = "0.16b";
    private static final int REQ = 1602;
    private static final long SCAN_MS = 15000;
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID NUS_RX = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9f");
    private static final UUID NUS_TX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9f");
    private static final byte[] CAPTURED = new byte[]{(byte) 0xDF, 0x00, 0x06, 0x01, 0x05, 0x10, 0x06, 0x00, 0x01, 0x00};

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
    private byte[] lastSnapshot;

    private TextView statusView;
    private TextView logView;
    private ScrollView logScroll;
    private Button searchButton;
    private Button capturedButton;
    private Button readButton;
    private Button clearButton;
    private Button copyButton;
    private Button resetButton;
    private Button disconnectButton;

    private final Runnable scanTimeout = () -> {
        stopScan();
        if (!connected) setStatus("G28 não encontrado");
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = safeName(device);
            if ((name == null || name.isEmpty()) && result.getScanRecord() != null) name = result.getScanRecord().getDeviceName();
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
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true;
                connecting = false;
                setStatus("Conectado. Descobrindo serviços...");
                try { current.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH); } catch (Exception ignored) {}
                try { current.requestMtu(247); } catch (Exception ignored) {}
                append("discoverServices=" + current.discoverServices());
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                connected = false;
                connecting = false;
                nusRx = null;
                nusTx = null;
                setStatus("Desconectado");
            }
            updateUi();
        }

        @Override public void onMtuChanged(BluetoothGatt current, int mtu, int status) {
            append("MTU " + mtu + " status=" + status);
        }

        @Override public void onServicesDiscovered(BluetoothGatt current, int status) {
            append("SERVICES status=" + status);
            if (status != BluetoothGatt.GATT_SUCCESS) return;
            for (BluetoothGattService service : current.getServices()) {
                for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                    if (NUS_RX.equals(c.getUuid())) nusRx = c;
                    if (NUS_TX.equals(c.getUuid())) nusTx = c;
                }
            }
            enableNotify();
            setStatus(nusRx != null && nusTx != null ? "G28 pronto | analisador diferencial ativo" : "Nordic UART incompleto");
            updateUi();
        }

        @Override public void onDescriptorWrite(BluetoothGatt current, BluetoothGattDescriptor descriptor, int status) {
            append("CCCD status=" + status);
        }

        @Override public void onCharacteristicChanged(BluetoothGatt current, BluetoothGattCharacteristic c) {
            handleNotify(c, c.getValue());
        }

        @Override public void onCharacteristicChanged(BluetoothGatt current, BluetoothGattCharacteristic c, byte[] value) {
            handleNotify(c, value);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        buildUi();
        append("Orbis Watch Lab v" + VERSION + " iniciado");
        append("Comparador genérico: idioma, brilho, hora e demais campos DF 00 4C");
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

        LinearLayout tools1 = new LinearLayout(this);
        tools1.setOrientation(LinearLayout.HORIZONTAL);
        clearButton = button("LIMPAR LOG", v -> clearLog());
        copyButton = button("COPIAR LOG", v -> copyLog());
        addWeighted(tools1, clearButton);
        addWeighted(tools1, copyButton);
        root.addView(tools1);

        LinearLayout tools2 = new LinearLayout(this);
        tools2.setOrientation(LinearLayout.HORIZONTAL);
        resetButton = button("NOVA BASE", v -> resetComparator());
        disconnectButton = button("DESCONECTAR", v -> disconnect());
        addWeighted(tools2, resetButton);
        addWeighted(tools2, disconnectButton);
        root.addView(tools2);

        TextView warning = new TextView(this);
        warning.setText("O Orbis compara todo pacote DF 00 4C com o anterior. Mude apenas uma opção por vez. LIMPAR LOG preserva a base; NOVA BASE apaga a comparação anterior.");
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
        gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        updateUi();
    }

    private void enableNotify() {
        if (gatt == null || nusTx == null || !hasConnect()) return;
        try {
            append("setCharacteristicNotification=" + gatt.setCharacteristicNotification(nusTx, true));
            BluetoothGattDescriptor descriptor = nusTx.getDescriptor(CCCD);
            if (descriptor == null) { append("CCCD não encontrado"); return; }
            byte[] value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            if (Build.VERSION.SDK_INT >= 33) append("writeDescriptor result=" + gatt.writeDescriptor(descriptor, value));
            else {
                descriptor.setValue(value);
                append("writeDescriptor iniciado=" + gatt.writeDescriptor(descriptor));
            }
        } catch (Exception e) {
            append("ERRO NOTIFY " + e.getMessage());
        }
    }

    private void sendPacket(String label, byte[] data) {
        if (!connected || gatt == null || nusRx == null || !hasConnect()) {
            toast("Conecte ao G28 primeiro");
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
        if (value == null) return;
        if (NUS_TX.equals(c.getUuid())) append("RX NUS-TX | " + hex(value));
        else append("RX " + c.getUuid() + " | " + hex(value));
        if (isStateSnapshot(value)) analyzeSnapshot(value);
    }

    private boolean isStateSnapshot(byte[] value) {
        return value.length >= 4 && (value[0] & 0xFF) == 0xDF && (value[1] & 0xFF) == 0x00 && (value[2] & 0xFF) == 0x4C;
    }

    private void analyzeSnapshot(byte[] current) {
        byte[] copy = Arrays.copyOf(current, current.length);
        append("ANÁLISE DF 00 4C | " + describeKnownFields(copy));
        if (lastSnapshot == null) {
            lastSnapshot = copy;
            append("BASE CAPTURADA | altere uma única configuração no relógio");
            return;
        }

        int common = Math.min(lastSnapshot.length, copy.length);
        int changes = 0;
        StringBuilder report = new StringBuilder();
        for (int i = 0; i < common; i++) {
            int before = lastSnapshot[i] & 0xFF;
            int after = copy[i] & 0xFF;
            if (before != after) {
                changes++;
                report.append("\n  offset ").append(i)
                        .append(" (byte ").append(i + 1).append(")")
                        .append(": ").append(String.format(Locale.US, "%02X", before))
                        .append(" → ").append(String.format(Locale.US, "%02X", after))
                        .append(fieldHint(i, before, after));
            }
        }
        if (lastSnapshot.length != copy.length) {
            report.append("\n  tamanho: ").append(lastSnapshot.length).append(" → ").append(copy.length);
        }
        append("DIFERENÇAS: " + changes + report);
        validateLanguageRelation(copy);
        lastSnapshot = copy;
    }

    private String describeKnownFields(byte[] packet) {
        if (packet.length <= 49) return "snapshot curto (" + packet.length + " bytes)";
        int language = packet[49] & 0xFF;
        return "idioma=" + languageName(language) + " [" + String.format(Locale.US, "%02X", language) + "]";
    }

    private String fieldHint(int offset, int before, int after) {
        if (offset == 49) return " | IDIOMA: " + languageName(before) + " → " + languageName(after);
        if (offset == 3) return " | controle/valor derivado provável";
        return " | campo ainda desconhecido";
    }

    private void validateLanguageRelation(byte[] packet) {
        if (packet.length <= 49) return;
        int control = packet[3] & 0xFF;
        int language = packet[49] & 0xFF;
        int expected = (0x11 + language) & 0xFF;
        append("RELAÇÃO IDIOMA | controle=" + String.format(Locale.US, "%02X", control)
                + " esperado=" + String.format(Locale.US, "%02X", expected)
                + (control == expected ? " | VÁLIDA" : " | NÃO CONFIRMADA"));
    }

    private String languageName(int code) {
        switch (code) {
            case 0x00: return "Inglês";
            case 0x04: return "Espanhol";
            case 0x06: return "Português";
            case 0x1C: return "22º idioma (nome desconhecido)";
            default: return "desconhecido";
        }
    }

    private void clearLog() {
        log.setLength(0);
        logView.setText("");
        append("LOG LIMPO | base diferencial preservada");
    }

    private void resetComparator() {
        lastSnapshot = null;
        append("COMPARADOR RESETADO | o próximo DF 00 4C será a nova base");
        toast("Próximo snapshot será a nova base");
    }

    private void copyLog() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) { toast("Área de transferência indisponível"); return; }
        clipboard.setPrimaryClip(ClipData.newPlainText("Orbis Watch Lab Log", log.toString()));
        toast("Log copiado");
    }

    private void disconnect() {
        stopScan();
        if (gatt != null) {
            try { gatt.disconnect(); } catch (Exception ignored) {}
            try { gatt.close(); } catch (Exception ignored) {}
        }
        gatt = null;
        connected = false;
        connecting = false;
        nusRx = null;
        nusTx = null;
        setStatus("Desconectado");
        updateUi();
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

    private boolean hasScan() {
        return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasConnect() {
        return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private String safeName(BluetoothDevice d) {
        if (!hasConnect()) return null;
        try { return d.getName(); } catch (Exception e) { return null; }
    }

    private String safeAddress(BluetoothDevice d) {
        if (!hasConnect()) return "?";
        try { return d.getAddress(); } catch (Exception e) { return "?"; }
    }

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

    private void addWeighted(LinearLayout row, View view) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(56), 1f);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        row.addView(view, lp);
    }

    private void append(String message) {
        String line = clock.format(new Date()) + "  " + message;
        log.append(line).append('\n');
        runOnUiThread(() -> {
            logView.setText(log.toString());
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
            updateUi();
        });
    }

    private void setStatus(String text) {
        runOnUiThread(() -> statusView.setText(text));
    }

    private void updateUi() {
        runOnUiThread(() -> {
            boolean ready = connected && nusRx != null && nusTx != null;
            searchButton.setEnabled(!connected && !connecting && gatt == null);
            capturedButton.setEnabled(ready);
            readButton.setEnabled(ready);
            clearButton.setEnabled(true);
            copyButton.setEnabled(log.length() > 0);
            resetButton.setEnabled(true);
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String text) {
        runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show());
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        disconnect();
        super.onDestroy();
    }
}
