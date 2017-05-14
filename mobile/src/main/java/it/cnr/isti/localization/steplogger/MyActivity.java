/*
	Copyright 2015-2017 CNR-ISTI, http://isti.cnr.it
	Institute of Information Science and Technologies
	of the Italian National Research Council

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at

	  http://www.apache.org/licenses/LICENSE-2.0

	Unless required by applicable law or agreed to in writing, software
	distributed under the License is distributed on an "AS IS" BASIS,
	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
	See the License for the specific language governing permissions and
	limitations under the License.
 */
package it.cnr.isti.localization.steplogger;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Environment;
import android.support.v7.app.ActionBarActivity;
import android.text.method.ScrollingMovementMethod;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import org.altbeacon.beacon.Beacon;
import org.altbeacon.beacon.BeaconConsumer;
import org.altbeacon.beacon.BeaconManager;
import org.altbeacon.beacon.BeaconParser;
import org.altbeacon.beacon.RangeNotifier;
import org.altbeacon.beacon.Region;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * @author <a href="mailto:davide.larosa@isti.cnr.it">Davide La Rosa</a>
 */
public class MyActivity extends ActionBarActivity implements BeaconConsumer {

    private static final int SAMPLING_INTERVAL = 100;			// sampling interval [ms]
	private static final boolean CONTINUOUS_SAMPLING = true;	// continuous sampling mode

    private TextView tbInfo;
    private TextView tbTime;
    private TextView tbCounter;
    private TextView tbLog;
    private Button btnSwitch;
    //private GoogleApiClient mGoogleApiClient;

    private WifiManager wifiManager;
	private BeaconManager beaconManager;

    private int stepCount = 0;
    private SimpleDateFormat sdf = new SimpleDateFormat("yyyy.MM.dd.HH.mm.ss", Locale.ENGLISH);
    private FileOutputStream recStream = null;
    private FileOutputStream stepStream = null;
    private FileOutputStream wifiStream = null;
	private FileOutputStream beaconStream = null;
    private Sensor mMagneticFieldSensor;
    private Sensor mAccelerationSensor;
    private Sensor mGyroscopeSensor;
    private Sensor mOrientationSensor;
    private SensorManager mSensorManager;
    private SensorEventListener selMagnetic = null;
    private SensorEventListener selAcc = null;
    private SensorEventListener selGyro = null;
    private SensorEventListener selOrientation = null;
    private PendingIntent pintent;
    private AlarmManager alarm;

    private volatile float[] lastAcc = new float[] {0, 0, 0};
    private volatile float[] lastGyro = new float[] {0, 0, 0};
    private volatile float[] lastOrientation = new float[] {0, 0, 0};
    private volatile float[] lastMagnetic = new float[] {0, 0, 0};

    private enum AppState {
        READY,
        MOVING,
        STOPPED_WAITING
    }
    AppState state = AppState.READY;

    private class MyBroadcastReceiver extends BroadcastReceiver {

        @Override
        public void onReceive (Context context, Intent intent)
        {
 			writeSensorsData();
        }
    }
    MyBroadcastReceiver br = new MyBroadcastReceiver ();

