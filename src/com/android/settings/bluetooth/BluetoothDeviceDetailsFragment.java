/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.settings.bluetooth;

import static android.bluetooth.BluetoothDevice.BOND_NONE;
import static android.os.UserManager.DISALLOW_CONFIG_BLUETOOTH;

import android.app.settings.SettingsEnums;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.res.TypedArray;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemProperties;
import android.provider.DeviceConfig;
import android.sysprop.BluetoothProperties;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.annotation.VisibleForTesting;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import com.android.settings.R;
import com.android.settings.core.SettingsUIDeviceConfig;
import com.android.settings.dashboard.DashboardFragment;
import com.android.settings.dashboard.RestrictedDashboardFragment;
import com.android.settings.overlay.FeatureFactory;
import com.android.settings.slices.BlockingSlicePrefController;
import com.android.settings.slices.SlicePreferenceController;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.core.AbstractPreferenceController;
import com.android.settingslib.core.lifecycle.Lifecycle;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

public class BluetoothDeviceDetailsFragment extends RestrictedDashboardFragment {
    public static final String KEY_DEVICE_ADDRESS = "device_address";
    private static final String TAG = "BTDeviceDetailsFrg";
    private static final String BLUETOOTH_ADV_AUDIO_MASK_PROP
                                                  = "persist.vendor.service.bt.adv_audio_mask";
    private static final String BLUETOOTH_BROADCAST_UI_PROP = "persist.bluetooth.broadcast_ui";
    private static final String BLUETOOTH_BROADCAST_PTS_PROP
                                                  = "persist.vendor.service.bt.broadcast_pts";
    private static final int BA_MASK = 0x02;
    private static boolean mBAEnabled = false;
    private static boolean mBAPropertyChecked = false;

    @VisibleForTesting
    static int EDIT_DEVICE_NAME_ITEM_ID = Menu.FIRST;

    /**
     * An interface to let tests override the normal mechanism for looking up the
     * CachedBluetoothDevice and LocalBluetoothManager, and substitute their own mocks instead.
     * This is only needed in situations where you instantiate the fragment indirectly (eg via an
     * intent) and can't use something like spying on an instance you construct directly via
     * newInstance.
     */
    @VisibleForTesting
    interface TestDataFactory {
        CachedBluetoothDevice getDevice(String deviceAddress);

        LocalBluetoothManager getManager(Context context);
    }

    @VisibleForTesting
    static TestDataFactory sTestDataFactory;

    @VisibleForTesting
    String mDeviceAddress;
    @VisibleForTesting
    LocalBluetoothManager mManager;
    @VisibleForTesting
    CachedBluetoothDevice mCachedDevice;

    public BluetoothDeviceDetailsFragment() {
        super(DISALLOW_CONFIG_BLUETOOTH);
        boolean broadcastPtsEnabled =
                SystemProperties.getBoolean(BLUETOOTH_BROADCAST_PTS_PROP, false);
        if ((BluetoothProperties.isProfileBapBroadcastSourceEnabled().orElse(false) ||
                BluetoothProperties.isProfileBapBroadcastAssistEnabled().orElse(false)) &&
                !broadcastPtsEnabled) {
            SystemProperties.set(BLUETOOTH_BROADCAST_UI_PROP, "false");
        } else {
            Log.d(TAG, "Use legacy broadcast if available");
            SystemProperties.set(BLUETOOTH_BROADCAST_UI_PROP, "true");
        }
    }

    @VisibleForTesting
    LocalBluetoothManager getLocalBluetoothManager(Context context) {
        if (sTestDataFactory != null) {
            return sTestDataFactory.getManager(context);
        }
        return Utils.getLocalBtManager(context);
    }

    @VisibleForTesting
    CachedBluetoothDevice getCachedDevice(String deviceAddress) {
        if (sTestDataFactory != null) {
            return sTestDataFactory.getDevice(deviceAddress);
        }
        BluetoothDevice remoteDevice =
                mManager.getBluetoothAdapter().getRemoteDevice(deviceAddress);
        return mManager.getCachedDeviceManager().findDevice(remoteDevice);
    }

