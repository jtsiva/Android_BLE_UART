package com.adafruit.bleuart;

class BluetoothLeUartServer {
    private static final BluetoothLeUartServer ourInstance = new BluetoothLeUartServer();

    static BluetoothLeUartServer getInstance() {
        return ourInstance;
    }

    private BluetoothLeUartServer() {
    }
}
