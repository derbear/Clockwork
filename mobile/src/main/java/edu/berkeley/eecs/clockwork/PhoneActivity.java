package edu.berkeley.eecs.clockwork;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;


public class PhoneActivity extends ActionBarActivity {

    private Messenger service = null;
    private final Messenger client = new Messenger(new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case ClockworkPingService.POLL_RESPONSE:
                    final TextView label = ((TextView) findViewById(R.id.label));

                    Bundle data = msg.getData();
                    final long remote = data.getLong("remote", -1);
                    final long estimate = data.getLong("estimate", -1);
                    final long lower = data.getLong("lower", -1);
                    final long upper = data.getLong("upper", -1);

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // TODO different text if anything is -1 (i.e. failure)
                            label.setText("Estimated anchor: " + remote + " @ " + estimate
                                + ", Error bounds: " + lower + " ... " + upper + " (magnitude "
                                + (upper - lower) + ")");
                        }
                    });
                    return;
                default:
                    super.handleMessage(msg);
            }
        }
    });

    private boolean serviceBound = false;

    private long baseline = SystemClock.elapsedRealtime();

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            PhoneActivity.this.service = new Messenger(service);
            serviceBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            serviceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_phone);

        Button pollButton = ((Button) findViewById(R.id.pollButton));
        final TextView label = ((TextView) findViewById(R.id.label));
        pollButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!serviceBound) {
                    return;
                }

                // procedure call POLL_SYNC(service)
                Bundle data = new Bundle();
                data.putParcelable("client", client);
                Message m = Message.obtain(null, ClockworkPingService.POLL_SYNC);
                m.setData(data);
                try {
                    service.send(m);
                    label.setText("Waiting for poll request...");
                } catch (RemoteException e) {
                    label.setText("Error: ping poll request failed");
                    Log.e("PhoneActivity", "poll request error", e);
                    return;
                }
            }
        });

        Button syncButton = ((Button) findViewById(R.id.resyncButton));
        syncButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!serviceBound) {
                    return;
                }

                Message m = Message.obtain(null, ClockworkPingService.REQUEST_SYNC);
                try {
                    service.send(m);
                    label.setText("Sent ping synchronization request");
                } catch (RemoteException e) {
                    label.setText("Error: ping synchronization request failed");
                    Log.e("PhoneActivity", "synchronization request error", e);
                }
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();

        bindService(new Intent(this, ClockworkPingService.class), serviceConnection,
                Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();

        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
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
}