	private void writeSensorsData ()
	{
		if (recStream != null)
		{
			try {
				recStream.write(
						String.format(Locale.ENGLISH, "%d,%3.3f,%3.3f,%3.3f,%3.3f,%3.3f,%3.3f,%3.3f,%3.3f,%3.3f,%3.3f,%3.3f,%3.3f\r\n",
								System.currentTimeMillis(),
								lastAcc[0], lastAcc[1], lastAcc[2],
								lastMagnetic[0], lastMagnetic[1], lastMagnetic[2],
								lastOrientation[0], lastOrientation[1], lastOrientation[2],
								lastGyro[0], lastGyro[1], lastGyro[2]).getBytes()
				);
			} catch (IOException e) {
				e.printStackTrace();
			}

			runOnUiThread(new Runnable() {
				private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
				@Override
				public void run() {
					tbTime.setText("Time: " + sdf.format(new Date()));
				}
			});
		}
	}

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_my);

        tbInfo = (TextView) findViewById(R.id.tbInfo1);
        tbTime = (TextView) findViewById(R.id.tbTime);
        tbCounter = (TextView) findViewById(R.id.tbCounter);
        tbLog = (TextView) findViewById(R.id.tbLog);
        btnSwitch = (Button) findViewById(R.id.btnSwitch);

        wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);

		beaconManager = BeaconManager.getInstanceForApplication(this);
		beaconManager.getBeaconParsers().add(new BeaconParser().setBeaconLayout("m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24"));
		beaconManager.bind(this);

        tbInfo.setText("READY");
        btnSwitch.setOnClickListener(new BtnSwitchListener());
        tbLog.setMovementMethod(new ScrollingMovementMethod());
        tbCounter.setText("" + stepCount);
        tbLog.setText("\nApp started");

        registerReceiver(wifiscanReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));

        mSensorManager = ((SensorManager)getSystemService(SENSOR_SERVICE));

        mAccelerationSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mGyroscopeSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        mOrientationSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION);
        mMagneticFieldSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

        if (mAccelerationSensor != null) {
            selAcc = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent sensorEvent) {
                    lastAcc = sensorEvent.values;
					if (CONTINUOUS_SAMPLING)
						writeSensorsData();
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int i) {

                }
            };
            mSensorManager.registerListener(selAcc, this.mAccelerationSensor, SAMPLING_INTERVAL * 1000);
        }
        else tbLog.append("\nAcceleration sensor not present");

        if (mOrientationSensor != null) {
            selOrientation = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent sensorEvent) {
                    lastOrientation = sensorEvent.values;
					if (CONTINUOUS_SAMPLING)
						writeSensorsData();
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int i) {

                }
            };
            mSensorManager.registerListener(selOrientation, this.mOrientationSensor, SAMPLING_INTERVAL * 1000);
        }
        else tbLog.append("\nOrientation sensor not present");

        if (mMagneticFieldSensor != null) {
            selMagnetic = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent sensorEvent) {
                    lastMagnetic = sensorEvent.values;
					if (CONTINUOUS_SAMPLING)
						writeSensorsData();
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int i) {

                }
            };
            mSensorManager.registerListener(selMagnetic, this.mMagneticFieldSensor, SAMPLING_INTERVAL * 1000);
        }
        else tbLog.append("\nMagnetic field sensor not present");

        if (mGyroscopeSensor != null) {
            selGyro = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent sensorEvent) {
                    lastGyro = sensorEvent.values;
					if (CONTINUOUS_SAMPLING)
						writeSensorsData();
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int i) {

                }
            };
            mSensorManager.registerListener(selGyro, this.mGyroscopeSensor, SAMPLING_INTERVAL * 1000);
        }
        else tbLog.append("\nGyroscope sensor not present");

        String datestr = sdf.format(new Date());
        try {
            File f = new File(Environment.getExternalStorageDirectory() + File.separator + "logs" + File.separator + "phone_" + datestr + "_sens.txt");
            f.getParentFile().mkdirs();
            f.createNewFile();
            recStream = new FileOutputStream(f);
        } catch (IOException e) {
            tbLog.append("\nRec file creation failed!");
            e.printStackTrace();
        }

        try {
            File f = new File(Environment.getExternalStorageDirectory() + File.separator + "logs" + File.separator + "phone_" + datestr + "_steps.txt");
            f.getParentFile().mkdirs();
            f.createNewFile();
            stepStream = new FileOutputStream(f);
        } catch (IOException e) {
            tbLog.append("\nStep file creation failed!");
            e.printStackTrace();
        }

        try {
            File f = new File(Environment.getExternalStorageDirectory() + File.separator + "logs" + File.separator + "phone_" + datestr + "_wifi.txt");
            f.getParentFile().mkdirs();
            f.createNewFile();
            wifiStream = new FileOutputStream(f);
        } catch (IOException e) {
            tbLog.append("\nWifi file creation failed!");
            e.printStackTrace();
        }

		try {
			File f = new File(Environment.getExternalStorageDirectory() + File.separator + "logs" + File.separator + "phone_" + datestr + "_beacons.txt");
			f.getParentFile().mkdirs();
			f.createNewFile();
			beaconStream = new FileOutputStream(f);
		} catch (IOException e) {
			tbLog.append("\nBeacons file creation failed!");
			e.printStackTrace();
		}

		if (CONTINUOUS_SAMPLING)
			wifiManager.startScan();
		else
		{
			getApplicationContext ().registerReceiver (br, new IntentFilter("it.cnr.isti.giraff.android.wear.WAKEUP"));

			Calendar cal = Calendar.getInstance();
			cal.add(Calendar.SECOND, 1);
			Intent i = new Intent("it.cnr.isti.giraff.android.wear.WAKEUP");
			pintent = PendingIntent.getBroadcast(this.getApplicationContext(), 14, i, 0);

			alarm = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
			alarm.setRepeating(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), SAMPLING_INTERVAL, pintent);
			tbLog.append("\nSampling interval set to " + SAMPLING_INTERVAL + "ms");
		}

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (recStream != null) {
            try {
                recStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if (stepStream != null) {
            try {
                stepStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if (wifiStream != null) {
            try {
                wifiStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

		if (beaconStream != null) {
			try {
				beaconStream.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

        if (selAcc != null)
            mSensorManager.unregisterListener(selAcc);
        if (selMagnetic != null)
            mSensorManager.unregisterListener(selMagnetic);
        if (selOrientation != null)
            mSensorManager.unregisterListener(selOrientation);
        if (selGyro != null)
            mSensorManager.unregisterListener(selGyro);

		if (!CONTINUOUS_SAMPLING) {
			alarm.cancel(pintent);
			getApplicationContext().unregisterReceiver(br);
		}

		beaconManager.unbind(this);
        unregisterReceiver(wifiscanReceiver);

        //mGoogleApiClient.disconnect();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.my, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private BroadcastReceiver wifiscanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

			final List<ScanResult> aps = wifiManager.getScanResults();

			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					tbLog.append("\nWifi AP detected: " + aps.size());
				}
			});

			String firstCol;
			if (CONTINUOUS_SAMPLING)
				firstCol =  String.format(Locale.ENGLISH, "%d", System.currentTimeMillis());
			else
				firstCol = "" + stepCount;

            if (CONTINUOUS_SAMPLING || (!CONTINUOUS_SAMPLING && state == AppState.STOPPED_WAITING)) {
                String out = "";
                for (ScanResult sr : aps)
                    out += (firstCol + "," + sr.SSID + "," + sr.BSSID + "," + sr.level + "\r\n");

                if (wifiStream != null) {
                    try {
                        wifiStream.write(out.getBytes());
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

				if (!CONTINUOUS_SAMPLING) {
					//tbLog.append(out);
					tbInfo.setText("READY");
					btnSwitch.setText("START");
					btnSwitch.setTextColor(Color.GREEN);
					btnSwitch.setClickable(true);
					//isReady = true;
					state = AppState.READY;
				}
				else
					wifiManager.startScan();
            }
			else {
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						tbLog.append("\nUnexpected wifi condition");
					}
				});
			}
        }
    };

	@Override
	public void onBeaconServiceConnect()
	{
		beaconManager.addRangeNotifier(new RangeNotifier() {
			@Override
			public void didRangeBeaconsInRegion(final Collection<Beacon> beacons, Region region)
			{
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						tbLog.append("\nBeacons detected: " + beacons.size());
					}
				});

				if (beacons.size() > 0)
				{
					String ts = String.format(Locale.ENGLISH, "%d", System.currentTimeMillis());

					String out = "";
					for (Beacon b : beacons)
						out += (ts + ", " + b.getBluetoothAddress() + ", " + b.getRssi() + "\r\n");

					final String out2 = out;
					runOnUiThread(new Runnable() {
						@Override
						public void run() {
							tbLog.append("\n" + out2);
						}
					});

					if (beaconStream != null) {
						try {
							beaconStream.write(out.getBytes());
						} catch (IOException e) {
							e.printStackTrace();
						}
					}
				}
			}
		});

		try {
			beaconManager.startRangingBeaconsInRegion(new Region("myRangingUniqueId", null, null, null));
		} catch (Exception e) {  }
	}

    private class BtnSwitchListener implements Button.OnClickListener {
        @Override
        public void onClick(View view) {

            if (state == AppState.READY)
            {
                tbInfo.setText("MOVING TO POINT " + (stepCount + 1));
                btnSwitch.setText("STOP");
                btnSwitch.setTextColor(Color.RED);

                if (stepStream != null) {
                    try {
                        stepStream.write(String.format(Locale.ENGLISH, "%d,%d\r\n",
                                System.currentTimeMillis(), stepCount).getBytes());
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                state = AppState.MOVING;
            }
            else if (state == AppState.MOVING)
            {
				if (!CONTINUOUS_SAMPLING) {
					stepCount++;
					tbInfo.setText("STOPPED, SCANNING WIFI AP");
					btnSwitch.setText("WAIT");
					btnSwitch.setTextColor(Color.GRAY);
					btnSwitch.setClickable(false);
					tbCounter.setText("" + stepCount);

					state = AppState.STOPPED_WAITING;

					wifiManager.startScan();
					//tbLog.append("\nWifi scan started");
				}
				else {
					stepCount++;

					tbInfo.setText("READY");
					btnSwitch.setText("START");
					btnSwitch.setTextColor(Color.GREEN);
					btnSwitch.setClickable(true);
					state = AppState.READY;

					tbCounter.setText("" + stepCount);
				}

				if (stepStream != null) {
					try {
						stepStream.write(String.format(Locale.ENGLISH, "%d,%d\r\n",
								System.currentTimeMillis(), stepCount).getBytes());
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
            }
            else
                tbLog.append("\nERROR: State not reachable");
        }
    }
}

// <<< code within the onCreate() method >>>
// Wearable Client API
       /* mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                    @Override
                    public void onConnected(final Bundle connectionHint) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                tbLog.append("\n" + "onConnected: " + connectionHint);
                            }
                        });

                        Wearable.NodeApi.getConnectedNodes(mGoogleApiClient).
                                setResultCallback(new ResultCallback<NodeApi.GetConnectedNodesResult>() {
                            @Override
                            public void onResult(NodeApi.GetConnectedNodesResult getConnectedNodesResult) {

                                for (final Node node : getConnectedNodesResult.getNodes()) {
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            tbLog.append("\n" + node.getDisplayName() + "," + node.getId());
                                        }
                                    });
                                }

                                // Sends a message to the watch
                                Wearable.MessageApi.sendMessage(
                                        mGoogleApiClient, getConnectedNodesResult.getNodes().get(0).getId(), "/timesync",
                                        new String ("" + System.currentTimeMillis()).getBytes()).setResultCallback(
                                        new ResultCallback<MessageApi.SendMessageResult>() {
                                            @Override
                                            public void onResult(MessageApi.SendMessageResult sendMessageResult) {
                                                if (!sendMessageResult.getStatus().isSuccess()) {
                                                    runOnUiThread(new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            tbLog.append("\n" + "Failed sending message!");
                                                        }
                                                    });
                                                }
                                            }
                                        }
                                );
                            }
                        });
                    }
                    @Override
                    public void onConnectionSuspended(final int cause) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                tbLog.append("\n" + "onConnectionSuspended: " + cause);
                            }
                        });
                    }
                })
                .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
                    @Override
                    public void onConnectionFailed(final ConnectionResult result) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                tbLog.append("\n" + "onConnectionFailed: " + result);
                            }
                        });
                    }
                })
                .addApi(Wearable.API)
                .build();

        mGoogleApiClient.connect();
*/
