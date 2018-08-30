package com.adafruit.bleuart;

//import android.support.v7.app.ActivityCompat;
import android.support.v4.app.ActivityCompat;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.content.Intent;
import android.view.View;
import android.Manifest;

public class RoleChooser extends Activity {

    final static int CENTRAL = 0;
    final static int PERIPHERAL = 1;
    final static int BRIDGE = 2;
    final static int AUTO = 3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_role_chooser);

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

    public void setCentral(View view) {
        kickOffMain(CENTRAL);
    }

    public void setPeripheral(View view) {
        kickOffMain(PERIPHERAL);
    }

    private void kickOffMain(int role) {
        Bundle sendBundle = new Bundle();
        sendBundle.putInt("role", role);

        Intent i = new Intent(RoleChooser.this, MainActivity.class);
        i.putExtras(sendBundle);
        startActivity(i);

        finish();
    }
}
