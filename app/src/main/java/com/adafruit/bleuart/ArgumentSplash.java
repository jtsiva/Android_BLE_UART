package com.adafruit.bleuart;

//import android.support.v7.app.ActivityCompat;
import android.support.v4.app.ActivityCompat;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.content.Intent;
import android.util.Log;
import android.view.View;
import android.Manifest;
import android.widget.EditText;

// util and lang used for CL parser
import java.lang.reflect.Array;
import java.util.*;
import java.lang.reflect.Field;

public class ArgumentSplash extends Activity implements BluetoothRestarter.RestartListener {

    final static int CENTRAL = 0;
    final static int PERIPHERAL = 1;
    final static int BRIDGE = 2;
    final static int AUTO = 3;

    final static int GATT_WRITE_REQUEST = 0;
    final static int GATT_WRITE_COMMAND = 1;
    final static int GATT_READ = 2;
    final static int GATT_NOTIFY = 3;

    final static int CONNECTABLE = 1;

    BluetoothRestarter btRestarter = null;

    private boolean buttonClicked = false;
    private boolean btRestarted = false;
    private Intent i = null;

    public void onRestartComplete()
    {
        Log.i("bleuart", "Restarted Bluetooth!");
        btRestarted = true;
        if (buttonClicked) {
            this.go();
        }
    }

    void go(){
        startActivity(i);
        finish();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_argument_splash);

        btRestarter = new BluetoothRestarter(this);

        checkPermissions();

    }

    private static boolean hasPermissions(Context context, String... permissions) {
        if (context != null && permissions != null) {
            for (String permission : permissions) {
                if (ActivityCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
            }
        }
        return true;
    }

    private void checkPermissions(){
        int PERMISSION_ALL = 1;
        String[] PERMISSIONS = {Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION};

        if(!hasPermissions(this, PERMISSIONS)){
            ActivityCompat.requestPermissions(this, PERMISSIONS, PERMISSION_ALL);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        btRestarter.restart(this);
    }

    @Override
    protected void onStop() {
        super.onStop();

    }

    public void parseArguments(View view) {
        EditText field = (EditText)findViewById(R.id.args);
        String args = field.getText().toString();
        String[] arrayArgs = args.split(" ");
        int logAdvTime = 0;
        int role = BRIDGE;
        int connection = 0;
        int advInterval = 0;
        int scanSetting = 0;
        int connInterval = 0;
        int mtu = 0;
        int gattComm = 0;
        CliArgs cliArgs = new CliArgs(arrayArgs);

        boolean isConnectable = cliArgs.switchPresent("--connectable");
        boolean isCentral = cliArgs.switchPresent("--central");
        boolean isPeripheral = cliArgs.switchPresent("--peripheral");
        boolean isAdvInterval = cliArgs.switchPresent("--advInterval");
        boolean isLogAdvTime = cliArgs.switchPresent("--log-adv-t");
        boolean isScanSetting = cliArgs.switchPresent("--scanSetting");
        boolean isConnInterval = cliArgs.switchPresent("--connInterval");
        boolean isMtu = cliArgs.switchPresent("--mtu");
        boolean isGattCommType = cliArgs.switchPresent("--gatt-comm");

        double advIntervalDouble = 0;
        double scanSettingDouble = 0;
        double connIntervalDouble = 0;
        double mtuDouble = 0;

        if (isConnectable)
            connection = CONNECTABLE;

        if (isCentral)
            role = CENTRAL;

        else if (isPeripheral)
            role = PERIPHERAL;

        if (isLogAdvTime) {
           logAdvTime = 1;
        }

        if (isAdvInterval) {
            advIntervalDouble = cliArgs.switchDoubleValue("--advInterval");
            advInterval = (int) advIntervalDouble;
        }

        if (isScanSetting) {
            scanSettingDouble = cliArgs.switchDoubleValue("--scanSetting");
            scanSetting = (int) scanSettingDouble;
        }

        if (isConnInterval) {
            connIntervalDouble = cliArgs.switchDoubleValue("--connInterval");
            connInterval = (int) connIntervalDouble;
        }

        if (isMtu) {
            mtuDouble = cliArgs.switchDoubleValue("--mtu");
            mtu = (int) mtuDouble;
        }

        if (isGattCommType) {
            if (cliArgs.switchValue("--gatt-comm").equals("WR")) {
                gattComm = GATT_WRITE_REQUEST;
            } else if (cliArgs.switchValue("--gatt-comm").equals("WC")) {
                gattComm = GATT_WRITE_COMMAND;
            } else if (cliArgs.switchValue("--gatt-comm").equals("R")) {
                gattComm = GATT_READ;
            } else if (cliArgs.switchValue("--gatt-comm").equals("N")) {
                gattComm = GATT_NOTIFY;
            }
        }

        kickOffMain(logAdvTime, role, connection, advInterval, scanSetting, connInterval, mtu, gattComm);

    }

    private void kickOffMain(int logAdvTime, int role, int connection,
                             int advInterval, int scanSetting, int connInterval,
                             int mtu, int gattComm) {
        Bundle sendBundle = new Bundle();
        sendBundle.putInt("logging", logAdvTime);
        sendBundle.putInt("gapRole", role);
        sendBundle.putInt("connectable", connection);
        sendBundle.putInt("advInterval", advInterval);
        sendBundle.putInt("scanSetting", scanSetting);
        sendBundle.putInt("connInterval", connInterval);
        sendBundle.putInt("mtu", mtu);
        sendBundle.putInt("gattComm", gattComm);


        i = new Intent(ArgumentSplash.this, MainActivity.class);
        i.putExtras(sendBundle);
        buttonClicked = true;

        if (btRestarted) {
            this.go();
        }
    }
}