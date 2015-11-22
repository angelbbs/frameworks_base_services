/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.server;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IHardwareService;
import android.os.ServiceManager;
import android.os.Message;
import android.util.Slog;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.*;
import java.net.*;
import java.util.List;
import java.util.Properties;


public class LightsService {
    private static final String TAG = "LightsService";
    private static final boolean DEBUG = false;

    public static final int LIGHT_ID_BACKLIGHT = 0;
    public static final int LIGHT_ID_KEYBOARD = 1;
    public static final int LIGHT_ID_BUTTONS = 2;
    public static final int LIGHT_ID_BATTERY = 3;
    public static final int LIGHT_ID_NOTIFICATIONS = 4;
    public static final int LIGHT_ID_ATTENTION = 5;
    public static final int LIGHT_ID_BLUETOOTH = 6;
    public static final int LIGHT_ID_WIFI = 7;
    public static final int LIGHT_ID_CAPS = 8;
    public static final int LIGHT_ID_FUNC = 9;
    public static final int LIGHT_ID_MUSIC = 10;
    public static final int LIGHT_ID_COUNT = 11;

    public static final int LIGHT_FLASH_NONE = 0;
    public static final int LIGHT_FLASH_TIMED = 1;
    public static final int LIGHT_FLASH_HARDWARE = 2;
    public static Socket sc;
    public static InputStream is;
    public static OutputStream os;
    public static InetAddress localSvr;
    public static int m_serverPort = 9010;
    public static int m_myPort = 9061;
    public static DatagramSocket socket;


    /**
     * Light brightness is managed by a user setting.
     */
    public static final int BRIGHTNESS_MODE_USER = 0;

    /**
     * Light brightness is managed by a light sensor.
     */
    public static final int BRIGHTNESS_MODE_SENSOR = 1;

    private final Light mLights[] = new Light[LIGHT_ID_COUNT];

    public final class Light {

        private Light(int id) {
            mId = id;
        }

        public void setBrightness(int brightness) {
            setBrightness(brightness, BRIGHTNESS_MODE_USER);
        }

        public void setBrightness(int brightness, int brightnessMode) {
            synchronized (this) {
                int color = brightness & 0x000000ff;
        if (brightnessMode == 0 && ( brightness != 0 && brightness != 255 ) )
        {
        SendBrightnessToMCU(brightness);
        }
                color = 0xff000000 | (color << 16) | (color << 8) | color;
                setLightLocked(color, LIGHT_FLASH_NONE, 0, 0, brightnessMode);
            }
        }

        public void setColor(int color) {
            synchronized (this) {
                setLightLocked(color, LIGHT_FLASH_NONE, 0, 0, 0);
            }
        }

        public void setFlashing(int color, int mode, int onMS, int offMS) {
            synchronized (this) {
                setLightLocked(color, mode, onMS, offMS, BRIGHTNESS_MODE_USER);
            }
        }


        public void pulse() {
            pulse(0x00ffffff, 7);
        }

        public void pulse(int color, int onMS) {
            synchronized (this) {
                if (mColor == 0) {
                    setLightLocked(color, LIGHT_FLASH_HARDWARE, onMS, 1000, BRIGHTNESS_MODE_USER);
                    mH.sendMessageDelayed(Message.obtain(mH, 1, this), onMS);
                }
            }
        }

        public void turnOff() {
            synchronized (this) {
                setLightLocked(0, LIGHT_FLASH_NONE, 0, 0, 0);
            }
        }

        private void setLightLocked(int color, int mode, int onMS, int offMS, int brightnessMode) {
            if (color != mColor || mode != mMode || onMS != mOnMS || offMS != mOffMS) {
                if (DEBUG) Slog.v(TAG, "setLight #" + mId + ": color=#"
                        + Integer.toHexString(color));
                mColor = color;
                mMode = mode;
                mOnMS = onMS;
                mOffMS = offMS;
                setLight_native(mNativePointer, mId, color, mode, onMS, offMS, brightnessMode);
            }
        }

        private int mId;
        private int mColor;
        private int mMode;
        private int mOnMS;
        private int mOffMS;
    }

