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

public class ArgumentSplash extends Activity {

    final static int CENTRAL = 0;
    final static int PERIPHERAL = 1;
    final static int BRIDGE = 2;
    final static int AUTO = 3;

    final static int CONNECTABLE = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_argument_splash);

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
        CliArgs cliArgs = new CliArgs(arrayArgs);

        boolean isConnectable = cliArgs.switchPresent("--connectable");
        boolean isCentral = cliArgs.switchPresent("--central");
        boolean isPeripheral = cliArgs.switchPresent("--peripheral");
        boolean isAdvInterval = cliArgs.switchPresent("--advInterval");
        boolean isLogAdvTime = cliArgs.switchPresent("--log-adv-t");
        boolean isScanSetting = cliArgs.switchPresent("--scanSetting");

        double logAdvTimeDouble = 0;
        double advIntervalDouble = 0;
        double scanSettingDouble = 0;

        if (isConnectable)
            connection = CONNECTABLE;

        if (isCentral)
            bridge = CENTRAL;

        else if (isPeripheral)
            bridge = PERIPHERAL;

        if (isLogAdvTime) {
            logAdvTimeDouble = cliArgs.switchDoubleValue("--log-adv-t");
            logAdvTime = (int) logAdvTimeDouble;
        }

        if (isAdvInterval) {
            advIntervalDouble = cliArgs.switchDoubleValue("--advInterval");
            advInterval = (int) advIntervalDouble;
        }

        if (isScanSetting) {
            scanSettingDouble = cliArgs.switchDoubleValue("--scanSetting");
            scanSetting = (int) scanSettingDouble;
        }

        kickOffMain(logAdvTime, role, connection, advInterval, scanSetting);

    }

    private void kickOffMain(int logAdvTime, int bridge, int connection, int advInterval, int scanSetting) {
        Bundle sendBundle = new Bundle();
        sendBundle.putInt("logging", logAdvTime);
        sendBundle.putInt("gapRole", bridge);
        sendBundle.putInt("connectable", connection);
        sendBundle.putInt("advInterval", advInterval);
        sendBundle.putInt("scanSetting", scanSetting);

        Intent i = new Intent(ArgumentSplash.this, MainActivity.class);
        i.putExtras(sendBundle);
        startActivity(i);

        finish();
    }
}