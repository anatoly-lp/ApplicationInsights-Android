package com.microsoft.applicationinsights.library;

import android.annotation.TargetApi;
import android.os.AsyncTask;
import android.os.Build;

import com.microsoft.applicationinsights.library.config.ISenderConfig;
import com.microsoft.applicationinsights.logging.InternalLogging;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

/**
 * This singleton class sends data to the endpoint.
 */
class Sender {

    private static final String TAG = "Sender";

    /**
     * Volatile boolean for double checked synchronize block.
     */
    private static volatile boolean isSenderLoaded = false;


    /**
     * Synchronization LOCK for setting static config.
     */
    private static final Object LOCK = new Object();

    /**
     * The configuration for this sender.
     */
    protected final ISenderConfig config;

    /**
     * The shared Sender instance.
     */
    private static Sender instance;

    /**
     * Persistence object used to reserve, free, or delete files.
     */
    protected Persistence persistence;

    /**
     * Thread safe counter to keep track of num of operations
     */
    private AtomicInteger operationsCount;

    /**
     * Restrict access to the default constructor
     * @param config the telemetryconfig object used to configure the telemetry module
     */
    protected Sender(ISenderConfig config) {
        this.config = config;
        this.operationsCount = new AtomicInteger(0);
        this.persistence = Persistence.getInstance();
    }

    /**
     * Initialize the INSTANCE of sender.
     */
    protected static void initialize(ISenderConfig config) {
        // note: isSenderLoaded must be volatile for the double-checked LOCK to work
        if (!Sender.isSenderLoaded) {
            synchronized (Sender.LOCK) {
                if (!Sender.isSenderLoaded) {
                    Sender.isSenderLoaded = true;
                    Sender.instance = new Sender(config);
                }
            }
        }
    }

    /**
     * @return the INSTANCE of the sender calls initialize before that.
     */
    protected static Sender getInstance() {
        if (Sender.instance == null) {
            InternalLogging.error(TAG, "getInstance was called before initialization");
        }

        return Sender.instance;
    }


