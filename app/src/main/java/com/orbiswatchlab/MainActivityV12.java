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
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.Queue;
import java.util.UUID;

public class MainActivityV12 extends Activity {
    private static final String VERSION = "0.12";
    private static final int REQUEST_PERMISSIONS = 1201;
    private static final long SCAN_TIMEOUT_MS = 15000;
    private static final long CONNECT_TIMEOUT_MS = 12000;
    private static final long OP_TIMEOUT_MS = 5000;

    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID NUS_RX = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9f");
    private static final UUID NUS_TX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9f");

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothDevice lastDevice;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Queue<GattOperation> queue = new ArrayDeque<>();
    private boolean operationRunning;
    private boolean scanning;
    private boolean connecting;
    private boolean connected;
    private boolean manualDisconnect;
    private int negotiatedMtu = 23;

    private TextView statusView;
    private TextView logView;
    private ScrollView logScroll;
    private Button scanButton;
    private Button forceButton;
    private Button disconnectButton;

    private enum OperationType { MTU, READ, NOTIFY }

    private static class GattOperation {
        final OperationType type;
        final BluetoothGattCharacteristic characteristic;
        final int mtu;

        GattOperation(OperationType type, BluetoothGattCharacteristic characteristic, int mtu) {
            this.type = type;
            this.characteristic = characteristic;
            this.mtu = mtu;
        }

        static GattOperation mtu(int value) {
            return new GattOperation(OperationType.MTU, null, value);
        }

        static GattOperation read(BluetoothGattCharacteristic characteristic) {
            return new GattOperation(OperationType.READ, characteristic, 0);
        }

        static GattOperation notify(BluetoothGattCharacteristic characteristic) {
            return new GattOperation(OperationType.NOTIFY, characteristic, 0);
        }
    }

    private final Runnable scanTimeout = () -> {
        stopScan();
        if (!connected && !connecting) setStatus("G28 não encontrado");
    };

    private final Runnable connectTimeout = () -> {
        if (connecting && !connected) {
            appendLog("TIMEOUT DE CONEXÃO");
            connecting = false;
            closeGatt();
            setStatus("Tempo de conexão esgotado");
            updateButtons();
        }
    };

