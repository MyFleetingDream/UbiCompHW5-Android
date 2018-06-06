package edu.uw.ubicomplab.androidaccelapp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGattCharacteristic;
import android.graphics.Color;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.GridLabelRenderer;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;

@SuppressLint("SetTextI18n")
public class MainActivity extends AppCompatActivity implements BluetoothLeUart.Callback {

    // GLOBALS
    // UI elements
    private TextView resultText;
    private TextView gesture1CountText, gesture2CountText, gesture3CountText;
    private List<Button> buttons;

    // Accelerometer
    private LineGraphSeries<DataPoint> timeAccelX = new LineGraphSeries<>();
    private LineGraphSeries<DataPoint> timeAccelY = new LineGraphSeries<>();
    private LineGraphSeries<DataPoint> timeAccelZ = new LineGraphSeries<>();

    // Graph
    private GraphView graphAccel;
    private GraphView xfeatures, yfeatures, zfeatures;
    private int graphXBounds = 50;
    private int graphYBounds = 20;
    private int graphColor[] = {Color.argb(255,244,170,50),
            Color.argb(255, 60, 175, 240),
            Color.argb(225, 50, 220, 100),
            Color.argb(225, 180, 50, 255),
            Color.argb(225, 255, 50, 180)};
    private static final int MAX_DATA_POINTS_UI_IMU = 100; // Adjust to show more points on graph
    public int accelGraphXTime = 0;

    // Machine learning
    private Model model;
    private boolean isRecording;
    private DescriptiveStatistics accelTime, accelX, accelY, accelZ;
    private static final int GESTURE_DURATION_SECS = 2;

    // Bluetooth
    private BluetoothLeUart uart;
    private TextView messages;
    private boolean isTraining;
    private String recentLabel;

    private static final String DEVICE_NAME = "Anthony Arduino BLE";

    private String lastCharacteristic = "";
    private StringBuilder buffer = new StringBuilder(50);

    private int colorIndex = 0;
    private int graphIndex = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Get the UI elements
        resultText = findViewById(R.id.resultText);
        gesture1CountText = findViewById(R.id.gesture1TextView);
        gesture2CountText = findViewById(R.id.gesture2TextView);
        gesture3CountText = findViewById(R.id.gesture3TextView);
        buttons = new ArrayList<>();
        buttons.add((Button) findViewById(R.id.gesture1Button));
        buttons.add((Button) findViewById(R.id.gesture2Button));
        buttons.add((Button) findViewById(R.id.gesture3Button));
        buttons.add((Button) findViewById(R.id.testButton));

        // Initialize the graphs
        initializeFilteredGraph();

        // Initialize data structures for gesture recording
        accelTime = new DescriptiveStatistics();
        accelX = new DescriptiveStatistics();
        accelY = new DescriptiveStatistics();
        accelZ = new DescriptiveStatistics();

        // Initialize the model
        model = new Model(this);

        // Get Bluetooth
        messages = findViewById(R.id.bluetoothText);
        messages.setMovementMethod(new ScrollingMovementMethod());
        uart = new BluetoothLeUart(getApplicationContext(), DEVICE_NAME);

