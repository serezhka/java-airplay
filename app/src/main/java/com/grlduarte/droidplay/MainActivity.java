package com.grlduarte.droidplay;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import androidx.appcompat.app.AppCompatActivity;

import com.github.serezhka.airplay.server.AirPlayServer;
import com.github.serezhka.airplay.server.AirPlayConfig;

public class MainActivity extends AppCompatActivity  implements SurfaceHolder.Callback {
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
            streamConsumer = new StreamConsumer(config);
            airPlayServer = new AirPlayServer(this, config, streamConsumer);
            airPlayServer.start();

            setContentView(R.layout.activity_main);

            SurfaceView surfaceView = (SurfaceView) findViewById(R.id.video_layout);
            SurfaceHolder holder = surfaceView.getHolder();
            holder.addCallback(this);
        } catch (Exception ex) {
            Log.e(TAG, "Error while starting AirPlayServer", ex);
            this.finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        airPlayServer.stop();
        streamConsumer.stop();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        try {
            streamConsumer.init(holder.getSurface());
        } catch (Exception ex){
            Log.e(TAG, ex.toString(), ex);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) { }
}
