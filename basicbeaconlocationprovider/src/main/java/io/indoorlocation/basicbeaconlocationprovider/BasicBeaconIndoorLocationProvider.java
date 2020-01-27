package io.indoorlocation.basicbeaconlocationprovider;

//import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.location.Location;
import android.os.RemoteException;
import android.util.Log;

import com.nexenio.bleindoorpositioning.ble.advertising.AdvertisingPacket;
import com.nexenio.bleindoorpositioning.ble.beacon.Beacon;
import com.nexenio.bleindoorpositioning.ble.beacon.BeaconManager;
import com.nexenio.bleindoorpositioning.ble.beacon.IBeacon;
import com.nexenio.bleindoorpositioning.location.Location;
import com.nexenio.bleindoorpositioning.location.provider.IBeaconLocationProvider;
import com.polidea.rxandroidble.RxBleClient;
import com.polidea.rxandroidble.scan.ScanResult;
import com.polidea.rxandroidble.scan.ScanSettings;

import org.altbeacon.beacon.Beacon;
import org.altbeacon.beacon.BeaconConsumer;
import org.altbeacon.beacon.BeaconManager;
import org.altbeacon.beacon.BeaconParser;
import org.altbeacon.beacon.RangeNotifier;
import org.altbeacon.beacon.Region;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import io.indoorlocation.core.IndoorLocation;
import io.indoorlocation.core.IndoorLocationProvider;
import io.indoorlocation.core.IndoorLocationProviderListener;
import io.indoorlocation.gps.GPSIndoorLocationProvider;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class BasicBeaconIndoorLocationProvider extends IndoorLocationProvider implements BeaconConsumer, IndoorLocationProviderListener {

    private BeaconManager beaconManager;
    private Context context;
    private OkHttpClient okHttpClient;
    private String apiKey;
    private Map<String,LatLngFloor> locationByBeaconUniqId;
    private Set<String> knownUuids;
    private GPSIndoorLocationProvider gpsIndoorLocationProvider;
    private boolean isStarted = false;
    private boolean beaconManagerIsConnected = false;
    private IndoorLocation lastLocationFetch;

    public BasicBeaconIndoorLocationProvider(Context context, String apiKey, GPSIndoorLocationProvider gpsIndoorLocationProvider) {
        super();
        this.context = context;
        this.apiKey = apiKey;
        this.locationByBeaconUniqId = new HashMap<>();
        this.knownUuids = new HashSet<>();
        this.okHttpClient = new OkHttpClient.Builder().build();
        this.gpsIndoorLocationProvider = gpsIndoorLocationProvider;
        this.gpsIndoorLocationProvider.addListener(this);
        this.beaconManager = BeaconManager.getInstanceForApplication(context);
        this.beaconManager.getBeaconParsers().add(0, new BeaconParser().setBeaconLayout("m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24,d:25-25"));
    }

    public BasicBeaconIndoorLocationProvider(Context context, String apiKey, GPSIndoorLocationProvider gpsIndoorLocationProvider, OkHttpClient mapwizeClient) {
        super();
        this.context = context;
        this.apiKey = apiKey;
        this.okHttpClient = mapwizeClient;
        this.gpsIndoorLocationProvider = gpsIndoorLocationProvider;
        this.gpsIndoorLocationProvider.addListener(this);
        this.locationByBeaconUniqId = new HashMap<>();
        this.knownUuids = new HashSet<>();
        this.beaconManager = BeaconManager.getInstanceForApplication(context);
        this.beaconManager.getBeaconParsers().add(0, new BeaconParser().setBeaconLayout("m:2-3=0215,i:4-19,i:20-21,i:22-23,p:24-24,d:25-25"));
    }

    private void processScanResult(ScanResult scanResult) {
        String macAddress = scanResult.getBleDevice().getMacAddress();
        byte[] advertisingData = scanResult.getScanRecord().getBytes();
        int rssi = scanResult.getRssi();
        BeaconManager.processAdvertisingData(macAddress, advertisingData, rssi);
    }

    public void fetchBeaconData(IndoorLocation indoorLocation) {
        Double latitudeMin = indoorLocation.getLatitude() - 0.5;
        Double latitudeMax = indoorLocation.getLatitude() + 0.5;
        Double longitudeMin = indoorLocation.getLongitude() - 0.5;
        Double longitudeMax = indoorLocation.getLongitude() + 0.5;
        Log.d("debug", "Starting location fetching");
        Request request = new Request.Builder()
                .url("https://www.mapwize.io/api/v1/beacons/?api_key="+apiKey+"&type=ibeacon&latitudeMin=" + latitudeMin + "&latitudeMax=" + latitudeMax + "&longitudeMin=" + longitudeMin + "&longitudeMax=" + longitudeMax)
                .build();

        this.okHttpClient.newCall(request).enqueue(new Callback() {
             @Override
             public void onFailure(Call call, IOException e) {
                 dispatchOnProviderError(new Error("Error while fetching data from server : " + e.getMessage()));
             }

             @Override
             public void onResponse(Call call, Response response) throws IOException {
                 if (response.isSuccessful()) {
                     try {
                         JSONArray jsonArray = new JSONArray(response.body().string());
                         for (int i = 0; i < jsonArray.length(); i++) {
                             JSONObject object = jsonArray.getJSONObject(i);
                             JSONObject properties = object.optJSONObject("properties");
                             if (properties != null) {
                                 String uuid = properties.optString("uuid");
                                 Integer major = properties.optInt("major");
                                 Integer minor = properties.optInt("minor");
                                 JSONObject locationObject = object.getJSONObject("location");
                                 Double latitude = locationObject.getDouble("lat");
                                 Double longitude = locationObject.getDouble("lon");
                                 //Double floor = object.getDouble("floor");
                                 Double floor = 2.0;
                                 LatLngFloor latLngFloor = new LatLngFloor(latitude, longitude, floor);
                                 locationByBeaconUniqId.put((uuid+'-'+major+'-'+minor), latLngFloor);
                                 knownUuids.add(uuid);
                                 Log.d("debug", "success: " + uuid);
                             }
                         }
                     } catch (JSONException e) {
                         Log.d("debug", "failure case 1");
                         e.printStackTrace();
                     }
                     onFetchBeaconDataEnded();
                 }
                 else {
                     Log.d("debug", "failure case 2");
                     dispatchOnProviderError(new Error("Error while fetching data from server : " + response.code()));
                 }
             }
         });
        Log.d("debug", "hello world");
        lastLocationFetch = indoorLocation;
    }

    private void onFetchBeaconDataEnded() {
        try {
            this.beaconManager.startRangingBeaconsInRegion(new Region("AllRegion", null, null, null));
        } catch (RemoteException e) {    }
    }

    @Override
    public boolean supportsFloor() {
        return true;
    }

    @Override
    public void start() {
        if (this.gpsIndoorLocationProvider != null) {
            this.gpsIndoorLocationProvider.start();
        }
        this.beaconManager.bind(this);
        this.isStarted = true;
    }

    @Override
    public void stop() {
        if (this.gpsIndoorLocationProvider != null) {
            this.gpsIndoorLocationProvider.stop();
        }
        this.beaconManager.unbind(this);
        this.isStarted = false;
    }

    @Override
    public boolean isStarted() {
        return this.isStarted;
    }

    /*
    BeaconConsumer
     */
    @Override
    public void onBeaconServiceConnect() {
        this.beaconManagerIsConnected = true;
        this.beaconManager.addRangeNotifier(new RangeNotifier() {
            @Override
            public void didRangeBeaconsInRegion(Collection<Beacon> beacons, Region region) {
                if (beacons.size() > 0) {
                    Beacon beacon = (Beacon)beacons.toArray()[0];
                    String uniqId = (beacon.getId1()+"-"+beacon.getId2()+"-"+beacon.getId3()).toUpperCase();
                    LatLngFloor llf = locationByBeaconUniqId.get(uniqId);
                    if (llf != null) {
                        IndoorLocation indoorLocation = new IndoorLocation(getName(), llf.getLatitude(), llf.getLongitude(), llf.getFloor(), System.currentTimeMillis());
                        dispatchIndoorLocationChange(indoorLocation);
                    }

                }
            }
        });


    }

    @Override
    public Context getApplicationContext() {
        return context;
    }

    @Override
    public void unbindService(ServiceConnection serviceConnection) {

    }

    @Override
    public boolean bindService(Intent intent, ServiceConnection serviceConnection, int i) {
        return false;
    }

    /*
    GPSIndoorLocationProviderListener
     */
    @Override
    public void onProviderStarted() {

    }

    @Override
    public void onProviderStopped() {

    }

    @Override
    public void onProviderError(Error error) {

    }

    @Override
    public void onIndoorLocationChange(IndoorLocation indoorLocation) {
        Log.d("fug", "AAAAAAAAAAAAAA");
        if (lastLocationFetch == null || distanceExceeded(lastLocationFetch, indoorLocation)) {
            this.fetchBeaconData(indoorLocation);
        }
    }

    private boolean distanceExceeded(IndoorLocation lastLocation, IndoorLocation newLocation) {
        final int R = 6371; // Radius of the earth

        double latDistance = Math.toRadians(newLocation.getLatitude() - lastLocation.getLatitude());
        double lonDistance = Math.toRadians(newLocation.getLongitude() - lastLocation.getLongitude());
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lastLocation.getLatitude())) * Math.cos(Math.toRadians(newLocation.getLatitude()))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        double distance = R * c * 1000; // convert to meters

        if (Math.sqrt(Math.pow(distance, 2)) > 500) {
            return true;
        }
        return false;

    }
}
