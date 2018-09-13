package com.adafruit.bleuart;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.content.Context;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.Map;
import java.util.HashMap;
import java.lang.String;
import java.util.concurrent.ConcurrentLinkedQueue;

import android.util.Log;

public class BluetoothLeUart extends BluetoothGattCallback implements BluetoothAdapter.LeScanCallback, UartBase {

    // UUIDs for UART service and associated characteristics.
    public static UUID UART_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    public static UUID TX_UUID   = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    public static UUID RX_UUID   = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");

    // UUID for the UART BTLE client characteristic which is necessary for notifications.
    public static UUID CLIENT_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // UUIDs for the Device Information service and associated characeristics.
    public static UUID DIS_UUID       = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb");
    public static UUID DIS_MANUF_UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb");
    public static UUID DIS_MODEL_UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb");
    public static UUID DIS_HWREV_UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb");
    public static UUID DIS_SWREV_UUID = UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb");

    // Internal UART state.
    private Context context;
    private WeakHashMap<UartBase.HostCallback, Object> callbacks = new WeakHashMap<UartBase.HostCallback, Object>();
    private BluetoothAdapter adapter;
    private Map<String, BluetoothGatt> mConnectedDevices = new HashMap<String, BluetoothGatt>();
    private BluetoothGattCharacteristic tx;
    private BluetoothGattCharacteristic rx;
    private boolean connectFirst;
    private boolean writeInProgress; // Flag to indicate a write is currently in progress

    // Device Information state.
    private BluetoothGattCharacteristic disManuf;
    private BluetoothGattCharacteristic disModel;
    private BluetoothGattCharacteristic disHWRev;
    private BluetoothGattCharacteristic disSWRev;
    private boolean disAvailable;


    // Queues for characteristic write (synchronous)
    private Queue<BluetoothGattCharacteristic> readQueue;
    private Queue<WriteData> writeQueue = new ConcurrentLinkedQueue<WriteData>();
    private boolean idle = true;

    public class WriteData {
        public BluetoothGatt gatt;
        byte [] data;

        public WriteData (BluetoothGatt gatt, byte [] data) {
            this.gatt = gatt;
            this.data = data;
        }
    }

    public BluetoothLeUart(Context context) {
        super();
        this.context = context;
        this.adapter = BluetoothAdapter.getDefaultAdapter();

        this.disManuf = null;
        this.disModel = null;
        this.disHWRev = null;
        this.disSWRev = null;
        this.disAvailable = false;
        this.connectFirst = false;

        this.readQueue = new ConcurrentLinkedQueue<BluetoothGattCharacteristic>();
    }


    // Return true if connected to UART device, false otherwise.
    public boolean isConnected() {
        return (!mConnectedDevices.isEmpty());
    }

    public String getDeviceInfo() {
        if (!disAvailable ) {
            // Do nothing if there is no connection.
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Manufacturer : " + disManuf.getStringValue(0) + "\n");
        sb.append("Model        : " + disModel.getStringValue(0) + "\n");
        sb.append("Firmware     : " + disSWRev.getStringValue(0) + "\n");
        return sb.toString();
    };

    public boolean deviceInfoAvailable() { return disAvailable; }

    // Send data to connected UART device.

    public void doWrite (WriteData writeData) {
        Log.i("BlueNet", "writing " + new String(writeData.data));
        BluetoothGattService service = writeData.gatt.getService(UART_UUID);
        BluetoothGattCharacteristic characteristic = service.getCharacteristic(TX_UUID);
        characteristic.setValue(writeData.data);
        idle = false;

        writeData.gatt.writeCharacteristic(characteristic);
    }

    // Send data to connected UART device.
    public void send(byte[] data) {
        if (data == null || data.length == 0) {
            // Do nothing if there is no connection or message to send.
            return;
        }

        for (Map.Entry<String, BluetoothGatt> entry : mConnectedDevices.entrySet()) {
            //could expand to communicate to other periphs
            writeQueue.offer(new WriteData(entry.getValue(), data));
        }

        if (idle && !writeQueue.isEmpty()) {
            WriteData writeData = writeQueue.poll();
            doWrite(writeData);
        }
    }

    // Send data to connected UART device.
    public void send(String data) {
        if (data != null && !data.isEmpty()) {
            send(data.getBytes(Charset.forName("UTF-8")));
        }
    }

    // Register the specified callback to receive UART callbacks.
    public void registerCallback(UartBase.HostCallback callback) {
        callbacks.put(callback, null);
    }

    // Unregister the specified callback.
    public void unregisterCallback(UartBase.HostCallback callback) {
        callbacks.remove(callback);
    }

    public void connect(BluetoothDevice device) {
        for (Map.Entry<String, BluetoothGatt> entry : mConnectedDevices.entrySet()) {
            if (device.getAddress() == entry.getValue().getRemoteDevice().getAddress()) {
                return; //already connected, so don't try again
            }
        }

        device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE);
    }

    // Disconnect to a device if currently connected.
    public void disconnect() {
        for (Map.Entry<String, BluetoothGatt> entry : mConnectedDevices.entrySet()) {
            //could expand to communicate to other periphs
            entry.getValue().disconnect();
        }

        mConnectedDevices.clear();

    }

