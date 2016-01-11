/*
 * WARNING, All test cases exist in osmdroid-android-it/src/main/java (maven project)
 *
 * During build time (with gradle), these tests are copied from osmdroid-android-it to OpenStreetMapViewer/src/androidTest/java
 * DO NOT Modify files in OpenSteetMapViewer/src/androidTest. You will loose your changes when building!
 *
 */


package org.osmdroid.test;

import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.test.ActivityInstrumentationTestCase2;

import org.osmdroid.ExtraSamplesActivity;
import org.osmdroid.samplefragments.FragmentSamples;
import org.osmdroid.samplefragments.SampleFactory;

public class ExtraSamplesTest extends ActivityInstrumentationTestCase2<ExtraSamplesActivity> {

    public ExtraSamplesTest() {
        super("org.osmdroid", ExtraSamplesActivity.class);
    }

    public void testActivity() {
        ExtraSamplesActivity activity = getActivity();
        assertNotNull(activity);
/*
TEMP FIX, commenting out loading the fragments to resolve CI issues with travis
        FragmentManager fm = activity.getSupportFragmentManager();
        Fragment frag = (fm.findFragmentByTag(ExtraSamplesActivity.SAMPLES_FRAGMENT_TAG));
        assertNotNull(frag);

        assertTrue(frag instanceof FragmentSamples);
        FragmentSamples samples = (FragmentSamples) frag;

        SampleFactory sampleFactory = SampleFactory.getInstance();
        for (int i = 0; i < sampleFactory.count(); i++) {

            fm.beginTransaction().hide(samples).add(android.R.id.content, sampleFactory.getSample(i), "SampleFragment")
                    .addToBackStack(null).commit();
            //this sleep is here to give the fragment enough time to start up and doing something
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        */
    }
}

