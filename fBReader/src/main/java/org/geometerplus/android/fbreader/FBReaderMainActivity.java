/*
 * Copyright (C) 2009-2015 FBReader.ORG Limited <contact@fbreader.org>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301, USA.
 */

package org.geometerplus.android.fbreader;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.app.SkinAppCompatDelegateImpl;

import android.os.Bundle;
import android.view.WindowManager;

import com.github.johnpersano.supertoasts.SuperActivityToast;

import org.geometerplus.android.fbreader.dict.DictionaryUtil;
import org.geometerplus.zlibrary.core.options.ZLIntegerOption;
import org.geometerplus.zlibrary.core.options.ZLIntegerRangeOption;
import org.geometerplus.zlibrary.core.options.ZLStringOption;
import org.geometerplus.zlibrary.ui.android.library.ZLAndroidApplication;
import org.geometerplus.zlibrary.ui.android.library.ZLAndroidLibrary;
import org.geometerplus.zlibrary.ui.android.view.AndroidFontUtil;

import timber.log.Timber;

public abstract class FBReaderMainActivity extends AppCompatActivity {

    public static final int REQUEST_PREFERENCES = 1;
    public static final int REQUEST_CANCEL_MENU = 2;
    public static final int REQUEST_DICTIONARY = 3;

    private volatile SuperActivityToast myToast;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_DICTIONARY) {
            DictionaryUtil.onActivityResult(this, resultCode, data);
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    public ZLAndroidLibrary getZLibrary() {
        return ((ZLAndroidApplication) getApplication()).library();
    }

    /* ++++++ SCREEN BRIGHTNESS ++++++ */
    protected void setScreenBrightnessAuto() {
        final WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.screenBrightness = -1.0f;
        getWindow().setAttributes(attrs);
    }

    public void setScreenBrightnessSystem(float level) {
        final WindowManager.LayoutParams attrs = getWindow().getAttributes();
        attrs.screenBrightness = level;
        getWindow().setAttributes(attrs);
    }

    public float getScreenBrightnessSystem() {
        final float level = getWindow().getAttributes().screenBrightness;
        return level >= 0 ? level : .5f;
    }
    /* ------ SCREEN BRIGHTNESS ------ */

    /* ++++++ SUPER TOAST ++++++ */
    public boolean isToastShown() {
        final SuperActivityToast toast = myToast;
        return toast != null && toast.isShowing();
    }

    public void hideToast() {
        final SuperActivityToast toast = myToast;
        if (toast != null && toast.isShowing()) {
            myToast = null;
            runOnUiThread(new Runnable() {
                public void run() {
                    toast.dismiss();
                }
            });
        }
    }

    public void showToast(final SuperActivityToast toast) {
        hideToast();
        myToast = toast;
        // TODO: avoid this hack (accessing text style via option)
        final int dpi = getZLibrary().getDisplayDPI();
        final int defaultFontSize = dpi * 18 / 160;
        final int fontSize = new ZLIntegerOption("Style", "Base:fontSize", defaultFontSize).getValue();
        final int percent = new ZLIntegerRangeOption("Options", "ToastFontSizePercent", 25, 100, 90).getValue();
        final int dpFontSize = fontSize * 160 * percent / dpi / 100;
        toast.setTextSize(dpFontSize);
        toast.setButtonTextSize(dpFontSize * 7 / 8);

        final String fontFamily = new ZLStringOption("Style", "Base:fontFamily", "sans-serif").getValue();
        toast.setTypeface(AndroidFontUtil.systemTypeface(fontFamily, false, false));

        runOnUiThread(new Runnable() {
            public void run() {
                toast.show();
            }
        });
    }
    /* ------ SUPER TOAST ------ */

    public abstract void hideDictionarySelection();

    @NonNull
    @Override
    public AppCompatDelegate getDelegate() {
        return SkinAppCompatDelegateImpl.get(this, this);
    }


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Timber.v("lifeCycle, onCreate");
    }

    @Override
    protected void onStart() {
        super.onStart();
        Timber.v("lifeCycle, onStart");
    }

    @Override
    protected void onResume() {
        super.onResume();
        Timber.v("lifeCycle, onResume");
    }

    @Override
    protected void onPause() {
        super.onPause();
        Timber.v("lifeCycle, onPause");
    }

    @Override
    protected void onStop() {
        super.onStop();
        Timber.v("lifeCycle, onStop");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Timber.v("lifeCycle, onDestroy");
    }
}
