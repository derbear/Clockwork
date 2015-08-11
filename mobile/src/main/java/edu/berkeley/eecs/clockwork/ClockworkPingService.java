package edu.berkeley.eecs.clockwork;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import edu.berkeley.eecs.shared.ProtocolConstants;

/**
 * Created by derek on 8/10/15.
 */
public class ClockworkPingService extends Service implements
        MessageApi.MessageListener,
        GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener {
    // TODO for production use optimize object allocation, double-check race possibilities
    // TODO move synchronization on this to method signatures

    // msg.what request code: request Clockwork service to run offset discovery (if not in progress)
    public static final int REQUEST_SYNC = 1;

    // msg.what request code: ask Clockwork service for current offset
    // parameters:
    // "client": Messenger - client object to send response
    public static final int POLL_SYNC = 2;

    // msg.what response code: sent in response to POLL_SYNC
    // return values:
    // "remote": long - remote reference timestamp
    // "estimate": long - local reference timestamp estimate
    // "lower": long - local reference timestamp lower bound
    // "upper": long - local reference timestamp upper bound
    public static final int POLL_RESPONSE = -2;

    // stop offset discovery when either of these two conditions are met
    public static final int POLL_TIMEOUT = 20000; // ms
    public static final int GOOD_ENOUGH_RTT = 22; // ms

    // parameters for sending discovery "pings"
    public static final int WORKER_THREADS = 1;
    public static final int PING_DELAY = 100;

    // SYNCHRONIZE to read/write time all three calculation values atomically
    private Object calculationStateLock = new Object();

    // state of discovery so far (-1 means uninitialized)
    private long lastResponse = -1;
    private long lowerGuess = -1;
    private long upperGuess = -1;

    private long syncStart = -1;
    private int packetNumber = 0;

    private ScheduledThreadPoolExecutor workers;

    private GoogleApiClient googleApiClient;
    private Node remoteNode;
    // SYNCHRONIZE to read/write values over local network during communication with remote
    private ByteBuffer packetBuffer = ByteBuffer.allocate(ProtocolConstants.PING_SIZE);

    private boolean initialized = false;
    private boolean started = false;

    private synchronized void initialize() {
        if (initialized) {
            return;
        }

        googleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        initialized = true;
    }

    private synchronized void start() {
        if (started) {
            return;
        }

        remoteNode = null;

        lastResponse = -1;
        lowerGuess = -1;
        upperGuess = -1;
        syncStart = SystemClock.elapsedRealtime();

        googleApiClient.connect();
        started = true;
    }

    private synchronized void stop() {
        if (!started) {
            return;
        }

        Wearable.MessageApi.removeListener(googleApiClient, this);
        googleApiClient.disconnect();
        workers.shutdown();

        started = false;
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

    private final Runnable pingProcedure = new Runnable() {
        @Override
        public void run() {
            if (remoteNode == null) {
                List<Node> nodes = Wearable.NodeApi.getConnectedNodes(googleApiClient).await().getNodes();
                for (Node node: nodes) {
                    if (node.isNearby()) {
                        remoteNode = node;
                    }
                }
            }

            if (remoteNode != null) {
                synchronized (packetBuffer) {
                    // prepare packet
                    packetBuffer.clear();
                    packetBuffer.putInt(packetNumber);
                    packetNumber += 2; // 2 for future "pongs"
                    // long send = SystemClock.elapsedRealtime() - baseline;
                    long send = SystemClock.elapsedRealtime();
                    packetBuffer.putLong(send);
                    // packetBuffer.putLong(-1);
                    // packetBuffer.putLong(-1);

                    // send packet
                    MessageApi.SendMessageResult result =
                            Wearable.MessageApi.sendMessage(googleApiClient, remoteNode.getId(),
                                    ProtocolConstants.PING_PATH, packetBuffer.array()).await();
                    // Log.v("PhoneActivity", "node id: " + remoteNode.getId());
                }
            }

            // check stop sync conditions (timeout or good enough)
            if (SystemClock.elapsedRealtime() - syncStart > POLL_TIMEOUT ||
                    (lastResponse != -1 && upperGuess - lowerGuess <= GOOD_ENOUGH_RTT)) {
                stop();
            }
        }
    };

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        // long received = SystemClock.elapsedRealtime() - baseline;
        long received = SystemClock.elapsedRealtime();
        if (messageEvent.getPath().equalsIgnoreCase(ProtocolConstants.PONG_PATH)) {
            byte[] data = messageEvent.getData();

            int number;
            long reqSend, reqRecv, resSend, resRecv;
            synchronized (packetBuffer) {
                packetBuffer.clear();
                packetBuffer.put(data);
                packetBuffer.rewind();

                number = packetBuffer.getInt();
                reqSend = packetBuffer.getLong();
                reqRecv = packetBuffer.getLong();
                resSend = packetBuffer.getLong();
                resRecv = received;
            }

            // adjust request send time
            long lower = reqSend + (resSend - reqRecv);
            long upper = resRecv;
            synchronized (calculationStateLock) {
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

    private final Messenger messenger = new Messenger(new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case REQUEST_SYNC:
                    // client wants service to start synchronization if not yet started

                    start();
                    return;
                case POLL_SYNC:
                    // client wants current delay estimation

                    Bundle parameters = msg.getData();
                    Messenger client = (Messenger) parameters.get("client");
                    if (client == null) {
                        Log.w("ClockworkService", "could not send response: no Messenger given");
                        return;
                    }

                    long remoteTime, localEstimate, localLowerBound, localUpperBound;
                    synchronized (calculationStateLock) {
                        remoteTime = lastResponse;
                        localLowerBound = lowerGuess;
                        localUpperBound = upperGuess;
                    }
                    localEstimate = (localLowerBound + localUpperBound) / 2;

                    Bundle responseData = new Bundle();
                    responseData.putLong("remote", remoteTime);
                    responseData.putLong("estimate", localEstimate);
                    responseData.putLong("lower", localLowerBound);
                    responseData.putLong("upper", localUpperBound);

                    Message m = Message.obtain(null, POLL_RESPONSE);
                    m.setData(responseData);
                    try {
                        client.send(m);
                    } catch (RemoteException e) {
                        Log.w("ClockworkService", "could not send response: exception occurred", e);
                    }
                    return;
                default:
                    super.handleMessage(msg);
            }
        }
    });

    @Override
    public IBinder onBind(Intent intent) {
        initialize();
        return messenger.getBinder();
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        // TODO: could have orderly shutdown code (e.g. kill workers)?
        Log.e("ClockworkService", "connection failed: " + connectionResult.toString());
    }
}
