package com.codaminds.xcpecleaner;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Boot guard, part 1: after every boot, schedule RecheckJob. Nothing periodic; the job runs once
 * a minute or so after boot, rescans, re-removes what the firmware brought back and notifies.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "xcpecleaner";

    @Override public void onReceive(Context ctx, Intent i) {
        if (i == null || !Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) return;
        try {
            RecheckJob.cancelAfterRemove(ctx);
            RecheckJob.schedule(ctx);
            Log.i(TAG, "boot: recheck scheduled");
        } catch (Exception e) {
            Log.w(TAG, "boot: could not schedule recheck: " + e);
        }
    }
}
