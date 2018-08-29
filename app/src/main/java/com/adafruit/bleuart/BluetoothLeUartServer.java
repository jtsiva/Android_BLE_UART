package com.adafruit.bleuart;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
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

import android.util.Log;

class BluetoothLeUartServer extends BluetoothGattServerCallback implements AdvertiseCallback, UartBase{

    // UUIDs for UART service and associated characteristics.
    public static UUID UART_UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    public static UUID TX_UUID   = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"); //from central
    public static UUID RX_UUID   = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"); //to central

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
    private Set<UartBase.HostCallback> callbacks = new HashSet();
    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private BluetoothGattServer mGattServer;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeAdvertiser mBluetoothLeAdvertiser;
    private Set<BluetoothDevice> mRegisteredDevices = new HashSet();
    private BluetoothGattCharacteristic tx;
    private BluetoothGattCharacteristic rx;
    private boolean writeInProgress; // Flag to indicate a write is currently in progress

    // Device Information state.
    private BluetoothGattCharacteristic disManuf;
    private BluetoothGattCharacteristic disModel;
    private BluetoothGattCharacteristic disHWRev;
    private BluetoothGattCharacteristic disSWRev;
    private boolean disAvailable;

    // Queues for characteristic read (synchronous)
    private Queue<BluetoothGattCharacteristic> readQueue;


    public BluetoothLeUartServer(Context context) {
        this.context = context;

        final BluetoothManager mBluetoothManager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = mBluetoothManager.getAdapter();

        //create the gatt server
        mGattServer = mBluetoothManager.openGattServer(context,this);

        //add the service to the gatt server
        mGattServer.addService(createService());

        startLeAdvertising();
    }

    // Register the specified callback to receive UART callbacks.
    public void registerCallback(UartBase.HostCallback callback) {
        callbacks.put(callback);
    }

    // Unregister the specified callback.
    public void unregisterCallback(UartBase.HostCallback callback) {
        callbacks.remove(callback);
    }

    public String getDeviceInfo() {
        return "";
    };

    public boolean deviceInfoAvailable() { return disAvailable; }

    // Send data to connected UART device.
    public void send(byte[] data) {
        if (data == null || data.length == 0) {
            // Do nothing if there is no connection or message to send.
            return;
        }
        // Update TX characteristic value.  Note the setValue overload that takes a byte array must be used.
        writeInProgress = true; // Set the write in progress flag

        BluetoothGattCharacteristic characteristic = mGattServer
                .getService(UART_UUID)
                .getCharacteristic(RX_UUID);
        characteristic.setValue(data);
        for (BluetoothDevice device : mRegisteredDevices) {
            //set the value of the characteristic

            Log.i("BlueNet", "notifying " + device.getAddress());
            try {
                boolean res = mGattServer.notifyCharacteristicChanged(device, characteristic, false);

                if (!res) {
                    Log.e("BlueNet", "Notification unsuccessful!");
                }
            } catch (NullPointerException x) {
                Log.e("BlueNet", "A device disconnected unexpectedly");
            }

            // ToDo: Update to include a timeout in case this goes into the weeds
            while (writeInProgress); // Wait for the flag to clear in onCharacteristicWrite
        }
    }

    // Send data to connected UART device.
    public void send(String data) {
        if (data != null && !data.isEmpty()) {
            send(data.getBytes(Charset.forName("UTF-8")));
        }
    }

    private BluetoothGattService createService() {
        //give our service it's UUID and set it to be primary
        BluetoothGattService service = new BluetoothGattService(UART_UUID, SERVICE_TYPE_PRIMARY);

        //Remember that rx means from server to client (perhipheral to central)
        BluetoothGattCharacteristic rxChar =
                new BluetoothGattCharacteristic(RX_UUID,
                        //Read-only characteristic, supports notifications
                        BluetoothGattCharacteristic.PROPERTY_READ | BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                        BluetoothGattCharacteristic.PERMISSION_READ);

        //Descriptor for read notifications
        BluetoothGattDescriptor rxDesc = new BluetoothGattDescriptor(CLIENT_UUID,
                BluetoothGattCharacteristic.PERMISSION_READ | BluetoothGattCharacteristic.PERMISSION_WRITE);
        rxChar.addDescriptor(rxDesc);

        //from gatt client to server
        BluetoothGattCharacteristic txChar =
                new BluetoothGattCharacteristic(TX_UUID,
                        //write permissions
                        BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE);


        service.addCharacteristic(txChar);
        service.addCharacteristic(rxChar);

        return service;
    }

