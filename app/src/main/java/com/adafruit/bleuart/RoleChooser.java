package com.adafruit.bleuart;

import android.support.v7.app.AppCompatActivity;
import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.view.View;

public class RoleChooser extends Activity {

    final static int CENTRAL = 0;
    final static int PERIPHERAL = 1;
    final static int BRIDGE = 2;
    final static int AUTO = 3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_role_chooser);
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