    private final Runnable operationTimeout = () -> {
        if (operationRunning) {
            appendLog("FILA: operação expirou; avançando");
            operationRunning = false;
            runNextOperation();
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = safeName(device);
            if ((name == null || "(sem nome)".equals(name)) && result.getScanRecord() != null) {
                String advertisedName = result.getScanRecord().getDeviceName();
                if (advertisedName != null) name = advertisedName;
            }
            appendLog("SCAN: " + name + " | " + safeAddress(device) + " | RSSI " + result.getRssi());
            if (name != null && name.toUpperCase(Locale.US).contains("G28")) {
                lastDevice = device;
                stopScan();
                connectOnce(device, false);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            appendLog("SCAN FAILED: " + errorCode);
            setStatus("Falha no scanner: " + errorCode);
            updateButtons();
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt currentGatt, int status, int newState) {
            handler.removeCallbacks(connectTimeout);
            appendLog("STATE: status=" + status + " state=" + newState);

            if (currentGatt != gatt) {
                appendLog("GATT ANTIGO IGNORADO — callback de conexão obsoleta");
                try { currentGatt.close(); } catch (Exception ignored) { }
                return;
            }

            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                connecting = false;
                connected = true;
                setStatus("G28 conectado. Descobrindo serviços...");
                updateButtons();
                if (hasConnectPermission()) {
                    try { currentGatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH); } catch (Exception ignored) { }
                    appendLog("discoverServices()=" + currentGatt.discoverServices());
                }
                return;
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connecting = false;
                connected = false;
                clearQueue();
                setStatus("Desconectado");
                try { currentGatt.close(); } catch (Exception ignored) { }
                if (gatt == currentGatt) gatt = null;
                updateButtons();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt currentGatt, int status) {
            if (currentGatt != gatt) return;
            appendLog("SERVICES: status=" + status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setStatus("Falha ao descobrir serviços");
                return;
            }

            clearQueue();
            appendLog("TOTAL DE SERVIÇOS: " + currentGatt.getServices().size());
            queue.offer(GattOperation.mtu(247));

            for (BluetoothGattService service : currentGatt.getServices()) {
                appendLog("SERVIÇO: " + service.getUuid());
                for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                    int properties = characteristic.getProperties();
                    appendLog("  CHAR: " + characteristic.getUuid() + " | props=" + propertiesText(properties));
                    if ((properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                            (properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                        queue.offer(GattOperation.notify(characteristic));
                    }
                    if ((properties & BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                        queue.offer(GattOperation.read(characteristic));
                    }
                }
            }
            runNextOperation();
        }

        @Override
        public void onMtuChanged(BluetoothGatt currentGatt, int mtu, int status) {
            if (currentGatt != gatt) return;
            negotiatedMtu = mtu;
            appendLog("MTU: " + mtu + " status=" + status);
            completeOperation();
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt currentGatt, BluetoothGattCharacteristic characteristic, int status) {
            if (currentGatt != gatt) return;
            appendLog("RX READ [" + shortUuid(characteristic.getUuid()) + "] " + bytesToHex(characteristic.getValue()) + " status=" + status);
            completeOperation();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt currentGatt, BluetoothGattCharacteristic characteristic) {
            if (currentGatt != gatt) return;
            appendLog("RX NOTIFY [" + shortUuid(characteristic.getUuid()) + "] " + bytesToHex(characteristic.getValue()));
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt currentGatt, BluetoothGattDescriptor descriptor, int status) {
            if (currentGatt != gatt) return;
            appendLog("CCCD [" + shortUuid(descriptor.getCharacteristic().getUuid()) + "] status=" + status);
            completeOperation();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        scanButton = button("PROCURAR", v -> startScan());
        forceButton = button("FORÇAR SEGURO", v -> forceSafeReconnect());
        disconnectButton = button("DESCONECTAR", v -> disconnectGatt());
        row1.addView(scanButton, weight());
        row1.addView(forceButton, weight());
        row1.addView(disconnectButton, weight());
        root.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.addView(button("GUIA HCI", v -> showHciGuide()), weight());
        row2.addView(button("COMPARTILHAR LOG", v -> shareLog()), weight());
        row2.addView(button("LIMPAR", v -> clearLog()), weight());
        root.addView(row2);

        TextView note = new TextView(this);
        note.setText("Captura HryFine: use o Bluetooth HCI snoop log do Android. O Orbis não abre uma segunda conexão enquanto outra estiver ativa.");
        note.setTextSize(12);
        root.addView(note);

        logScroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setText("LOG ORBIS v" + VERSION + "\n");
        logView.setTextSize(9);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logScroll.addView(logView);
        root.addView(logScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
        updateButtons();
    }

    private Button button(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(9);
        button.setOnClickListener(listener);
        return button;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    }

    private void initializeBluetooth() {
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (manager == null) {
            setStatus("BluetoothManager indisponível");
            return;
        }
        adapter = manager.getAdapter();
        if (adapter == null) {
            setStatus("Sem Bluetooth");
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        setStatus(adapter.isEnabled() ? "Pronto para procurar o G28" : "Ative o Bluetooth");
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
            toast("Já existe uma conexão GATT ativa. Desconecte antes de procurar novamente.");
            appendLog("SCAN BLOQUEADO — GATT ativo");
            return;
        }
        if (!hasScanPermission()) {
            requestRequiredPermissions();
            return;
        }
        if (scanner == null && adapter != null) scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            setStatus("Scanner BLE indisponível");
            return;
        }
        scanning = true;
        manualDisconnect = false;
        appendLog("Iniciando busca BLE...");
        setStatus("Procurando o G28...");
        updateButtons();
        scanner.startScan(scanCallback);
        handler.removeCallbacks(scanTimeout);
        handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS);
    }

    private void stopScan() {
        handler.removeCallbacks(scanTimeout);
        if (scanner != null && scanning && hasScanPermission()) {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) { }
        }
        scanning = false;
        updateButtons();
    }

    private void connectOnce(BluetoothDevice device, boolean forced) {
        if (connected || connecting || gatt != null) {
            appendLog("CONNECT BLOQUEADO — já existe GATT ativo");
            toast("Conexão duplicada bloqueada");
            return;
        }
        if (!hasConnectPermission()) {
            requestRequiredPermissions();
            return;
        }
        connecting = true;
        lastDevice = device;
        appendLog("CONNECT ÚNICO | " + (forced ? "FORÇADO" : "NORMAL") + " | " + safeAddress(device));
        try {
            if (forced && Build.VERSION.SDK_INT >= 26) {
                gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK);
            } else if (Build.VERSION.SDK_INT >= 23) {
                gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
            } else {
                gatt = device.connectGatt(this, false, gattCallback);
            }
            handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
        } catch (Exception exception) {
            appendLog("EXCEÇÃO connectGatt: " + exception.getMessage());
            connecting = false;
            gatt = null;
        }
        updateButtons();
    }

