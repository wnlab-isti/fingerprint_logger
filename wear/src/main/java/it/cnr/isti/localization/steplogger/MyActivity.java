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

import android.app.Activity;
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
import android.os.Bundle;
import android.os.Environment;
import android.support.wearable.view.WatchViewStub;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

import org.altbeacon.beacon.Beacon;
import org.altbeacon.beacon.BeaconConsumer;
import org.altbeacon.beacon.BeaconManager;
import org.altbeacon.beacon.BeaconParser;
import org.altbeacon.beacon.RangeNotifier;
import org.altbeacon.beacon.Region;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.Locale;

/**
 * @author <a href="mailto:davide.larosa@isti.cnr.it">Davide La Rosa</a>
 */
public class MyActivity extends Activity implements View.OnClickListener, BeaconConsumer {

    private static final int SAMPLING_INTERVAL = 100;                 // sampling interval [ms]
    private static final String TAG = "StepLogger";

    private TextView tbStatus;
    private Button  btnLog;
    static MyActivity instance;
    private long offset;

    private boolean isLogging = false;

	private BeaconManager beaconManager;

    private FileOutputStream fos = null;
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
    private SimpleDateFormat sdf = new SimpleDateFormat("yyyy.MM.dd.HH.mm.ss", Locale.ENGLISH);
    private File recFile;

    private volatile float[] lastAcc = new float[] {0, 0, 0};
    private volatile float[] lastGyro = new float[] {0, 0, 0};
    private volatile float[] lastOrientation = new float[] {0, 0, 0};
    private volatile float[] lastMagnetic = new float[] {0, 0, 0};

    // Enable this in the manifest!!
    public static class DataLayerListenerService extends WearableListenerService {

