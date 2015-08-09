package edu.berkeley.eecs.clockwork;

import android.os.SystemClock;
import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMapItem;
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
        DataApi.DataListener,
        GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener {

    public static final int WORKER_THREADS = 1;
    public static final int PING_DELAY = 100;

    private int packetNumber = 0;

    private GoogleApiClient googleApiClient;
    private ScheduledThreadPoolExecutor workers;
    private ByteBuffer packetBuffer;

    private List<PingPacket> pings;

    private long baseline = SystemClock.elapsedRealtime();

    private final Runnable pingProcedure = new Runnable() {
        @Override
        public void run() {
            synchronized (packetBuffer) {

                // prepare packet
                PutDataRequest request = PutDataRequest.create(ProtocolConstants.PING_PATH);
                packetBuffer.clear();
                packetBuffer.putInt(packetNumber);
                packetNumber += 2; // 2 for future "pongs"
                long send = SystemClock.elapsedRealtime() - baseline;
                packetBuffer.putLong(send);
                // packetBuffer.putLong(-1);
                // packetBuffer.putLong(-1);

                // send packet
                request.setData(packetBuffer.array());
                Wearable.DataApi.putDataItem(googleApiClient, request);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_phone);

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
        workers = new ScheduledThreadPoolExecutor(WORKER_THREADS);
        workers.scheduleWithFixedDelay(pingProcedure, 0, PING_DELAY, TimeUnit.MILLISECONDS);
    }

    @Override
    protected void onPause() {
        super.onPause();

        Wearable.DataApi.removeListener(googleApiClient, this);
        googleApiClient.disconnect();
        workers.shutdown();
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
        Wearable.DataApi.addListener(googleApiClient, this);
    }

    @Override
    public void onConnectionSuspended(int i) {

    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {

    }

    @Override
    public void onDataChanged(DataEventBuffer dataEventBuffer) {
        long received = SystemClock.elapsedRealtime() - baseline;

        for (DataEvent event: dataEventBuffer) {
            if (event.getType() == DataEvent.TYPE_CHANGED) {
                DataItem item = event.getDataItem();
                if (item.getUri().getPath().compareTo(ProtocolConstants.PONG_PATH) == 0) {
                    byte[] data = item.getData();

                    synchronized (packetBuffer) {
                        packetBuffer.clear();
                        packetBuffer.put(data);
                        packetBuffer.rewind();

                        pings.add(new PingPacket(packetBuffer.getInt(),
                                packetBuffer.getLong(), packetBuffer.getLong(),
                                packetBuffer.getLong(), received));
                    }
                }
            }
        }

        if (pings.size() % 50 == 0) {
            Log.v("PhoneActivity", "packets right now: ");
            List<PingPacket> pings2 = Collections.unmodifiableList(pings);
            for (PingPacket p : pings2) {
                Log.v("PhoneActivity", p.toString());
            }
        }
    }
}
