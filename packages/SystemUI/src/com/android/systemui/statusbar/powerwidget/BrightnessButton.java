
package com.android.systemui.statusbar.powerwidget;

import com.android.server.PowerManagerService;
import com.android.systemui.R;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.IPowerManager;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;
import android.view.View;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class BrightnessButton extends PowerButton {

    private static final String TAG = "BrightnessButton";

    /**
     * Minimum and maximum brightnesses. Don't go to 0 since that makes the
     * display unusable
     */
    private static final int MIN_BACKLIGHT = PowerManager.BRIGHTNESS_DIM + 10;
    private static final int MAX_BACKLIGHT = PowerManager.BRIGHTNESS_ON;

    // Auto-backlight level
    private static final int AUTO_BACKLIGHT = -1;
    // Mid-range brightness values + thresholds
    private static final int LOW_BACKLIGHT = (int) (MAX_BACKLIGHT * 0.25f);
    private static final int MID_BACKLIGHT = (int) (MAX_BACKLIGHT * 0.5f);
    private static final int HIGH_BACKLIGHT = (int) (MAX_BACKLIGHT * 0.75f);

    // Defaults for now. MIN_BACKLIGHT will be replaced later
    private static final int[] BACKLIGHTS = new int[] {
            AUTO_BACKLIGHT, MIN_BACKLIGHT, LOW_BACKLIGHT, MID_BACKLIGHT, HIGH_BACKLIGHT,
            MAX_BACKLIGHT
    };

    private static final Uri BRIGHTNESS_URI = Settings.System
            .getUriFor(Settings.System.SCREEN_BRIGHTNESS);
    private static final Uri BRIGHTNESS_MODE_URI = Settings.System
            .getUriFor(Settings.System.SCREEN_BRIGHTNESS_MODE);
    private static final List<Uri> OBSERVED_URIS = new ArrayList<Uri>();
    static {
        OBSERVED_URIS.add(BRIGHTNESS_URI);
        OBSERVED_URIS.add(BRIGHTNESS_MODE_URI);
        OBSERVED_URIS.add(Settings.System.getUriFor(Settings.System.LIGHT_SENSOR_CUSTOM));
        OBSERVED_URIS.add(Settings.System.getUriFor(Settings.System.LIGHT_SCREEN_DIM));
        OBSERVED_URIS.add(Settings.System.getUriFor(Settings.System.EXPANDED_BRIGHTNESS_MODE));
    }

    private boolean mAutoBrightnessSupported = false;

    private boolean mAutoBrightness = false;

    private int mCurrentBrightness;

    private int mCurrentBacklightIndex = 0;

    private int[] mBacklightValues = new int[] {
            0, 1, 2, 3, 4, 5
    };

    public BrightnessButton() {
        mType = BUTTON_BRIGHTNESS;
    }

    @Override
    protected void setupButton(View view) {
        super.setupButton(view);
        if (mView != null) {
            Context context = mView.getContext();
            mAutoBrightnessSupported = context.getResources().getBoolean(
                    com.android.internal.R.bool.config_automatic_brightness_available);
            updateSettings(context.getContentResolver());
        }
    }

    @Override
    protected void updateState(Context context) {
        if (mAutoBrightness) {
            mIcon = R.drawable.stat_brightness_auto;
            mState = STATE_ENABLED;
        } else if (mCurrentBrightness <= LOW_BACKLIGHT) {
            mIcon = R.drawable.stat_brightness_off;
            mState = STATE_DISABLED;
        } else if (mCurrentBrightness <= MID_BACKLIGHT) {
            mIcon = R.drawable.stat_brightness_mid;
            mState = STATE_INTERMEDIATE;
        } else {
            mIcon = R.drawable.stat_brightness_on;
            mState = STATE_ENABLED;
        }
    }

    @Override
    protected void toggleState(Context context) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        ContentResolver resolver = context.getContentResolver();

        mCurrentBacklightIndex++;
        if (mCurrentBacklightIndex > mBacklightValues.length - 1) {
            mCurrentBacklightIndex = 0;
        }

        File f = new File("/sys/devices/platform/i2c-adapter/i2c-0/0-0036/mode");
        String modeFile = "";

        if (f.isFile() && f.canRead())
            modeFile = "/sys/devices/platform/i2c-adapter/i2c-0/0-0036/mode";
        else
            modeFile = "/sys/devices/i2c-0/0-0036/mode";

        int backlightIndex = mBacklightValues[mCurrentBacklightIndex];
        if (backlightIndex > BACKLIGHTS.length - 1) {
            writeOneLine(modeFile, "i2c_pwm");
            Settings.System.putInt(resolver, Settings.System.SCREEN_RAISED_BRIGHTNESS, 1);
            backlightIndex = BACKLIGHTS.length - 1;
        }
        else if (backlightIndex == 1) {
            writeOneLine(modeFile, "i2c_pwm_als");
            Settings.System.putInt(resolver, Settings.System.SCREEN_RAISED_BRIGHTNESS, 0);
        }

        int brightness = BACKLIGHTS[backlightIndex];

        if (brightness == AUTO_BACKLIGHT) {
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        } else {
            if (mAutoBrightnessSupported) {
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            }
            if (power != null) {
                power.setBacklightBrightness(brightness);
            }
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, brightness);
        }
    }

    @Override
    protected boolean handleLongClick(Context context) {
        Intent intent = new Intent("android.settings.DISPLAY_SETTINGS");
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        return true;
    }

    @Override
    protected List<Uri> getObservedUris() {
        return OBSERVED_URIS;
    }

    @Override
    protected void onChangeUri(ContentResolver resolver, Uri uri) {
        if (BRIGHTNESS_URI.equals(uri)) {
            mCurrentBrightness = Settings.System.getInt(resolver,
                    Settings.System.SCREEN_BRIGHTNESS, 0);
        } else if (BRIGHTNESS_MODE_URI.equals(uri)) {
            mAutoBrightness = (Settings.System.getInt(resolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        } else {
            updateSettings(resolver);
        }
    }

    private void updateSettings(ContentResolver resolver) {
        boolean lightSensorCustom = (Settings.System.getInt(resolver,
                Settings.System.LIGHT_SENSOR_CUSTOM, 0) != 0);
        if (lightSensorCustom) {
            BACKLIGHTS[1] = Settings.System.getInt(resolver, Settings.System.LIGHT_SCREEN_DIM,
                    MIN_BACKLIGHT);
        } else {
            BACKLIGHTS[1] = MIN_BACKLIGHT;
        }

        String[] modes = parseStoredValue(Settings.System.getString(
                resolver, Settings.System.EXPANDED_BRIGHTNESS_MODE));
        if (modes == null || modes.length == 0) {
            mBacklightValues = new int[] {
                    0, 1, 2, 3, 4, 5
            };
        } else {
            mBacklightValues = new int[modes.length];
            for (int i = 0; i < modes.length; i++) {
                mBacklightValues[i] = Integer.valueOf(modes[i]);
            }
        }

        mAutoBrightness = (Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        if (mAutoBrightness) {
            mCurrentBrightness = AUTO_BACKLIGHT;
        } else {
            mCurrentBrightness = Settings.System.getInt(resolver,
                    Settings.System.SCREEN_BRIGHTNESS, -1);
            for (int i = 0; i < BACKLIGHTS.length; i++) {
                if (mCurrentBrightness == BACKLIGHTS[i]) {
                    mCurrentBacklightIndex = i;
                    break;
                }
            }
        }
    }

    public static boolean writeOneLine(String fname, String value) {
        try {
            FileWriter fw = new FileWriter(fname);
            try {
                fw.write(value);
            } finally {
                fw.close();
            }
        } catch (IOException e) {
            String Error = "Error writing to " + fname + ". Exception: ";
            Log.e(TAG, Error, e);
            return false;
        }
        return true;
    }
}
