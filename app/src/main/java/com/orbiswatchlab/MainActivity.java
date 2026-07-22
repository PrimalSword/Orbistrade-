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
    private static final String VERSION = "0.13";
    private static final int REQUEST_PERMISSIONS = 1001;
    private static final long SCAN_TIMEOUT_MS = 15000;
    private static final long CONNECT_TIMEOUT_MS = 12000;
    private static final long OP_TIMEOUT_MS = 5000;
    private static final long PROBE_WINDOW_MS = 10000;
    private static final String KNOWN_STATUS = "FD 00 05 07 00 00 00 04 01";

    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
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
    private Button searchButton, forceButton, disconnectButton, sendButton;
    private Button dfLowButton, dfHighButton, feLowButton, stopButton;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuffer = new StringBuilder();
    private final StringBuilder rxBuffer = new StringBuilder();
    private final Queue<GattOperation> operationQueue = new ArrayDeque<>();
    private final List<WriteChannel> writeChannels = new ArrayList<>();
    private final List<SessionEvent> sessionEvents = new ArrayList<>();
    private final Map<String, Integer> packetCounts = new LinkedHashMap<>();
    private final Map<String, String> probeMap = new LinkedHashMap<>();
    private ArrayAdapter<String> channelAdapter;

    private boolean connected;
    private boolean connecting;
    private boolean operationRunning;
    private boolean explorerRunning;
    private int negotiatedMtu = 23;
    private int rxPacketCount;
    private int connectionAttempt;
    private int probeIndex;
    private int probeResponses;
    private long sessionStartedAt;
    private long activeProbeSentAt;
    private String explorerMode = "";
    private String activeProbe = "";
    private final List<String> probes = new ArrayList<>();

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
        if (connecting && !connected) {
            appendLog("TIMEOUT DE CONEXÃO após " + CONNECT_TIMEOUT_MS + " ms");
            closeGattOnly();
            setStatus("Timeout ao conectar");
        }
    };

    private final Runnable operationTimeout = () -> {
        if (operationRunning) {
            appendLog("FILA: operação expirou; avançando");
            operationRunning = false;
            runNextOperation();
        }
    };

    private final Runnable nextProbe = new Runnable() {
        @Override public void run() {
            if (!explorerRunning) return;
            if (!connected) { stopExplorer("desconectado"); return; }
            if (operationRunning || !operationQueue.isEmpty()) {
                handler.postDelayed(this, 200);
                return;
            }
            if (probeIndex >= probes.size()) {
                stopExplorer("faixa concluída");
                return;
            }
            WriteChannel nus = findNusRx();
            if (nus == null) { stopExplorer("NUS-RX indisponível"); return; }

            activeProbe = probes.get(probeIndex++);
            activeProbeSentAt = System.currentTimeMillis();
            appendLog("PROBE " + probeIndex + "/" + probes.size() + " TX=" + activeProbe + " | janela=" + PROBE_WINDOW_MS + "ms");
            addEvent("PROBE_TX", "NUS-RX", activeProbe, explorerMode);
            updateExplorerText(explorerMode + " | " + probeIndex + "/" + probes.size() + " | " + activeProbe);
            enqueue(GattOperation.write(nus.characteristic, parseHex(activeProbe)));
            runNextOperation();
            handler.postDelayed(this, PROBE_WINDOW_MS);
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = safeName(device);
            if ((name == null || "(sem nome)".equals(name)) && result.getScanRecord() != null) {
                String recordName = result.getScanRecord().getDeviceName();
                if (recordName != null) name = recordName;
            }
            appendLog("SCAN: " + name + " | " + safeAddress(device) + " | RSSI " + result.getRssi());
            if (name != null && name.toUpperCase(Locale.US).contains("G28")) {
                lastDevice = device;
                stopScan();
                connect(device, false);
            }
        }

        @Override public void onScanFailed(int errorCode) {
            connecting = false;
            appendLog("SCAN FAILED: código=" + errorCode);
            setStatus("Falha no scanner: " + errorCode);
            updateUiState();
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt currentGatt, int status, int newState) {
            if (currentGatt != gatt) {
                appendLog("CALLBACK IGNORADO: GATT antigo");
                try { currentGatt.close(); } catch (Exception ignored) { }
                return;
            }
            handler.removeCallbacks(connectionTimeout);
            appendLog("STATE: status=" + statusName(status) + " state=" + stateName(newState));
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connecting = false;
                connected = true;
                sessionStartedAt = System.currentTimeMillis();
                setStatus("G28 conectado. Descobrindo serviços...");
                addEvent("STATE", "", "", "CONNECTED");
                if (hasConnectPermission()) {
                    try { currentGatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH); } catch (Exception ignored) { }
                    appendLog("discoverServices()=" + currentGatt.discoverServices());
                }
                updateUiState();
                return;
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                connecting = false;
                connected = false;
                stopExplorer("desconectado");
                clearGattQueue();
                setStatus("Desconectado: " + statusName(status));
                addEvent("STATE", "", "", "DISCONNECTED " + statusName(status));
                try { currentGatt.close(); } catch (Exception ignored) { }
                if (gatt == currentGatt) gatt = null;
                writeChannels.clear();
                refreshChannelSpinner();
                updateUiState();
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt currentGatt, int status) {
            if (currentGatt != gatt) return;
            appendLog("SERVICES: status=" + statusName(status));
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setStatus("Falha ao descobrir serviços");
                return;
            }
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
                    if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                            (p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                        writeChannels.add(new WriteChannel(channelLabel(c.getUuid()), c));
                    }
                    if ((p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                            (p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                        enqueue(GattOperation.notify(c));
                    }
                    if ((p & BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                        enqueue(GattOperation.read(c));
                    }
                }
            }
            refreshChannelSpinner();
            setStatus("Configurando canais GATT...");
            runNextOperation();
        }

        @Override public void onMtuChanged(BluetoothGatt currentGatt, int mtu, int status) {
            if (currentGatt != gatt) return;
            negotiatedMtu = mtu;
            appendLog("MTU: " + mtu + " status=" + statusName(status));
            completeOperation();
        }

        @Override public void onCharacteristicRead(BluetoothGatt currentGatt, BluetoothGattCharacteristic c, int status) {
            if (currentGatt != gatt) return;
            String channel = shortUuid(c.getUuid());
            String payload = bytesToHex(c.getValue());
            appendLog("RX READ [" + channel + "] status=" + statusName(status) + " | " + payload);
            recordRx("READ", channel, payload, statusName(status));
            completeOperation();
        }

        @Override public void onCharacteristicChanged(BluetoothGatt currentGatt, BluetoothGattCharacteristic c) {
            if (currentGatt != gatt) return;
            String channel = shortUuid(c.getUuid());
            String payload = bytesToHex(c.getValue());
            appendLog("RX NOTIFY [" + channel + "] " + payload);
            recordRx("NOTIFY", channel, payload, "");

            if (explorerRunning && c.getUuid().equals(NUS_TX) && !activeProbe.isEmpty()) {
                long latency = System.currentTimeMillis() - activeProbeSentAt;
                probeResponses++;
                boolean known = KNOWN_STATUS.equals(payload);
                String classification = known ? "STATUS_CONHECIDO" : "PADRÃO_NOVO";
                String key = explorerMode + "#" + activeProbe;
                probeMap.put(key, payload + " | " + latency + "ms | " + classification);
                appendLog("*** PROBE RX TX=" + activeProbe + " RX=" + payload + " LATÊNCIA=" + latency + "ms " + classification + " ***");
                addEvent("PROBE_RX", channel, payload, "tx=" + activeProbe + ";latency_ms=" + latency + ";class=" + classification);
                if (!known) {
                    stopExplorer("padrão novo detectado em " + activeProbe);
                    toast("Resposta nova encontrada: " + activeProbe);
                }
            }
        }

        @Override public void onCharacteristicWrite(BluetoothGatt currentGatt, BluetoothGattCharacteristic c, int status) {
            if (currentGatt != gatt) return;
            appendLog("TX CALLBACK [" + shortUuid(c.getUuid()) + "] status=" + statusName(status));
            completeOperation();
        }

        @Override public void onDescriptorWrite(BluetoothGatt currentGatt, BluetoothGattDescriptor d, int status) {
            if (currentGatt != gatt) return;
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
        root.addView(statusView);

        LinearLayout row1 = new LinearLayout(this);
        searchButton = button("PROCURAR", v -> startScan());
        forceButton = button("FORÇAR SEGURO", v -> forceGatt());
        disconnectButton = button("DESCONECTAR", v -> disconnectGatt());
        row1.addView(searchButton, weight());
        row1.addView(forceButton, weight());
        row1.addView(disconnectButton, weight());
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
        explorerView.setText("Explorador controlado parado");
        root.addView(explorerView);

        LinearLayout row3 = new LinearLayout(this);
        dfLowButton = button("DF00–0F", v -> startRange("DF00–0F", 0xDF, 0x00, 0x0F));
        dfHighButton = button("DF10–1F", v -> startRange("DF10–1F", 0xDF, 0x10, 0x1F));
        feLowButton = button("FE00–0F", v -> startRange("FE00–0F", 0xFE, 0x00, 0x0F));
        row3.addView(dfLowButton, weight());
        row3.addView(dfHighButton, weight());
        row3.addView(feLowButton, weight());
        root.addView(row3);

        stopButton = button("PARAR EXPLORADOR", v -> stopExplorer("parada manual"));
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
        updateUiState();
    }

    private Button button(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(8);
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
        if (adapter == null) { setStatus("Sem Bluetooth"); return; }
        if (!adapter.isEnabled()) { setStatus("Ative o Bluetooth"); return; }
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
        if (connected || connecting || gatt != null) {
            toast("Já existe uma conexão GATT ativa");
            return;
        }
        if (!hasScanPermission()) { requestRequiredPermissions(); return; }
        if (scanner == null && adapter != null) scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { setStatus("Scanner BLE indisponível"); return; }
        connecting = true;
        connectionAttempt = 0;
        appendLog("Iniciando busca BLE...");
        setStatus("Procurando o G28...");
        updateUiState();
        scanner.startScan(scanCallback);
        handler.postDelayed(() -> {
            stopScan();
            if (!connected && gatt == null) {
                connecting = false;
                setStatus("G28 não encontrado");
                updateUiState();
            }
        }, SCAN_TIMEOUT_MS);
    }

    private void forceGatt() {
        if (connected || connecting || gatt != null) {
            toast("Conexão já ativa. Desconecte primeiro.");
            return;
        }
        if (lastDevice == null) {
            startScan();
            return;
        }
        connect(lastDevice, true);
    }

    private void stopScan() {
        if (scanner != null && hasScanPermission()) {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) { }
        }
    }

    private void connect(BluetoothDevice device, boolean forced) {
        if (!hasConnectPermission()) { requestRequiredPermissions(); return; }
        if (gatt != null || connected) {
            toast("GATT ativo; conexão duplicada bloqueada");
            return;
        }
        stopScan();
        connecting = true;
        lastDevice = device;
        connectionAttempt++;
        appendLog("CONNECT tentativa " + connectionAttempt + " | " + (forced ? "FORÇADO SEGURO" : "NORMAL") + " | " + safeAddress(device));
        try {
            if (forced && Build.VERSION.SDK_INT >= 26) {
                gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK);
            } else if (Build.VERSION.SDK_INT >= 23) {
                gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            } else {
                gatt = device.connectGatt(this, false, gattCallback);
            }
            handler.postDelayed(connectionTimeout, CONNECT_TIMEOUT_MS);
        } catch (Exception e) {
            connecting = false;
            gatt = null;
            appendLog("EXCEÇÃO connectGatt: " + e.getMessage());
        }
        updateUiState();
    }

    private void disconnectGatt() {
        stopExplorer("desconexão manual");
        stopScan();
        clearGattQueue();
        BluetoothGatt old = gatt;
        gatt = null;
        connected = false;
        connecting = false;
        if (old != null && hasConnectPermission()) {
            try { old.disconnect(); } catch (Exception ignored) { }
            try { old.close(); } catch (Exception ignored) { }
        }
        writeChannels.clear();
        refreshChannelSpinner();
        setStatus("Desconectado");
        appendLog("Conexão encerrada manualmente");
        updateUiState();
    }

    private void closeGattOnly() {
        clearGattQueue();
        BluetoothGatt old = gatt;
        gatt = null;
        connected = false;
        connecting = false;
        if (old != null && hasConnectPermission()) {
            try { old.disconnect(); } catch (Exception ignored) { }
            try { old.close(); } catch (Exception ignored) { }
        }
        updateUiState();
    }

    private void enqueue(GattOperation op) { operationQueue.offer(op); }

    private void runNextOperation() {
        if (!connected || gatt == null || operationRunning || !hasConnectPermission()) return;
        GattOperation op = operationQueue.poll();
        if (op == null) {
            setStatus("G28 pronto | MTU " + negotiatedMtu + " | " + writeChannels.size() + " canais TX");
            appendLog("FILA GATT concluída — captura ativa");
            updateUiState();
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
        byte[] value = (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
        if (Build.VERSION.SDK_INT >= 33) return currentGatt.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS;
        d.setValue(value);
        return currentGatt.writeDescriptor(d);
    }

    private boolean startWrite(BluetoothGatt currentGatt, BluetoothGattCharacteristic c, byte[] data) {
        boolean noResponse = c.getUuid().equals(NUS_RX) &&
                (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0;
        int type = noResponse ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        c.setWriteType(type);
        appendLog("TX MODE [" + shortUuid(c.getUuid()) + "] " + (noResponse ? "NO_RESPONSE" : "DEFAULT"));
        if (Build.VERSION.SDK_INT >= 33) {
            boolean ok = currentGatt.writeCharacteristic(c, data, type) == BluetoothStatusCodes.SUCCESS;
            if (ok && noResponse) handler.postDelayed(this::completeOperation, 250);
            return ok;
        }
        c.setValue(data);
        boolean ok = currentGatt.writeCharacteristic(c);
        if (ok && noResponse) handler.postDelayed(this::completeOperation, 250);
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
        for (WriteChannel c : writeChannels) {
            if (c.characteristic.getUuid().equals(NUS_RX)) return c;
        }
        return null;
    }

    private void startRange(String mode, int prefix, int start, int end) {
        if (!connected || operationRunning || !operationQueue.isEmpty()) {
            toast("Aguarde o GATT ficar pronto");
            return;
        }
        if (findNusRx() == null) {
            toast("NUS-RX não disponível");
            return;
        }
        stopExplorer("reinício");
        probes.clear();
        for (int value = start; value <= end; value++) {
            probes.add(String.format(Locale.US, "%02X %02X", prefix, value));
        }
        explorerMode = mode;
        probeIndex = 0;
        probeResponses = 0;
        activeProbe = "";
        probeMap.clear();
        explorerRunning = true;
        appendLog("========== EXPLORADOR v" + VERSION + " INICIADO: " + mode + " ==========");
        appendLog("Somente NUS-RX; pacotes de 2 bytes; janela 10s; pausa em padrão novo; FFF1/FF13/FF02 excluídos");
        addEvent("PROBE_START", "NUS-RX", "", mode);
        updateExplorerText(mode + " iniciado");
        updateUiState();
        handler.post(nextProbe);
    }

    private void stopExplorer(String reason) {
        boolean wasRunning = explorerRunning;
        explorerRunning = false;
        handler.removeCallbacks(nextProbe);
        if (wasRunning) {
            appendLog("========== EXPLORADOR ENCERRADO: " + reason + " | TX=" + probeIndex + " RX=" + probeResponses + " ==========");
            for (Map.Entry<String, String> entry : probeMap.entrySet()) {
                appendLog("MAPA " + entry.getKey() + " => " + entry.getValue());
            }
            addEvent("PROBE_STOP", "", "", reason + ";tx=" + probeIndex + ";rx=" + probeResponses);
        }
        updateExplorerText("Explorador parado: " + reason);
        updateUiState();
    }

    private void updateExplorerText(String text) {
        runOnUiThread(() -> explorerView.setText(text));
    }

    private void sendHex() {
        if (!connected || writeChannels.isEmpty() || explorerRunning) {
            toast(explorerRunning ? "Pare o explorador primeiro" : "Conecte o G28 primeiro");
            return;
        }
        try {
            byte[] data = parseHex(hexInput.getText().toString());
            if (data.length == 0) { toast("Digite HEX"); return; }
            if (data.length > Math.max(20, negotiatedMtu - 3)) { toast("Pacote grande demais"); return; }
            int index = channelSpinner.getSelectedItemPosition();
            if (index < 0 || index >= writeChannels.size()) return;
            enqueue(GattOperation.write(writeChannels.get(index).characteristic, data));
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
        for (int i = 0; i < clean.length(); i += 2) {
            out[i / 2] = (byte) Integer.parseInt(clean.substring(i, i + 2), 16);
        }
        return out;
    }

    private void addMarker() {
        String note = markerInput.getText().toString().trim();
        if (note.isEmpty()) note = "marcador manual";
        appendLog("========== MARCADOR: " + note + " ==========");
        addEvent("MARKER", "", "", note);
        markerInput.setText("");
    }

    private void recordRx(String type, String channel, String payload, String note) {
        rxPacketCount++;
        String signature = type + "|" + channel + "|" + payload;
        Integer count = packetCounts.get(signature);
        packetCounts.put(signature, count == null ? 1 : count + 1);
        synchronized (rxBuffer) {
            rxBuffer.append(timestamp()).append("  RX ").append(type).append(" [").append(channel).append("] ").append(payload).append('\n');
        }
        addEvent("RX_" + type, channel, payload, note);
        runOnUiThread(() -> rxSummaryView.setText("RX: " + rxPacketCount + " pacotes | " + packetCounts.size() + " padrões"));
    }

    private void addEvent(String type, String channel, String payload, String note) {
        synchronized (sessionEvents) {
            sessionEvents.add(new SessionEvent(System.currentTimeMillis(), type, channel, payload, note));
        }
    }

    private void clearSession() {
        stopExplorer("sessão limpa");
        synchronized (rxBuffer) { rxBuffer.setLength(0); }
        synchronized (sessionEvents) { sessionEvents.clear(); }
        packetCounts.clear();
        probeMap.clear();
        rxPacketCount = 0;
        sessionStartedAt = System.currentTimeMillis();
        rxSummaryView.setText("RX: 0 pacotes | 0 padrões");
        appendLog("SESSÃO DE CAPTURA LIMPA");
    }

    private void shareLog() {
        String text;
        synchronized (rxBuffer) {
            text = "ORBIS v" + VERSION + " — EXPLORADOR CONTROLADO\n\n" + rxBuffer + "\nLOG COMPLETO\n\n" + logBuffer;
        }
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
            root.put("explorer_mode", explorerMode);
            root.put("probe_index", probeIndex);
            root.put("probe_responses", probeResponses);
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
            for (Map.Entry<String, String> entry : probeMap.entrySet()) map.put(entry.getKey(), entry.getValue());
            root.put("events", events);
            root.put("pattern_counts", patterns);
            root.put("probe_map", map);
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
            int preferred = preferredChannel();
            if (preferred >= 0) channelSpinner.setSelection(preferred);
            updateUiState();
        });
    }

    private int preferredChannel() {
        for (int i = 0; i < writeChannels.size(); i++) {
            if (writeChannels.get(i).characteristic.getUuid().equals(NUS_RX)) return i;
        }
        return writeChannels.isEmpty() ? -1 : 0;
    }

    private String channelLabel(UUID uuid) {
        if (uuid.equals(NUS_RX)) return "NUS RX → NUS TX";
        return shortUuid(uuid);
    }

    private void updateUiState() {
        runOnUiThread(() -> {
            boolean ready = connected && findNusRx() != null && !operationRunning;
            boolean idle = !connected && !connecting && gatt == null;
            if (searchButton != null) searchButton.setEnabled(idle);
            if (forceButton != null) forceButton.setEnabled(idle);
            if (disconnectButton != null) disconnectButton.setEnabled(connected || connecting || gatt != null);
            if (sendButton != null) sendButton.setEnabled(ready && !explorerRunning);
            if (hexInput != null) hexInput.setEnabled(ready && !explorerRunning);
            if (channelSpinner != null) channelSpinner.setEnabled(ready && !explorerRunning);
            if (dfLowButton != null) dfLowButton.setEnabled(ready && !explorerRunning);
            if (dfHighButton != null) dfHighButton.setEnabled(ready && !explorerRunning);
            if (feLowButton != null) feLowButton.setEnabled(ready && !explorerRunning);
            if (stopButton != null) stopButton.setEnabled(explorerRunning);
        });
    }

    private String safeName(BluetoothDevice d) {
        if (!hasConnectPermission()) return "(sem permissão)";
        try {
            String name = d.getName();
            return name == null ? "(sem nome)" : name;
        } catch (Exception e) {
            return "(protegido)";
        }
    }

    private String safeAddress(BluetoothDevice d) {
        if (!hasConnectPermission()) return "(protegido)";
        try { return d.getAddress(); } catch (Exception e) { return "(protegido)"; }
    }

    private String shortUuid(UUID uuid) {
        if (uuid.equals(NUS_RX)) return "NUS-RX";
        if (uuid.equals(NUS_TX)) return "NUS-TX";
        String value = uuid.toString().toUpperCase(Locale.US);
        if (value.endsWith("-0000-1000-8000-00805F9B34FB")) return value.substring(4, 8);
        return value;
    }

    private String bytesToHex(byte[] data) {
        if (data == null || data.length == 0) return "(vazio)";
        StringBuilder out = new StringBuilder();
        for (byte value : data) out.append(String.format(Locale.US, "%02X ", value & 0xFF));
        return out.toString().trim();
    }

    private String propertiesText(int properties) {
        StringBuilder out = new StringBuilder();
        if ((properties & BluetoothGattCharacteristic.PROPERTY_READ) != 0) out.append("READ ");
        if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) out.append("WRITE ");
        if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) out.append("WRITE_NR ");
        if ((properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) out.append("NOTIFY ");
        if ((properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) out.append("INDICATE ");
        return out.toString().trim();
    }

    private String statusName(int status) {
        if (status == BluetoothGatt.GATT_SUCCESS) return "SUCCESS";
        if (status == 1) return "GATT_FAILURE_1";
        if (status == 133) return "GATT_ERROR_133";
        return "STATUS_" + status;
    }

    private String stateName(int state) {
        if (state == BluetoothProfile.STATE_CONNECTED) return "CONNECTED";
        if (state == BluetoothProfile.STATE_DISCONNECTED) return "DISCONNECTED";
        return "STATE_" + state;
    }

    private String timestamp() { return formatTime(System.currentTimeMillis()); }

    private String formatTime(long time) {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date(time));
    }

    private void setStatus(String text) {
        runOnUiThread(() -> statusView.setText(text));
    }

    private void appendLog(String message) {
        String line = timestamp() + "  " + message;
        synchronized (logBuffer) { logBuffer.append(line).append('\n'); }
        runOnUiThread(() -> {
            logView.append(line + "\n");
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void toast(String text) {
        runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show());
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override protected void onDestroy() {
        disconnectGatt();
        super.onDestroy();
    }
}