    private void forceSafeReconnect() {
        if (connected || connecting || gatt != null) {
            toast("FORÇAR bloqueado: desconecte primeiro. Isso evita GATT duplicado.");
            appendLog("FORÇAR BLOQUEADO — conexão existente");
            return;
        }
        if (lastDevice != null) {
            connectOnce(lastDevice, true);
        } else {
            startScan();
        }
    }

    private void disconnectGatt() {
        manualDisconnect = true;
        stopScan();
        clearQueue();
        handler.removeCallbacks(connectTimeout);
        BluetoothGatt oldGatt = gatt;
        gatt = null;
        connecting = false;
        connected = false;
        if (oldGatt != null && hasConnectPermission()) {
            try { oldGatt.disconnect(); } catch (Exception ignored) { }
            try { oldGatt.close(); } catch (Exception ignored) { }
        }
        setStatus("Desconectado");
        appendLog("GATT encerrado e referência limpa");
        updateButtons();
    }

    private void closeGatt() {
        BluetoothGatt oldGatt = gatt;
        gatt = null;
        connecting = false;
        connected = false;
        if (oldGatt != null && hasConnectPermission()) {
            try { oldGatt.disconnect(); } catch (Exception ignored) { }
            try { oldGatt.close(); } catch (Exception ignored) { }
        }
    }

    private void runNextOperation() {
        if (!connected || gatt == null || operationRunning || !hasConnectPermission()) return;
        GattOperation operation = queue.poll();
        if (operation == null) {
            setStatus("G28 pronto | MTU " + negotiatedMtu + " | GATT único");
            appendLog("FILA GATT concluída — captura Orbis ativa");
            return;
        }
        operationRunning = true;
        handler.removeCallbacks(operationTimeout);
        handler.postDelayed(operationTimeout, OP_TIMEOUT_MS);
        boolean started = false;
        try {
            if (operation.type == OperationType.MTU) {
                started = gatt.requestMtu(operation.mtu);
                appendLog("FILA MTU " + operation.mtu + " iniciado=" + started);
            } else if (operation.type == OperationType.READ) {
                started = gatt.readCharacteristic(operation.characteristic);
                appendLog("FILA READ [" + shortUuid(operation.characteristic.getUuid()) + "] iniciado=" + started);
            } else {
                started = startNotification(gatt, operation.characteristic);
                appendLog("FILA NOTIFY [" + shortUuid(operation.characteristic.getUuid()) + "] iniciado=" + started);
            }
        } catch (Exception exception) {
            appendLog("FILA EXCEÇÃO: " + exception.getMessage());
        }
        if (!started) completeOperation();
    }