        // Check permissions
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.BLUETOOTH,
                        Manifest.permission.BLUETOOTH_ADMIN,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
    }

    /**
     * Initializes the graph that will show filtered data
     */
    public void initializeFilteredGraph() {
        graphAccel = findViewById(R.id.graphAccel);
        graphAccel.getGridLabelRenderer().setGridStyle(GridLabelRenderer.GridStyle.HORIZONTAL);
        graphAccel.setBackgroundColor(Color.TRANSPARENT);
        graphAccel.getGridLabelRenderer().setHorizontalLabelsVisible(false);
        graphAccel.getGridLabelRenderer().setVerticalLabelsVisible(true);
        graphAccel.getViewport().setXAxisBoundsManual(true);
        graphAccel.getViewport().setYAxisBoundsManual(true);
        graphAccel.getViewport().setMinX(0);
        graphAccel.getViewport().setMaxX(graphXBounds);
        graphAccel.getViewport().setMinY(-graphYBounds);
        graphAccel.getViewport().setMaxY(graphYBounds);
        timeAccelX.setColor(graphColor[0]);
        timeAccelX.setThickness(10);
        graphAccel.addSeries(timeAccelX);
        timeAccelY.setColor(graphColor[1]);
        timeAccelY.setThickness(10);
        graphAccel.addSeries(timeAccelY);
        timeAccelZ.setColor(graphColor[2]);
        timeAccelZ.setThickness(10);
        graphAccel.addSeries(timeAccelZ);

        xfeatures = findViewById(R.id.xfeatures);
        xfeatures.getGridLabelRenderer().setGridStyle(GridLabelRenderer.GridStyle.HORIZONTAL);
        xfeatures.setBackgroundColor(Color.TRANSPARENT);
        xfeatures.getGridLabelRenderer().setHorizontalLabelsVisible(false);
        xfeatures.getGridLabelRenderer().setVerticalLabelsVisible(false);

        yfeatures = findViewById(R.id.yfeatures);
        yfeatures.getGridLabelRenderer().setGridStyle(GridLabelRenderer.GridStyle.HORIZONTAL);
        yfeatures.setBackgroundColor(Color.TRANSPARENT);
        yfeatures.getGridLabelRenderer().setHorizontalLabelsVisible(false);
        yfeatures.getGridLabelRenderer().setVerticalLabelsVisible(false);

        zfeatures = findViewById(R.id.zfeatures);
        zfeatures.getGridLabelRenderer().setGridStyle(GridLabelRenderer.GridStyle.HORIZONTAL);
        zfeatures.setBackgroundColor(Color.TRANSPARENT);
        zfeatures.getGridLabelRenderer().setHorizontalLabelsVisible(false);
        zfeatures.getGridLabelRenderer().setVerticalLabelsVisible(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateButtons(false);
        uart.registerCallback(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        uart.unregisterCallback(this);
        uart.disconnect();
    }

    public void connect(View v) {
        startScan();
    }

    private void startScan(){
        writeLine("Scanning for devices ...");
        uart.connectFirstAvailable();
    }


    /**
     * Records a gesture that is GESTURE_DURATION_SECS long
     */
    public void recordGesture(View v) {
        final View v2 = v;

        // Create the timer to start data collection
        Timer startTimer = new Timer();
        TimerTask startTask = new TimerTask() {
            @Override
            public void run() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        accelTime.clear(); accelX.clear(); accelY.clear(); accelZ.clear();
                        isRecording = true;
                        v2.setEnabled(false);
                    }
                });
            }
        };

        // Figure out which button got pressed to determine label
        switch (v.getId()) {
            case R.id.gesture1Button:
                recentLabel = model.outputClasses[0];
                isTraining = true;
                break;
            case R.id.gesture2Button:
                recentLabel = model.outputClasses[1];
                isTraining = true;
                break;
            case R.id.gesture3Button:
                recentLabel = model.outputClasses[2];
                isTraining = true;
                break;
            default:
                recentLabel = "?";
                isTraining = false;
                break;
        }


        // Create the timer to stop data collection
        Timer endTimer = new Timer();
        TimerTask endTask = new TimerTask() {
            @Override
            public void run() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        // Add the recent gesture to the train or test set
                        Double[] data;
                        isRecording = false;
                        data = model.addFeatures(accelX, accelY, accelZ, recentLabel, isTraining);

                        addFeatureDataToGraphs(data);

                        // Predict if the recent sample is for testing
                        if (!isTraining) {
                            String result = model.test();
                            resultText.setText("Result: "+result);
                        }

                        // Update number of samples shown
                        updateTrainDataCount();
                        v2.setEnabled(true);
                    }
                });
            }
        };

        // Start the timers
        startTimer.schedule(startTask, 0);
        endTimer.schedule(endTask, GESTURE_DURATION_SECS*1000);
    }

    /**
     * Trains the model as long as there is at least one sample per class
     */
    public void trainModel(View v) {
        // Make sure there is training data for each gesture
        for (int i=0; i<model.outputClasses.length; i++) {
            int gestureCount = model.getNumTrainSamples(i);
            if (gestureCount == 0) {
                Toast.makeText(getApplicationContext(), "Need examples for gesture" + (i+1),
                        Toast.LENGTH_SHORT).show();
                return;
            }
        }

        // Train
        model.train();
    }

    /**
     * Resets the training data of the model
     */
    public void clearModel(View v) {
        model.resetTrainingData();
        updateTrainDataCount();
        resultText.setText("Result: ");
    }

    /**
     * Updates the text boxes that show how many samples have been recorded
     */
    public void updateTrainDataCount() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                gesture1CountText.setText("Num samples: " + model.getNumTrainSamples(0));
                gesture2CountText.setText("Num samples: " + model.getNumTrainSamples(1));
                gesture3CountText.setText("Num samples: " + model.getNumTrainSamples(2));
            }
        });
    }

    /**
     * Writes a line to the messages textbox
     * @param text: the text that you want to write
     */
    private void writeLine(final CharSequence text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                messages.append(text);
                messages.append("\n");
            }
        });
    }

    /**
     * Enables or disables the buttons that send messages to the BLE device
     * @param enabled: whether the buttons should be enabled or disabled
     */
    private void updateButtons(boolean enabled){
        for (Button b: buttons) {
            b.setClickable(enabled);
            b.setEnabled(enabled);
        }
    }

    /**
     * Called when a UART device is discovered (after calling startScan)
     * @param device: the BLE device
     */
    @Override
    public void onDeviceFound(BluetoothDevice device) {
        writeLine("Found device : " + device.getAddress());
        writeLine("Waiting for a connection ...");
    }

    /**
     * Prints the devices information
     */
    @Override
    public void onDeviceInfoAvailable() {
        writeLine(uart.getDeviceInfo());
    }

    /**
     * Called when UART device is connected and ready to send/receive data
     * @param uart: the BLE UART object
     */
    @Override
    public void onConnected(BluetoothLeUart uart) {
        writeLine("Connected!");
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                updateButtons(true);
            }
        });
    }

    /**
     * Called when some error occurred which prevented UART connection from completing
     * @param uart: the BLE UART object
     */
    @Override
    public void onConnectFailed(BluetoothLeUart uart) {
        writeLine("Error connecting to device!");
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                updateButtons(false);
            }
        });
    }

    /**
     * Called when the UART device disconnected
     * @param uart: the BLE UART object
     */
    @Override
    public void onDisconnected(BluetoothLeUart uart) {
        writeLine("Disconnected!");
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                updateButtons(false);
            }
        });
    }

    /**
     * Called when data is received by the UART
     * @param uart: the BLE UART object
     * @param rx: the received characteristic
     */
    @Override
    public void onReceive(BluetoothLeUart uart, BluetoothGattCharacteristic rx) {
        String currentCharacteristic;
        int endOfPayloadIndex;

        // 1. Get rid of duplicates by checking if most recent stringValue is the same as the current stringValue
        // 2. Add stringValue to buffer
        // 3. If end of payload seen, then save the payload and flush the payload from the buffer
        // 4. Repeat

        currentCharacteristic = rx.getStringValue(0);
        //writeLine("Received:" + currentCharacteristic);

        if (currentCharacteristic.equals(lastCharacteristic))
        {
            return;
        }

        buffer.append(currentCharacteristic);
        endOfPayloadIndex = buffer.indexOf("|");
        //writeLine("Current buffer:" + buffer.toString());
        if (endOfPayloadIndex != -1)
        {
            Log.i("onReceive", "Flushing payload");
            //writeLine("Flushing payload:" + buffer.substring(0, endOfPayloadIndex));
            savePayload(buffer.substring(0, endOfPayloadIndex));
            Log.i("onReceive", "Finished flushing payload");

            buffer.delete(0, endOfPayloadIndex + 1);
            //writeLine("New buffer:" + buffer.toString());
        }

        lastCharacteristic = currentCharacteristic;
    }

    public void addFeatureDataToGraphs(Double[] data)
    {
        LineGraphSeries<DataPoint> xLineGraph = new LineGraphSeries<>();
        for (int i = 0; i < 5; i++)
        {
            DataPoint dataPointAccX = new DataPoint(i, data[i].doubleValue());
            xLineGraph.appendData(dataPointAccX, false, 5);
        }

        LineGraphSeries<DataPoint> yLineGraph = new LineGraphSeries<>();
        for (int i = 0; i < 5; i++)
        {
            DataPoint dataPointAccY = new DataPoint(i, data[i + 5].doubleValue());
            yLineGraph.appendData(dataPointAccY, false, 5);
        }

        LineGraphSeries<DataPoint> zLineGraph = new LineGraphSeries<>();
        for (int i = 0; i < 5; i++)
        {
            DataPoint dataPointAccZ = new DataPoint(i, data[i + 10].doubleValue());
            zLineGraph.appendData(dataPointAccZ, false, 5);
        }

        xLineGraph.setColor(graphColor[colorIndex]);
        xfeatures.addSeries(xLineGraph);
        yLineGraph.setColor(graphColor[colorIndex]);
        yfeatures.addSeries(yLineGraph);
        zLineGraph.setColor(graphColor[colorIndex]);
        zfeatures.addSeries(zLineGraph);

        colorIndex++;
        if (colorIndex == 5)
        {
            colorIndex = 0;
        }
    }

    // Takes a string payload for our data and saves it to
    public void savePayload(String payload) {
        int id, xindex, yindex, zindex;
        double ax, ay, az;
        long timestamp;

        id = payload.indexOf(':');
        xindex = id + 1 + 6;
        yindex = xindex + 6;
        zindex = yindex + 6;

        accelGraphXTime += 1;

        /*
        Log.d("savePayload", "id = " + id);
        Log.d("savePayload", "xindex = " + xindex);
        Log.d("savePayload", "yindex = " + yindex);
        Log.d("savePayload", "zindex = " + zindex);
        */

        Log.d("savePayload", "payload = " + payload);
        Log.d("savePayload", "time = " + payload.substring(0, id));
        Log.d("savePayload", "ax = " + payload.substring(id + 1, xindex));
        Log.d("savePayload", "ay = " + payload.substring(xindex, yindex));
        Log.d("savePayload", "az = " + payload.substring(yindex, zindex));


        timestamp = (long) Double.parseDouble(payload.substring(0, id));
        ax = Double.parseDouble(payload.substring(id + 1, xindex));
        ay = Double.parseDouble(payload.substring(xindex, yindex));
        az = Double.parseDouble(payload.substring(yindex, zindex));

        // Add the original data to the graph
        final DataPoint dataPointAccX = new DataPoint(accelGraphXTime, ax);
        final DataPoint dataPointAccY = new DataPoint(accelGraphXTime, ay);
        final DataPoint dataPointAccZ = new DataPoint(accelGraphXTime, az);

        runOnUiThread(new Runnable() {
            @Override
            public void run()
            {
                timeAccelX.appendData(dataPointAccX, true, MAX_DATA_POINTS_UI_IMU);
                timeAccelY.appendData(dataPointAccY, true, MAX_DATA_POINTS_UI_IMU);
                timeAccelZ.appendData(dataPointAccZ, true, MAX_DATA_POINTS_UI_IMU);

                // Advance the graph
                graphAccel.getViewport().setMinX(accelGraphXTime-graphXBounds);
                graphAccel.getViewport().setMaxX(accelGraphXTime);
            }
        });


        if (isRecording)
        {
            accelTime.addValue(timestamp);
            accelX.addValue(ax);
            accelY.addValue(ay);
            accelZ.addValue(az);
        }
    }
}
