package com.grlduarte.droidplay;

import android.content.Context;
import android.util.Log;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

import com.github.serezhka.airplay.server.AirPlayServer;
import com.github.serezhka.airplay.server.AirPlayConfig;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private AirPlayServer airPlayServer;
    private StreamConsumer streamConsumer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Context context = getApplicationContext();
        Log.d(TAG, "Creating activity");

        try {
            AirPlayConfig config = new AirPlayConfig();
            streamConsumer = new StreamConsumer();
            airPlayServer = new AirPlayServer(this, config, streamConsumer);
            airPlayServer.start();

            setContentView(R.layout.activity_main);
        } catch (Exception ex) {
            Log.e(TAG, "Error while starting AirPlayServer", ex);
            this.finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        airPlayServer.stop();
    }
}
