package edu.berkeley.eecs.clockwork;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.support.wearable.view.WatchViewStub;
import android.util.Log;
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

public class WatchActivity extends Activity implements
        MessageApi.MessageListener,
        GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener {

    private TextView mTextView;

    private GoogleApiClient googleApiClient;
    private ByteBuffer packetBuffer;

    private long baseline = SystemClock.elapsedRealtime();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_watch);
        final WatchViewStub stub = (WatchViewStub) findViewById(R.id.watch_view_stub);
        stub.setOnLayoutInflatedListener(new WatchViewStub.OnLayoutInflatedListener() {
            @Override
            public void onLayoutInflated(WatchViewStub stub) {
                mTextView = (TextView) stub.findViewById(R.id.text);
            }
        });

        googleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        packetBuffer = ByteBuffer.allocate(ProtocolConstants.PING_SIZE);
    }

    @Override
    protected void onResume() {
        super.onResume();

        googleApiClient.connect();
    }

    @Override
    protected void onPause() {
        super.onPause();

        Wearable.MessageApi.removeListener(googleApiClient, this);
        googleApiClient.disconnect();
    }

    @Override
    public void onConnected(Bundle bundle) {
        Wearable.MessageApi.addListener(googleApiClient, this);
    }

    @Override
    public void onConnectionSuspended(int i) {

    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {

    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        long received = SystemClock.elapsedRealtime() - baseline;

        if (messageEvent.getPath().equalsIgnoreCase(ProtocolConstants.PING_PATH)) {
            synchronized (packetBuffer) {
                // read data
                byte[] data = messageEvent.getData();
                packetBuffer.clear();
                packetBuffer.put(data);
                packetBuffer.rewind();
                packetBuffer.getInt(); // packet number
                packetBuffer.getLong(); // request send time
                packetBuffer.putLong(received);

                // send packet while setting response send time
                long send = SystemClock.elapsedRealtime() - baseline;
                packetBuffer.putLong(send);

                Wearable.MessageApi.sendMessage(googleApiClient, messageEvent.getSourceNodeId(),
                        ProtocolConstants.PONG_PATH, packetBuffer.array());
            }
        }
    }
}
