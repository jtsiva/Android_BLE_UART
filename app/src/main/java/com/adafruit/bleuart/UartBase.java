package com.adafruit.bleuart;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;

public interface UartBase {
    // Interface for a BluetoothLeUart client to be notified of UART actions.
    public interface HostCallback {
        public void onConnected(UartBase uart);
        public void onConnectFailed(UartBase uart);
        public void onDisconnected(UartBase uart);
        public void onReceive(UartBase uart, BluetoothGattCharacteristic rx);
        public void onDeviceFound(BluetoothDevice device);
        public void onDeviceInfoAvailable();
    }

    public void registerCallback(UartBase.HostCallback callback);
    public void unregisterCallback(UartBase.HostCallback callback);
    public void start();
    public void connect(BluetoothDevice device);
    public void disconnect();
    public void stop();
    public String getDeviceInfo();
    public void send(byte[] data);
    public void send(String data);
    public int getNumConnections();
}
