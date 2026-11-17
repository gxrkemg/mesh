package com.example.mesh;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

@SuppressLint("MissingPermission")
public class MainActivity extends AppCompatActivity {

    private EditText etMessage;
    private TextView tvLog;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;
    private AdvertiseCallback currentAdvCallback;

    // Deprem ağına özel sabit UUID
    private static final ParcelUuid MESH_UUID = new ParcelUuid(UUID.fromString("0000CAFE-0000-1000-8000-00805F9B34FB"));
    // Aynı mesajın sonsuz döngüye girmesini önleyen liste
    private final Set<Byte> seenMessages = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etMessage = findViewById(R.id.etMessage);
        tvLog = findViewById(R.id.tvLog);
        Button btnSend = findViewById(R.id.btnSend);

        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager.getAdapter();
        if (adapter != null) {
            advertiser = adapter.getBluetoothLeAdvertiser();
            scanner = adapter.getBluetoothLeScanner();
        }

        checkPermissionsAndStart();

        btnSend.setOnClickListener(v -> {
            String msg = etMessage.getText().toString().trim();
            if (!msg.isEmpty()) {
                byte msgId = (byte) new Random().nextInt(120);
                byte ttl = 5; // 5 telefona kadar sekebilir
                broadcastMessage(msgId, ttl, msg);
                logYaz("GÖNDERİLDİ [ID:" + msgId + " TTL:" + ttl + "]: " + msg);
                etMessage.setText("");
            }
        });
    }

    private void checkPermissionsAndStart() {
        String[] perms;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms = new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
        } else {
            perms = new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        }

        ActivityCompat.requestPermissions(this, perms, 101);
        startScanning();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        startScanning();
    }

    private void startScanning() {
        if (scanner == null) return;
        ScanFilter filter = new ScanFilter.Builder().setServiceUuid(MESH_UUID).build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
        logYaz("BLE Dinleme (Tarama) aktif...");
    }

    private void broadcastMessage(byte msgId, byte ttl, String message) {
        if (advertiser == null) {
            logYaz("Hata: BLE Advertiser desteklenmiyor veya Bluetooth kapalı!");
            return;
        }
        seenMessages.add(msgId);

        byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);
        int len = Math.min(msgBytes.length, 18);
        ByteBuffer buffer = ByteBuffer.allocate(2 + len);
        buffer.put(msgId);
        buffer.put(ttl);
        buffer.put(msgBytes, 0, len);

        if (currentAdvCallback != null) {
            advertiser.stopAdvertising(currentAdvCallback);
        }

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(MESH_UUID)
                .addServiceData(MESH_UUID, buffer.array())
                .build();

        currentAdvCallback = new AdvertiseCallback() {
            @Override
            public void onStartFailure(int errorCode) {
                logYaz("Yayın Hatası Kodu: " + errorCode);
            }
        };

        advertiser.startAdvertising(settings, data, currentAdvCallback);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (result.getScanRecord() == null) return;
            byte[] data = result.getScanRecord().getServiceData(MESH_UUID);
            if (data == null || data.length < 3) return;

            byte msgId = data[0];
            byte ttl = data[1];
            String text = new String(data, 2, data.length - 2, StandardCharsets.UTF_8);

            if (!seenMessages.contains(msgId)) {
                seenMessages.add(msgId);
                logYaz("YAKALANDI [ID:" + msgId + " TTL:" + ttl + "]: " + text);

                // Sekme (Relay) Mantığı: TTL 1'den büyükse 1 düşürüp diğer telefonlara fırlat
                if (ttl > 1) {
                    byte newTtl = (byte) (ttl - 1);
                    logYaz("-> Mesaj sektiriliyor (Yeni TTL: " + newTtl + ")");
                    broadcastMessage(msgId, newTtl, text);
                }
            }
        }
    };

    private void logYaz(String metin) {
        runOnUiThread(() -> tvLog.append(metin + "\n"));
    }
}