    public void startLeAdvertising(){ // without adv payload
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY) //3 modes: LOW_POWER, BALANCED, LOW_LATENCY
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH) // ULTRA_LOW, LOW, MEDIUM, HIGH
                .build();

        //set of the advertising data to advertise the service!
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .setIncludeTxPowerLevel(false)
                .addServiceUuid(new ParcelUuid(UART_UUID))
                .build();

        //get an advertiser object
        mBluetoothLeAdvertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
        if (mBluetoothLeAdvertiser == null){
            Log.e(ERR_TAG, "no BLE advertiser assigned!!!");
            return;
        }

        //start advertising
        mBluetoothLeAdvertiser.startAdvertising(settings, data, this);
    }

    //Advertising Callbacks!

    @Override
    public void onStartSuccess(AdvertiseSettings settingsInEffect) {
        Log.i(INFO_TAG, "LE Advertise Started");
    }

    @Override
    public void onStartFailure(int errorCode) {
        Log.e(ERR_TAG, "LE Advertise Failed: " + errorCode);
    }

    //GATT server callbacks

    // make sure to log when other devices have connected and disconnected from the gatt server
    @Override
    public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
        super.onConnectionStateChange(device, status, newState);
        if (status == BluetoothGatt.GATT_SUCCESS){
            switch (newState) {
                case BluetoothGatt.STATE_CONNECTED:
                   // bleHandler.obtainMessage(MSG_CONNECTED, device).sendToTarget();
                    break;

                case BluetoothGatt.STATE_DISCONNECTED:
                  //  bleHandler.obtainMessage(MSG_DISCONNECTED, device).sendToTarget();
                    notifyOnDisconnected(this);
                    break;
            }
        }
    }

    @Override
    public void onCharacteristicWriteRequest(BluetoothDevice device,
                                             int requestId,
                                             BluetoothGattCharacteristic characteristic,
                                             boolean preparedWrite,
                                             boolean responseNeeded,
                                             int offset,
                                             byte[] value) {
        super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite,
                responseNeeded, offset, value);
        Log.i(TAG, "onCharacteristicWriteRequest " + characteristic.getUuid().toString());

        //handle long writes?
        //handle different receive queues
        notifyOnReceive(this, characteristic);

    }

    //Handle read requests to the read  characteristic. Can handle long reads
    //also marks the last read time on this characteristic. We use this for a timeout
    //on the availability of the data in this characteristic
    @Override
    public void onCharacteristicReadRequest(BluetoothDevice device,
                                            int requestId, int offset,
                                            BluetoothGattCharacteristic characteristic) {

//        ReadRequest readReq = new ReadRequest(device, requestId, offset, characteristic);
//        bleHandler.obtainMessage(MSG_READ, readReq).sendToTarget();
    }

    //clients will try to write to the descriptor to enable notifications.
    //this doesn't need to be done per characteristic
    @Override
    public void onDescriptorWriteRequest(BluetoothDevice device, int requestId, BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
        if (CLIENT_UUID.equals(descriptor.getUuid())) {
            if (Arrays.equals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, value)) {
                mRegisteredDevices.add(device);
                notifyOnConnected(this);
            } else if (Arrays.equals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE, value)) {
                mRegisteredDevices.remove(device);
                notifyOnDisconnected(this);
            }

            if (responseNeeded) {
                mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
            }
        } else {
            Log.w(INFO_TAG, "Unknown descriptor write request");
            if (responseNeeded) {
                mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
            }
        }
    }


    @Override
    public void onNotificationSent (BluetoothDevice device, int status) {
        super.onNotificationSent(device, status);
        writeInProgress = false;
        if (status == BluetoothGatt.GATT_SUCCESS) {
            //bleHandler.obtainMessage(MSG_NOTIFIED, device).sendToTarget();
        }
        else {
            Log.i("BlueNet", String.format("Failure: %d", status));
        }
    }

    // Private functions to simplify the notification of all callbacks of a certain event.
    private void notifyOnConnected(BluetoothLeUartServer uart) {
        for (UartBase.HostCallback cb : callbacks) {
            if (cb != null) {
                cb.onConnected(uart);
            }
        }
    }

    private void notifyOnConnectFailed(BluetoothLeUartServer uart) {
        for (UartBase.HostCallback cb : callbacks) {
            if (cb != null) {
                cb.onConnectFailed(uart);
            }
        }
    }

    private void notifyOnDisconnected(BluetoothLeUartServer uart) {
        for (UartBase.HostCallback cb : callbacks) {
            if (cb != null) {
                cb.onDisconnected(uart);
            }
        }
    }

    private void notifyOnReceive(BluetoothLeUartServer uart, BluetoothGattCharacteristic characteristic) {
        for (UartBase.HostCallback cb : callbacks) {
            if (cb != null ) {
                cb.onReceive(uart, characteristic);
            }
        }
    }

    private void notifyOnDeviceFound(BluetoothDevice device) {
        for (UartBase.HostCallback cb : callbacks) {
            if (cb != null) {
                cb.onDeviceFound(device);
            }
        }
    }

    private void notifyOnDeviceInfoAvailable() {
        for (UartBase.HostCallback cb : callbacks) {
            if (cb != null) {
                cb.onDeviceInfoAvailable();
            }
        }
    }

    // Notify callbacks of connection failure, and reset connection state.
    private void connectFailure() {
//        rx = null;
//        tx = null;
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