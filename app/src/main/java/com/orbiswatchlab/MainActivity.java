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
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 1001;
    private static final long SCAN_TIMEOUT_MS = 15000;
    private static final long CONNECT_TIMEOUT_MS = 12000;
    private static final long OP_TIMEOUT_MS = 5000;
    private static final long PROBE_GAP_MS = 1400;

    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID FF01 = uuid16("ff01"), FF02 = uuid16("ff02"), FFF1 = uuid16("fff1"), FF13 = uuid16("ff13"), FF14 = uuid16("ff14");
    private static final UUID NUS_RX = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9f");
    private static final UUID NUS_TX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9f");

    /* Somente sondas curtas. Não usa FFF1/FF13, pacotes longos, OTA ou reset. */
    private static final String[][] SAFE_PROBES = {
            {"P01_ZERO", "00"},
            {"P02_ONE", "01"},
            {"P03_QUERY", "02"},
            {"P04_A55A", "A5 5A"},
            {"P05_55AA", "55 AA"},
            {"P06_DF00", "DF 00"},
            {"P07_DF0000", "DF 00 00"},
            {"P08_FE01", "FE 01"}
    };

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothDevice lastDevice;
    private TextView statusView, logView, rxSummaryView, probeView;
    private ScrollView logScroll;
    private EditText hexInput, markerInput;
    private Spinner channelSpinner;
    private Button sendButton, probeButton, stopProbeButton;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuffer = new StringBuilder(), rxBuffer = new StringBuilder();
    private final Queue<GattOperation> operationQueue = new ArrayDeque<>();
    private final List<WriteChannel> writeChannels = new ArrayList<>();
    private final List<SessionEvent> sessionEvents = new ArrayList<>();
    private final Map<String, Integer> packetCounts = new LinkedHashMap<>();
    private ArrayAdapter<String> channelAdapter;

    private boolean connected, manualDisconnect, forceMode, operationRunning;
    private boolean probeRunning, probeResponseDetected;
    private int connectionAttempt, negotiatedMtu = 23, rxPacketCount;
    private int probeChannelIndex, probeCommandIndex, probeSentCount;
    private long sessionStartedAt;
    private String lastProbeLabel = "", lastProbeChannel = "", lastProbePayload = "";

    private enum OperationType { MTU, READ, NOTIFY, WRITE }

    private static class GattOperation {
        final OperationType type;
        final BluetoothGattCharacteristic characteristic;
        final byte[] data;
        final int mtu;
        GattOperation(OperationType type, BluetoothGattCharacteristic characteristic, byte[] data, int mtu) {
            this.type = type; this.characteristic = characteristic; this.data = data; this.mtu = mtu;
        }
        static GattOperation mtu(int value) { return new GattOperation(OperationType.MTU, null, null, value); }
        static GattOperation read(BluetoothGattCharacteristic c) { return new GattOperation(OperationType.READ, c, null, 0); }
        static GattOperation notify(BluetoothGattCharacteristic c) { return new GattOperation(OperationType.NOTIFY, c, null, 0); }
        static GattOperation write(BluetoothGattCharacteristic c, byte[] data) { return new GattOperation(OperationType.WRITE, c, data, 0); }
    }

    private static class WriteChannel {
        final String label;
        final BluetoothGattCharacteristic characteristic;
        WriteChannel(String label, BluetoothGattCharacteristic characteristic) { this.label = label; this.characteristic = characteristic; }
    }

    private static class SessionEvent {
        final long timestamp;
        final String type, channel, payload, note;
        SessionEvent(long timestamp, String type, String channel, String payload, String note) {
            this.timestamp = timestamp; this.type = type; this.channel = channel; this.payload = payload; this.note = note;
        }
    }

    private final Runnable connectionTimeout = () -> {
        if (!connected && gatt != null) {
            appendLog("TIMEOUT DE CONEXÃO após " + CONNECT_TIMEOUT_MS + " ms");
            setStatus("Timeout ao conectar"); closeGattOnly();
            if (forceMode && lastDevice != null && connectionAttempt < 3) handler.postDelayed(() -> connect(lastDevice, true), 1200);
        }
    };

    private final Runnable operationTimeout = () -> {
        if (operationRunning) {
            appendLog("FILA: operação expirou; avançando"); operationRunning = false; runNextOperation();
        }
    };

    private final Runnable nextProbe = new Runnable() {
        @Override public void run() {
            if (!probeRunning || !connected || operationRunning || !operationQueue.isEmpty()) {
                if (probeRunning) handler.postDelayed(this, 250);
                return;
            }
            if (probeResponseDetected) { stopProbe("resposta detectada"); return; }
            List<WriteChannel> channels = probeChannels();
            if (channels.isEmpty()) { stopProbe("nenhum canal seguro disponível"); return; }
            if (probeChannelIndex >= channels.size()) { stopProbe("matriz concluída sem notificação"); return; }

            WriteChannel channel = channels.get(probeChannelIndex);
            String[] probe = SAFE_PROBES[probeCommandIndex];
            byte[] data = parseHex(probe[1]);
            lastProbeLabel = probe[0]; lastProbeChannel = channel.label; lastProbePayload = probe[1]; probeSentCount++;
            appendLog("PROBE " + probeSentCount + " [" + probe[0] + "] via " + channel.label + " | " + probe[1]);
            addEvent("PROBE", channel.label, probe[1], probe[0]);
            updateProbeText("Executando " + probe[0] + " em " + channel.label + " | " + probeSentCount);
            enqueue(GattOperation.write(channel.characteristic, data)); runNextOperation();

            probeCommandIndex++;
            if (probeCommandIndex >= SAFE_PROBES.length) { probeCommandIndex = 0; probeChannelIndex++; }
            handler.postDelayed(this, PROBE_GAP_MS);
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice(); String name = safeName(device);
            if ((name == null || "(sem nome)".equals(name)) && result.getScanRecord() != null && result.getScanRecord().getDeviceName() != null) name = result.getScanRecord().getDeviceName();
            appendLog("SCAN: " + name + " | " + safeAddress(device) + " | RSSI " + result.getRssi());
            if (name != null && name.toUpperCase(Locale.US).contains("G28")) { lastDevice = device; stopScan(); connect(device, forceMode); }
        }
        @Override public void onScanFailed(int errorCode) { appendLog("SCAN FAILED: código=" + errorCode); setStatus("Falha no scanner: " + errorCode); }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt currentGatt, int status, int newState) {
            handler.removeCallbacks(connectionTimeout);
            appendLog("STATE: status=" + status + " (" + statusName(status) + ") state=" + newState + " (" + stateName(newState) + ")");
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true; gatt = currentGatt; sessionStartedAt = System.currentTimeMillis(); addEvent("STATE", "", "", "CONNECTED");
                setStatus("G28 conectado. Descobrindo serviços...");
                if (hasConnectPermission()) {
                    try { currentGatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH); } catch (Exception ignored) { }
                    appendLog("discoverServices()=" + currentGatt.discoverServices());
                }
                return;
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false; stopProbe("desconectado"); clearGattQueue(); addEvent("STATE", "", "", "DISCONNECTED " + statusName(status));
                setStatus("Desconectado: " + statusName(status)); try { currentGatt.close(); } catch (Exception ignored) { }
                if (gatt == currentGatt) gatt = null; updateSendState();
                if (!manualDisconnect && forceMode && lastDevice != null && connectionAttempt < 3) handler.postDelayed(() -> connect(lastDevice, true), 1500);
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt currentGatt, int status) {
            appendLog("SERVICES: status=" + statusName(status));
            if (status != BluetoothGatt.GATT_SUCCESS) { setStatus("Falha ao descobrir serviços"); return; }
            writeChannels.clear(); clearGattQueue(); List<BluetoothGattService> services = currentGatt.getServices();
            appendLog("TOTAL DE SERVIÇOS: " + services.size()); enqueue(GattOperation.mtu(247));
            for (BluetoothGattService service : services) {
                appendLog("SERVIÇO: " + service.getUuid());
                for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                    int p = c.getProperties(); appendLog("  CHAR: " + c.getUuid() + " | props=" + propertiesText(p));
                    if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 || (p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) writeChannels.add(new WriteChannel(channelLabel(c.getUuid()), c));
                    if ((p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 || (p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) enqueue(GattOperation.notify(c));
                    if ((p & BluetoothGattCharacteristic.PROPERTY_READ) != 0) enqueue(GattOperation.read(c));
                }
            }
            refreshChannelSpinner(); setStatus("Configurando canais GATT..."); runNextOperation();
        }

        @Override public void onMtuChanged(BluetoothGatt currentGatt, int mtu, int status) {
            negotiatedMtu = mtu; appendLog("MTU: " + mtu + " status=" + statusName(status)); addEvent("MTU", "", String.valueOf(mtu), statusName(status)); completeOperation();
        }
        @Override public void onCharacteristicRead(BluetoothGatt currentGatt, BluetoothGattCharacteristic c, int status) {
            String channel = shortUuid(c.getUuid()), payload = bytesToHex(c.getValue());
            appendLog("RX READ [" + channel + "] status=" + statusName(status) + " | " + payload); recordRx("READ", channel, payload, statusName(status)); completeOperation();
        }
        @Override public void onCharacteristicChanged(BluetoothGatt currentGatt, BluetoothGattCharacteristic c) {
            String channel = shortUuid(c.getUuid()), payload = bytesToHex(c.getValue());
            appendLog("RX NOTIFY [" + channel + "] " + payload); recordRx("NOTIFY", channel, payload, "");
            if (probeRunning) {
                probeResponseDetected = true;
                appendLog("*** RESPOSTA AO PROBE: " + lastProbeLabel + " via " + lastProbeChannel + " TX=" + lastProbePayload + " RX[" + channel + "]=" + payload + " ***");
                addEvent("PROBE_HIT", channel, payload, lastProbeLabel + " via " + lastProbeChannel + " TX=" + lastProbePayload);
                stopProbe("resposta detectada em " + channel);
            }
        }
        @Override public void onCharacteristicWrite(BluetoothGatt currentGatt, BluetoothGattCharacteristic c, int status) { appendLog("TX CALLBACK [" + shortUuid(c.getUuid()) + "] status=" + statusName(status)); completeOperation(); }
        @Override public void onDescriptorWrite(BluetoothGatt currentGatt, BluetoothGattDescriptor d, int status) { appendLog("CCCD [" + shortUuid(d.getCharacteristic().getUuid()) + "] status=" + statusName(status)); completeOperation(); }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState); sessionStartedAt = System.currentTimeMillis(); buildInterface(); initializeBluetooth(); requestRequiredPermissions();
        appendLog("Orbis Watch Lab v0.7 iniciado | Android " + Build.VERSION.RELEASE + " API " + Build.VERSION.SDK_INT);
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(8), dp(8), dp(8), dp(8));
        TextView title = new TextView(this); title.setText("ORBIS WATCH LAB v0.7"); title.setTextSize(20); title.setTypeface(Typeface.DEFAULT_BOLD); root.addView(title);
        statusView = new TextView(this); statusView.setText("Inicializando Bluetooth..."); statusView.setTextSize(13); root.addView(statusView);
        LinearLayout row1 = new LinearLayout(this); row1.addView(button("PROCURAR", v -> startScan()), weight()); row1.addView(button("FORÇAR", v -> forceGatt()), weight()); row1.addView(button("DESCONECTAR", v -> disconnectGatt()), weight()); root.addView(row1);
        LinearLayout row2 = new LinearLayout(this); row2.addView(button("LOG", v -> shareLog()), weight()); row2.addView(button("JSON", v -> shareJson()), weight()); row2.addView(button("LIMPAR", v -> clearSession()), weight()); root.addView(row2);
        rxSummaryView = new TextView(this); rxSummaryView.setText("RX: 0 pacotes | 0 padrões"); rxSummaryView.setTypeface(Typeface.DEFAULT_BOLD); root.addView(rxSummaryView);
        probeView = new TextView(this); probeView.setText("Sonda controlada parada"); root.addView(probeView);
        LinearLayout probeRow = new LinearLayout(this); probeButton = button("INICIAR SONDA SEGURA", v -> startProbe()); stopProbeButton = button("PARAR", v -> stopProbe("parada manual")); probeRow.addView(probeButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2)); probeRow.addView(stopProbeButton, weight()); root.addView(probeRow);
        LinearLayout markerRow = new LinearLayout(this); markerInput = new EditText(this); markerInput.setHint("Marcador"); markerInput.setSingleLine(true); markerRow.addView(markerInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3)); markerRow.addView(button("MARCAR", v -> addMarker()), weight()); root.addView(markerRow);
        channelSpinner = new Spinner(this); channelAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new ArrayList<>()); channelSpinner.setAdapter(channelAdapter); root.addView(channelSpinner);
        LinearLayout sendRow = new LinearLayout(this); hexInput = new EditText(this); hexInput.setHint("HEX manual conhecido"); hexInput.setSingleLine(true); hexInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS); sendButton = button("ENVIAR", v -> sendHex()); sendRow.addView(hexInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3)); sendRow.addView(sendButton, weight()); root.addView(sendRow);
        logScroll = new ScrollView(this); logView = new TextView(this); logView.setText("LOG ORBIS\n"); logView.setTextSize(9); logView.setTypeface(Typeface.MONOSPACE); logView.setTextIsSelectable(true); logScroll.addView(logView); root.addView(logScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root); updateSendState();
    }

    private Button button(String text, View.OnClickListener listener) { Button b = new Button(this); b.setText(text); b.setTextSize(9); b.setOnClickListener(listener); return b; }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1); }

    private void initializeBluetooth() {
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (manager == null) { setStatus("BluetoothManager indisponível"); return; }
        adapter = manager.getAdapter(); if (adapter == null) { setStatus("Sem Bluetooth"); return; }
        if (!adapter.isEnabled()) { setStatus("Ative o Bluetooth"); return; }
        scanner = adapter.getBluetoothLeScanner(); setStatus("Pronto para procurar o G28");
    }

    private void requestRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_PERMISSIONS);
        } else if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_PERMISSIONS);
    }
    private boolean hasScanPermission() { return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED; }
    private boolean hasConnectPermission() { return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED; }

    private void startScan() {
        stopProbe("nova busca"); forceMode = false; manualDisconnect = false; connectionAttempt = 0; lastDevice = null;
        if (!hasScanPermission()) { requestRequiredPermissions(); return; }
        if (scanner == null && adapter != null) scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { setStatus("Scanner BLE indisponível"); return; }
        appendLog("Iniciando busca BLE..."); setStatus("Procurando o G28..."); scanner.startScan(scanCallback);
        handler.postDelayed(() -> { stopScan(); if (lastDevice == null) setStatus("G28 não encontrado"); }, SCAN_TIMEOUT_MS);
    }

    private void forceGatt() {
        stopProbe("reconexão forçada"); forceMode = true; manualDisconnect = false; connectionAttempt = 0;
        if (lastDevice != null) connect(lastDevice, true); else { if (!hasScanPermission()) { requestRequiredPermissions(); return; } if (scanner == null && adapter != null) scanner = adapter.getBluetoothLeScanner(); if (scanner != null) scanner.startScan(scanCallback); }
    }

    private void stopScan() { if (scanner != null && hasScanPermission()) try { scanner.stopScan(scanCallback); } catch (Exception ignored) { } }

    private void connect(BluetoothDevice device, boolean forced) {
        if (!hasConnectPermission()) { requestRequiredPermissions(); return; }
        stopScan(); closeGattOnly(); lastDevice = device; connected = false; manualDisconnect = false; connectionAttempt++;
        appendLog("CONNECT tentativa " + connectionAttempt + " | " + (forced ? "FORÇADO" : "NORMAL") + " | " + safeAddress(device));
        try {
            if (forced && Build.VERSION.SDK_INT >= 26) gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK);
            else if (Build.VERSION.SDK_INT >= 23) gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            else gatt = device.connectGatt(this, false, gattCallback);
            handler.postDelayed(connectionTimeout, CONNECT_TIMEOUT_MS);
        } catch (Exception e) { appendLog("EXCEÇÃO connectGatt: " + e.getMessage()); }
    }

    private void enqueue(GattOperation op) { operationQueue.offer(op); }
    private void runNextOperation() {
        if (!connected || gatt == null || operationRunning || !hasConnectPermission()) return;
        GattOperation op = operationQueue.poll();
        if (op == null) { setStatus("G28 pronto | MTU " + negotiatedMtu + " | " + writeChannels.size() + " canais TX"); updateSendState(); appendLog("FILA GATT concluída — captura ativa"); return; }
        operationRunning = true; handler.removeCallbacks(operationTimeout); handler.postDelayed(operationTimeout, OP_TIMEOUT_MS); boolean started = false;
        try {
            if (op.type == OperationType.MTU) { started = gatt.requestMtu(op.mtu); appendLog("FILA MTU " + op.mtu + " iniciado=" + started); }
            else if (op.type == OperationType.READ) { started = gatt.readCharacteristic(op.characteristic); appendLog("FILA READ [" + shortUuid(op.characteristic.getUuid()) + "] iniciado=" + started); }
            else if (op.type == OperationType.NOTIFY) { started = startNotification(gatt, op.characteristic); appendLog("FILA NOTIFY [" + shortUuid(op.characteristic.getUuid()) + "] iniciado=" + started); }
            else { started = startWrite(gatt, op.characteristic, op.data); String ch = shortUuid(op.characteristic.getUuid()), payload = bytesToHex(op.data); appendLog("TX [" + ch + "] " + payload + " iniciado=" + started); addEvent("TX", ch, payload, "started=" + started); }
        } catch (Exception e) { appendLog("FILA EXCEÇÃO: " + e.getMessage()); }
        if (!started) completeOperation();
    }

    private boolean startNotification(BluetoothGatt currentGatt, BluetoothGattCharacteristic c) {
        if (!currentGatt.setCharacteristicNotification(c, true)) return false;
        BluetoothGattDescriptor d = c.getDescriptor(CCCD); if (d == null) return false;
        byte[] value = (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
        if (Build.VERSION.SDK_INT >= 33) return currentGatt.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS;
        d.setValue(value); return currentGatt.writeDescriptor(d);
    }

    private boolean startWrite(BluetoothGatt currentGatt, BluetoothGattCharacteristic c, byte[] data) {
        int type = (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        c.setWriteType(type);
        if (Build.VERSION.SDK_INT >= 33) {
            boolean ok = currentGatt.writeCharacteristic(c, data, type) == BluetoothStatusCodes.SUCCESS;
            if (ok && type == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) handler.postDelayed(this::completeOperation, 180);
            return ok;
        }
        c.setValue(data); boolean ok = currentGatt.writeCharacteristic(c); if (ok && type == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) handler.postDelayed(this::completeOperation, 180); return ok;
    }

    private void completeOperation() { handler.removeCallbacks(operationTimeout); if (!operationRunning) return; operationRunning = false; handler.postDelayed(this::runNextOperation, 90); }
    private void clearGattQueue() { handler.removeCallbacks(operationTimeout); operationQueue.clear(); operationRunning = false; }

    private List<WriteChannel> probeChannels() {
        List<WriteChannel> result = new ArrayList<>();
        for (WriteChannel c : writeChannels) {
            UUID u = c.characteristic.getUuid();
            if (u.equals(NUS_RX) || u.equals(FF02)) result.add(c);
        }
        return result;
    }

    private void startProbe() {
        if (!connected || operationRunning || !operationQueue.isEmpty()) { toast("Aguarde GATT ficar pronto"); return; }
        if (probeChannels().isEmpty()) { toast("NUS RX/FF02 não disponíveis"); return; }
        probeRunning = true; probeResponseDetected = false; probeChannelIndex = 0; probeCommandIndex = 0; probeSentCount = 0;
        lastProbeLabel = lastProbeChannel = lastProbePayload = "";
        appendLog("========== SONDA CONTROLADA INICIADA ==========");
        appendLog("Escopo: NUS-RX e FF02; 8 pacotes curtos; FFF1/FF13/OTA excluídos");
        addEvent("PROBE_START", "", "", "controlled-safe-set"); updateProbeText("Sonda ativa"); updateSendState(); handler.post(nextProbe);
    }

    private void stopProbe(String reason) {
        boolean wasRunning = probeRunning; probeRunning = false; handler.removeCallbacks(nextProbe);
        if (wasRunning) { appendLog("========== SONDA ENCERRADA: " + reason + " =========="); addEvent("PROBE_STOP", "", "", reason); }
        updateProbeText("Sonda parada: " + reason); updateSendState();
    }

    private void updateProbeText(String text) { runOnUiThread(() -> { if (probeView != null) probeView.setText(text); }); }

    private void sendHex() {
        if (!connected || writeChannels.isEmpty() || probeRunning) { toast(probeRunning ? "Pare a sonda primeiro" : "Conecte o G28 primeiro"); return; }
        try {
            byte[] data = parseHex(hexInput.getText().toString()); if (data.length == 0) { toast("Digite HEX"); return; }
            if (data.length > Math.max(20, negotiatedMtu - 3)) { toast("Pacote grande demais"); return; }
            int i = channelSpinner.getSelectedItemPosition(); if (i < 0 || i >= writeChannels.size()) return;
            enqueue(GattOperation.write(writeChannels.get(i).characteristic, data)); runNextOperation();
        } catch (IllegalArgumentException e) { toast(e.getMessage()); }
    }

    private byte[] parseHex(String input) {
        String clean = input == null ? "" : input.replaceAll("(?i)0x", "").replaceAll("[^0-9A-Fa-f]", "");
        if (clean.isEmpty()) return new byte[0]; if ((clean.length() & 1) != 0) throw new IllegalArgumentException("HEX inválido");
        byte[] out = new byte[clean.length() / 2]; for (int i = 0; i < clean.length(); i += 2) out[i / 2] = (byte) Integer.parseInt(clean.substring(i, i + 2), 16); return out;
    }

    private void addMarker() {
        String note = markerInput.getText().toString().trim(); if (note.isEmpty()) note = "marcador manual";
        appendLog("========== MARCADOR: " + note + " =========="); synchronized (rxBuffer) { rxBuffer.append(timestamp()).append("  MARKER ").append(note).append('\n'); }
        addEvent("MARKER", "", "", note); markerInput.setText(""); toast("Marcador registrado");
    }

    private void recordRx(String type, String channel, String payload, String note) {
        rxPacketCount++; String signature = type + "|" + channel + "|" + payload; Integer count = packetCounts.get(signature); packetCounts.put(signature, count == null ? 1 : count + 1);
        synchronized (rxBuffer) { rxBuffer.append(timestamp()).append("  RX ").append(type).append(" [").append(channel).append("] ").append(payload).append('\n'); }
        addEvent("RX_" + type, channel, payload, note); runOnUiThread(() -> rxSummaryView.setText("RX: " + rxPacketCount + " pacotes | " + packetCounts.size() + " padrões"));
    }

    private void addEvent(String type, String channel, String payload, String note) { synchronized (sessionEvents) { sessionEvents.add(new SessionEvent(System.currentTimeMillis(), type, channel, payload, note)); } }
    private void clearSession() { stopProbe("sessão limpa"); synchronized (rxBuffer) { rxBuffer.setLength(0); } synchronized (sessionEvents) { sessionEvents.clear(); } packetCounts.clear(); rxPacketCount = 0; sessionStartedAt = System.currentTimeMillis(); rxSummaryView.setText("RX: 0 pacotes | 0 padrões"); appendLog("SESSÃO DE CAPTURA LIMPA"); }

    private void shareLog() { String text; synchronized (rxBuffer) { text = "ORBIS v0.7 — RX E PROBES\n\n" + rxBuffer + "\nLOG COMPLETO\n\n" + logBuffer; } shareText("Orbis v0.7 G28", text, "Compartilhar captura"); }
    private void shareJson() {
        try {
            JSONObject root = new JSONObject(); JSONArray events = new JSONArray(); JSONObject patterns = new JSONObject();
            root.put("app", "Orbis Watch Lab"); root.put("version", "0.7"); root.put("session_started_epoch_ms", sessionStartedAt); root.put("exported_epoch_ms", System.currentTimeMillis()); root.put("mtu", negotiatedMtu); root.put("rx_packet_count", rxPacketCount); root.put("probe_sent_count", probeSentCount); root.put("probe_response_detected", probeResponseDetected);
            synchronized (sessionEvents) { for (SessionEvent e : sessionEvents) { JSONObject item = new JSONObject(); item.put("timestamp_epoch_ms", e.timestamp); item.put("time", formatTime(e.timestamp)); item.put("type", e.type); item.put("channel", e.channel); item.put("payload_hex", e.payload); item.put("note", e.note); events.put(item); } }
            for (Map.Entry<String, Integer> entry : packetCounts.entrySet()) patterns.put(entry.getKey(), entry.getValue()); root.put("events", events); root.put("pattern_counts", patterns);
            shareText("Orbis v0.7 JSON", root.toString(2), "Compartilhar JSON");
        } catch (Exception e) { toast("Falha no JSON: " + e.getMessage()); }
    }
    private void shareText(String subject, String text, String title) { Intent i = new Intent(Intent.ACTION_SEND); i.setType("text/plain"); i.putExtra(Intent.EXTRA_SUBJECT, subject); i.putExtra(Intent.EXTRA_TEXT, text); startActivity(Intent.createChooser(i, title)); }

    private void refreshChannelSpinner() { runOnUiThread(() -> { channelAdapter.clear(); for (WriteChannel c : writeChannels) channelAdapter.add(c.label); channelAdapter.notifyDataSetChanged(); int p = preferredChannel(); if (p >= 0) channelSpinner.setSelection(p); updateSendState(); }); }
    private int preferredChannel() { for (int i = 0; i < writeChannels.size(); i++) if (writeChannels.get(i).characteristic.getUuid().equals(NUS_RX)) return i; for (int i = 0; i < writeChannels.size(); i++) if (writeChannels.get(i).characteristic.getUuid().equals(FF02)) return i; return writeChannels.isEmpty() ? -1 : 0; }
    private String channelLabel(UUID u) { if (u.equals(NUS_RX)) return "NUS RX → NUS TX"; if (u.equals(FF02)) return "FF02 → FF01"; if (u.equals(FF13)) return "FF13 → FF14"; if (u.equals(FFF1)) return "FFF1"; return shortUuid(u); }

    private void disconnectGatt() { stopProbe("desconexão manual"); manualDisconnect = true; forceMode = false; stopScan(); clearGattQueue(); if (gatt != null && hasConnectPermission()) { try { gatt.disconnect(); } catch (Exception ignored) { } try { gatt.close(); } catch (Exception ignored) { } } gatt = null; connected = false; writeChannels.clear(); refreshChannelSpinner(); setStatus("Desconectado"); appendLog("Conexão encerrada manualmente"); }
    private void closeGattOnly() { stopProbe("GATT fechado"); clearGattQueue(); if (gatt != null && hasConnectPermission()) { try { gatt.disconnect(); } catch (Exception ignored) { } try { gatt.close(); } catch (Exception ignored) { } } gatt = null; connected = false; updateSendState(); }
    private void updateSendState() { runOnUiThread(() -> { boolean ready = connected && !writeChannels.isEmpty(); if (sendButton != null) sendButton.setEnabled(ready && !probeRunning); if (hexInput != null) hexInput.setEnabled(ready && !probeRunning); if (channelSpinner != null) channelSpinner.setEnabled(ready && !probeRunning); if (probeButton != null) probeButton.setEnabled(ready && !probeRunning); if (stopProbeButton != null) stopProbeButton.setEnabled(probeRunning); }); }

    private String safeName(BluetoothDevice d) { if (!hasConnectPermission()) return "(sem permissão)"; try { String n = d.getName(); return n == null ? "(sem nome)" : n; } catch (Exception e) { return "(protegido)"; } }
    private String safeAddress(BluetoothDevice d) { if (!hasConnectPermission()) return "(protegido)"; try { return d.getAddress(); } catch (Exception e) { return "(protegido)"; } }
    private static UUID uuid16(String v) { return UUID.fromString("0000" + v.toLowerCase(Locale.US) + "-0000-1000-8000-00805f9b34fb"); }
    private String shortUuid(UUID u) { String s = u.toString().toUpperCase(Locale.US); if (s.endsWith("-0000-1000-8000-00805F9B34FB")) return s.substring(4, 8); if (u.equals(NUS_RX)) return "NUS-RX"; if (u.equals(NUS_TX)) return "NUS-TX"; return s; }
    private String bytesToHex(byte[] data) { if (data == null || data.length == 0) return "(vazio)"; StringBuilder b = new StringBuilder(); for (byte v : data) b.append(String.format(Locale.US, "%02X ", v & 0xFF)); return b.toString().trim(); }
    private String propertiesText(int p) { StringBuilder b = new StringBuilder(); if ((p & BluetoothGattCharacteristic.PROPERTY_READ) != 0) b.append("READ "); if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) b.append("WRITE "); if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) b.append("WRITE_NR "); if ((p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) b.append("NOTIFY "); if ((p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) b.append("INDICATE "); return b.toString().trim(); }
    private String statusName(int s) { if (s == BluetoothGatt.GATT_SUCCESS) return "SUCCESS"; if (s == 133) return "GATT_ERROR_133"; if (s == 201) return "BUSY"; return "STATUS_" + s; }
    private String stateName(int s) { if (s == BluetoothProfile.STATE_CONNECTED) return "CONNECTED"; if (s == BluetoothProfile.STATE_DISCONNECTED) return "DISCONNECTED"; return "STATE_" + s; }
    private String timestamp() { return formatTime(System.currentTimeMillis()); }
    private String formatTime(long t) { return new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date(t)); }
    private void setStatus(String m) { runOnUiThread(() -> statusView.setText(m)); }
    private void appendLog(String m) { String line = timestamp() + "  " + m; synchronized (logBuffer) { logBuffer.append(line).append('\n'); } runOnUiThread(() -> { logView.append(line + "\n"); logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN)); }); }
    private void toast(String m) { runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show()); }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
    @Override protected void onDestroy() { disconnectGatt(); super.onDestroy(); }
}
