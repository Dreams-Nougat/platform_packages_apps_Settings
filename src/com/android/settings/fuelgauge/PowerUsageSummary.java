/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.fuelgauge;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.drawable.Drawable;
import android.hardware.SensorManager;
import android.os.BatteryStats;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.BatteryStats.Uid;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.util.Log;
import android.util.SparseArray;
import android.view.Menu;
import android.view.MenuItem;

import com.android.internal.app.IBatteryStats;
import com.android.internal.os.BatteryStatsImpl;
import com.android.internal.os.PowerProfile;
import com.android.settings.R;
import com.android.settings.fuelgauge.PowerUsageDetail.DrainType;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Displays a list of apps and subsystems that consume power, ordered by how much power was
 * consumed since the last time it was unplugged.
 */
public class PowerUsageSummary extends PreferenceActivity implements Runnable {

    private static final boolean DEBUG = false;

    private static final String TAG = "PowerUsageSummary";

    private static final int MENU_STATS_TYPE = Menu.FIRST;
    private static final int MENU_STATS_REFRESH = Menu.FIRST + 1;

    IBatteryStats mBatteryInfo;
    BatteryStatsImpl mStats;
    private final List<BatterySipper> mUsageList = new ArrayList<BatterySipper>();

    private PreferenceGroup mAppListGroup;

    private int mStatsType = BatteryStats.STATS_UNPLUGGED;

    private static final int MIN_POWER_THRESHOLD = 5;
    private static final int MAX_ITEMS_TO_LIST = 10;

    private long mStatsPeriod = 0;
    private double mMaxPower = 1;
    private double mTotalPower;
    private PowerProfile mPowerProfile;

    private final HashMap<String,UidToDetail> mUidCache = new HashMap<String,UidToDetail>();

    /** Queue for fetching name and icon for an application */
    private final ArrayList<BatterySipper> mRequestQueue = new ArrayList<BatterySipper>();
    private Thread mRequestThread;
    private boolean mAbort;
    
    static class UidToDetail {
        String name;
        String packageName;
        Drawable icon;
    }