    protected void sendDataOnAppStart() {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                sendNextFile();
                return null;
            }
        }.execute();
    }

    protected void sendNextFile(){
        if(runningRequestCount() < 10) {
            // Send the persisted data

            if (this.persistence != null) {
                File fileToSend = this.persistence.nextAvailableFile();
                if(fileToSend != null) {
                    send(fileToSend);
                }
            }
        }
        else {
            InternalLogging.info(TAG, "We have already 10 pending reguests", "");
        }
    }

    protected void send(File fileToSend) {

        String persistedData = this.persistence.load(fileToSend);
        if (!persistedData.isEmpty()) {
            InternalLogging.info(TAG, "sending persisted data", persistedData);
            try {
                InternalLogging.info(TAG, "sending persisted data", persistedData);
                this.operationsCount.getAndIncrement();
                this.sendRequestWithPayload(persistedData, fileToSend);
            } catch (IOException e) {
                InternalLogging.warn(TAG,"Couldn't send request with IOException: " + e.toString());
                this.operationsCount.getAndDecrement();
            }
        }else{
            this.persistence.deleteFile(fileToSend);
            sendNextFile();
        }
    }

    protected int runningRequestCount() {
        return this.operationsCount.get();
    }

    protected void sendRequestWithPayload(String payload, File fileToSend) throws IOException {
        Writer writer = null;
        URL url = new URL(config.getEndpointUrl());
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setReadTimeout(config.getSenderReadTimeout());
        connection.setConnectTimeout(config.getSenderConnectTimeout());
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        connection.setDoInput(true);
        connection.setUseCaches(false);

        try {
            InternalLogging.info(TAG, "writing payload", payload);
            writer = getWriter(connection);
            writer.write(payload);
            writer.flush();

            // Starts the query
            connection.connect();

            // read the response code while we're ready to catch the IO exception
            int responseCode = connection.getResponseCode();

            // process the response
            onResponse(connection, responseCode, payload, fileToSend);
        } catch (IOException e) {
            InternalLogging.warn(TAG, "Couldn't send data with IOException: " + e.toString());
            if (this.persistence != null) {
                this.persistence.makeAvailable(fileToSend); //send again later
            }
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    // no-op
                }
            }
        }
    }

    /**
     * Handler for the http response from the sender
     *
     * @param connection   a connection containing a response
     * @param responseCode the response code from the connection
     * @param payload      the payload which generated this response
     * @param fileToSend reference to the file we want to send
     */
    protected void onResponse(HttpURLConnection connection, int responseCode, String payload, File fileToSend) {
        this.operationsCount.getAndDecrement();

        StringBuilder builder = new StringBuilder();

        InternalLogging.info(TAG, "response code", Integer.toString(responseCode));
        boolean isExpected = ((responseCode > 199) && (responseCode < 203));
        boolean isRecoverableError = (responseCode == 429) || (responseCode == 408) ||
              (responseCode == 500) || (responseCode == 503) || (responseCode == 511);
        boolean deleteFile = isExpected || !isRecoverableError;

        // If this was expected and developer mode is enabled, read the response
        if(isExpected) {
            this.onExpected(connection, builder);
            this.sendNextFile();
        }

        if(deleteFile) {
            if(this.persistence != null) {
                this.persistence.deleteFile(fileToSend);
            }
        }

        // If there was a server issue, flush the data
        if (isRecoverableError) {
            this.onRecoverable(payload, fileToSend);
        }

        // If it isn't the usual success code (200), log the response from the server.
        if (!isExpected) {
            this.onUnexpected(connection, responseCode, builder);
        }
    }

    /**
     * Process the expected response. If {code:TelemetryChannelConfig.isDeveloperMode}, read the
     * response and log it.
     *  @param connection a connection containing a response
     * @param builder    a string builder for storing the response
     */
    protected void onExpected(HttpURLConnection connection, StringBuilder builder) {
        if (ApplicationInsights.isDeveloperMode()) {
            this.readResponse(connection, builder);
        }
    }

    /**
     *  @param connection   a connection containing a response
     * @param responseCode the response code from the connection
     * @param builder      a string builder for storing the response
     */
    protected void onUnexpected(HttpURLConnection connection, int responseCode, StringBuilder builder) {
        String message = String.format(Locale.ROOT, "Unexpected response code: %d", responseCode);
        builder.append(message);
        builder.append("\n");

        // log the unexpected response
        InternalLogging.warn(TAG, message);

        // attempt to read the response stream
        this.readResponse(connection, builder);
    }

    /**
     * Writes the payload to disk if the response code indicates that the server or network caused
     * the failure instead of the client.
     *
     * @param payload the payload which generated this response
     * @param fileToSend reference to the file we sent
     */
    protected void onRecoverable(String payload, File fileToSend) {
        InternalLogging.info(TAG, "Server error, persisting data", payload);
        if (this.persistence != null) {
            this.persistence.makeAvailable(fileToSend);
        }
    }

    /**
     * Reads the response from a connection.
     *
     * @param connection the connection which will read the response
     * @param builder a string builder for storing the response
     */
    private void readResponse(HttpURLConnection connection, StringBuilder builder) {
        BufferedReader reader = null;
        try {
            InputStream inputStream = connection.getErrorStream();
            if (inputStream == null) {
                inputStream = connection.getInputStream();
            }

            if (inputStream != null) {
                InputStreamReader streamReader = new InputStreamReader(inputStream, "UTF-8");
                reader = new BufferedReader(streamReader);
                String responseLine = reader.readLine();
                while (responseLine != null) {
                    builder.append(responseLine);
                    responseLine = reader.readLine();
                }
            } else {
                builder.append(connection.getResponseMessage());
            }
        } catch (IOException e) {
            InternalLogging.warn(TAG, e.toString());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException e) {
                    InternalLogging.warn(TAG, e.toString());
                }
            }
        }
    }

    /**
     * Gets a writer from the connection stream (allows for test hooks into the write stream)
     *
     * @param connection the connection to which the stream will be flushed
     * @return a writer for the given connection stream
     * @throws java.io.IOException Exception thrown by GZIP (used in SDK 19+)
     */
    @TargetApi(Build.VERSION_CODES.KITKAT)
    protected Writer getWriter(HttpURLConnection connection) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            // GZIP if we are running SDK 19 or higher
            connection.addRequestProperty("Content-Encoding", "gzip");
            connection.setRequestProperty("Content-Type", "application/json");
            GZIPOutputStream gzip = new GZIPOutputStream(connection.getOutputStream(), true);
            return new OutputStreamWriter(gzip);
        } else {
            // no GZIP for older devices
            return new OutputStreamWriter(connection.getOutputStream());
        }
    }

    /**
     * Set persistence used to reserve, free, or delete files (enables dependency injection).
     *
     * @param persistence a persistence used to reserve, free, or delete files
     */
    protected void setPersistence(Persistence persistence) {
        this.persistence = persistence;
    }
}


