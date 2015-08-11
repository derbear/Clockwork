package edu.berkeley.eecs.clockwork;

import android.os.SystemClock;
import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import edu.berkeley.eecs.shared.PingPacket;
import edu.berkeley.eecs.shared.ProtocolConstants;


public class PhoneActivity extends ActionBarActivity implements
        MessageApi.MessageListener,
        GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener {

    public static final int WORKER_THREADS = 1;
    public static final int PING_DELAY = 100;

    private long lastResponse = -1;
    private long lowerGuess = -1;
    private long upperGuess = -1;

    private int packetNumber = 0;

    private GoogleApiClient googleApiClient;
    private ScheduledThreadPoolExecutor workers;
    private ByteBuffer packetBuffer;

    private Node watchNode;

    private List<PingPacket> pings;

    private long baseline = SystemClock.elapsedRealtime();

    private final Runnable pingProcedure = new Runnable() {
        @Override
        public void run() {
            if (watchNode == null) {
                List<Node> nodes = Wearable.NodeApi.getConnectedNodes(googleApiClient).await().getNodes();
                for (Node node: nodes) {
                    if (node.isNearby()) {
                        watchNode = node;
                    }
                }
            }

            if (watchNode != null) {
                synchronized (packetBuffer) {
                    // prepare packet
                    packetBuffer.clear();
                    packetBuffer.putInt(packetNumber);
                    packetNumber += 2; // 2 for future "pongs"
                    long send = SystemClock.elapsedRealtime();
                    packetBuffer.putLong(send);
                    // packetBuffer.putLong(-1);
                    // packetBuffer.putLong(-1);

                    // send packet
                    MessageApi.SendMessageResult result =
                            Wearable.MessageApi.sendMessage(googleApiClient, watchNode.getId(),
                            ProtocolConstants.PING_PATH, packetBuffer.array()).await();
                    // Log.v("PhoneActivity", "node id: " + watchNode.getId());
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_phone);

        Button b = ((Button) findViewById(R.id.button));
        final TextView label = ((TextView) findViewById(R.id.label));
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                long _lowerGuess = lowerGuess;
                long _upperGuess = upperGuess;
                long _lastResponse = lastResponse;

                long estimate = (_lowerGuess + _upperGuess) / 2;
                label.setText("Estimated anchor: " + _lastResponse + " @ " + estimate
                        + ", Error bounds: " + _lowerGuess + " ... " + _upperGuess + " (magnitude "
                        + (_upperGuess - _lowerGuess) + ")");
            }
        });

        googleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        packetBuffer = ByteBuffer.allocate(ProtocolConstants.PING_SIZE);
        pings = new ArrayList<>();
    }

    @Override
    protected void onResume() {
        super.onResume();

        googleApiClient.connect();

        lastResponse = -1;
        lowerGuess = -1;
        upperGuess = -1;

        watchNode = null;
    }

    @Override
    protected void onPause() {
        super.onPause();

        Wearable.MessageApi.removeListener(googleApiClient, this);
        googleApiClient.disconnect();
        workers.shutdown();

        watchNode = null;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_phone, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onConnected(Bundle bundle) {
        Wearable.MessageApi.addListener(googleApiClient, this);

        workers = new ScheduledThreadPoolExecutor(WORKER_THREADS);
        workers.scheduleWithFixedDelay(pingProcedure, 0, PING_DELAY, TimeUnit.MILLISECONDS);
    }

    @Override
    public void onConnectionSuspended(int i) {

    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {

    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        long received = SystemClock.elapsedRealtime();
        if (messageEvent.getPath().equalsIgnoreCase(ProtocolConstants.PONG_PATH)) {
            byte[] data = messageEvent.getData();

            synchronized (packetBuffer) {
                packetBuffer.clear();
                packetBuffer.put(data);
                packetBuffer.rewind();

                int number = packetBuffer.getInt();
                long reqSend = packetBuffer.getLong();
                long reqRecv = packetBuffer.getLong();
                long resSend = packetBuffer.getLong();
                long resRecv = received;

                // adjust request send time
                long lower = reqSend + (resSend - reqRecv);
                long upper = resRecv;

                if (lastResponse == -1) {
                    lastResponse = resSend;
                    lowerGuess = lower;
                    upperGuess = upper;
                } else {
                    // assume no skew
                    long delta = resSend - lastResponse;
                    lastResponse = resSend;
                    lowerGuess += delta;
                    upperGuess += delta;

                    if (lower > lowerGuess) {
                        lowerGuess = lower;
                    }
                    if (upper < upperGuess) {
                        upperGuess = upper;
                    }
                }
            }
        }
    }
}
