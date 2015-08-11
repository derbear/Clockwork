package edu.berkeley.eecs.clockwork;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.support.wearable.view.WatchViewStub;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.nio.ByteBuffer;

import edu.berkeley.eecs.shared.ProtocolConstants;

public class WatchActivity extends Activity {

    private TextView mTextView;

    private GoogleApiClient googleApiClient;
    private ByteBuffer packetBuffer;

    private long baseline = SystemClock.elapsedRealtime();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_watch);

        // service toggle button incantations
        final Button button = (Button) findViewById(R.id.button);
        final Intent serviceIntent = new Intent(this, ClockworkPongService.class);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!ClockworkPongService.running) {
                    startService(serviceIntent);
                    button.setText("Stop service");
                } else {
                    stopService(serviceIntent);
                    button.setText("Start service");
                }
            }
        });
        if (ClockworkPongService.running) {
            button.setText("Stop service");
        }
    }
}