        @Override
        public void onMessageReceived(final MessageEvent messageEvent) {
            super.onMessageReceived(messageEvent);

            instance.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    instance.offset = System.currentTimeMillis() - Long.parseLong(new String(messageEvent.getData()));
                }
            });
        }
    }

    private class MyBroadcastReceiver extends BroadcastReceiver {

        @Override
        public void onReceive (Context context, Intent intent)
        {
            if (fos != null)
            {
                try {
                    fos.write(
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
                        Date d  = new Date ();
                        d.setTime(System.currentTimeMillis() - offset);
                        tbStatus.setText("Time: " + sdf.format(d));
                    }
                });
            }
        }
    }
    MyBroadcastReceiver br = new MyBroadcastReceiver ();


    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_my);

        final WatchViewStub stub = (WatchViewStub) findViewById(R.id.watch_view_stub);
        stub.setOnLayoutInflatedListener(new WatchViewStub.OnLayoutInflatedListener() {
            @Override
            public void onLayoutInflated(WatchViewStub stub) {
                tbStatus = (TextView) stub.findViewById(R.id.tbStatus);
                btnLog = (Button) stub.findViewById(R.id.btnLogging);

                tbStatus.setText("READY");
                btnLog.setText("Start logging");
                btnLog.setTextColor(Color.GREEN);

                btnLog.setOnClickListener(MyActivity.this);
            }
        });

        instance = this;

        //while (tbLog == null);
		beaconManager = BeaconManager.getInstanceForApplication(this);
		beaconManager.getBeaconParsers().add(new BeaconParser().setBeaconLayout("m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24"));
		beaconManager.bind(this);

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
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int i) {

                }
            };

        }
        else Log.e(TAG,"Accelerometer not present");

        if (mOrientationSensor != null) {
            selOrientation = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent sensorEvent) {
                    lastOrientation = sensorEvent.values;
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int i) {

                }
            };
           // mSensorManager.registerListener(selOrientation, this.mOrientationSensor, SAMPLING_INTERVAL * 1000);
        }
        else Log.e(TAG,"Orientation sensor not present");

        if (mMagneticFieldSensor != null) {
            selMagnetic = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent sensorEvent) {
                    lastMagnetic = sensorEvent.values;
                    Log.i(TAG, "" + sensorEvent.values);
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int i) {

                }
            };
           // mSensorManager.registerListener(selMagnetic, this.mMagneticFieldSensor, SAMPLING_INTERVAL * 1000);
        }
        else Log.e(TAG,"Magnetic field sensor not present");

        if (mGyroscopeSensor != null) {
            selGyro = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent sensorEvent) {
                    lastGyro = sensorEvent.values;
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int i) {

                }
            };
           // mSensorManager.registerListener(selGyro, this.mGyroscopeSensor, SAMPLING_INTERVAL * 1000);
        }
        else Log.e(TAG,"Gyroscope not present");

		String datestr = sdf.format(new Date());
        recFile = new File(Environment.getExternalStorageDirectory() + File.separator + "logs" + File.separator + "watch_" + datestr + "_sens.txt");
        try {
            recFile.getParentFile().mkdirs();
            recFile.createNewFile();
        } catch (IOException e) {
            e.printStackTrace();
        }

        try {
            fos = new FileOutputStream(recFile);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
           // tbLog.append("\nLog file creation failed!");
        }

		try {
			File f = new File(Environment.getExternalStorageDirectory() + File.separator + "logs" + File.separator + "watch_" + datestr + "_beacons.txt");
			f.getParentFile().mkdirs();
			f.createNewFile();
			beaconStream = new FileOutputStream(f);
		} catch (IOException e) {
			e.printStackTrace();
		}

		Log.i(MyActivity.class.getName(), Environment.getExternalStorageDirectory() + File.separator + "logs");

        getApplicationContext ().registerReceiver(br, new IntentFilter("it.cnr.isti.giraff.android.wear.WAKEUP"));

        //Calendar cal = Calendar.getInstance();
        //cal.add(Calendar.SECOND, 1);

        Intent i = new Intent("it.cnr.isti.giraff.android.wear.WAKEUP");
        pintent = PendingIntent.getBroadcast(this.getApplicationContext(), 14, i, 0);

        alarm = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        //alarm.setRepeating(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), SAMPLING_INTERVAL, pintent);

    }

	@Override
	public void onBeaconServiceConnect() {
		beaconManager.addRangeNotifier(new RangeNotifier() {
			@Override
			public void didRangeBeaconsInRegion(final Collection<Beacon> beacons, Region region) {

				if (beacons.size() > 0) {
					String ts = String.format(Locale.ENGLISH, "%d", System.currentTimeMillis());

					String out = "";
					for (Beacon b : beacons) {
						out += (ts + ", " + b.getBluetoothAddress() + ", " + b.getRssi() + "\r\n");
						Log.i(MyActivity.class.getName(), b.getBluetoothAddress());
					}

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
			Thread.sleep (10);
		} catch (Exception e) {  }
	}

    @Override
    protected void onStart() {

        super.onStart();

        // tbLog.append("\nLog saved to : " + recFile.getAbsolutePath());
        //tbLog.append("\nSampling interval set to " + SAMPLING_INTERVAL + "ms");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (fos != null) {
            try {
                fos.close();
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

		beaconManager.unbind(this);

        if (selAcc != null)
            mSensorManager.unregisterListener(selAcc);
        if (selMagnetic != null)
            mSensorManager.unregisterListener(selMagnetic);
        if (selOrientation != null)
            mSensorManager.unregisterListener(selOrientation);
        if (selGyro != null)
            mSensorManager.unregisterListener(selGyro);

        alarm.cancel (pintent);
        getApplicationContext ().unregisterReceiver (br);
    }

    @Override
    public void onClick(View view) {

        if (!isLogging) {
            isLogging = true;
            btnLog.setText("STOP");
            btnLog.setTextColor(Color.RED);

            if (selAcc != null)
                mSensorManager.registerListener(selAcc, this.mAccelerationSensor, SAMPLING_INTERVAL * 1000);
            if (selMagnetic != null)
                mSensorManager.registerListener(selMagnetic, this.mMagneticFieldSensor, SAMPLING_INTERVAL * 1000);
            if (selGyro != null)
                mSensorManager.registerListener(selGyro, this.mGyroscopeSensor, SAMPLING_INTERVAL * 1000);
            if (selOrientation != null)
                mSensorManager.registerListener(selOrientation, this.mOrientationSensor, SAMPLING_INTERVAL * 1000);

            alarm.setRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis(), SAMPLING_INTERVAL, pintent);
        }
        else
        {
            isLogging = false;
            alarm.cancel(pintent);

            if (selAcc != null)
                mSensorManager.unregisterListener(selAcc);
            if (selMagnetic != null)
                mSensorManager.unregisterListener(selMagnetic);
            if (selOrientation != null)
                mSensorManager.unregisterListener(selOrientation);
            if (selGyro != null)
                mSensorManager.unregisterListener(selGyro);

            if (fos != null) {
                try {
                    fos.write("\r\n".getBytes());
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            btnLog.setText("START");
            btnLog.setTextColor(Color.GREEN);
            tbStatus.setText("READY");
        }
    }
}
