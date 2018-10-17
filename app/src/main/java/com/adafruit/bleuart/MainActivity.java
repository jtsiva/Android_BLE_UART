package com.adafruit.bleuart;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.CompoundButton;
import android.widget.Switch;

import java.lang.Thread;

public class MainActivity extends Activity implements UartBase.HostCallback {

    // UI elements
    private TextView messages;
    private EditText input;
    private Button   send;
    private CheckBox newline;
    private Switch   role;

    // Bluetooth LE UART instance.  This is defined in BluetoothLeUart.java.
    private UartBase uart;

    // Write some text to the messages text view.
    // Care is taken to do this on the main UI thread so writeLine can be called from any thread
    // (like the BTLE callback).
    private void writeLine(final CharSequence text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                messages.append(text);
                messages.append("\n");
            }
        });
    }

    // Handler for mouse click on the send button.
    public void sendClick(View view) {
        StringBuilder stringBuilder = new StringBuilder();
        String message = input.getText().toString();
        input.setText("");

        // We can only send 20 bytes per packet, so break longer messages
        // up into 20 byte payloads
        int len = message.length();
        int pos = 0;
        while(len != 0) {
            stringBuilder.setLength(0);
            if (len>=20) {
                stringBuilder.append(message.toCharArray(), pos, 20 );
                len-=20;
                pos+=20;
            }
            else {
                stringBuilder.append(message.toCharArray(), pos, len);
                len = 0;
            }
            uart.send(stringBuilder.toString());
        }
        // Terminate with a newline character if requests
        newline = (CheckBox) findViewById(R.id.newline);
        if (newline.isChecked()) {
            stringBuilder.setLength(0);
            stringBuilder.append("\n");
            uart.send(stringBuilder.toString());
        }
    }

    public void restartClick(View view) {
        uart.stop();
        uart.start();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Grab references to UI elements.
        messages = (TextView) findViewById(R.id.messages);
        input = (EditText) findViewById(R.id.input);

        Bundle receiveBundle = this.getIntent().getExtras();
        final int role = receiveBundle.getInt("role");
        uart = new DualRoleBluetoothLeUart(getApplicationContext(), role);

        switch(role) {
            case RoleChooser.CENTRAL:
                writeLine("I am a central!");
                writeLine("Scanning for devices ...");
                break;
            case RoleChooser.PERIPHERAL:
                writeLine("I am a peripheral!");
                writeLine("Advertising device ...");
                break;
            case RoleChooser.BRIDGE:
                break;
            case RoleChooser.AUTO:
                break;
        }

        // Disable the send button until we're connected.
        send = (Button)findViewById(R.id.send);
        send.setClickable(false);
        send.setEnabled(false);

        // Enable auto-scroll in the TextView
        messages.setMovementMethod(new ScrollingMovementMethod());
    }

    // OnCreate, called once to initialize the activity.
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    // OnResume, called right before UI is displayed.  Connect to the bluetooth device.
    @Override
    protected void onResume() {
        super.onResume();
        uart.registerCallback(this);
        uart.start();
    }

    // OnStop, called right before the activity loses foreground focus.  Close the BTLE connection.
    @Override
    protected void onStop() {
        super.onStop();
        uart.unregisterCallback(this);
        uart.disconnect();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // UART Callback event handlers.
    @Override
    public void onConnected(UartBase uart) {
        // Called when UART device is connected and ready to send/receive data.
        writeLine("Connected!");

        // Enable the send button
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                send = (Button)findViewById(R.id.send);
                send.setClickable(true);
                send.setEnabled(true);
            }
        });
    }

    @Override
    public void onConnectFailed(UartBase uart) {
        // Called when some error occured which prevented UART connection from completing.
        writeLine("Error connecting to device!");
        if (0 == uart.getNumConnections()) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    send = (Button) findViewById(R.id.send);
                    send.setClickable(false);
                    send.setEnabled(false);
                }
            });
        }
    }

    @Override
    public void onDisconnected(UartBase uart) {
        // Called when the UART device disconnected.
        writeLine("Disconnected!");
        // Disable the send button.
        if (0 == uart.getNumConnections()) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    send = (Button) findViewById(R.id.send);
                    send.setClickable(false);
                    send.setEnabled(false);
                }
            });
        }
    }

    @Override
    public void onReceive(UartBase uart, BluetoothGattCharacteristic rx) {
        // Called when data is received by the UART.
        writeLine("Received: " + rx.getStringValue(0));
    }

    @Override
    public void onDeviceFound(BluetoothDevice device) {
        // Called when a UART device is discovered (after calling startScan).
        writeLine("Found device : " + device.getAddress());
        writeLine("Waiting for a connection ...");
        // automatically connecting now, but we could later allow choosing to connect
        // from a list of devices
        uart.connect(device);
    }

    @Override
    public void onDeviceInfoAvailable() {
        writeLine(uart.getDeviceInfo());
    }
}
