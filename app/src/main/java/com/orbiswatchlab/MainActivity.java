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

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 1001;
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private TextView statusView;
    private TextView logView;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = safeName(device);
            if ((name == null || name.equals("(sem nome)")) && result.getScanRecord() != null) {
                name = result.getScanRecord().getDeviceName();
            }
            appendLog("Encontrado: " + name + " | " + safeAddress(device) + " | RSSI " + result.getRssi());
            if (name != null && name.toUpperCase(Locale.US).contains("G28")) {
                stopScan();
                connect(device);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            setStatus("Falha no scanner: " + errorCode);
            appendLog("SCAN FAILED: " + errorCode);
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt currentGatt, int status, int newState) {
            appendLog("Conexão: status=" + status + " state=" + newState);
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                gatt = currentGatt;
                setStatus("G28 conectado. Descobrindo serviços...");
                if (hasConnectPermission()) currentGatt.discoverServices();
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                setStatus("Desconectado");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt currentGatt, int status) {
            appendLog("Serviços descobertos. Status: " + status);
            List<BluetoothGattService> services = currentGatt.getServices();
            for (BluetoothGattService service : services) {
                appendLog("SERVIÇO: " + service.getUuid());
                for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                    int properties = characteristic.getProperties();
                    appendLog("  CARACTERÍSTICA: " + characteristic.getUuid() + " | propriedades: 0x" + Integer.toHexString(properties));
                    if ((properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
                            (properties & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                        enableNotification(currentGatt, characteristic);
                    }
                }
            }
            setStatus("Conectado e monitorando o G28");
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt currentGatt, BluetoothGattCharacteristic characteristic) {
            appendLog("RX " + characteristic.getUuid() + ": " + bytesToHex(characteristic.getValue()));
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt currentGatt, BluetoothGattCharacteristic characteristic, int status) {
            appendLog("READ " + characteristic.getUuid() + " status=" + status + " valor=" + bytesToHex(characteristic.getValue()));
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt currentGatt, BluetoothGattDescriptor descriptor, int status) {
            appendLog("NOTIFY configurado: " + descriptor.getCharacteristic().getUuid() + " status=" + status);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildInterface();
        initializeBluetooth();
        requestRequiredPermissions();
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(14));

        TextView title = new TextView(this);
        title.setText("ORBIS WATCH LAB");
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setText("Inicializando Bluetooth...");
        statusView.setTextSize(16);
        statusView.setPadding(0, dp(10), 0, dp(10));
        root.addView(statusView);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        Button scanButton = new Button(this);
        scanButton.setText("PROCURAR G28");
        scanButton.setOnClickListener(v -> startScan());

        Button disconnectButton = new Button(this);
        disconnectButton.setText("DESCONECTAR");
        disconnectButton.setOnClickListener(v -> disconnectGatt());

        buttons.addView(scanButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        buttons.addView(disconnectButton, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(buttons);

        ScrollView scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setText("LOG ORBIS\n");
        logView.setTextSize(12);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
    }

    private void initializeBluetooth() {
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (manager == null) {
            setStatus("BluetoothManager indisponível");
            return;
        }
        adapter = manager.getAdapter();
        if (adapter == null) {
            setStatus("Este celular não possui Bluetooth");
            return;
        }
        if (!adapter.isEnabled()) {
            setStatus("Ative o Bluetooth do celular");
            return;
        }
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
        if (!hasScanPermission()) {
            requestRequiredPermissions();
            setStatus("Autorize a permissão de Bluetooth");
            return;
        }
        if (scanner == null && adapter != null) scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            setStatus("Scanner BLE indisponível");
            return;
        }
        appendLog("Iniciando busca BLE...");
        setStatus("Procurando o G28...");
        scanner.startScan(scanCallback);
        handler.postDelayed(() -> {
            stopScan();
            if (gatt == null) setStatus("G28 não encontrado. Tente novamente.");
        }, 15000);
    }

    private void stopScan() {
        if (scanner != null && hasScanPermission()) scanner.stopScan(scanCallback);
    }

    private void connect(BluetoothDevice device) {
        if (!hasConnectPermission()) {
            requestRequiredPermissions();
            return;
        }
        setStatus("Conectando ao G28...");
        appendLog("Conectando a " + safeName(device) + " | " + safeAddress(device));
        gatt = device.connectGatt(this, false, gattCallback);
    }

    private void enableNotification(BluetoothGatt currentGatt, BluetoothGattCharacteristic characteristic) {
        if (!hasConnectPermission()) return;
        boolean enabled = currentGatt.setCharacteristicNotification(characteristic, true);
        appendLog("Ativando NOTIFY em " + characteristic.getUuid() + ": " + enabled);
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD);
        if (descriptor != null) {
            byte[] value = (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            if (Build.VERSION.SDK_INT >= 33) currentGatt.writeDescriptor(descriptor, value);
            else {
                descriptor.setValue(value);
                currentGatt.writeDescriptor(descriptor);
            }
        }
    }

    private void disconnectGatt() {
        stopScan();
        if (gatt != null && hasConnectPermission()) {
            gatt.disconnect();
            gatt.close();
            gatt = null;
        }
        setStatus("Desconectado");
        appendLog("Conexão encerrada.");
    }

    private String safeName(BluetoothDevice device) {
        if (!hasConnectPermission()) return "(sem permissão)";
        String name = device.getName();
        return name == null ? "(sem nome)" : name;
    }

    private String safeAddress(BluetoothDevice device) {
        return hasConnectPermission() ? device.getAddress() : "(endereço protegido)";
    }

    private String bytesToHex(byte[] data) {
        if (data == null || data.length == 0) return "(vazio)";
        StringBuilder builder = new StringBuilder();
        for (byte value : data) builder.append(String.format(Locale.US, "%02X ", value & 0xFF));
        return builder.toString().trim();
    }

    private void setStatus(String message) {
        runOnUiThread(() -> statusView.setText(message));
    }

    private void appendLog(String message) {
        runOnUiThread(() -> logView.append(message + "\n"));
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
