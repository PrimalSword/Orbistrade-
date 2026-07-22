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
import android.bluetooth.BluetoothStatusCodes;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class MainActivityV14 extends Activity {
    private static final String VERSION = "0.14";
    private static final int REQ = 1401;
    private static final long SCAN_MS = 15000;
    private static final long TEST_WINDOW_MS = 15000;
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID NUS_SERVICE = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9f");
    private static final UUID NUS_RX = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9f");
    private static final UUID NUS_TX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9f");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private final StringBuilder log = new StringBuilder();
    private final Map<String, List<String>> results = new LinkedHashMap<>();

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothDevice lastDevice;
    private BluetoothGattCharacteristic nusRx;
    private BluetoothGattCharacteristic nusTx;

    private TextView statusView;
    private TextView summaryView;
    private TextView logView;
    private ScrollView logScroll;
    private Button searchButton;
    private Button forceButton;
    private Button disconnectButton;
    private Button df00Button;
    private Button df01Button;
    private Button df02Button;
    private Button df03Button;
    private Button stopButton;

    private boolean connected;
    private boolean connecting;
    private boolean running;
    private String activeCommand = "";
    private int repeatTarget;
    private int repeatIndex;
    private long sentAt;
    private int rxThisRun;

    private final Runnable scanTimeout = () -> {
        stopScan();
        if (!connected && !connecting) setStatus("G28 não encontrado");
    };

    private final Runnable nextRepeat = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (!connected || nusRx == null) {
                stopTest("conexão/canal indisponível");
                return;
            }
            if (repeatIndex >= repeatTarget) {
                finishTest();
                return;
            }
            repeatIndex++;
            sentAt = System.currentTimeMillis();
            append("TESTE " + repeatIndex + "/" + repeatTarget + " TX=" + activeCommand);
            writeHex(activeCommand);
            updateSummary();
            handler.postDelayed(this, TEST_WINDOW_MS);
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice d = result.getDevice();
            String name = safeName(d);
            if ((name == null || name.isEmpty()) && result.getScanRecord() != null) {
                name = result.getScanRecord().getDeviceName();
            }
            append("SCAN: " + (name == null ? "(sem nome)" : name) + " | " + safeAddress(d) + " | RSSI " + result.getRssi());
            if (name != null && name.toUpperCase(Locale.US).contains("G28")) {
                lastDevice = d;
                stopScan();
                connect(d);
            }
        }

        @Override public void onScanFailed(int errorCode) {
            connecting = false;
            append("SCAN FAILED: " + errorCode);
            setStatus("Falha no scanner: " + errorCode);
            updateUi();
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt current, int status, int newState) {
            if (current != gatt) {
                try { current.close(); } catch (Exception ignored) { }
                return;
            }
            append("STATE: status=" + status + " state=" + newState);
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connecting = false;
                connected = true;
                setStatus("G28 conectado. Descobrindo serviços...");
                if (hasConnect()) {
                    try { current.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH); } catch (Exception ignored) { }
                    append("discoverServices()=" + current.discoverServices());
                    try { current.requestMtu(247); } catch (Exception ignored) { }
                }
                updateUi();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                connected = false;
                connecting = false;
                running = false;
                nusRx = null;
                nusTx = null;
                setStatus("Desconectado");
                updateUi();
            }
        }

        @Override public void onMtuChanged(BluetoothGatt current, int mtu, int status) {
            if (current == gatt) append("MTU: " + mtu + " status=" + status);
        }

        @Override public void onServicesDiscovered(BluetoothGatt current, int status) {
            if (current != gatt) return;
            append("SERVICES: status=" + status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setStatus("Falha ao descobrir serviços");
                return;
            }
            List<BluetoothGattService> services = current.getServices();
            append("TOTAL DE SERVIÇOS: " + services.size());
            for (BluetoothGattService service : services) {
                append("SERVIÇO: " + service.getUuid());
                for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                    append("  CHAR: " + c.getUuid() + " | props=" + c.getProperties());
                    if (c.getUuid().equals(NUS_RX)) nusRx = c;
                    if (c.getUuid().equals(NUS_TX)) nusTx = c;
                }
            }
            enableNusNotify();
            setStatus(nusRx != null && nusTx != null ? "G28 pronto | NUS ativo" : "G28 conectado | NUS incompleto");
            updateUi();
        }

        @Override public void onDescriptorWrite(BluetoothGatt current, BluetoothGattDescriptor descriptor, int status) {
            if (current == gatt) append("CCCD [NUS-TX] status=" + status);
        }

        @Override public void onCharacteristicChanged(BluetoothGatt current, BluetoothGattCharacteristic c) {
            if (current != gatt) return;
            handleNotify(c, c.getValue());
        }

        @Override public void onCharacteristicChanged(BluetoothGatt current, BluetoothGattCharacteristic c, byte[] value) {
            if (current != gatt) return;
            handleNotify(c, value);
        }

        @Override public void onCharacteristicWrite(BluetoothGatt current, BluetoothGattCharacteristic c, int status) {
            if (current == gatt) append("TX CALLBACK [NUS-RX] status=" + status);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        buildUi();
        append("Orbis Watch Lab v" + VERSION + " iniciado | Android " + Build.VERSION.RELEASE + " API " + Build.VERSION.SDK_INT);
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
        root.addView(statusView);

        LinearLayout row1 = row();
        searchButton = button("PROCURAR", v -> startScan());
        forceButton = button("FORÇAR SEGURO", v -> forceSafe());
        disconnectButton = button("DESCONECTAR", v -> disconnect());
        addWeighted(row1, searchButton); addWeighted(row1, forceButton); addWeighted(row1, disconnectButton);
        root.addView(row1);

        summaryView = new TextView(this);
        summaryView.setTextSize(17);
        summaryView.setPadding(0, dp(10), 0, dp(8));
        root.addView(summaryView);

        LinearLayout row2 = row();
        df00Button = button("DF00 ×3", v -> startTest("DF 00", 3));
        df01Button = button("DF01 ×3", v -> startTest("DF 01", 3));
        addWeighted(row2, df00Button); addWeighted(row2, df01Button);
        root.addView(row2);

        LinearLayout row3 = row();
        df02Button = button("DF02 ×5", v -> startTest("DF 02", 5));
        df03Button = button("DF03 ×3", v -> startTest("DF 03", 3));
        addWeighted(row3, df02Button); addWeighted(row3, df03Button);
        root.addView(row3);

        stopButton = button("PARAR ENSAIO", v -> stopTest("interrompido manualmente"));
        root.addView(stopButton, new LinearLayout.LayoutParams(-1, dp(58)));

        LinearLayout tools = row();
        addWeighted(tools, button("RESUMO", v -> showSummary()));
        addWeighted(tools, button("LIMPAR", v -> clearLog()));
        root.addView(tools);

        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logScroll = new ScrollView(this);
        logScroll.addView(logView);
        root.addView(logScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        updateSummary();
        updateUi();
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private Button button(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(listener);
        return b;
    }

    private void addWeighted(LinearLayout row, View view) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(64), 1f);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        row.addView(view, lp);
    }

    private void startScan() {
        if (connected || connecting || gatt != null) {
            toast("Já existe conexão ativa. Use desconectar primeiro.");
            return;
        }
        if (!ensurePermissions()) return;
        if (adapter == null || !adapter.isEnabled()) {
            toast("Ative o Bluetooth");
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) return;
        append("Iniciando busca BLE...");
        setStatus("Procurando G28...");
        scanner.startScan(scanCallback);
        handler.postDelayed(scanTimeout, SCAN_MS);
    }

    private void stopScan() {
        handler.removeCallbacks(scanTimeout);
        if (scanner != null && hasScan()) {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) { }
        }
        scanner = null;
    }

    private void forceSafe() {
        if (connected || connecting || gatt != null) {
            toast("Conexão já ativa; FORÇAR bloqueado por segurança");
            return;
        }
        if (lastDevice == null) {
            toast("Procure o G28 primeiro");
            return;
        }
        connect(lastDevice);
    }

    private void connect(BluetoothDevice device) {
        if (!hasConnect()) return;
        if (gatt != null || connected || connecting) return;
        connecting = true;
        setStatus("Conectando ao G28...");
        append("CONNECT | " + safeAddress(device));
        gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        updateUi();
    }

    private void enableNusNotify() {
        if (gatt == null || nusTx == null || !hasConnect()) return;
        try {
            gatt.setCharacteristicNotification(nusTx, true);
            BluetoothGattDescriptor d = nusTx.getDescriptor(CCCD);
            if (d != null) {
                byte[] value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                if (Build.VERSION.SDK_INT >= 33) gatt.writeDescriptor(d, value);
                else { d.setValue(value); gatt.writeDescriptor(d); }
                append("NOTIFY NUS-TX solicitado");
            }
        } catch (Exception e) {
            append("ERRO NOTIFY: " + e.getMessage());
        }
    }

    private void startTest(String command, int count) {
        if (!connected || nusRx == null) {
            toast("Conecte ao G28 primeiro");
            return;
        }
        if (running) {
            toast("Já existe ensaio em andamento");
            return;
        }
        running = true;
        activeCommand = command;
        repeatTarget = count;
        repeatIndex = 0;
        rxThisRun = 0;
        results.put(command, new ArrayList<>());
        append("========== LAB v0.14 INICIADO: " + command + " ×" + count + " ==========");
        append("Um pacote por vez; janela 15s; NUS-RX WRITE_NO_RESPONSE");
        updateUi();
        handler.post(nextRepeat);
    }

    private void writeHex(String hex) {
        if (gatt == null || nusRx == null || !hasConnect()) return;
        byte[] data = parseHex(hex);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                int result = gatt.writeCharacteristic(nusRx, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                append("TX [NUS-RX] " + hex + " result=" + result);
            } else {
                nusRx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                nusRx.setValue(data);
                append("TX [NUS-RX] " + hex + " iniciado=" + gatt.writeCharacteristic(nusRx));
            }
        } catch (Exception e) {
            append("ERRO TX: " + e.getMessage());
        }
    }

    private void handleNotify(BluetoothGattCharacteristic c, byte[] value) {
        String payload = hex(value);
        append("RX NOTIFY [" + shortUuid(c.getUuid()) + "] " + payload);
        if (running && c.getUuid().equals(NUS_TX)) {
            long latency = System.currentTimeMillis() - sentAt;
            rxThisRun++;
            List<String> list = results.get(activeCommand);
            if (list != null) list.add("#" + repeatIndex + " " + payload + " | " + latency + "ms");
            append("*** AMOSTRA " + repeatIndex + " TX=" + activeCommand + " RX=" + payload + " LATÊNCIA=" + latency + "ms ***");
            updateSummary();
        }
    }

    private void finishTest() {
        if (!running) return;
        running = false;
        handler.removeCallbacks(nextRepeat);
        append("========== LAB ENCERRADO: " + activeCommand + " | TX=" + repeatTarget + " RX=" + rxThisRun + " ==========");
        printCommandSummary(activeCommand);
        updateSummary();
        updateUi();
    }

    private void stopTest(String reason) {
        if (!running) return;
        running = false;
        handler.removeCallbacks(nextRepeat);
        append("========== LAB INTERROMPIDO: " + reason + " ==========");
        updateSummary();
        updateUi();
    }

    private void printCommandSummary(String command) {
        List<String> list = results.get(command);
        append("RESUMO " + command + ": " + (list == null ? 0 : list.size()) + " respostas");
        if (list != null) for (String item : list) append("  " + item);
    }

    private void showSummary() {
        append("========== RESUMO GERAL v0.14 ==========");
        for (Map.Entry<String, List<String>> e : results.entrySet()) {
            append(e.getKey() + " => " + e.getValue().size() + " respostas");
            for (String item : e.getValue()) append("  " + item);
        }
    }

    private void updateSummary() {
        String text = running
                ? "Ensaio: " + activeCommand + " | " + repeatIndex + "/" + repeatTarget + " | RX " + rxThisRun
                : "Laboratório parado | comandos mapeados: " + results.size();
        if (summaryView != null) summaryView.setText(text);
    }

    private void disconnect() {
        stopTest("desconectado");
        stopScan();
        if (gatt != null && hasConnect()) {
            try { gatt.disconnect(); } catch (Exception ignored) { }
            try { gatt.close(); } catch (Exception ignored) { }
        }
        gatt = null;
        connected = false;
        connecting = false;
        nusRx = null;
        nusTx = null;
        setStatus("Desconectado");
        updateUi();
    }

    private void clearLog() {
        log.setLength(0);
        if (logView != null) logView.setText("");
    }

    private void append(String message) {
        String line = clock.format(new Date()) + "  " + message;
        log.append(line).append('\n');
        runOnUiThread(() -> {
            if (logView != null) logView.setText(log.toString());
            if (logScroll != null) logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void setStatus(String text) {
        runOnUiThread(() -> { if (statusView != null) statusView.setText(text); });
    }

    private void updateUi() {
        runOnUiThread(() -> {
            boolean ready = connected && nusRx != null && nusTx != null && !running;
            if (searchButton != null) searchButton.setEnabled(!connected && !connecting && gatt == null);
            if (forceButton != null) forceButton.setEnabled(!connected && !connecting && gatt == null && lastDevice != null);
            if (disconnectButton != null) disconnectButton.setEnabled(gatt != null || connected || connecting);
            if (df00Button != null) df00Button.setEnabled(ready);
            if (df01Button != null) df01Button.setEnabled(ready);
            if (df02Button != null) df02Button.setEnabled(ready);
            if (df03Button != null) df03Button.setEnabled(ready);
            if (stopButton != null) stopButton.setEnabled(running);
        });
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
        if (!hasConnect()) return "(sem permissão)";
        try { return d.getAddress(); } catch (Exception e) { return "?"; }
    }

    private static byte[] parseHex(String text) {
        String clean = text.replaceAll("[^0-9A-Fa-f]", "");
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    private static String hex(byte[] value) {
        if (value == null || value.length == 0) return "(vazio)";
        StringBuilder b = new StringBuilder();
        for (byte v : value) {
            if (b.length() > 0) b.append(' ');
            b.append(String.format(Locale.US, "%02X", v & 0xFF));
        }
        return b.toString();
    }

    private static String shortUuid(UUID u) {
        if (u.equals(NUS_TX)) return "NUS-TX";
        if (u.equals(NUS_RX)) return "NUS-RX";
        return u.toString().substring(4, 8).toUpperCase(Locale.US);
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
