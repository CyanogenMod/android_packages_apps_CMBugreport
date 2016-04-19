package org.cyanogenmod.bugreport;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;

public class UploadHelper {

    private static final String TAG = "UploadHelper";

    private static final boolean DEBUG = false;

    public static final String LINE_END = "\r\n";
    public static final String DASHES = "--";

    public static String uploadAndGetId(Context context, JSONObject input)
            throws IOException, JSONException {
        final String urlStr = context.getString(R.string.config_api_url);
        URL url = new URL(urlStr);
        HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
        try {
            urlConnection.setRequestProperty("Accept", "application/json");
            urlConnection.setRequestProperty("Authorization", "Basic "
                    + context.getString(R.string.config_auth));
            urlConnection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

            urlConnection.setReadTimeout(10000);
            urlConnection.setConnectTimeout(15000);
            urlConnection.setInstanceFollowRedirects(true);
            urlConnection.setDoInput(true);
            urlConnection.setDoOutput(true);

            OutputStream os = urlConnection.getOutputStream();
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, "UTF-8"));
            writer.write(input.toString());
            writer.flush();
            writer.close();
            os.close();
            urlConnection.connect();

            if (DEBUG) {
                Log.d(TAG, "server response: " + urlConnection.getResponseCode());
            }

            try {
                String response = getResponse(urlConnection);
                if (DEBUG) {
                    Log.v(TAG, response);
                }

                JSONObject output = new JSONObject(response);
                return output.getString("key");
            } catch (IOException | JSONException e) {
                Log.e(TAG, "error getting response", e);

                if (DEBUG) {
                    try {
                        Log.w(TAG, "error: " + getError(urlConnection));
                    } catch (Exception e1) {
                        // ignored
                    }
                }
            }
        } finally {
            urlConnection.disconnect();
        }
        return null;
    }

    public static boolean attachFile(Context context, File f, String issueId) throws IOException {
        if (f == null || !f.exists()) {
            throw new NullPointerException("can't upload a null file");
        }

        URL url;

        if (context.getString(R.string.config_api_url_upload).isEmpty()) {
            url = new URL(context.getString(R.string.config_api_url) + issueId + "/attachments");
        } else {
            url = new URL(
                    context.getString(R.string.config_api_url_upload) + issueId + "/attachments");
        }

        HttpURLConnection urlConnection = null;
        urlConnection = (HttpURLConnection) url.openConnection();

        try {
            String boundry = "-------" + System.currentTimeMillis();

            String part1 = DASHES + boundry + LINE_END
                    + "Content-Disposition: form-data; name=\""
                    + "file" + "\"; filename=\"" + f.getName() + "\"" + LINE_END
                    + "Content-Type: application/octet-stream" + LINE_END
                    + "Content-Transfer-Encoding: binary" + LINE_END
                    + LINE_END;

            String part2 = LINE_END + DASHES + boundry + DASHES + LINE_END;

            final long length = part1.length() + f.length() + part2.length();

            if (DEBUG) {
                Log.d(TAG, "boundary length: : " + boundry.getBytes().length);
                Log.d(TAG, "calculated length: " + length);
            }

            urlConnection.setRequestProperty("Authorization", "Basic "
                    + context.getString(R.string.config_auth));
            urlConnection.setRequestProperty("X-Atlassian-Token", "nocheck");
            urlConnection.setRequestProperty("Content-Type", "multipart/form-data; boundary="
                    + boundry);
            urlConnection.setRequestProperty("Content-Length", String.valueOf(length));
            urlConnection.setDoOutput(true);
            urlConnection.setDoInput(true);
            urlConnection.setUseCaches(false);
            urlConnection.setInstanceFollowRedirects(true);
            urlConnection.setFixedLengthStreamingMode(length);
            urlConnection.setReadTimeout(10000);
            urlConnection.setConnectTimeout(15000);

            FileInputStream inputStream = new FileInputStream(f);
            final OutputStream outputStream = urlConnection.getOutputStream();
            final PrintWriter printWriter
                    = new PrintWriter(new OutputStreamWriter(outputStream, "utf-8"));

            printWriter.write(part1);
            printWriter.flush();

            // write contents of the file
            byte[] buffer = new byte[4096];
            int bytesRead = -1;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.flush();
            inputStream.close();

            // signal end
            printWriter.write(part2);
            printWriter.flush();
            printWriter.close();

            if (DEBUG) {
                Log.v(TAG, part1);
                Log.v(TAG, " <file>");
                Log.v(TAG, part2);
            }

            final int responseCode = urlConnection.getResponseCode();
            if (DEBUG) {
                Log.d(TAG, "response code: " + responseCode);
            }

            if (HttpURLConnection.HTTP_OK == responseCode) {
                String response = getResponse(urlConnection);

                if (DEBUG) {
                    Log.v(TAG, "server response: " + LINE_END + response);
                }

                try {
                    JSONArray jsonResponse = new JSONArray(response);
                    return jsonResponse.getJSONObject(0).get("filename").equals(f.getName());
                } catch (JSONException e) {
                    e.printStackTrace();
                    // not a proper response
                }
            }
        } finally {
            urlConnection.disconnect();
        }
        return false;
    }

    private static String getResponse(HttpURLConnection httpUrlConnection) throws IOException {
        InputStream responseStream = new BufferedInputStream(httpUrlConnection.getInputStream());

        BufferedReader responseStreamReader = new BufferedReader(
                new InputStreamReader(responseStream));
        String line = "";
        StringBuilder stringBuilder = new StringBuilder();
        while ((line = responseStreamReader.readLine()) != null) {
            stringBuilder.append(line).append("\n");
        }
        responseStreamReader.close();
        responseStream.close();

        return stringBuilder.toString();
    }

    private static String getError(HttpURLConnection httpUrlConnection) throws IOException {
        InputStream responseStream = new BufferedInputStream(httpUrlConnection.getErrorStream());

        BufferedReader responseStreamReader = new BufferedReader(
                new InputStreamReader(responseStream));
        String line = "";
        StringBuilder stringBuilder = new StringBuilder();
        while ((line = responseStreamReader.readLine()) != null) {
            stringBuilder.append(line).append("\n");
        }
        responseStreamReader.close();
        responseStream.close();

        return stringBuilder.toString();
    }

    static File zipFiles(Context context, File... files) throws ZipException {
        ZipOutputStream zos = null;
        File zippedFile = new File(context.getCacheDir(), files[0].getName() + ".zip");
        try {
            byte[] buffer = new byte[1024];
            FileOutputStream fos = new FileOutputStream(zippedFile);
            zos = new ZipOutputStream(fos);

            for (int i = 0; i < files.length; i++) {
                FileInputStream fis = new FileInputStream(files[i]);
                try {
                    zos.putNextEntry(new ZipEntry(files[i].getName()));
                    int length;
                    while ((length = fis.read(buffer)) != -1) {
                        zos.write(buffer, 0, length);
                    }
                    zos.closeEntry();
                } finally {
                    try {
                        fis.close();
                    } catch (IOException e) {
                    }
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Could not zip bug report", e);
            throw new ZipException();
        } finally {
            if (zos != null)
                try {
                    zos.close();
                } catch (IOException e) {
                }
        }
        return zippedFile;
    }
}
