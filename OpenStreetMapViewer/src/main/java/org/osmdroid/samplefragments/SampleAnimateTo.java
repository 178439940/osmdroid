package org.osmdroid.samplefragments;

import android.widget.Toast;

import org.osmdroid.util.GeoPoint;

import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

/**
 * https://github.com/osmdroid/osmdroid/issues/264
 * extends gridlines to
 * Created by alex on 2/22/16.
 */
public class SampleAnimateTo extends SampleGridlines {
    Random rand = new Random();

    @Override
    public String getSampleTitle() {
        return "Animate To";
    }

    @Override
    public void addOverlays() {
        super.addOverlays();
        TimerTask task = new TimerTask() {
            @Override
            public void run() {
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        double lat=rand.nextDouble()*180 -90;
                        double lon=rand.nextDouble()*360-180;
                        mMapView.getController().animateTo(new GeoPoint(lat,lon));
                        Toast.makeText(getActivity(), "Animate to " + lat + "," + lon,Toast.LENGTH_LONG).show();
                    }
                });
            }
        };
        Timer t = new Timer();
        t.schedule(task,2000,5000);


    }
}