    // Stop any in progress UART device scan.
    public void stopScan() {
        if (adapter != null) {
            adapter.stopLeScan(this);
        }
    }

    public void start(){
        startScan();
    }

    public void stop() {

    }

    // Start scanning for BLE UART devices.  Registered callback's onDeviceFound method will be called
    // when devices are found during scanning.
    public void startScan() {
        if (adapter != null) {
            adapter.startLeScan(this);
        }
    }

    // Connect to the first available UART device.
    public void connectFirstAvailable() {
        // Disconnect to any connected device.
        disconnect();
        // Stop any in progress device scan.
        stopScan();
        // Start scan and connect to first available device.
        connectFirst = true;
        startScan();
    }

    // Handlers for BluetoothGatt and LeScan events.
    @Override
    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
        super.onConnectionStateChange(gatt, status, newState);
        if (newState == BluetoothGatt.STATE_CONNECTED) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mConnectedDevices.put(gatt.getDevice().getAddress(), gatt);

                // Connected to device, start discovering services.
                if (!gatt.discoverServices()) {
                    // Error starting service discovery.
                    Log.e("", "error discovering services!");
                    connectFailure();
                }
            }
            else {
                // Error connecting to device.
                connectFailure();
            }
        }
        else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
            // Disconnected, notify callbacks of disconnection.
            mConnectedDevices.remove(gatt.getDevice().getAddress());

            notifyOnDisconnected(this);
        }
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
        super.onServicesDiscovered(gatt, status);
        // Notify connection failure if service discovery failed.
        if (status == BluetoothGatt.GATT_FAILURE) {
            connectFailure();
            Log.e("", "onServicesDiscovered gatt failure");
            return;
        } else {
            Log.e("", "onServicesDiscovered gatt success");
        }

        BluetoothDevice device = gatt.getDevice();
        String address = device.getAddress();
        final BluetoothGatt bluetoothGatt = mConnectedDevices.get(address);

        //reference to each UART characteristic
        BluetoothGattCharacteristic rx = bluetoothGatt.getService(UART_UUID).getCharacteristic(RX_UUID);

        // Save reference to each DIS characteristic.
        if (null != gatt.getService(DIS_UUID)) {
            disManuf = gatt.getService(DIS_UUID).getCharacteristic(DIS_MANUF_UUID);
            disModel = gatt.getService(DIS_UUID).getCharacteristic(DIS_MODEL_UUID);
            disHWRev = gatt.getService(DIS_UUID).getCharacteristic(DIS_HWREV_UUID);
            disSWRev = gatt.getService(DIS_UUID).getCharacteristic(DIS_SWREV_UUID);

            // Add device information characteristics to the read queue
            // These need to be queued because we have to wait for the response to the first
            // read request before a second one can be processed (which makes you wonder why they
            // implemented this with async logic to begin with???)
            readQueue.offer(disManuf);
            readQueue.offer(disModel);
            readQueue.offer(disHWRev);
            readQueue.offer(disSWRev);

            // Request a dummy read to get the device information queue going
            gatt.readCharacteristic(disModel);
        } else {
            Log.w("Central", "null service!");
        }



        // Setup notifications on RX characteristic changes (i.e. data received).
        // First call setCharacteristicNotification to enable notification.
        if (!bluetoothGatt.setCharacteristicNotification(rx, true)) {
            // Stop if the characteristic notification setup failed.
            connectFailure();
            Log.e("", "onServicesDiscovered notification setup failed");
            return;
        }
        // Next update the RX characteristic's client descriptor to enable notifications.
        BluetoothGattDescriptor desc = rx.getDescriptor(CLIENT_UUID);
        if (desc == null) {
            // Stop if the RX characteristic has no client descriptor.
            connectFailure();
            Log.e("", "onServicesDiscovered no client descriptor");
            return;
        }
        desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        if (!bluetoothGatt.writeDescriptor(desc)) {
            // Stop if the client descriptor could not be written.
            connectFailure();
            Log.e("", "onServicesDiscovered descriptor could not be written");
            return;
        }
        // Notify of connection completion.
        notifyOnConnected(this);
    }

    @Override
    public void onDescriptorWrite(BluetoothGatt gatt,
                                  BluetoothGattDescriptor descriptor, int status) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            Log.w("Central", "Descriptor written!");
        } else {
            Log.w("Central", "Descriptor NOT written!");
        }
    }

    @Override
    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        super.onCharacteristicChanged(gatt, characteristic);
        notifyOnReceive(this, characteristic);
    }

    @Override
    public void onCharacteristicRead (BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        super.onCharacteristicRead(gatt, characteristic, status);

        BluetoothDevice device = gatt.getDevice();
        String address = device.getAddress();
        final BluetoothGatt bluetoothGatt = mConnectedDevices.get(address);

        if (status == BluetoothGatt.GATT_SUCCESS) {
            //Log.w("DIS", characteristic.getStringValue(0));
            // Check if there is anything left in the queue
            BluetoothGattCharacteristic nextRequest = readQueue.poll();
            if(nextRequest != null){
                // Send a read request for the next item in the queue
                bluetoothGatt.readCharacteristic(nextRequest);
            }
            else {
                // We've reached the end of the queue
                disAvailable = true;
                notifyOnDeviceInfoAvailable();
            }
        }
        else {
            Log.w("DIS", "Failed reading characteristic " + characteristic.getUuid().toString());
        }
    }

    @Override
    public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        super.onCharacteristicWrite(gatt, characteristic, status);

        if (status == BluetoothGatt.GATT_SUCCESS) {
            Log.d("BlueNet","Characteristic write successful");
            WriteData writeData = writeQueue.poll();

            if (null == writeData) { //empty!
                idle = true;
            } else {
                doWrite(writeData);
            }

        } else {
            Log.d("BlueNet","Characteristic write FAILED");
        }

    }

    @Override
    public void onLeScan(BluetoothDevice device, int rssi, byte[] scanRecord) {
        // Stop if the device doesn't have the UART service.
        if (!parseUUIDs(scanRecord).contains(UART_UUID)) {
            return;
        }

        // Connect to first found device if required.
        if (connectFirst) {
            // Notify registered callbacks of found device.
            notifyOnDeviceFound(device);
            // Stop scanning for devices.
            stopScan();
            // Prevent connections to future found devices.
            connectFirst = false;
            // Connect to device.
            device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE);
        }
        else if (!mConnectedDevices.containsKey(device.getAddress())){
            // Notify registered callbacks of found device.
            notifyOnDeviceFound(device);
            device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE);
        }
    }

    // Private functions to simplify the notification of all callbacks of a certain event.
    private void notifyOnConnected(BluetoothLeUart uart) {
        for (UartBase.HostCallback cb : callbacks.keySet()) {
            if (cb != null) {
                cb.onConnected(uart);
            }
        }
    }

    private void notifyOnConnectFailed(BluetoothLeUart uart) {
        for (UartBase.HostCallback cb : callbacks.keySet()) {
            if (cb != null) {
                cb.onConnectFailed(uart);
            }
        }
    }

    private void notifyOnDisconnected(BluetoothLeUart uart) {
        for (UartBase.HostCallback cb : callbacks.keySet()) {
            if (cb != null) {
                cb.onDisconnected(uart);
            }
        }
    }

    private void notifyOnReceive(BluetoothLeUart uart, BluetoothGattCharacteristic rx) {
        for (UartBase.HostCallback cb : callbacks.keySet()) {
            if (cb != null ) {
                cb.onReceive(uart, rx);
            }
        }
    }

    private void notifyOnDeviceFound(BluetoothDevice device) {
        for (UartBase.HostCallback cb : callbacks.keySet()) {
            if (cb != null) {
                cb.onDeviceFound(device);
            }
        }
    }

    private void notifyOnDeviceInfoAvailable() {
        for (UartBase.HostCallback cb : callbacks.keySet()) {
            if (cb != null) {
                //cb.onDeviceInfoAvailable();
            }
        }
    }

    // Notify callbacks of connection failure, and reset connection state.
    private void connectFailure() {
        notifyOnConnectFailed(this);
    }

    // Filtering by custom UUID is broken in Android 4.3 and 4.4, see:
    //   http://stackoverflow.com/questions/18019161/startlescan-with-128-bit-uuids-doesnt-work-on-native-android-ble-implementation?noredirect=1#comment27879874_18019161
    // This is a workaround function from the SO thread to manually parse advertisement data.
    private List<UUID> parseUUIDs(final byte[] advertisedData) {
        List<UUID> uuids = new ArrayList<UUID>();

        int offset = 0;
        while (offset < (advertisedData.length - 2)) {
            int len = advertisedData[offset++];
            if (len == 0)
                break;

            int type = advertisedData[offset++];
            switch (type) {
                case 0x02: // Partial list of 16-bit UUIDs
                case 0x03: // Complete list of 16-bit UUIDs
                    while (len > 1) {
                        int uuid16 = advertisedData[offset++];
                        uuid16 += (advertisedData[offset++] << 8);
                        len -= 2;
                        uuids.add(UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", uuid16)));
                    }
                    break;
                case 0x06:// Partial list of 128-bit UUIDs
                case 0x07:// Complete list of 128-bit UUIDs
                    // Loop through the advertised 128-bit UUID's.
                    while (len >= 16) {
                        try {
                            // Wrap the advertised bits and order them.
                            ByteBuffer buffer = ByteBuffer.wrap(advertisedData, offset++, 16).order(ByteOrder.LITTLE_ENDIAN);
                            long mostSignificantBit = buffer.getLong();
                            long leastSignificantBit = buffer.getLong();
                            uuids.add(new UUID(leastSignificantBit,
                                    mostSignificantBit));
                        } catch (IndexOutOfBoundsException e) {
                            // Defensive programming.
                            //Log.e(LOG_TAG, e.toString());
                            continue;
                        } finally {
                            // Move the offset to read the next uuid.
                            offset += 15;
                            len -= 16;
                        }
                    }
                    break;
                default:
                    offset += (len - 1);
                    break;
            }
        }
        return uuids;
    }
}