    /* This class implements an obsolete API that was removed after eclair and re-added during the
     * final moments of the froyo release to support flashlight apps that had been using the private
     * IHardwareService API. This is expected to go away in the next release.
     */
    private final IHardwareService.Stub mLegacyFlashlightHack = new IHardwareService.Stub() {

        private static final String FLASHLIGHT_FILE = "/sys/class/leds/spotlight/brightness";

        public boolean getFlashlightEnabled() {
            try {
                FileInputStream fis = new FileInputStream(FLASHLIGHT_FILE);
                int result = fis.read();
                fis.close();
                return (result != '0');
            } catch (Exception e) {
                return false;
            }
        }

        public void setFlashlightEnabled(boolean on) {
            if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.FLASHLIGHT)
                    != PackageManager.PERMISSION_GRANTED &&
                    mContext.checkCallingOrSelfPermission(android.Manifest.permission.HARDWARE_TEST)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Requires FLASHLIGHT or HARDWARE_TEST permission");
            }
            try {
                FileOutputStream fos = new FileOutputStream(FLASHLIGHT_FILE);
                byte[] bytes = new byte[2];
                bytes[0] = (byte)(on ? '1' : '0');
                bytes[1] = '\n';
                fos.write(bytes);
                fos.close();
            } catch (Exception e) {
                // fail silently
            }
        }
    };

    LightsService(Context context) {

        mNativePointer = init_native();
        mContext = context;

        ServiceManager.addService("hardware", mLegacyFlashlightHack);

        for (int i = 0; i < LIGHT_ID_COUNT; i++) {
            mLights[i] = new Light(i);
        }
       CreateSocket();
    }

    protected void finalize() throws Throwable {
        finalize_native(mNativePointer);
        super.finalize();
    }

    public Light getLight(int id) {
        return mLights[id];
    }

    private Handler mH = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            Light light = (Light)msg.obj;
            light.turnOff();
        }
    };

    private static native int init_native();
    private static native void finalize_native(int ptr);

    private static native void setLight_native(int ptr, int light, int color, int mode,
            int onMS, int offMS, int brightnessMode);

    private final Context mContext;

    private int mNativePointer;

    //************************************************
    public static boolean CreateSocket()
    {

        while (localSvr == null)
            try
            {
                localSvr = InetAddress.getByName("127.0.0.1");
            }
            catch (Exception localException)
            {
                localException.getMessage();
                if (DEBUG) Slog.v(TAG, "local InetAddress err:" + localException.getMessage());
                try
                {
                    Thread.sleep(1000L);
                }
                catch (InterruptedException localInterruptedException)
                {
                    localInterruptedException.printStackTrace();
                }
            }
        try
        {
            InetSocketAddress localInetSocketAddress = new InetSocketAddress("127.0.0.1", m_myPort);
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(localInetSocketAddress);
            socket.setSoTimeout(0);
            if (DEBUG) Slog.v(TAG, "create socket in " + m_myPort);
            return true;
        }
        catch (SocketException localSocketException)
        {
            localSocketException.printStackTrace();
            Slog.v(TAG, "create socket err:" + localSocketException.getMessage() + " port:" + m_myPort);
        }
        return false;
    }
    //*******************************************
    public static void SendBrightnessToMCU(int brightness)
    {
      byte[] var2 = new byte[7];
         var2[0] = -86;
         var2[1] = 4;
         var2[2] = 73;
         var2[3] = -95;
         int br = (int)brightness/8;
         var2[4] = (byte)br;
         var2[5] = (byte)(var2[1] + var2[2] + var2[3] + var2[4]);
         var2[6] = 85;

        DatagramPacket localDatagramPacket = new DatagramPacket(var2, var2.length, localSvr, m_serverPort);
        String var4 = byteArrToHexStr(var2);

        try
        {
            if (DEBUG) Slog.v(TAG, "arm->mcu: " + var4);
            if (DEBUG) Slog.v(TAG, "Set brightness: " + brightness + "Set br: " + br);
            socket.send(localDatagramPacket);
            return;
        }
        catch (Exception localException)
        {
            localException.printStackTrace();
            Slog.v(TAG, "Send packet to MCU fail: " + localException.getMessage());
        }
    }
    //********************************************

    public static byte[] hexStringTobyte(String paramString)
    {
        int i = paramString.length() / 2;
        byte[] arrayOfByte = new byte[i];
        char[] arrayOfChar = paramString.toCharArray();
        for (int j = 0; ; j++)
        {
            if (j >= i)
                return arrayOfByte;
            int k = j * 2;
            arrayOfByte[j] = ((byte)(tobyte(arrayOfChar[k]) << 4 | tobyte(arrayOfChar[(k + 1)])));
        }
    }
    private static byte tobyte(char paramChar)
    {
        return (byte)"0123456789abcdef".indexOf(paramChar);
    }

   public static String byteArrToHexStr(byte[] var0) {
      StringBuilder var1 = new StringBuilder("");

      for(int var2 = 0; var2 < var0.length; ++var2) {
         byte var10000 = var0[var2];
         int var4 = 15 & var0[var2] >> 4;
         int var5 = 15 & var0[var2];
         if(var4 >= 10) {
            var1.append((char)(65 + (var4 - 10)));
         } else {
            var1.append(var4);
         }

         if(var5 >= 10) {
            var1.append((char)(65 + (var5 - 10)));
         } else {
            var1.append(var5);
         }
      }

      return var1.toString();
   }


}