    public static BluetoothDeviceDetailsFragment newInstance(String deviceAddress) {
        Bundle args = new Bundle(1);
        args.putString(KEY_DEVICE_ADDRESS, deviceAddress);
        BluetoothDeviceDetailsFragment fragment = new BluetoothDeviceDetailsFragment();
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onAttach(Context context) {
        mDeviceAddress = getArguments().getString(KEY_DEVICE_ADDRESS);
        mManager = getLocalBluetoothManager(context);
        mCachedDevice = getCachedDevice(mDeviceAddress);
        super.onAttach(context);
        if (mCachedDevice == null) {
            // Close this page if device is null with invalid device mac address
            Log.w(TAG, "onAttach() CachedDevice is null!");
            finish();
            return;
        }
        use(AdvancedBluetoothDetailsHeaderController.class).init(mCachedDevice);
        use(LeAudioBluetoothDetailsHeaderController.class).init(mCachedDevice, mManager);

        final BluetoothFeatureProvider featureProvider = FeatureFactory.getFactory(
                context).getBluetoothFeatureProvider();
        final boolean sliceEnabled = DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_SETTINGS_UI,
                SettingsUIDeviceConfig.BT_SLICE_SETTINGS_ENABLED, true);

        use(BlockingSlicePrefController.class).setSliceUri(sliceEnabled
                ? featureProvider.getBluetoothDeviceSettingsUri(mCachedDevice.getDevice())
                : null);

        use(BADeviceVolumeController.class).init(this, mManager, mCachedDevice);
    }

