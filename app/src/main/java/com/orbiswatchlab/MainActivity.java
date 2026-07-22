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

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 1001;
    private static final long SCAN_TIMEOUT_MS = 15000;
    private static final long CONNECT_TIMEOUT_MS = 12000;
    private static final long OP_TIMEOUT_MS = 5000;

    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID UUID_FF01 = uuid16("ff01");
    private static final UUID UUID_FF02 = uuid16("ff02");
    private static final UUID UUID_FFF1 = uuid16("fff1");
    private static final UUID UUID_FF13 = uuid16("ff13");
    private static final UUID UUID_FF14 = uuid16("ff14");
    private static final UUID UUID_NUS_RX = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9f");
    private static final UUID UUID_NUS_TX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9f");

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothDevice lastDevice;

    private TextView statusView;
    private TextView logView;
    private ScrollView logScroll;
    private EditText hexInput;
    private Spinner channelSpinner;
    private Button sendButton;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final StringBuilder logBuffer = new StringBuilder();
    private final Queue<GattOperation> operationQueue = new ArrayDeque<>();
    private final List<WriteChannel> writeChannels = new ArrayList<>();
    private ArrayAdapter<String> channelAdapter;

    private boolean connected;
    private boolean manualDisconnect;
    private boolean forceMode;
    private boolean operationRunning;
    private int connectionAttempt;
    private int negotiatedMtu = 23;

    private enum OperationType { MTU, READ, NOTIFY, WRITE }

    private static class GattOperation {
        final OperationType type;
        final BluetoothGattCharacteristic characteristic;
        final byte[] data;
        final int mtu;

        private GattOperation(OperationType type, BluetoothGattCharacteristic characteristic, byte[] data, int mtu) {
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

        @Override public String toString() { return label; }
    }

    private final Runnable connectionTimeout = () -> {
        if (!connected && gatt != null) {
            appendLog("TIMEOUT DE CONEXÃO após " + CONNECT_TIMEOUT_MS + " ms");
            setStatus("Timeout ao conectar");
            closeGattOnly();
            if (forceMode && lastDevice != null && connectionAttempt < 3) {
                handler.postDelayed(() -> connect(lastDevice, true), 1200);
            }
        }
    };

    private final Runnable operationTimeout = () -> {
        if (operationRunning) {
            appendLog("FILA: operação expirou; avançando para a próxima");
            operationRunning = false;
            runNextOperation();
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
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
                setStatus("G28 conectado. Descobrindo serviços...");
                if (hasConnectPermission()) {
                    try { currentGatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH); } catch (Exception ignored) { }
                    boolean started = currentGatt.discoverServices();
                    appendLog("discoverServices()=" + started);
                }
                return;
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false;
                clearGattQueue();
                setStatus("Desconectado: " + statusName(status));
                try { currentGatt.close(); } catch (Exception ignored) { }
                if (gatt == currentGatt) gatt = null;
                updateSendState();

                if (!manualDisconnect && forceMode && lastDevice != null && connectionAttempt < 3) {
                    appendLog("Reconexão automática agendada...");
                    handler.postDelayed(() -> connect(lastDevice, true), 1500);
                }
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt currentGatt, int status) {
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
                        String label = channelLabel(c.getUuid());
                        writeChannels.add(new WriteChannel(label, c));
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
            negotiatedMtu = mtu;
            appendLog("MTU: " + mtu + " status=" + statusName(status));
            completeOperation();
        }

        @Override public void onCharacteristicRead(BluetoothGatt currentGatt, BluetoothGattCharacteristic c, int status) {
            appendLog("RX READ [" + shortUuid(c.getUuid()) + "] status=" + statusName(status) + " | " + bytesToHex(c.getValue()));
            completeOperation();
        }

        @Override public void onCharacteristicChanged(BluetoothGatt currentGatt, BluetoothGattCharacteristic c) {
            appendLog("RX NOTIFY [" + shortUuid(c.getUuid()) + "] " + bytesToHex(c.getValue()));
        }

        @Override public void onCharacteristicWrite(BluetoothGatt currentGatt, BluetoothGattCharacteristic c, int status) {
            appendLog("TX CALLBACK [" + shortUuid(c.getUuid()) + "] status=" + statusName(status));
            completeOperation();
        }

        @Override public void onDescriptorWrite(BluetoothGatt currentGatt, BluetoothGattDescriptor d, int status) {
            appendLog("CCCD [" + shortUuid(d.getCharacteristic().getUuid()) + "] status=" + statusName(status));
            completeOperation();
        }

        @Override public void onReadRemoteRssi(BluetoothGatt currentGatt, int rssi, int status) {
            appendLog("RSSI GATT: " + rssi + " status=" + statusName(status));
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildInterface();
        initializeBluetooth();
        requestRequiredPermissions();
        appendLog("Orbis Watch Lab v0.5 iniciado | Android " + Build.VERSION.RELEASE + " API " + Build.VERSION.SDK_INT);
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView title = new TextView(this);
        title.setText("ORBIS WATCH LAB v0.5");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setText("Inicializando Bluetooth...");
        statusView.setTextSize(15);
        statusView.setPadding(0, dp(8), 0, dp(8));
        root.addView(statusView);

        LinearLayout row1 = new LinearLayout(this);
        row1.addView(button("PROCURAR G28", v -> startScan()), weight());
        row1.addView(button("FORÇAR GATT", v -> forceGatt()), weight());
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.addView(button("DESCONECTAR", v -> disconnectGatt()), weight());
        row2.addView(button("COMPARTILHAR LOG", v -> shareLog()), weight());
        root.addView(row2);

        TextView terminalTitle = new TextView(this);
        terminalTitle.setText("TERMINAL HEX — envie somente comandos conhecidos");
        terminalTitle.setTypeface(Typeface.DEFAULT_BOLD);
        terminalTitle.setPadding(0, dp(8), 0, dp(4));
        root.addView(terminalTitle);

        channelSpinner = new Spinner(this);
        channelAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new ArrayList<>());
        channelSpinner.setAdapter(channelAdapter);
        root.addView(channelSpinner);

        LinearLayout sendRow = new LinearLayout(this);
        hexInput = new EditText(this);
        hexInput.setHint("Ex.: DF 00 0B 86...");
        hexInput.setSingleLine(true);
        hexInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        sendButton = button("ENVIAR HEX", v -> sendHex());
        sendRow.addView(hexInput, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2));
        sendRow.addView(sendButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(sendRow);

        logScroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setText("LOG ORBIS\n");
        logView.setTextSize(11);
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
        lastDevice = null;
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
            appendLog("FORÇAR GATT: " + safeAddress(lastDevice));
            connect(lastDevice, true);
        } else {
            appendLog("FORÇAR GATT: localizando o G28...");
            if (!hasScanPermission()) { requestRequiredPermissions(); return; }
            if (scanner == null && adapter != null) scanner = adapter.getBluetoothLeScanner();
            if (scanner != null) scanner.startScan(scanCallback);
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
        manualDisconnect = false;
        connectionAttempt++;
        setStatus("Conectando ao G28... tentativa " + connectionAttempt);
        appendLog("CONNECT tentativa " + connectionAttempt + " | " + (forced ? "FORÇADO" : "NORMAL") + " | " + safeAddress(device));

        try {
            if (forced && Build.VERSION.SDK_INT >= 26) {
                gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK);
            } else if (Build.VERSION.SDK_INT >= 23) {
                gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            } else {
                gatt = device.connectGatt(this, false, gattCallback);
            }
            handler.removeCallbacks(connectionTimeout);
            handler.postDelayed(connectionTimeout, CONNECT_TIMEOUT_MS);
        } catch (Exception e) {
            appendLog("EXCEÇÃO connectGatt: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    private void enqueue(GattOperation operation) {
        operationQueue.offer(operation);
    }

    private void runNextOperation() {
        if (!connected || gatt == null || operationRunning || !hasConnectPermission()) return;
        GattOperation op = operationQueue.poll();
        if (op == null) {
            setStatus("G28 pronto | MTU " + negotiatedMtu + " | " + writeChannels.size() + " canais TX");
            updateSendState();
            appendLog("FILA GATT concluída");
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
            } else if (op.type == OperationType.WRITE) {
                started = startWrite(gatt, op.characteristic, op.data);
                appendLog("TX [" + shortUuid(op.characteristic.getUuid()) + "] " + bytesToHex(op.data) + " iniciado=" + started);
            }
        } catch (Exception e) {
            appendLog("FILA EXCEÇÃO: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }

        if (!started) completeOperation();
    }

    private boolean startNotification(BluetoothGatt currentGatt, BluetoothGattCharacteristic c) {
        boolean local = currentGatt.setCharacteristicNotification(c, true);
        if (!local) return false;
        BluetoothGattDescriptor descriptor = c.getDescriptor(CCCD);
        if (descriptor == null) {
            appendLog("CCCD ausente em " + shortUuid(c.getUuid()));
            return false;
        }
        byte[] value = (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
        if (Build.VERSION.SDK_INT >= 33) {
            int result = currentGatt.writeDescriptor(descriptor, value);
            return result == BluetoothStatusCodes.SUCCESS;
        }
        descriptor.setValue(value);
        return currentGatt.writeDescriptor(descriptor);
    }

    private boolean startWrite(BluetoothGatt currentGatt, BluetoothGattCharacteristic c, byte[] data) {
        int properties = c.getProperties();
        int writeType = (properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        c.setWriteType(writeType);

        if (Build.VERSION.SDK_INT >= 33) {
            int result = currentGatt.writeCharacteristic(c, data, writeType);
            boolean started = result == BluetoothStatusCodes.SUCCESS;
            if (started && writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
                handler.postDelayed(this::completeOperation, 180);
            }
            return started;
        }

        c.setValue(data);
        boolean started = currentGatt.writeCharacteristic(c);
        if (started && writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            handler.postDelayed(this::completeOperation, 180);
        }
        return started;
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

    private void sendHex() {
        if (!connected || gatt == null || writeChannels.isEmpty()) {
            toast("Conecte o G28 primeiro");
            return;
        }
        int index = channelSpinner.getSelectedItemPosition();
        if (index < 0 || index >= writeChannels.size()) {
            toast("Selecione um canal TX");
            return;
        }
        try {
            byte[] data = parseHex(hexInput.getText().toString());
            if (data.length == 0) { toast("Digite bytes HEX"); return; }
            int payloadLimit = Math.max(20, negotiatedMtu - 3);
            if (data.length > payloadLimit) {
                toast("Pacote grande demais: máximo atual " + payloadLimit + " bytes");
                return;
            }
            WriteChannel channel = writeChannels.get(index);
            enqueue(GattOperation.write(channel.characteristic, data));
            appendLog("ENFILEIRADO TX via " + channel.label + " | " + data.length + " bytes");
            runNextOperation();
        } catch (IllegalArgumentException e) {
            toast(e.getMessage());
        }
    }

    private byte[] parseHex(String input) {
        String clean = input == null ? "" : input.replaceAll("(?i)0x", "").replaceAll("[^0-9A-Fa-f]", "");
        if (clean.isEmpty()) return new byte[0];
        if ((clean.length() & 1) != 0) throw new IllegalArgumentException("HEX inválido: falta um dígito");
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < clean.length(); i += 2) {
            try { out[i / 2] = (byte) Integer.parseInt(clean.substring(i, i + 2), 16); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("HEX inválido"); }
        }
        return out;
    }

    private void refreshChannelSpinner() {
        runOnUiThread(() -> {
            channelAdapter.clear();
            for (WriteChannel channel : writeChannels) channelAdapter.add(channel.label);
            channelAdapter.notifyDataSetChanged();
            int preferred = findPreferredChannelIndex();
            if (preferred >= 0) channelSpinner.setSelection(preferred);
            updateSendState();
        });
    }

    private int findPreferredChannelIndex() {
        for (int i = 0; i < writeChannels.size(); i++) if (writeChannels.get(i).characteristic.getUuid().equals(UUID_NUS_RX)) return i;
        for (int i = 0; i < writeChannels.size(); i++) if (writeChannels.get(i).characteristic.getUuid().equals(UUID_FF02)) return i;
        return writeChannels.isEmpty() ? -1 : 0;
    }

    private String channelLabel(UUID uuid) {
        if (uuid.equals(UUID_NUS_RX)) return "Nordic UART RX (6E400002) → resposta 6E400003";
        if (uuid.equals(UUID_FF02)) return "FF02 → resposta FF01";
        if (uuid.equals(UUID_FF13)) return "FF13 → resposta FF14";
        if (uuid.equals(UUID_FFF1)) return "FFF1 proprietário";
        return shortUuid(uuid) + " proprietário";
    }

    private void disconnectGatt() {
        manualDisconnect = true;
        forceMode = false;
        handler.removeCallbacks(connectionTimeout);
        stopScan();
        clearGattQueue();
        if (gatt != null && hasConnectPermission()) {
            try { gatt.disconnect(); } catch (Exception ignored) { }
            try { gatt.close(); } catch (Exception ignored) { }
            gatt = null;
        }
        connected = false;
        writeChannels.clear();
        refreshChannelSpinner();
        setStatus("Desconectado");
        appendLog("Conexão encerrada manualmente");
    }

    private void closeGattOnly() {
        clearGattQueue();
        if (gatt != null && hasConnectPermission()) {
            try { gatt.disconnect(); } catch (Exception ignored) { }
            try { gatt.close(); } catch (Exception ignored) { }
        }
        gatt = null;
        connected = false;
        updateSendState();
    }

    private void shareLog() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "Orbis Watch Lab v0.5 — log G28");
        intent.putExtra(Intent.EXTRA_TEXT, logBuffer.toString());
        startActivity(Intent.createChooser(intent, "Compartilhar log"));
    }

    private void updateSendState() {
        runOnUiThread(() -> {
            boolean enabled = connected && !writeChannels.isEmpty();
            if (sendButton != null) sendButton.setEnabled(enabled);
            if (hexInput != null) hexInput.setEnabled(enabled);
            if (channelSpinner != null) channelSpinner.setEnabled(enabled);
        });
    }

    private String safeName(BluetoothDevice device) {
        if (!hasConnectPermission()) return "(sem permissão)";
        try {
            String name = device.getName();
            return name == null ? "(sem nome)" : name;
        } catch (SecurityException e) { return "(protegido)"; }
    }

    private String safeAddress(BluetoothDevice device) {
        if (!hasConnectPermission()) return "(endereço protegido)";
        try { return device.getAddress(); } catch (SecurityException e) { return "(protegido)"; }
    }

    private static UUID uuid16(String value) {
        return UUID.fromString("0000" + value.toLowerCase(Locale.US) + "-0000-1000-8000-00805f9b34fb");
    }

    private String shortUuid(UUID uuid) {
        String s = uuid.toString().toUpperCase(Locale.US);
        if (s.endsWith("-0000-1000-8000-00805F9B34FB")) return s.substring(4, 8);
        if (uuid.equals(UUID_NUS_RX)) return "NUS-RX";
        if (uuid.equals(UUID_NUS_TX)) return "NUS-TX";
        return s;
    }

    private String bytesToHex(byte[] data) {
        if (data == null || data.length == 0) return "(vazio)";
        StringBuilder builder = new StringBuilder();
        for (byte value : data) builder.append(String.format(Locale.US, "%02X ", value & 0xFF));
        return builder.toString().trim();
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

    private String statusName(int status) {
        if (status == BluetoothGatt.GATT_SUCCESS) return "SUCCESS";
        switch (status) {
            case 8: return "TIMEOUT/CONNECTION_TIMEOUT";
            case 19: return "PEER_TERMINATED";
            case 22: return "LOCAL_HOST_TERMINATED";
            case 62: return "FAIL_ESTABLISH";
            case 133: return "GATT_ERROR_133";
            case 201: return "BUSY";
            default: return "STATUS_" + status;
        }
    }

    private String stateName(int state) {
        if (state == BluetoothProfile.STATE_CONNECTED) return "CONNECTED";
        if (state == BluetoothProfile.STATE_CONNECTING) return "CONNECTING";
        if (state == BluetoothProfile.STATE_DISCONNECTED) return "DISCONNECTED";
        if (state == BluetoothProfile.STATE_DISCONNECTING) return "DISCONNECTING";
        return "STATE_" + state;
    }

    private void setStatus(String message) {
        runOnUiThread(() -> statusView.setText(message));
    }

    private void appendLog(String message) {
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date()) + "  " + message;
        synchronized (logBuffer) { logBuffer.append(line).append('\n'); }
        runOnUiThread(() -> {
            logView.append(line + "\n");
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void toast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override protected void onDestroy() {
        disconnectGatt();
        super.onDestroy();
    }
}
