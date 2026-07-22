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
    private static final String VERSION = "0.10";
    private static final int REQUEST_PERMISSIONS = 1001;
    private static final long SCAN_TIMEOUT_MS = 15000;
    private static final long CONNECT_TIMEOUT_MS = 12000;
    private static final long OP_TIMEOUT_MS = 5000;
    private static final long SHORT_GAP_MS = 2200;
    private static final long LONG_GAP_MS = 12000;

    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID FF02 = uuid16("ff02");
    private static final UUID FFF1 = uuid16("fff1");
    private static final UUID FF13 = uuid16("ff13");
    private static final UUID NUS_RX = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9f");
    private static final UUID NUS_TX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9f");

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothDevice lastDevice;

    private TextView statusView, logView, rxSummaryView, explorerView;
    private ScrollView logScroll;
    private EditText hexInput, markerInput;
    private Spinner channelSpinner;
    private Button sendButton, df00Button, df0000Button, fe01Button, stopButton;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuffer = new StringBuilder();
    private final StringBuilder rxBuffer = new StringBuilder();
    private final Queue<GattOperation> operationQueue = new ArrayDeque<>();
    private final List<WriteChannel> writeChannels = new ArrayList<>();
    private final List<SessionEvent> sessionEvents = new ArrayList<>();
    private final Map<String, Integer> packetCounts = new LinkedHashMap<>();
    private final Map<String, String> responseMap = new LinkedHashMap<>();
    private ArrayAdapter<String> channelAdapter;

    private boolean connected, manualDisconnect, forceMode, operationRunning, explorerRunning;
    private int connectionAttempt, negotiatedMtu = 23, rxPacketCount;
    private int explorerIndex, explorerSentCount, explorerHitCount;
    private long sessionStartedAt, activeTxAt, explorerGapMs;
    private String[] explorerCommands = new String[0];
    private String explorerMode = "", activeCommand = "", previousCommand = "", baselineResponse = "";

    private enum OperationType { MTU, READ, NOTIFY, WRITE }

    private static class GattOperation {
        final OperationType type;
        final BluetoothGattCharacteristic characteristic;
        final byte[] data;
        final int mtu;
        GattOperation(OperationType type, BluetoothGattCharacteristic characteristic, byte[] data, int mtu) {
            this.type = type;
            this.characteristic = characteristic;
            this.data = data;
            this.mtu = mtu;
        }
        static GattOperation mtu(int value) { return new GattOperation(OperationType.MTU, null, null, value); }
        static GattOperation read(BluetoothGattCharacteristic c) { return new GattOperation(OperationType.READ, c, null, 0); }
        static GattOperation notify(BluetoothGattCharacteristic c) { return new GattOperation(OperationType.NOTIFY, c, null, 0); }
        static GattOperation write(BluetoothGattCharacteristic c, byte[] data) { return new GattOperation(OperationType.WRITE, c, data, 0); }
    }

    private static class WriteChannel {
        final String label;
        final BluetoothGattCharacteristic characteristic;
        WriteChannel(String label, BluetoothGattCharacteristic characteristic) {
            this.label = label;
            this.characteristic = characteristic;
        }
    }

    private static class SessionEvent {
        final long timestamp;
        final String type, channel, payload, note;
        SessionEvent(long timestamp, String type, String channel, String payload, String note) {
            this.timestamp = timestamp;
            this.type = type;
            this.channel = channel;
            this.payload = payload;
            this.note = note;
        }
    }

    private final Runnable connectionTimeout = () -> {
        if (!connected && gatt != null) {
            appendLog("TIMEOUT DE CONEXÃO após " + CONNECT_TIMEOUT_MS + " ms");
            setStatus("Timeout ao conectar");
            closeGattOnly();
            if (forceMode && lastDevice != null && connectionAttempt < 3) handler.postDelayed(() -> connect(lastDevice, true), 1200);
        }
    };

    private final Runnable operationTimeout = () -> {
        if (operationRunning) {
            appendLog("FILA: operação expirou; avançando");
            operationRunning = false;
            runNextOperation();
        }
    };

    private final Runnable nextExplorerCommand = new Runnable() {
        @Override public void run() {
            if (!explorerRunning) return;
            if (!connected) { stopExplorer("desconectado"); return; }
            if (operationRunning || !operationQueue.isEmpty()) { handler.postDelayed(this, 200); return; }
            if (explorerIndex >= explorerCommands.length) { stopExplorer("sequência concluída"); return; }
            WriteChannel nus = findNusRx();
            if (nus == null) { stopExplorer("NUS-RX indisponível"); return; }

            previousCommand = activeCommand;
            activeCommand = explorerCommands[explorerIndex++];
            activeTxAt = System.currentTimeMillis();
            explorerSentCount++;
            appendLog("TESTE " + explorerSentCount + "/" + explorerCommands.length + " TX=" + activeCommand + " | anterior=" + safeCommand(previousCommand));
            addEvent("PROTO_TX", "NUS-RX", activeCommand, "mode=" + explorerMode + ";previous=" + safeCommand(previousCommand));
            updateExplorerText(explorerMode + " | " + explorerSentCount + "/" + explorerCommands.length + " | TX " + activeCommand);
            enqueue(GattOperation.write(nus.characteristic, parseHex(activeCommand)));
            runNextOperation();
            handler.postDelayed(this, explorerGapMs);
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = safeName(device);
            if ((name == null || "(sem nome)".equals(name)) && result.getScanRecord() != null && result.getScanRecord().getDeviceName() != null) name = result.getScanRecord().getDeviceName();
            appendLog("SCAN: " + name + " | " + safeAddress(device) + " | RSSI " + result.getRssi());
            if (name != null && name.toUpperCase(Locale.US).contains("G28")) {
                lastDevice = device;
                stopScan();
                connect(device, forceMode);
            }
        }
        @Override public void onScanFailed(int errorCode) {
            appendLog("SCAN FAILED: código=" + errorCode);
            setStatus("Falha no scanner: " + errorCode);
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt currentGatt, int status, int newState) {
            handler.removeCallbacks(connectionTimeout);
            appendLog("STATE: status=" + status + " (" + statusName(status) + ") state=" + newState + " (" + stateName(newState) + ")");
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true;
                gatt = currentGatt;
                sessionStartedAt = System.currentTimeMillis();
                addEvent("STATE", "", "", "CONNECTED");
                setStatus("G28 conectado. Descobrindo serviços...");
                if (hasConnectPermission()) {
                    try { currentGatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH); } catch (Exception ignored) { }
                    appendLog("discoverServices()=" + currentGatt.discoverServices());
                }
                return;
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false;
                stopExplorer("desconectado");
                clearGattQueue();
                addEvent("STATE", "", "", "DISCONNECTED " + statusName(status));
                setStatus("Desconectado: " + statusName(status));
                try { currentGatt.close(); } catch (Exception ignored) { }
                if (gatt == currentGatt) gatt = null;
                updateSendState();
                if (!manualDisconnect && forceMode && lastDevice != null && connectionAttempt < 3) handler.postDelayed(() -> connect(lastDevice, true), 1500);
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt currentGatt, int status) {
            appendLog("SERVICES: status=" + statusName(status));
            if (status != BluetoothGatt.GATT_SUCCESS) { setStatus("Falha ao descobrir serviços"); return; }
            writeChannels.clear();
            clearGattQueue();
            List<BluetoothGattService> services = currentGatt.getServices();
            appendLog("TOTAL DE SERVIÇOS: " + services.size());
            enqueue(GattOperation.mtu(247));
            for (BluetoothGattService service : services) {
                appendLog("SERVIÇO: " + service.getUuid());
                for (BluetoothGattCharacteristic c : service.getCharacteristics()) {
                    int p = c.getProperties();
                    appendLog("  CHAR: " + c.getUuid() + " | props=" + propertiesText(p));
                    if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 || (p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) writeChannels.add(new WriteChannel(channelLabel(c.getUuid()), c));
                    if ((p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 || (p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) enqueue(GattOperation.notify(c));
                    if ((p & BluetoothGattCharacteristic.PROPERTY_READ) != 0) enqueue(GattOperation.read(c));
                }
            }
            refreshChannelSpinner();
            setStatus("Configurando canais GATT...");
            runNextOperation();
        }

        @Override public void onMtuChanged(BluetoothGatt currentGatt, int mtu, int status) {
            negotiatedMtu = mtu;
            appendLog("MTU: " + mtu + " status=" + statusName(status));
            addEvent("MTU", "", String.valueOf(mtu), statusName(status));
            completeOperation();
        }

        @Override public void onCharacteristicRead(BluetoothGatt currentGatt, BluetoothGattCharacteristic c, int status) {
            String channel = shortUuid(c.getUuid());
            String payload = bytesToHex(c.getValue());
            appendLog("RX READ [" + channel + "] status=" + statusName(status) + " | " + payload);
            recordRx("READ", channel, payload, statusName(status));
            completeOperation();
        }

        @Override public void onCharacteristicChanged(BluetoothGatt currentGatt, BluetoothGattCharacteristic c) {
            String channel = shortUuid(c.getUuid());
            String payload = bytesToHex(c.getValue());
            appendLog("RX NOTIFY [" + channel + "] " + payload);
            recordRx("NOTIFY", channel, payload, "");
            if (explorerRunning && c.getUuid().equals(NUS_TX) && !activeCommand.isEmpty()) {
                long latency = System.currentTimeMillis() - activeTxAt;
                String timing = latency <= 3000 ? "IMEDIATA" : "TARDIA";
                explorerHitCount++;
                String comparison;
                if (baselineResponse.isEmpty()) { baselineResponse = payload; comparison = "BASELINE"; }
                else comparison = baselineResponse.equals(payload) ? "IGUAL_A_BASELINE" : "DIFERENTE_DA_BASELINE";
                String key = explorerMode + "#" + explorerSentCount + "#" + activeCommand;
                responseMap.put(key, payload);
                appendLog("*** RESPOSTA TX=" + activeCommand + " ANTERIOR=" + safeCommand(previousCommand) + " RX=" + payload + " LATÊNCIA=" + latency + "ms " + timing + " " + comparison + " ***");
                addEvent("PROTO_RX", channel, payload, "tx=" + activeCommand + ";previous=" + safeCommand(previousCommand) + ";latency_ms=" + latency + ";timing=" + timing + ";" + comparison);
                updateExplorerText(explorerMode + " | respostas " + explorerHitCount + " | " + activeCommand + " → " + timing);
            }
        }

        @Override public void onCharacteristicWrite(BluetoothGatt currentGatt, BluetoothGattCharacteristic c, int status) {
            appendLog("TX CALLBACK [" + shortUuid(c.getUuid()) + "] status=" + statusName(status));
            completeOperation();
        }

        @Override public void onDescriptorWrite(BluetoothGatt currentGatt, BluetoothGattDescriptor d, int status) {
            appendLog("CCCD [" + shortUuid(d.getCharacteristic().getUuid()) + "] status=" + statusName(status));
            completeOperation();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sessionStartedAt = System.currentTimeMillis();
        buildInterface();
        initializeBluetooth();
        requestRequiredPermissions();
        appendLog("Orbis Watch Lab v" + VERSION + " iniciado | Android " + Build.VERSION.RELEASE + " API " + Build.VERSION.SDK_INT);
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(8), dp(8), dp(8));

        TextView title = new TextView(this);
        title.setText("ORBIS WATCH LAB v" + VERSION);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setText("Inicializando Bluetooth...");
        statusView.setTextSize(13);
        root.addView(statusView);

        LinearLayout row1 = new LinearLayout(this);
        row1.addView(button("PROCURAR", v -> startScan()), weight());
        row1.addView(button("FORÇAR", v -> forceGatt()), weight());
        row1.addView(button("DESCONECTAR", v -> disconnectGatt()), weight());
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.addView(button("LOG", v -> shareLog()), weight());
        row2.addView(button("JSON", v -> shareJson()), weight());
        row2.addView(button("LIMPAR", v -> clearSession()), weight());
        root.addView(row2);

        rxSummaryView = new TextView(this);
        rxSummaryView.setText("RX: 0 pacotes | 0 padrões");
        rxSummaryView.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(rxSummaryView);

        explorerView = new TextView(this);
        explorerView.setText("Testes isolados parados");
        root.addView(explorerView);

        LinearLayout testRow = new LinearLayout(this);
        df00Button = button("DF00 ×5", v -> startIsolated("DF00 ×5", "DF 00", SHORT_GAP_MS));
        df0000Button = button("DF0000 ×5", v -> startIsolated("DF0000 ×5", "DF 00 00", SHORT_GAP_MS));
        fe01Button = button("FE01 LONGO ×5", v -> startIsolated("FE01 LONGO ×5", "FE 01", LONG_GAP_MS));
        testRow.addView(df00Button, weight());
        testRow.addView(df0000Button, weight());
        testRow.addView(fe01Button, weight());
        root.addView(testRow);

        stopButton = button("PARAR TESTE", v -> stopExplorer("parada manual"));
        root.addView(stopButton);

        LinearLayout markerRow = new LinearLayout(this);
        markerInput = new EditText(this);
        markerInput.setHint("Marcador");
        markerInput.setSingleLine(true);
        markerRow.addView(markerInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3));
        markerRow.addView(button("MARCAR", v -> addMarker()), weight());
        root.addView(markerRow);

        channelSpinner = new Spinner(this);
        channelAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new ArrayList<>());
        channelSpinner.setAdapter(channelAdapter);
        root.addView(channelSpinner);

        LinearLayout sendRow = new LinearLayout(this);
        hexInput = new EditText(this);
        hexInput.setHint("HEX manual conhecido");
        hexInput.setSingleLine(true);
        hexInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        sendButton = button("ENVIAR", v -> sendHex());
        sendRow.addView(hexInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3));
        sendRow.addView(sendButton, weight());
        root.addView(sendRow);

        logScroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setText("LOG ORBIS v" + VERSION + "\n");
        logView.setTextSize(9);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logScroll.addView(logView);
        root.addView(logScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        updateSendState();
    }

    private Button button(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(8);
        b.setOnClickListener(listener);
        return b;
    }

    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1); }

    private void initializeBluetooth() {
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (manager == null) { setStatus("BluetoothManager indisponível"); return; }
        adapter = manager.getAdapter();
        if (adapter == null) { setStatus("Sem Bluetooth"); return; }
        if (!adapter.isEnabled()) { setStatus("Ative o Bluetooth"); return; }
        scanner = adapter.getBluetoothLeScanner();
        setStatus("Pronto para procurar o G28");
    }

    private void requestRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_PERMISSIONS);
        } else if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_PERMISSIONS);
        }
    }

    private boolean hasScanPermission() { return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED; }
    private boolean hasConnectPermission() { return Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED; }

    private void startScan() {
        stopExplorer("nova busca");
        forceMode = false;
        manualDisconnect = false;
        connectionAttempt = 0;
        lastDevice = null;
        if (!hasScanPermission()) { requestRequiredPermissions(); return; }
        if (scanner == null && adapter != null) scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { setStatus("Scanner BLE indisponível"); return; }
        appendLog("Iniciando busca BLE...");
        setStatus("Procurando o G28...");
        scanner.startScan(scanCallback);
        handler.postDelayed(() -> { stopScan(); if (lastDevice == null) setStatus("G28 não encontrado"); }, SCAN_TIMEOUT_MS);
    }

    private void forceGatt() {
        stopExplorer("reconexão forçada");
        forceMode = true;
        manualDisconnect = false;
        connectionAttempt = 0;
        if (lastDevice != null) connect(lastDevice, true);
        else {
            if (!hasScanPermission()) { requestRequiredPermissions(); return; }
            if (scanner == null && adapter != null) scanner = adapter.getBluetoothLeScanner();
            if (scanner != null) scanner.startScan(scanCallback);
        }
    }

    private void stopScan() {
        if (scanner != null && hasScanPermission()) try { scanner.stopScan(scanCallback); } catch (Exception ignored) { }
    }

    private void connect(BluetoothDevice device, boolean forced) {
        if (!hasConnectPermission()) { requestRequiredPermissions(); return; }
        stopScan();
        closeGattOnly();
        lastDevice = device;
        connected = false;
        manualDisconnect = false;
        connectionAttempt++;
        appendLog("CONNECT tentativa " + connectionAttempt + " | " + (forced ? "FORÇADO" : "NORMAL") + " | " + safeAddress(device));
        try {
            if (forced && Build.VERSION.SDK_INT >= 26) gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK);
            else if (Build.VERSION.SDK_INT >= 23) gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            else gatt = device.connectGatt(this, false, gattCallback);
            handler.postDelayed(connectionTimeout, CONNECT_TIMEOUT_MS);
        } catch (Exception e) {
            appendLog("EXCEÇÃO connectGatt: " + e.getMessage());
        }
    }

    private void enqueue(GattOperation op) { operationQueue.offer(op); }

    private void runNextOperation() {
        if (!connected || gatt == null || operationRunning || !hasConnectPermission()) return;
        GattOperation op = operationQueue.poll();
        if (op == null) {
            setStatus("G28 pronto | MTU " + negotiatedMtu + " | " + writeChannels.size() + " canais TX");
            updateSendState();
            appendLog("FILA GATT concluída — captura ativa");
            return;
        }
        operationRunning = true;
        handler.removeCallbacks(operationTimeout);
        handler.postDelayed(operationTimeout, OP_TIMEOUT_MS);
        boolean started = false;
        try {
            if (op.type == OperationType.MTU) {
                started = gatt.requestMtu(op.mtu);
                appendLog("FILA MTU " + op.mtu + " iniciado=" + started);
            } else if (op.type == OperationType.READ) {
                started = gatt.readCharacteristic(op.characteristic);
                appendLog("FILA READ [" + shortUuid(op.characteristic.getUuid()) + "] iniciado=" + started);
            } else if (op.type == OperationType.NOTIFY) {
                started = startNotification(gatt, op.characteristic);
                appendLog("FILA NOTIFY [" + shortUuid(op.characteristic.getUuid()) + "] iniciado=" + started);
            } else {
                started = startWrite(gatt, op.characteristic, op.data);
                String ch = shortUuid(op.characteristic.getUuid());
                String payload = bytesToHex(op.data);
                appendLog("TX [" + ch + "] " + payload + " iniciado=" + started);
                addEvent("TX", ch, payload, "started=" + started);
            }
        } catch (Exception e) {
            appendLog("FILA EXCEÇÃO: " + e.getMessage());
        }
        if (!started) completeOperation();
    }

    private boolean startNotification(BluetoothGatt currentGatt, BluetoothGattCharacteristic c) {
        if (!currentGatt.setCharacteristicNotification(c, true)) return false;
        BluetoothGattDescriptor d = c.getDescriptor(CCCD);
        if (d == null) return false;
        byte[] value = (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
        if (Build.VERSION.SDK_INT >= 33) return currentGatt.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS;
        d.setValue(value);
        return currentGatt.writeDescriptor(d);
    }

    private boolean startWrite(BluetoothGatt currentGatt, BluetoothGattCharacteristic c, byte[] data) {
        boolean forceNoResponse = c.getUuid().equals(NUS_RX) && (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
        int type = forceNoResponse ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        c.setWriteType(type);
        appendLog("TX MODE [" + shortUuid(c.getUuid()) + "] " + (type == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE ? "NO_RESPONSE" : "DEFAULT"));
        if (Build.VERSION.SDK_INT >= 33) {
            boolean ok = currentGatt.writeCharacteristic(c, data, type) == BluetoothStatusCodes.SUCCESS;
            if (ok && type == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) handler.postDelayed(this::completeOperation, 250);
            return ok;
        }
        c.setValue(data);
        boolean ok = currentGatt.writeCharacteristic(c);
        if (ok && type == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) handler.postDelayed(this::completeOperation, 250);
        return ok;
    }

    private void completeOperation() {
        handler.removeCallbacks(operationTimeout);
        if (!operationRunning) return;
        operationRunning = false;
        handler.postDelayed(this::runNextOperation, 90);
    }

    private void clearGattQueue() {
        handler.removeCallbacks(operationTimeout);
        operationQueue.clear();
        operationRunning = false;
    }

    private WriteChannel findNusRx() {
        for (WriteChannel c : writeChannels) if (c.characteristic.getUuid().equals(NUS_RX)) return c;
        return null;
    }

    private void startIsolated(String mode, String command, long gapMs) {
        startExplorer(mode, new String[]{command, command, command, command, command}, gapMs);
    }

    private void startExplorer(String mode, String[] commands, long gapMs) {
        if (!connected || operationRunning || !operationQueue.isEmpty()) { toast("Aguarde GATT ficar pronto"); return; }
        if (findNusRx() == null) { toast("NUS-RX não disponível"); return; }
        stopExplorer("reinício");
        explorerRunning = true;
        explorerMode = mode;
        explorerCommands = commands;
        explorerGapMs = gapMs;
        explorerIndex = 0;
        explorerSentCount = 0;
        explorerHitCount = 0;
        activeCommand = "";
        previousCommand = "";
        baselineResponse = "";
        responseMap.clear();
        appendLog("========== TESTE ISOLADO v" + VERSION + " INICIADO: " + mode + " ==========");
        appendLog("Canal NUS-RX em WRITE_NO_RESPONSE; janela " + gapMs + " ms; correlação imediata/tardia ativa");
        addEvent("PROTO_START", "NUS-RX", "", "mode=" + mode + ";gap_ms=" + gapMs);
        updateExplorerText(mode + " iniciado");
        updateSendState();
        handler.post(nextExplorerCommand);
    }

    private void stopExplorer(String reason) {
        boolean wasRunning = explorerRunning;
        explorerRunning = false;
        handler.removeCallbacks(nextExplorerCommand);
        if (wasRunning) {
            appendLog("========== TESTE ENCERRADO: " + reason + " | TX=" + explorerSentCount + " RX=" + explorerHitCount + " ==========");
            for (Map.Entry<String, String> e : responseMap.entrySet()) appendLog("MAPA " + e.getKey() + " => " + e.getValue());
            addEvent("PROTO_STOP", "", "", reason + ";tx=" + explorerSentCount + ";rx=" + explorerHitCount);
        }
        updateExplorerText("Testes isolados parados: " + reason);
        updateSendState();
    }

    private void updateExplorerText(String text) {
        runOnUiThread(() -> { if (explorerView != null) explorerView.setText(text); });
    }

    private void sendHex() {
        if (!connected || writeChannels.isEmpty() || explorerRunning) { toast(explorerRunning ? "Pare o teste primeiro" : "Conecte o G28 primeiro"); return; }
        try {
            byte[] data = parseHex(hexInput.getText().toString());
            if (data.length == 0) { toast("Digite HEX"); return; }
            if (data.length > Math.max(20, negotiatedMtu - 3)) { toast("Pacote grande demais"); return; }
            int i = channelSpinner.getSelectedItemPosition();
            if (i < 0 || i >= writeChannels.size()) return;
            enqueue(GattOperation.write(writeChannels.get(i).characteristic, data));
            runNextOperation();
        } catch (IllegalArgumentException e) {
            toast(e.getMessage());
        }
    }

    private byte[] parseHex(String input) {
        String clean = input == null ? "" : input.replaceAll("(?i)0x", "").replaceAll("[^0-9A-Fa-f]", "");
        if (clean.isEmpty()) return new byte[0];
        if ((clean.length() & 1) != 0) throw new IllegalArgumentException("HEX inválido");
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < clean.length(); i += 2) out[i / 2] = (byte) Integer.parseInt(clean.substring(i, i + 2), 16);
        return out;
    }

    private void addMarker() {
        String note = markerInput.getText().toString().trim();
        if (note.isEmpty()) note = "marcador manual";
        appendLog("========== MARCADOR: " + note + " ==========");
        synchronized (rxBuffer) { rxBuffer.append(timestamp()).append("  MARKER ").append(note).append('\n'); }
        addEvent("MARKER", "", "", note);
        markerInput.setText("");
        toast("Marcador registrado");
    }

    private void recordRx(String type, String channel, String payload, String note) {
        rxPacketCount++;
        String signature = type + "|" + channel + "|" + payload;
        Integer count = packetCounts.get(signature);
        packetCounts.put(signature, count == null ? 1 : count + 1);
        synchronized (rxBuffer) { rxBuffer.append(timestamp()).append("  RX ").append(type).append(" [").append(channel).append("] ").append(payload).append('\n'); }
        addEvent("RX_" + type, channel, payload, note);
        runOnUiThread(() -> rxSummaryView.setText("RX: " + rxPacketCount + " pacotes | " + packetCounts.size() + " padrões"));
    }

    private void addEvent(String type, String channel, String payload, String note) {
        synchronized (sessionEvents) { sessionEvents.add(new SessionEvent(System.currentTimeMillis(), type, channel, payload, note)); }
    }

    private void clearSession() {
        stopExplorer("sessão limpa");
        synchronized (rxBuffer) { rxBuffer.setLength(0); }
        synchronized (sessionEvents) { sessionEvents.clear(); }
        packetCounts.clear();
        responseMap.clear();
        rxPacketCount = 0;
        sessionStartedAt = System.currentTimeMillis();
        rxSummaryView.setText("RX: 0 pacotes | 0 padrões");
        appendLog("SESSÃO DE CAPTURA LIMPA");
    }

    private void shareLog() {
        String text;
        synchronized (rxBuffer) { text = "ORBIS v" + VERSION + " — TESTES ISOLADOS\n\n" + rxBuffer + "\nLOG COMPLETO\n\n" + logBuffer; }
        shareText("Orbis v" + VERSION + " G28", text, "Compartilhar captura");
    }

    private void shareJson() {
        try {
            JSONObject root = new JSONObject();
            JSONArray events = new JSONArray();
            JSONObject patterns = new JSONObject();
            JSONObject map = new JSONObject();
            root.put("app", "Orbis Watch Lab");
            root.put("version", VERSION);
            root.put("session_started_epoch_ms", sessionStartedAt);
            root.put("exported_epoch_ms", System.currentTimeMillis());
            root.put("mtu", negotiatedMtu);
            root.put("rx_packet_count", rxPacketCount);
            root.put("test_mode", explorerMode);
            root.put("sent_count", explorerSentCount);
            root.put("hit_count", explorerHitCount);
            root.put("gap_ms", explorerGapMs);
            synchronized (sessionEvents) {
                for (SessionEvent e : sessionEvents) {
                    JSONObject item = new JSONObject();
                    item.put("timestamp_epoch_ms", e.timestamp);
                    item.put("time", formatTime(e.timestamp));
                    item.put("type", e.type);
                    item.put("channel", e.channel);
                    item.put("payload_hex", e.payload);
                    item.put("note", e.note);
                    events.put(item);
                }
            }
            for (Map.Entry<String, Integer> entry : packetCounts.entrySet()) patterns.put(entry.getKey(), entry.getValue());
            for (Map.Entry<String, String> entry : responseMap.entrySet()) map.put(entry.getKey(), entry.getValue());
            root.put("events", events);
            root.put("pattern_counts", patterns);
            root.put("response_map", map);
            shareText("Orbis v" + VERSION + " JSON", root.toString(2), "Compartilhar JSON");
        } catch (Exception e) {
            toast("Falha no JSON: " + e.getMessage());
        }
    }

    private void shareText(String subject, String text, String title) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, subject);
        i.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(i, title));
    }

    private void refreshChannelSpinner() {
        runOnUiThread(() -> {
            channelAdapter.clear();
            for (WriteChannel c : writeChannels) channelAdapter.add(c.label);
            channelAdapter.notifyDataSetChanged();
            int p = preferredChannel();
            if (p >= 0) channelSpinner.setSelection(p);
            updateSendState();
        });
    }

    private int preferredChannel() {
        for (int i = 0; i < writeChannels.size(); i++) if (writeChannels.get(i).characteristic.getUuid().equals(NUS_RX)) return i;
        return writeChannels.isEmpty() ? -1 : 0;
    }

    private String channelLabel(UUID u) {
        if (u.equals(NUS_RX)) return "NUS RX → NUS TX";
        if (u.equals(FF02)) return "FF02 → FF01";
        if (u.equals(FF13)) return "FF13 → FF14";
        if (u.equals(FFF1)) return "FFF1";
        return shortUuid(u);
    }

    private void disconnectGatt() {
        stopExplorer("desconexão manual");
        manualDisconnect = true;
        forceMode = false;
        stopScan();
        clearGattQueue();
        if (gatt != null && hasConnectPermission()) {
            try { gatt.disconnect(); } catch (Exception ignored) { }
            try { gatt.close(); } catch (Exception ignored) { }
        }
        gatt = null;
        connected = false;
        writeChannels.clear();
        refreshChannelSpinner();
        setStatus("Desconectado");
        appendLog("Conexão encerrada manualmente");
    }

    private void closeGattOnly() {
        stopExplorer("GATT fechado");
        clearGattQueue();
        if (gatt != null && hasConnectPermission()) {
            try { gatt.disconnect(); } catch (Exception ignored) { }
            try { gatt.close(); } catch (Exception ignored) { }
        }
        gatt = null;
        connected = false;
        updateSendState();
    }

    private void updateSendState() {
        runOnUiThread(() -> {
            boolean ready = connected && !writeChannels.isEmpty();
            if (sendButton != null) sendButton.setEnabled(ready && !explorerRunning);
            if (hexInput != null) hexInput.setEnabled(ready && !explorerRunning);
            if (channelSpinner != null) channelSpinner.setEnabled(ready && !explorerRunning);
            boolean testReady = ready && findNusRx() != null && !explorerRunning;
            if (df00Button != null) df00Button.setEnabled(testReady);
            if (df0000Button != null) df0000Button.setEnabled(testReady);
            if (fe01Button != null) fe01Button.setEnabled(testReady);
            if (stopButton != null) stopButton.setEnabled(explorerRunning);
        });
    }

    private String safeName(BluetoothDevice d) {
        if (!hasConnectPermission()) return "(sem permissão)";
        try { String n = d.getName(); return n == null ? "(sem nome)" : n; } catch (Exception e) { return "(protegido)"; }
    }

    private String safeAddress(BluetoothDevice d) {
        if (!hasConnectPermission()) return "(protegido)";
        try { return d.getAddress(); } catch (Exception e) { return "(protegido)"; }
    }

    private static UUID uuid16(String v) { return UUID.fromString("0000" + v.toLowerCase(Locale.US) + "-0000-1000-8000-00805f9b34fb"); }

    private String shortUuid(UUID u) {
        String s = u.toString().toUpperCase(Locale.US);
        if (s.endsWith("-0000-1000-8000-00805F9B34FB")) return s.substring(4, 8);
        if (u.equals(NUS_RX)) return "NUS-RX";
        if (u.equals(NUS_TX)) return "NUS-TX";
        return s;
    }

    private String bytesToHex(byte[] data) {
        if (data == null || data.length == 0) return "(vazio)";
        StringBuilder b = new StringBuilder();
        for (byte v : data) b.append(String.format(Locale.US, "%02X ", v & 0xFF));
        return b.toString().trim();
    }

    private String propertiesText(int p) {
        StringBuilder b = new StringBuilder();
        if ((p & BluetoothGattCharacteristic.PROPERTY_READ) != 0) b.append("READ ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) b.append("WRITE ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) b.append("WRITE_NR ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) b.append("NOTIFY ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) b.append("INDICATE ");
        return b.toString().trim();
    }

    private String statusName(int s) {
        if (s == BluetoothGatt.GATT_SUCCESS) return "SUCCESS";
        if (s == 1) return "GATT_FAILURE_1";
        if (s == 133) return "GATT_ERROR_133";
        if (s == 201) return "BUSY";
        return "STATUS_" + s;
    }

    private String stateName(int s) {
        if (s == BluetoothProfile.STATE_CONNECTED) return "CONNECTED";
        if (s == BluetoothProfile.STATE_DISCONNECTED) return "DISCONNECTED";
        return "STATE_" + s;
    }

    private String safeCommand(String value) { return value == null || value.isEmpty() ? "(nenhum)" : value; }
    private String timestamp() { return formatTime(System.currentTimeMillis()); }
    private String formatTime(long t) { return new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date(t)); }
    private void setStatus(String m) { runOnUiThread(() -> statusView.setText(m)); }

    private void appendLog(String m) {
        String line = timestamp() + "  " + m;
        synchronized (logBuffer) { logBuffer.append(line).append('\n'); }
        runOnUiThread(() -> {
            logView.append(line + "\n");
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void toast(String m) { runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show()); }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    @Override protected void onDestroy() {
        disconnectGatt();
        super.onDestroy();
    }
}