    private void updateExtraControlUri(int viewWidth) {
        BluetoothFeatureProvider featureProvider = FeatureFactory.getFactory(
                getContext()).getBluetoothFeatureProvider();
        boolean sliceEnabled = DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_SETTINGS_UI,
                SettingsUIDeviceConfig.BT_SLICE_SETTINGS_ENABLED, true);
        Uri controlUri = null;
        String uri = featureProvider.getBluetoothDeviceControlUri(mCachedDevice.getDevice());
        if (!TextUtils.isEmpty(uri)) {
            try {
                controlUri = Uri.parse(uri + viewWidth);
            } catch (NullPointerException exception) {
                Log.d(TAG, "unable to parse uri");
                controlUri = null;
            }
        }
        final SlicePreferenceController slicePreferenceController = use(
                SlicePreferenceController.class);
        slicePreferenceController.setSliceUri(sliceEnabled ? controlUri : null);
        slicePreferenceController.onStart();
        slicePreferenceController.displayPreference(getPreferenceScreen());
    }

    private final ViewTreeObserver.OnGlobalLayoutListener mOnGlobalLayoutListener =
            new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    View view = getView();
                    if (view == null) {
                        return;
                    }
                    if (view.getWidth() <= 0) {
                        return;
                    }
                    updateExtraControlUri(view.getWidth() - getPaddingSize());
                    view.getViewTreeObserver().removeOnGlobalLayoutListener(
                            mOnGlobalLayoutListener);
                }
            };

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        View view = super.onCreateView(inflater, container, savedInstanceState);
        if (view != null) {
            view.getViewTreeObserver().addOnGlobalLayoutListener(mOnGlobalLayoutListener);
        }
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        finishFragmentIfNecessary();
    }

    @VisibleForTesting
    void finishFragmentIfNecessary() {
        if (mCachedDevice.getBondState() == BOND_NONE) {
            finish();
            return;
        }
    }

    @Override
    public int getMetricsCategory() {
        return SettingsEnums.BLUETOOTH_DEVICE_DETAILS;
    }

    @Override
    protected String getLogTag() {
        return TAG;
    }

    @Override
    protected int getPreferenceScreenResId() {
        return R.xml.bluetooth_device_details_fragment;
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        MenuItem item = menu.add(0, EDIT_DEVICE_NAME_ITEM_ID, 0, R.string.bluetooth_rename_button);
        item.setIcon(com.android.internal.R.drawable.ic_mode_edit);
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        if (menuItem.getItemId() == EDIT_DEVICE_NAME_ITEM_ID) {
            RemoteDeviceNameDialogFragment.newInstance(mCachedDevice).show(
                    getFragmentManager(), RemoteDeviceNameDialogFragment.TAG);
            return true;
        }
        return super.onOptionsItemSelected(menuItem);
    }

    @Override
    protected void displayResourceTilesToScreen(PreferenceScreen screen) {
        if (!mBAEnabled || !mCachedDevice.isBASeeker()) {
           screen.removePreference(screen.findPreference("sync_helper_buttons"));
           screen.removePreference(screen.findPreference("added_sources"));
        }
        super.displayResourceTilesToScreen(screen);
    }

    @Override
    protected List<AbstractPreferenceController> createPreferenceControllers(Context context) {
        ArrayList<AbstractPreferenceController> controllers = new ArrayList<>();

        if (mCachedDevice == null) return controllers;

        Lifecycle lifecycle = getSettingsLifecycle();
        controllers.add(new BluetoothDetailsHeaderController(context, this, mCachedDevice,
                lifecycle, mManager));
        controllers.add(new BluetoothDetailsButtonsController(context, this, mCachedDevice,
                lifecycle));
        controllers.add(new BluetoothDetailsCompanionAppsController(context, this,
                mCachedDevice, lifecycle));
        controllers.add(new BluetoothDetailsSpatialAudioController(context, this, mCachedDevice,
                lifecycle));
        controllers.add(new BluetoothDetailsProfilesController(context, this, mManager,
                mCachedDevice, lifecycle));
        controllers.add(new BluetoothDetailsMacAddressController(context, this, mCachedDevice,
                lifecycle));
        controllers.add(new BluetoothDetailsRelatedToolsController(context, this, mCachedDevice,
                lifecycle));
        controllers.add(new BluetoothDetailsPairOtherController(context, this, mCachedDevice,
                lifecycle));
        if (mBAPropertyChecked == false) {
            int advAudioMask = SystemProperties.getInt(BLUETOOTH_ADV_AUDIO_MASK_PROP, 0);
            mBAEnabled = (((advAudioMask & BA_MASK) == BA_MASK) &&
                SystemProperties.getBoolean(BLUETOOTH_BROADCAST_UI_PROP, true));
            mBAPropertyChecked = true;
        }
        if (mBAEnabled == false) {
            return controllers;
        }

        Log.d(TAG, "createPreferenceControllers for BA");

        try {
            if (mCachedDevice.isBASeeker()) {
                Class<?> classAddSourceController = Class.forName(
                    "com.android.settings.bluetooth.BluetoothDetailsAddSourceButtonController");
                Class<?> classBADeviceController = Class.forName(
                    "com.android.settings.bluetooth.BADevicePreferenceController");
                Constructor ctorAddSource = classAddSourceController
                    .getDeclaredConstructor(new Class[] {Context.class,
                PreferenceFragmentCompat.class, CachedBluetoothDevice.class, Lifecycle.class});
                Constructor ctorBADevice = classBADeviceController
                    .getDeclaredConstructor(new Class[] {Context.class, Lifecycle.class,
                    String.class});
                Object objAddSourceController = ctorAddSource.newInstance(context, this,
                    mCachedDevice, lifecycle);
                Object objBADeviceController = ctorBADevice.newInstance(context, lifecycle,
                    "added_sources");
                objBADeviceController.getClass()
                    .getMethod("init", DashboardFragment.class, CachedBluetoothDevice.class)
                    .invoke(objBADeviceController, this, mCachedDevice);
            controllers.add((AbstractPreferenceController) objAddSourceController);
            controllers.add((AbstractPreferenceController) objBADeviceController);
          }
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException |
            InvocationTargetException | InstantiationException | IllegalArgumentException |
            ExceptionInInitializerError e) {
            e.printStackTrace();
            mBAEnabled = false;
        } finally {
            return controllers;
        }
    }

    private int getPaddingSize() {
        TypedArray resolvedAttributes =
                getContext().obtainStyledAttributes(
                        new int[]{
                                android.R.attr.listPreferredItemPaddingStart,
                                android.R.attr.listPreferredItemPaddingEnd
                        });
        int width = resolvedAttributes.getDimensionPixelSize(0, 0)
                + resolvedAttributes.getDimensionPixelSize(1, 0);
        resolvedAttributes.recycle();
        return width;
    }
}