    private boolean startNotification(BluetoothGatt currentGatt, BluetoothGattCharacteristic characteristic) {
        if (!currentGatt.setCharacteristicNotification(characteristic, true)) return false;
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD);
        if (descriptor == null) return false;
        byte[] value = (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
        if (Build.VERSION.SDK_INT >= 33) {
            return currentGatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS;
        }
        descriptor.setValue(value);
        return currentGatt.writeDescriptor(descriptor);
    }

    private void completeOperation() {
        handler.removeCallbacks(operationTimeout);
        if (!operationRunning) return;
        operationRunning = false;
        handler.postDelayed(this::runNextOperation, 90);
    }

    private void clearQueue() {
        handler.removeCallbacks(operationTimeout);
        queue.clear();
        operationRunning = false;
    }

    private void showHciGuide() {
        String guide = "CAPTURA HRYFINE — ORBIS v0.12\n\n" +
                "1. Desconecte o G28 do Orbis.\n" +
                "2. Em Opções do desenvolvedor, ative Bluetooth HCI snoop log.\n" +
                "3. Desligue e ligue o Bluetooth.\n" +
                "4. Abra o HryFine e conecte ao G28.\n" +
                "5. Execute UMA ação identificável: sincronizar hora, localizar relógio ou trocar mostrador.\n" +
                "6. Aguarde 10 segundos e feche o HryFine.\n" +
                "7. Gere um relatório de bug do Android ou extraia o arquivo btsnoop_hci.log.\n" +
                "8. Envie o arquivo ao Orbis para decodificação.\n\n" +
                "Não mantenha Orbis e HryFine conectados ao mesmo tempo.";
        appendLog("GUIA HCI ABERTO");
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "Guia captura HCI G28");
        intent.putExtra(Intent.EXTRA_TEXT, guide);
        startActivity(Intent.createChooser(intent, "Abrir ou compartilhar guia HCI"));
    }

    private void shareLog() {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, "Orbis Watch Lab v" + VERSION);
        intent.putExtra(Intent.EXTRA_TEXT, logView.getText().toString());
        startActivity(Intent.createChooser(intent, "Compartilhar log"));
    }

    private void clearLog() {
        logView.setText("LOG ORBIS v" + VERSION + "\n");
        appendLog("LOG LIMPO");
    }

    private void updateButtons() {
        runOnUiThread(() -> {
            if (scanButton != null) scanButton.setEnabled(!scanning && !connecting && !connected && gatt == null);
            if (forceButton != null) forceButton.setEnabled(!scanning && !connecting && !connected && gatt == null);
            if (disconnectButton != null) disconnectButton.setEnabled(scanning || connecting || connected || gatt != null);
        });
    }

    private void setStatus(String message) {
        runOnUiThread(() -> statusView.setText(message));
    }

    private void appendLog(String message) {
        String line = timestamp() + "  " + message;
        runOnUiThread(() -> {
            logView.append(line + "\n");
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private String safeName(BluetoothDevice device) {
        if (!hasConnectPermission()) return "(sem permissão)";
        try {
            String name = device.getName();
            return name == null ? "(sem nome)" : name;
        } catch (Exception exception) {
            return "(protegido)";
        }
    }

    private String safeAddress(BluetoothDevice device) {
        if (!hasConnectPermission()) return "(protegido)";
        try { return device.getAddress(); } catch (Exception exception) { return "(protegido)"; }
    }

    private String propertiesText(int properties) {
        StringBuilder text = new StringBuilder();
        if ((properties & BluetoothGattCharacteristic.PROPERTY_READ) != 0) text.append("READ ");
        if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) text.append("WRITE ");
        if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) text.append("WRITE_NR ");
        if ((properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) text.append("NOTIFY ");
        if ((properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) text.append("INDICATE ");
        return text.toString().trim();
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
        StringBuilder text = new StringBuilder();
        for (byte value : data) text.append(String.format(Locale.US, "%02X ", value & 0xFF));
        return text.toString().trim();
    }

    private String timestamp() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
    }

    private void toast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        disconnectGatt();
        super.onDestroy();
    }
}
