package edu.berkeley.eecs.clockwork;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;

import java.nio.ByteBuffer;

import edu.berkeley.eecs.shared.ProtocolConstants;

/**
 * Created by derek on 8/10/15.
 */
public class ClockworkPongService extends Service implements
        MessageApi.MessageListener,
        GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener {

    public static boolean running = false;

    private ByteBuffer packetBuffer;
    private GoogleApiClient googleApiClient;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running) {
            Log.w("ClockworkPongService", "tried to start already started service");
            return START_STICKY;
        }
        running = true;

        googleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();
        googleApiClient.connect();

        packetBuffer = ByteBuffer.allocate(ProtocolConstants.PING_SIZE);

        return START_STICKY;
    }

    @Override
    public void onConnected(Bundle bundle) {
        Wearable.MessageApi.addListener(googleApiClient, this);
    }

    @Override
    public void onConnectionSuspended(int i) {
        Log.v("ClockworkPongService", "connection suspended, code " + i);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        running = false;

        Wearable.MessageApi.removeListener(googleApiClient, this);
        googleApiClient.disconnect();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        // long received = SystemClock.elapsedRealtime() - baseline;
        long received = SystemClock.elapsedRealtime();

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
                long send = SystemClock.elapsedRealtime();
                packetBuffer.putLong(send);

                Wearable.MessageApi.sendMessage(googleApiClient, messageEvent.getSourceNodeId(),
                        ProtocolConstants.PONG_PATH, packetBuffer.array());
            }
        }
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        Log.e("ClockworkPongService", "connection failed: " + connectionResult.toString());
    }
}