    @Override
    protected void onCreate(final Bundle icicle) {
        super.onCreate(icicle);

        addPreferencesFromResource(R.xml.power_usage_summary);
        mBatteryInfo = IBatteryStats.Stub.asInterface(
                ServiceManager.getService("batteryinfo"));
        mAppListGroup = (PreferenceGroup) findPreference("app_list");
        mPowerProfile = new PowerProfile(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mAbort = false;
        refreshStats();
    }

    @Override
    protected void onPause() {
        synchronized (mRequestQueue) {
            mAbort = true;
        }
        mHandler.removeMessages(MSG_UPDATE_NAME_ICON);
        super.onPause();
    }

    @Override
    public boolean onPreferenceTreeClick(final PreferenceScreen preferenceScreen, final Preference preference) {
        final PowerGaugePreference pgp = (PowerGaugePreference) preference;
        final BatterySipper sipper = pgp.getInfo();
        final Intent intent = new Intent(this, PowerUsageDetail.class);
        intent.putExtra(PowerUsageDetail.EXTRA_TITLE, sipper.name);
        intent.putExtra(PowerUsageDetail.EXTRA_PERCENT, (int)
                Math.ceil(sipper.getSortValue() * 100 / mTotalPower));
        intent.putExtra(PowerUsageDetail.EXTRA_GAUGE, (int)
                Math.ceil(sipper.getSortValue() * 100 / mMaxPower));
        intent.putExtra(PowerUsageDetail.EXTRA_USAGE_DURATION, mStatsPeriod);
        intent.putExtra(PowerUsageDetail.EXTRA_ICON_PACKAGE, sipper.defaultPackageName);
        intent.putExtra(PowerUsageDetail.EXTRA_ICON_ID, sipper.iconId);
        intent.putExtra(PowerUsageDetail.EXTRA_NO_COVERAGE, sipper.noCoveragePercent);
        if (sipper.uidObj != null) {
            intent.putExtra(PowerUsageDetail.EXTRA_UID, sipper.uidObj.getUid());
        }
        intent.putExtra(PowerUsageDetail.EXTRA_DRAIN_TYPE, sipper.drainType);

        int[] types;
        double[] values;
        switch (sipper.drainType) {
            case APP:
            {
                final Uid uid = sipper.uidObj;
                types = new int[] {
                    R.string.usage_type_cpu,
                    R.string.usage_type_cpu_foreground,
                    R.string.usage_type_gps,
                    R.string.usage_type_data_send,
                    R.string.usage_type_data_recv,
                    R.string.usage_type_audio,
                    R.string.usage_type_video,
                };
                values = new double[] {
                    sipper.cpuTime,
                    sipper.cpuFgTime,
                    sipper.gpsTime,
                    uid != null? uid.getTcpBytesSent(mStatsType) : 0,
                    uid != null? uid.getTcpBytesReceived(mStatsType) : 0,
                    0,
                    0
                };

                Writer result = new StringWriter();
                PrintWriter printWriter = new PrintWriter(result);
                mStats.dumpLocked(printWriter, "", mStatsType, uid.getUid());
                intent.putExtra(PowerUsageDetail.EXTRA_REPORT_DETAILS, result.toString());
                
                result = new StringWriter();
                printWriter = new PrintWriter(result);
                mStats.dumpCheckinLocked(printWriter, mStatsType, uid.getUid());
                intent.putExtra(PowerUsageDetail.EXTRA_REPORT_CHECKIN_DETAILS, result.toString());
            }
            break;
            case CELL:
            {
                types = new int[] {
                    R.string.usage_type_on_time,
                    R.string.usage_type_no_coverage
                };
                values = new double[] {
                    sipper.usageTime,
                    sipper.noCoveragePercent
                };
            }
            break;
            default:
            {
                types = new int[] {
                    R.string.usage_type_on_time
                };
                values = new double[] {
                    sipper.usageTime
                };
            }
        }
        intent.putExtra(PowerUsageDetail.EXTRA_DETAIL_TYPES, types);
        intent.putExtra(PowerUsageDetail.EXTRA_DETAIL_VALUES, values);
        startActivity(intent);

        return super.onPreferenceTreeClick(preferenceScreen, preference);
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        if (DEBUG) {
            menu.add(0, MENU_STATS_TYPE, 0, R.string.menu_stats_total)
                    .setIcon(com.android.internal.R.drawable.ic_menu_info_details)
                    .setAlphabeticShortcut('t');
        }
        menu.add(0, MENU_STATS_REFRESH, 0, R.string.menu_stats_refresh)
                .setIcon(com.android.internal.R.drawable.ic_menu_refresh)
                .setAlphabeticShortcut('r');
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(final Menu menu) {
        if (DEBUG) {
            menu.findItem(MENU_STATS_TYPE).setTitle(mStatsType == BatteryStats.STATS_TOTAL
                    ? R.string.menu_stats_unplugged
                    : R.string.menu_stats_total);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
            case MENU_STATS_TYPE:
                if (mStatsType == BatteryStats.STATS_TOTAL) {
                    mStatsType = BatteryStats.STATS_UNPLUGGED;
                } else {
                    mStatsType = BatteryStats.STATS_TOTAL;
                }
                refreshStats();
                return true;
            case MENU_STATS_REFRESH:
                mStats = null;
                refreshStats();
                return true;
            default:
                return false;
        }
    }

    private void refreshStats() {
        if (mStats == null) {
            load();
        }
        mMaxPower = 0;
        mTotalPower = 0;

        mAppListGroup.removeAll();
        mUsageList.clear();
        processAppUsage();
        processMiscUsage();

        mAppListGroup.setOrderingAsAdded(false);

        Collections.sort(mUsageList);
        for (final BatterySipper sipper : mUsageList) {
            if (sipper.getSortValue() < MIN_POWER_THRESHOLD) continue;
            final double percentOfTotal =  ((sipper.getSortValue() / mTotalPower) * 100);
            if (percentOfTotal < 1) continue;
            final PowerGaugePreference pref = new PowerGaugePreference(this, sipper.getIcon(), sipper);
            final double percentOfMax = (sipper.getSortValue() * 100) / mMaxPower;
            sipper.percent = percentOfTotal;
            pref.setTitle(sipper.name);
            pref.setPercent(percentOfTotal);
            pref.setOrder(Integer.MAX_VALUE - (int) sipper.getSortValue()); // Invert the order
            pref.setGaugeValue(percentOfMax);
            if (sipper.uidObj != null) {
                pref.setKey(Integer.toString(sipper.uidObj.getUid()));
            }
            mAppListGroup.addPreference(pref);
            if (mAppListGroup.getPreferenceCount() > MAX_ITEMS_TO_LIST) break;
        }
        if (DEBUG) setTitle("Battery total uAh = " + ((mTotalPower * 1000) / 3600));
        synchronized (mRequestQueue) {
            if (!mRequestQueue.isEmpty()) {
                if (mRequestThread == null) {
                    mRequestThread = new Thread(this, "BatteryUsage Icon Loader");
                    mRequestThread.setPriority(Thread.MIN_PRIORITY);
                    mRequestThread.start();
                }
                mRequestQueue.notify();
            }
        }
    }

    private void updateStatsPeriod(final long duration) {
        final String durationString = Utils.formatElapsedTime(this, duration / 1000);
        final String label = getString(mStats.isOnBattery()
                ? R.string.battery_stats_duration
                : R.string.battery_stats_last_duration, durationString);
        setTitle(label);
    }

    private void processAppUsage() {
        final SensorManager sensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
        final int which = mStatsType;
        final int speedSteps = mPowerProfile.getNumSpeedSteps();
        final double[] powerCpuNormal = new double[speedSteps];
        final long[] cpuSpeedStepTimes = new long[speedSteps];
        for (int p = 0; p < speedSteps; p++) {
            powerCpuNormal[p] = mPowerProfile.getAveragePower(PowerProfile.POWER_CPU_ACTIVE, p);
        }
        final double averageCostPerByte = getAverageDataCost();
        final long uSecTime = mStats.computeBatteryRealtime(SystemClock.elapsedRealtime() * 1000, which);
        mStatsPeriod = uSecTime;
        updateStatsPeriod(uSecTime);
        final SparseArray<? extends Uid> uidStats = mStats.getUidStats();
        final int NU = uidStats.size();
        for (int iu = 0; iu < NU; iu++) {
            final Uid u = uidStats.valueAt(iu);
            double power = 0;
            double highestDrain = 0;
            String packageWithHighestDrain = null;
            //mUsageList.add(new AppUsage(u.getUid(), new double[] {power}));
            final Map<String, ? extends BatteryStats.Uid.Proc> processStats = u.getProcessStats();
            long cpuTime = 0;
            long cpuFgTime = 0;
            long gpsTime = 0;
            if (processStats.size() > 0) {
                // Process CPU time
                for (final Map.Entry<String, ? extends BatteryStats.Uid.Proc> ent
                        : processStats.entrySet()) {
                    if (DEBUG) Log.i(TAG, "Process name = " + ent.getKey());
                    final Uid.Proc ps = ent.getValue();
                    final long userTime = ps.getUserTime(which);
                    final long systemTime = ps.getSystemTime(which);
                    final long foregroundTime = ps.getForegroundTime(which);
                    cpuFgTime += foregroundTime * 10; // convert to millis
                    final long tmpCpuTime = (userTime + systemTime) * 10; // convert to millis
                    int totalTimeAtSpeeds = 0;
                    // Get the total first
                    for (int step = 0; step < speedSteps; step++) {
                        cpuSpeedStepTimes[step] = ps.getTimeAtCpuSpeedStep(step, which);
                        totalTimeAtSpeeds += cpuSpeedStepTimes[step];
                    }
                    if (totalTimeAtSpeeds == 0) totalTimeAtSpeeds = 1;
                    // Then compute the ratio of time spent at each speed
                    double processPower = 0;
                    for (int step = 0; step < speedSteps; step++) {
                        final double ratio = (double) cpuSpeedStepTimes[step] / totalTimeAtSpeeds;
                        processPower += ratio * tmpCpuTime * powerCpuNormal[step];
                    }
                    cpuTime += tmpCpuTime;
                    power += processPower;
                    if (highestDrain < processPower) {
                        highestDrain = processPower;
                        packageWithHighestDrain = ent.getKey();
                    }

                }
                if (DEBUG) Log.i(TAG, "Max drain of " + highestDrain 
                        + " by " + packageWithHighestDrain);
            }
            if (cpuFgTime > cpuTime) {
                if (DEBUG && cpuFgTime > cpuTime + 10000) {
                    Log.i(TAG, "WARNING! Cputime is more than 10 seconds behind Foreground time");
                }
                cpuTime = cpuFgTime; // Statistics may not have been gathered yet.
            }
            power /= 1000;

            // Add cost of data traffic
            power += (u.getTcpBytesReceived(mStatsType) + u.getTcpBytesSent(mStatsType))
                    * averageCostPerByte;

            // Process Sensor usage
            final Map<Integer, ? extends BatteryStats.Uid.Sensor> sensorStats = u.getSensorStats();
            for (final Map.Entry<Integer, ? extends BatteryStats.Uid.Sensor> sensorEntry
                    : sensorStats.entrySet()) {
                final Uid.Sensor sensor = sensorEntry.getValue();
                final int sensorType = sensor.getHandle();
                final BatteryStats.Timer timer = sensor.getSensorTime();
                final long sensorTime = timer.getTotalTimeLocked(uSecTime, which) / 1000;
                double multiplier = 0;
                switch (sensorType) {
                    case Uid.Sensor.GPS:
                        multiplier = mPowerProfile.getAveragePower(PowerProfile.POWER_GPS_ON);
                        gpsTime = sensorTime;
                        break;
                    default:
                        final android.hardware.Sensor sensorData =
                                sensorManager.getDefaultSensor(sensorType);
                        if (sensorData != null) {
                            multiplier = sensorData.getPower();
                            if (DEBUG) {
                                Log.i(TAG, "Got sensor " + sensorData.getName() + " with power = "
                                        + multiplier);
                            }
                        }
                }
                power += (multiplier * sensorTime) / 1000;
            }

            // Add the app to the list if it is consuming power
            if (power != 0) {
                final BatterySipper app = new BatterySipper(packageWithHighestDrain, DrainType.APP, 0, u,
                        new double[] {power});
                app.cpuTime = cpuTime;
                app.gpsTime = gpsTime;
                app.cpuFgTime = cpuFgTime;
                mUsageList.add(app);
            }
            if (power > mMaxPower) mMaxPower = power;
            mTotalPower += power;
            if (DEBUG) Log.i(TAG, "Added power = " + power);
        }
    }

    private void addPhoneUsage(final long uSecNow) {
        final long phoneOnTimeMs = mStats.getPhoneOnTime(uSecNow, mStatsType) / 1000;
        final double phoneOnPower = mPowerProfile.getAveragePower(PowerProfile.POWER_RADIO_ACTIVE)
                * phoneOnTimeMs / 1000;
        addEntry(getString(R.string.power_phone), DrainType.PHONE, phoneOnTimeMs,
                R.drawable.ic_settings_voice_calls, phoneOnPower);
    }

    private void addScreenUsage(final long uSecNow) {
        double power = 0;
        final long screenOnTimeMs = mStats.getScreenOnTime(uSecNow, mStatsType) / 1000;
        power += screenOnTimeMs * mPowerProfile.getAveragePower(PowerProfile.POWER_SCREEN_ON);
        final double screenFullPower =
                mPowerProfile.getAveragePower(PowerProfile.POWER_SCREEN_FULL);
        for (int i = 0; i < BatteryStats.NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            final double screenBinPower = screenFullPower * (i + 0.5f)
                    / BatteryStats.NUM_SCREEN_BRIGHTNESS_BINS;
            final long brightnessTime = mStats.getScreenBrightnessTime(i, uSecNow, mStatsType) / 1000;
            power += screenBinPower * brightnessTime;
            if (DEBUG) {
                Log.i(TAG, "Screen bin power = " + (int) screenBinPower + ", time = "
                        + brightnessTime);
            }
        }
        power /= 1000; // To seconds
        addEntry(getString(R.string.power_screen), DrainType.SCREEN, screenOnTimeMs,
                R.drawable.ic_settings_display, power);
    }

    private void addRadioUsage(final long uSecNow) {
        double power = 0;
        final int BINS = BatteryStats.NUM_SIGNAL_STRENGTH_BINS;
        long signalTimeMs = 0;
        for (int i = 0; i < BINS; i++) {
            final long strengthTimeMs = mStats.getPhoneSignalStrengthTime(i, uSecNow, mStatsType) / 1000;
            power += strengthTimeMs / 1000
                    * mPowerProfile.getAveragePower(PowerProfile.POWER_RADIO_ON, i);
            signalTimeMs += strengthTimeMs;
        }
        final long scanningTimeMs = mStats.getPhoneSignalScanningTime(uSecNow, mStatsType) / 1000;
        power += scanningTimeMs / 1000 * mPowerProfile.getAveragePower(
                PowerProfile.POWER_RADIO_SCANNING);
        final BatterySipper bs =
                addEntry(getString(R.string.power_cell), DrainType.CELL, signalTimeMs,
                R.drawable.ic_settings_cell_standby, power);
        if (signalTimeMs != 0) {
            bs.noCoveragePercent = mStats.getPhoneSignalStrengthTime(0, uSecNow, mStatsType)
                    / 1000 * 100.0 / signalTimeMs;
        }
    }

    private void addWiFiUsage(final long uSecNow) {
        final long onTimeMs = mStats.getWifiOnTime(uSecNow, mStatsType) / 1000;
        final long runningTimeMs = mStats.getWifiRunningTime(uSecNow, mStatsType) / 1000;
        final double wifiPower = (onTimeMs * 0 /* TODO */
                * mPowerProfile.getAveragePower(PowerProfile.POWER_WIFI_ON)
            + runningTimeMs * mPowerProfile.getAveragePower(PowerProfile.POWER_WIFI_ON)) / 1000;
        addEntry(getString(R.string.power_wifi), DrainType.WIFI, runningTimeMs,
                R.drawable.ic_settings_wifi, wifiPower);
    }

    private void addIdleUsage(final long uSecNow) {
        final long idleTimeMs = (uSecNow - mStats.getScreenOnTime(uSecNow, mStatsType)) / 1000;
        final double idlePower = (idleTimeMs * mPowerProfile.getAveragePower(PowerProfile.POWER_CPU_IDLE))
                / 1000;
        addEntry(getString(R.string.power_idle), DrainType.IDLE, idleTimeMs,
                R.drawable.ic_settings_phone_idle, idlePower);
    }

    private void addBluetoothUsage(final long uSecNow) {
        final long btOnTimeMs = mStats.getBluetoothOnTime(uSecNow, mStatsType) / 1000;
        double btPower = btOnTimeMs * mPowerProfile.getAveragePower(PowerProfile.POWER_BLUETOOTH_ON)
                / 1000;
        final int btPingCount = mStats.getBluetoothPingCount();
        btPower += (btPingCount
                * mPowerProfile.getAveragePower(PowerProfile.POWER_BLUETOOTH_AT_CMD)) / 1000;

        addEntry(getString(R.string.power_bluetooth), DrainType.BLUETOOTH, btOnTimeMs,
                R.drawable.ic_settings_bluetooth, btPower);
    }

    private double getAverageDataCost() {
        final long WIFI_BPS = 1000000; // TODO: Extract average bit rates from system 
        final long MOBILE_BPS = 200000; // TODO: Extract average bit rates from system
        final double WIFI_POWER = mPowerProfile.getAveragePower(PowerProfile.POWER_WIFI_ACTIVE)
                / 3600;
        final double MOBILE_POWER = mPowerProfile.getAveragePower(PowerProfile.POWER_RADIO_ACTIVE)
                / 3600;
        final long mobileData = mStats.getMobileTcpBytesReceived(mStatsType) +
                mStats.getMobileTcpBytesSent(mStatsType);
        final long wifiData = mStats.getTotalTcpBytesReceived(mStatsType) +
                mStats.getTotalTcpBytesSent(mStatsType) - mobileData;
        final long radioDataUptimeMs = mStats.getRadioDataUptime() / 1000;
        final long mobileBps = radioDataUptimeMs != 0
                ? mobileData * 8 * 1000 / radioDataUptimeMs
                : MOBILE_BPS;

        final double mobileCostPerByte = MOBILE_POWER / (mobileBps / 8);
        final double wifiCostPerByte = WIFI_POWER / (WIFI_BPS / 8);
        if (wifiData + mobileData != 0) {
            return (mobileCostPerByte * mobileData + wifiCostPerByte * wifiData)
                    / (mobileData + wifiData);
        } else {
            return 0;
        }
    }

    private void processMiscUsage() {
        final int which = mStatsType;
        final long uSecTime = SystemClock.elapsedRealtime() * 1000;
        final long uSecNow = mStats.computeBatteryRealtime(uSecTime, which);
        final long timeSinceUnplugged = uSecNow;
        if (DEBUG) {
            Log.i(TAG, "Uptime since last unplugged = " + (timeSinceUnplugged / 1000));
        }

        addPhoneUsage(uSecNow);
        addScreenUsage(uSecNow);
        addWiFiUsage(uSecNow);
        addBluetoothUsage(uSecNow);
        addIdleUsage(uSecNow); // Not including cellular idle power
        addRadioUsage(uSecNow);
    }

    private BatterySipper addEntry(final String label, final DrainType drainType, final long time, final int iconId,
            final double power) {
        if (power > mMaxPower) mMaxPower = power;
        mTotalPower += power;
        final BatterySipper bs = new BatterySipper(label, drainType, iconId, null, new double[] {power});
        bs.usageTime = time;
        bs.iconId = iconId;
        mUsageList.add(bs);
        return bs;
    }

    private void load() {
        try {
            final byte[] data = mBatteryInfo.getStatistics();
            final Parcel parcel = Parcel.obtain();
            parcel.unmarshall(data, 0, data.length);
            parcel.setDataPosition(0);
            mStats = com.android.internal.os.BatteryStatsImpl.CREATOR
                    .createFromParcel(parcel);
        } catch (final RemoteException e) {
            Log.e(TAG, "RemoteException:", e);
        }
    }

    class BatterySipper implements Comparable<BatterySipper> {
        String name;
        Drawable icon;
        int iconId; // For passing to the detail screen.
        Uid uidObj;
        double value;
        double[] values;
        DrainType drainType;
        long usageTime;
        long cpuTime;
        long gpsTime;
        long cpuFgTime;
        double percent;
        double noCoveragePercent;
        String defaultPackageName;

        BatterySipper(final String label, final DrainType drainType, final int iconId, final Uid uid, final double[] values) {
            this.values = values;
            name = label;
            this.drainType = drainType;
            if (iconId > 0) {
                icon = getResources().getDrawable(iconId);
            }
            if (values != null) value = values[0];
            if ((label == null || iconId == 0) && uid != null) {
                getQuickNameIconForUid(uid);
            }
            uidObj = uid;
        }

        double getSortValue() {
            return value;
        }

        double[] getValues() {
            return values;
        }

        Drawable getIcon() {
            return icon;
        }

        public int compareTo(final BatterySipper other) {
            // Return the flipped value because we want the items in descending order
            return (int) (other.getSortValue() - getSortValue());
        }

        void getQuickNameIconForUid(final Uid uidObj) {
            final int uid = uidObj.getUid();
            final String uidString = Integer.toString(uid);
            if (mUidCache.containsKey(uidString)) {
                final UidToDetail utd = mUidCache.get(uidString);
                defaultPackageName = utd.packageName;
                name = utd.name;
                icon = utd.icon;
                return;
            }
            final PackageManager pm = getPackageManager();
            final Drawable defaultActivityIcon = pm.getDefaultActivityIcon();
            final String[] packages = pm.getPackagesForUid(uid);
            icon = pm.getDefaultActivityIcon();
            if (packages == null) {
                //name = Integer.toString(uid);
                if (uid == 0) {
                    name = getResources().getString(R.string.process_kernel_label);
                } else if ("mediaserver".equals(name)) {
                    name = getResources().getString(R.string.process_mediaserver_label);
                }
                iconId = R.drawable.ic_power_system;
                icon = getResources().getDrawable(iconId);
                return;
            } else {
                //name = packages[0];
            }
            synchronized (mRequestQueue) {
                mRequestQueue.add(this);
            }
        }

        /**
         * Sets name and icon
         * @param uid Uid of the application
         */
        void getNameIcon() {
            final PackageManager pm = getPackageManager();
            final int uid = uidObj.getUid();
            final Drawable defaultActivityIcon = pm.getDefaultActivityIcon();
            final String[] packages = pm.getPackagesForUid(uid);
            if (packages == null) {
                name = Integer.toString(uid);
                return;
            }

            final String[] packageLabels = new String[packages.length];
            System.arraycopy(packages, 0, packageLabels, 0, packages.length);

            int preferredIndex = -1;
            // Convert package names to user-facing labels where possible
            for (int i = 0; i < packageLabels.length; i++) {
                // Check if package matches preferred package
                if (packageLabels[i].equals(name)) preferredIndex = i;
                try {
                    final ApplicationInfo ai = pm.getApplicationInfo(packageLabels[i], 0);
                    final CharSequence label = ai.loadLabel(pm);
                    if (label != null) {
                        packageLabels[i] = label.toString();
                    }
                    if (ai.icon != 0) {
                        defaultPackageName = packages[i];
                        icon = ai.loadIcon(pm);
                        break;
                    }
                } catch (final NameNotFoundException e) {
                }
            }
            if (icon == null) icon = defaultActivityIcon;

            if (packageLabels.length == 1) {
                name = packageLabels[0];
            } else {
                // Look for an official name for this UID.
                for (final String pkgName : packages) {
                    try {
                        final PackageInfo pi = pm.getPackageInfo(pkgName, 0);
                        if (pi.sharedUserLabel != 0) {
                            final CharSequence nm = pm.getText(pkgName,
                                    pi.sharedUserLabel, pi.applicationInfo);
                            if (nm != null) {
                                name = nm.toString();
                                if (pi.applicationInfo.icon != 0) {
                                    defaultPackageName = pkgName;
                                    icon = pi.applicationInfo.loadIcon(pm);
                                }
                                break;
                            }
                        }
                    } catch (final PackageManager.NameNotFoundException e) {
                    }
                }
            }
            final String uidString = Integer.toString(uidObj.getUid());
            final UidToDetail utd = new UidToDetail();
            utd.name = name;
            utd.icon = icon;
            utd.packageName = defaultPackageName;
            mUidCache.put(uidString, utd);
            mHandler.sendMessage(mHandler.obtainMessage(MSG_UPDATE_NAME_ICON, this));
        }
    }

    public void run() {
        while (true) {
            BatterySipper bs;
            synchronized (mRequestQueue) {
                if (mRequestQueue.isEmpty() || mAbort) {
                    mRequestThread = null;
                    return;
                }
                bs = mRequestQueue.remove(0);
            }
            bs.getNameIcon();
        }
    }

    private static final int MSG_UPDATE_NAME_ICON = 1;

    Handler mHandler = new Handler() {

        @Override
        public void handleMessage(final Message msg) {
            switch (msg.what) {
                case MSG_UPDATE_NAME_ICON:
                    final BatterySipper bs = (BatterySipper) msg.obj;
                    final PowerGaugePreference pgp = 
                            (PowerGaugePreference) findPreference(
                                    Integer.toString(bs.uidObj.getUid()));
                    if (pgp != null) {
                        pgp.setIcon(bs.icon);
                        pgp.setPercent(bs.percent);
                        pgp.setTitle(bs.name);
                    }
                    break;
            }
            super.handleMessage(msg);
        }
    };
}
