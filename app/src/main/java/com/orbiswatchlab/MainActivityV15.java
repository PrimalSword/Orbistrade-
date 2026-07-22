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
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class MainActivityV15 extends Activity {
    private static final String VERSION = "0.15";
    private static final int REQ = 1501;
    private static final long SCAN_MS = 15000;
    private static final long SAMPLE_MS = 15000;
    private static final long CHANGE_MS = 30000;
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID NUS_RX = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9f");
    private static final UUID NUS_TX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9f");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private final StringBuilder log = new StringBuilder();
    private final List<byte[]> baseline = new ArrayList<>();
    private final List<byte[]> after = new ArrayList<>();

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothDevice lastDevice;
    private BluetoothGattCharacteristic nusRx;
    private BluetoothGattCharacteristic nusTx;

    private TextView statusView;
    private TextView phaseView;
    private TextView resultView;
    private TextView logView;
    private ScrollView logScroll;
    private Button searchButton;
    private Button forceButton;
    private Button disconnectButton;
    private Button baselineButton;
    private Button changeButton;
    private Button afterButton;
    private Button compareButton;
    private Button stopButton;

    private boolean connected;
    private boolean connecting;
    private boolean running;
    private String phase = "IDLE";
    private int sampleIndex;
    private long sentAt;

    private final Runnable scanTimeout = () -> {
        stopScan();
        if (!connected) setStatus("G28 não encontrado");
    };

    private final Runnable sampleRunner = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (!connected || nusRx == null) {
                stopRun("conexão/canal indisponível");
                return;
            }
            if (sampleIndex >= 3) {
                running = false;
                append("========== FASE ENCERRADA: " + phase + " | amostras=" + currentList().size() + " ==========");
                updatePhase();
                updateUi();
                if ("AFTER".equals(phase)) compareStates();
                return;
            }
            sampleIndex++;
            sentAt = System.currentTimeMillis();
            append("AMOSTRA " + sampleIndex + "/3 | " + phase + " | TX=DF 00");
            writeDf00();
            updatePhase();
            handler.postDelayed(this, SAMPLE_MS);
        }
    };

    private final Runnable changeCountdown = new Runnable() {
        private int left = 30;
        @Override public void run() {
            if (!running || !"CHANGE".equals(phase)) return;
            if (left <= 0) {
                running = false;
                append("========== JANELA DE MUDANÇA ENCERRADA ==========");
                phaseView.setText("Mudança concluída. Agora capture DEPOIS ×3.");
                updateUi();
                left = 30;
                return;
            }
            phaseView.setText("Altere UMA coisa no relógio agora | " + left + "s restantes");
            left--;
            handler.postDelayed(this, 1000);
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice d = result.getDevice();
            String name = safeName(d);
            if ((name == null || name.isEmpty()) && result.getScanRecord() != null) name = result.getScanRecord().getDeviceName();
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
            if (current != gatt) { try { current.close(); } catch (Exception ignored) {} return; }
            append("STATE: status=" + status + " state=" + newState);
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connecting = false;
                connected = true;
                setStatus("G28 conectado. Descobrindo serviços...");
                if (hasConnect()) {
                    try { current.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH); } catch (Exception ignored) {}
                    append("discoverServices()=" + current.discoverServices());
                    try { current.requestMtu(247); } catch (Exception ignored) {}
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
            if (status != BluetoothGatt.GATT_SUCCESS) return;
            for (BluetoothGattService service : current.getServices()) {
                for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                    if (c.getUuid().equals(NUS_RX)) nusRx = c;
                    if (c.getUuid().equals(NUS_TX)) nusTx = c;
                }
            }
            enableNotify();
            setStatus(nusRx != null && nusTx != null ? "G28 pronto | NUS ativo" : "G28 conectado | NUS incompleto");
            updateUi();
        }
        @Override public void onDescriptorWrite(BluetoothGatt current, BluetoothGattDescriptor descriptor, int status) {
            if (current == gatt) append("CCCD [NUS-TX] status=" + status);
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

        phaseView = new TextView(this);
        phaseView.setText("1) BASE ×3  2) MUDE UMA COISA  3) DEPOIS ×3  4) COMPARAR");
        phaseView.setTextSize(17);
        phaseView.setPadding(0, dp(10), 0, dp(8));
        root.addView(phaseView);

        LinearLayout row2 = row();
        baselineButton = button("BASE ×3", v -> startSamples("BASE"));
        changeButton = button("MUDAR 30s", v -> startChange());
        addWeighted(row2, baselineButton); addWeighted(row2, changeButton);
        root.addView(row2);

        LinearLayout row3 = row();
        afterButton = button("DEPOIS ×3", v -> startSamples("AFTER"));
        compareButton = button("COMPARAR", v -> compareStates());
        addWeighted(row3, afterButton); addWeighted(row3, compareButton);
        root.addView(row3);

        stopButton = button("PARAR", v -> stopRun("interrompido manualmente"));
        root.addView(stopButton, new LinearLayout.LayoutParams(-1, dp(58)));

        resultView = new TextView(this);
        resultView.setTextSize(16);
        resultView.setPadding(0, dp(8), 0, dp(8));
        root.addView(resultView);

        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logScroll = new ScrollView(this);
        logScroll.addView(logView);
        root.addView(logScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
        updateUi();
    }

    private void startSamples(String targetPhase) {
        if (!connected || nusRx == null) { toast("Conecte ao G28 primeiro"); return; }
        if (running) { toast("Já existe uma fase em andamento"); return; }
        phase = targetPhase;
        sampleIndex = 0;
        currentList().clear();
        running = true;
        append("========== FASE " + phase + " INICIADA: DF 00 ×3 ==========");
        updateUi();
        handler.post(sampleRunner);
    }

    private void startChange() {
        if (running) return;
        if (baseline.size() < 3) { toast("Capture BASE ×3 primeiro"); return; }
        phase = "CHANGE";
        running = true;
        append("========== JANELA DE MUDANÇA 30s ==========");
        append("Altere apenas UMA coisa no relógio e mantenha o novo estado.");
        updateUi();
        handler.post(changeCountdown);
    }

    private List<byte[]> currentList() { return "BASE".equals(phase) ? baseline : after; }

    private void writeDf00() {
        if (gatt == null || nusRx == null || !hasConnect()) return;
        byte[] data = new byte[]{(byte)0xDF, 0x00};
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                int r = gatt.writeCharacteristic(nusRx, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                append("TX [NUS-RX] DF 00 result=" + r);
            } else {
                nusRx.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                nusRx.setValue(data);
                append("TX [NUS-RX] DF 00 iniciado=" + gatt.writeCharacteristic(nusRx));
            }
        } catch (Exception e) { append("ERRO TX: " + e.getMessage()); }
    }

    private void handleNotify(BluetoothGattCharacteristic c, byte[] value) {
        String payload = hex(value);
        append("RX NOTIFY [NUS-TX] " + payload);
        if (running && ("BASE".equals(phase) || "AFTER".equals(phase)) && c.getUuid().equals(NUS_TX)) {
            long latency = System.currentTimeMillis() - sentAt;
            currentList().add(value == null ? new byte[0] : Arrays.copyOf(value, value.length));
            append("*** " + phase + " #" + sampleIndex + " RX=" + payload + " LATÊNCIA=" + latency + "ms ***");
            updatePhase();
        }
    }

    private void compareStates() {
        if (baseline.isEmpty() || after.isEmpty()) { toast("Capture BASE e DEPOIS primeiro"); return; }
        byte[] a = modePacket(baseline);
        byte[] b = modePacket(after);
        int n = Math.max(a.length, b.length);
        StringBuilder out = new StringBuilder();
        out.append("BASE: ").append(hex(a)).append('\n');
        out.append("DEPOIS: ").append(hex(b)).append('\n');
        out.append("MUDANÇAS:");
        int changes = 0;
        for (int i = 0; i < n; i++) {
            int av = i < a.length ? a[i] & 0xFF : -1;
            int bv = i < b.length ? b[i] & 0xFF : -1;
            if (av != bv) {
                changes++;
                out.append("\nbyte ").append(i + 1).append(": ").append(fmt(av)).append(" → ").append(fmt(bv));
            }
        }
        if (changes == 0) out.append(" nenhuma diferença estável");
        resultView.setText(out.toString());
        append("========== COMPARAÇÃO ==========");
        for (String line : out.toString().split("\n")) append(line);
    }

    private static byte[] modePacket(List<byte[]> packets) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, byte[]> originals = new LinkedHashMap<>();
        for (byte[] p : packets) {
            String h = hex(p);
            counts.put(h, counts.containsKey(h) ? counts.get(h) + 1 : 1);
            originals.put(h, p);
        }
        String best = null; int max = -1;
        for (Map.Entry<String, Integer> e : counts.entrySet()) if (e.getValue() > max) { best = e.getKey(); max = e.getValue(); }
        return best == null ? new byte[0] : originals.get(best);
    }

    private void stopRun(String reason) {
        if (!running) return;
        running = false;
        handler.removeCallbacks(sampleRunner);
        handler.removeCallbacks(changeCountdown);
        append("========== FASE INTERROMPIDA: " + reason + " ==========");
        updateUi();
    }

    private void updatePhase() {
        runOnUiThread(() -> {
            if ("BASE".equals(phase)) phaseView.setText("Capturando BASE | " + baseline.size() + "/3 respostas");
            else if ("AFTER".equals(phase)) phaseView.setText("Capturando DEPOIS | " + after.size() + "/3 respostas");
        });
    }

    private void enableNotify() {
        if (gatt == null || nusTx == null || !hasConnect()) return;
        try {
            gatt.setCharacteristicNotification(nusTx, true);
            BluetoothGattDescriptor d = nusTx.getDescriptor(CCCD);
            if (d != null) {
                byte[] value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
                if (Build.VERSION.SDK_INT >= 33) gatt.writeDescriptor(d, value);
                else { d.setValue(value); gatt.writeDescriptor(d); }
            }
        } catch (Exception e) { append("ERRO NOTIFY: " + e.getMessage()); }
    }

    private void startScan() {
        if (connected || connecting || gatt != null) { toast("Já existe conexão ativa"); return; }
        if (!ensurePermissions()) return;
        if (adapter == null || !adapter.isEnabled()) { toast("Ative o Bluetooth"); return; }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) return;
        connecting = true;
        append("Iniciando busca BLE...");
        setStatus("Procurando G28...");
        scanner.startScan(scanCallback);
        handler.postDelayed(scanTimeout, SCAN_MS);
        updateUi();
    }

    private void stopScan() {
        handler.removeCallbacks(scanTimeout);
        if (scanner != null && hasScan()) try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        scanner = null;
    }

    private void forceSafe() {
        if (connected || connecting || gatt != null) { toast("Conexão já ativa"); return; }
        if (lastDevice == null) { toast("Procure o G28 primeiro"); return; }
        connect(lastDevice);
    }

    private void connect(BluetoothDevice device) {
        if (!hasConnect() || gatt != null) return;
        connecting = true;
        append("CONNECT | " + safeAddress(device));
        gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        updateUi();
    }

    private void disconnect() {
        stopRun("desconectado");
        stopScan();
        if (gatt != null && hasConnect()) {
            try { gatt.disconnect(); } catch (Exception ignored) {}
            try { gatt.close(); } catch (Exception ignored) {}
        }
        gatt = null; connected = false; connecting = false; nusRx = null; nusTx = null;
        setStatus("Desconectado");
        updateUi();
    }

    private LinearLayout row() { LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); return r; }
    private Button button(String text, View.OnClickListener listener) { Button b = new Button(this); b.setText(text); b.setOnClickListener(listener); return b; }
    private void addWeighted(LinearLayout row, View view) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(64), 1f); lp.setMargins(dp(3), dp(3), dp(3), dp(3)); row.addView(view, lp); }

    private void append(String message) {
        String line = clock.format(new Date()) + "  " + message;
        log.append(line).append('\n');
        runOnUiThread(() -> { logView.setText(log.toString()); logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN)); });
    }
    private void setStatus(String text) { runOnUiThread(() -> statusView.setText(text)); }
    private void updateUi() {
        runOnUiThread(() -> {
            boolean ready = connected && nusRx != null && nusTx != null && !running;
            searchButton.setEnabled(!connected && !connecting && gatt == null);
            forceButton.setEnabled(!connected && !connecting && gatt == null && lastDevice != null);
            disconnectButton.setEnabled(gatt != null || connected || connecting);
            baselineButton.setEnabled(ready);
            changeButton.setEnabled(ready && baseline.size() >= 3);
            afterButton.setEnabled(ready && baseline.size() >= 3);
            compareButton.setEnabled(!running && !baseline.isEmpty() && !after.isEmpty());
            stopButton.setEnabled(running);
        });
    }

    private boolean ensurePermissions() {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (!missing.isEmpty()) { requestPermissions(missing.toArray(new String[0]), REQ); return false; }
        return true;
    }
    private boolean hasScan() { return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED; }
    private boolean hasConnect() { return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED; }
    private String safeName(BluetoothDevice d) { if (!hasConnect()) return null; try { return d.getName(); } catch (Exception e) { return null; } }
    private String safeAddress(BluetoothDevice d) { if (!hasConnect()) return "?"; try { return d.getAddress(); } catch (Exception e) { return "?"; } }
    private static String hex(byte[] value) { if (value == null || value.length == 0) return "(vazio)"; StringBuilder b = new StringBuilder(); for (byte v : value) { if (b.length() > 0) b.append(' '); b.append(String.format(Locale.US, "%02X", v & 0xFF)); } return b.toString(); }
    private static String fmt(int v) { return v < 0 ? "--" : String.format(Locale.US, "%02X", v); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String text) { runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show()); }

    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); disconnect(); super.onDestroy(); }
}
