package com.ht1.dexterity.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.BatteryManager;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;


import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * Created by John Costik on 6/7/14.
 */
public class SerialPortReader
{
	private Thread mThread = null;
	private Context mContext = null;
	private static SerialPortReader _instance;
	private String mError = "";
	private static final int  MAX_RECORDS_TO_UPLOAD = 6;
    private boolean mStop = false;

	// private constructor so can only be instantiated from static member
	private SerialPortReader(Context context)
	{
		mContext = context;
	}

	private void SetContext(Context context)
	{
		mContext = context;
	}

	public static SerialPortReader getInstance(Context context)
	{
		if (_instance == null)
		{
			_instance = new SerialPortReader(context);
		}

		// set service.  This survives change in service, since this is a static member
		_instance.SetContext(context);
		return _instance;
	}

	public void StartThread()
   	{
        // prevent any pending stop
        mStop = false;

        if (mThread == null)
		{
			mThread = new Thread(mMainRunLoop);
            mThread.start();
		}
	}

	public void StopThread()
	{
		if(mThread != null)
			mStop = true;
    }

	public String getErrorString()
	{
		return mError;
	}

	public void ShowToast(final String toast)
	{
		// push a notification rather than toast.
		NotificationManager NM = (NotificationManager)mContext.getSystemService(Context.NOTIFICATION_SERVICE);
		Notification n = new Notification.Builder(mContext)
				.setContentTitle("Dexterity receiver")
				.setContentText(toast)
				.setTicker(toast)
				.setSmallIcon(mContext.getResources().getIdentifier("ic_launcher", "drawable", mContext.getPackageName()))
				.build();

		NM.notify(R.string.notification_ReceiverAttached, n);
	}

	private Runnable mMainRunLoop = new Runnable()
	{
        private void done()
        {
            // clear object in parent so that the thread can be restarted if required
            ShowToast("USB port closed / suspended");
            mThread = null;
            mContext.sendBroadcast(new Intent("USB_DISCONNECT"));
            mStop = false;
        }

		@Override
		public void run()
		{
			Looper.prepare();

            UsbManager manager = (UsbManager)mContext.getSystemService(mContext.USB_SERVICE);
            if (manager == null)
            {
                SetError("Failed to obtain USB device manager");
                done();
                return;
            }
            UsbSerialDriver SerialPort = UsbSerialProber.findFirstDevice(manager);
            if (SerialPort == null)
            {
                SetError("Failed to obtain serial device");
                done();
                return;
            }

            try
			{
                SerialPort.open();
			}
			catch (IOException e)
			{
				SetError(e.getLocalizedMessage());
				e.printStackTrace();
                done();
				return;
			}

			try
			{
                ShowToast("Reading from USB port");
                mContext.sendBroadcast(new Intent("USB_CONNECT"));

				byte[] rbuf = new byte[4096];

				while(!mStop)
				{
					// this is the main loop for transferring
					try
					{
                        Log.i("SerialPortReader", "Reading...");
						// read aborts when the device is disconnected
                        int len = SerialPort.read(rbuf, 30000);
                        if (len > 0)
                        {
                            rbuf[len] = 0;
                            setSerialDataToTransmitterRawData(rbuf, len);
                        }
                    }
					catch (IOException e)
					{
						//Not a G4 Packet?
                        ShowToast("Worker thread IOException: " + e.hashCode());
                        // abort this thread
                        mStop = true;
						e.printStackTrace();
					}
				}

				// do this last as it can throw
                SerialPort.close();
				Log.i("SerialPortReader", "mStop is true, stopping read process");
			}
			catch (IOException e)
			{
				e.printStackTrace();
			}
            done();
		}
	};

	private void SetError(String sError)
	{
		mError = sError;
		ShowToast(mError);
		Log.i("SerialPortReader: ", mError);
	}

	public void setSerialDataToTransmitterRawData(byte[] buffer, int len)
	{
		TransmitterRawData data = new TransmitterRawData(buffer, len, mContext);
		DexterityDataSource source = new DexterityDataSource(mContext);
		data = source.createRawDataEntry(data);

		List<TransmitterRawData> retryList = source.getAllDataToUploadObjects();
		source.close();

        // we got the read, we should notify
        mContext.sendBroadcast(new Intent("NEW_READ"));

		int recordsToUpload = MAX_RECORDS_TO_UPLOAD;
		if (retryList.size() < MAX_RECORDS_TO_UPLOAD)
		{
			recordsToUpload = retryList.size();
		}

		for (int j = 0; j < recordsToUpload; ++j)
		{
			CloudUploader uploader = new CloudUploader(mContext, retryList.get(j));
		}
	}
}
