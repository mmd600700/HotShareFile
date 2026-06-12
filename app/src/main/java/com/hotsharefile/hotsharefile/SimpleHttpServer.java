package com.hotsharefile.hotsharefile;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class SimpleHttpServer extends Thread {

    private ServerSocket serverSocket;
    private volatile boolean running = true;
    private final Context context;
    private final ProgInterface progInterface;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final int PORT = 8888;
    private static final int BUFFER_SIZE = 1024 * 1024 *4; 

    public ArrayList<Uri> selectedFiles;

    public SimpleHttpServer(Context ctx, ArrayList<Uri> s, ProgInterface p) {
        context = ctx;
        selectedFiles = s;
        progInterface = p;
    }

    @Override
    public void run() {
        try {
            serverSocket = new ServerSocket(PORT);
            while (running) {
                Socket socket = serverSocket.accept();
                new ClientHandler(socket).start();
            }
        } catch (Exception e) {
            if (running) e.printStackTrace();
        }
    }

    public void stopServer() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (Exception ignored) {
        }
    }

    class ClientHandler extends Thread {
        private final Socket socket;

        ClientHandler(Socket s) {
            socket = s;
        }

        private String readLine(InputStream is) throws IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = is.read()) != -1) {
                if (c == '\r') {
                    int next = is.read();
                    if (next == '\n') break;
                    sb.append((char) c).append((char) next);
                } else if (c == '\n') {
                    break;
                } else {
                    sb.append((char) c);
                }
            }
            if (sb.length() == 0 && c == -1) return null;
            return sb.toString();
        }

        @Override
        public void run() {
            try {
                socket.setSendBufferSize(BUFFER_SIZE);
                socket.setReceiveBufferSize(BUFFER_SIZE);
                socket.setTcpNoDelay(true);

                InputStream input = new BufferedInputStream(socket.getInputStream());
                OutputStream output = socket.getOutputStream();

                String requestLine = readLine(input);
                if (requestLine == null) return;

                String[] parts = requestLine.split(" ");
                if (parts.length < 2) return;

                String method = parts[0];
                String path = parts[1];

                long contentLength = 0;
                String fileName = "unknown_file";
                String fileSize = "0";
                String fileIndex = "0/0";

                String line;
                while ((line = readLine(input)) != null && !line.isEmpty()) {
                    int idx = line.indexOf(":");
                    if (idx == -1) continue;

                    String header = line.substring(0, idx).trim();
                    String value = line.substring(idx + 1).trim();

                    if (header.equalsIgnoreCase("Content-Length")) {
                        contentLength = Long.parseLong(value);
                    } else if (header.equalsIgnoreCase("X-File-Name")) {
                        fileName = URLDecoder.decode(value, StandardCharsets.UTF_8);
                    } else if (header.equalsIgnoreCase("X-File-Index")) {
                        fileIndex = value;
                    } else if (header.equalsIgnoreCase("X-File-Size")) {
                        fileSize = value;
                    }
                }

                if (method.equals("GET")) {
                    handleGet(path, output);
                } else if (method.equals("POST")) {
                    handlePost(path, input, output, contentLength, fileName, fileSize, fileIndex);
                }

                output.flush();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }

        void handleGet(String path, OutputStream out) throws Exception {
            if (path.equals("/")) {
                String html = loadHtml("page.html");
                writeResponse(out, "text/html; charset=UTF-8", html.getBytes(StandardCharsets.UTF_8));
                return;
            }

            if (path.equals("/q")) {
                String msg = "Server shutting down...";
                writeResponse(out, "text/plain; charset=UTF-8", msg.getBytes(StandardCharsets.UTF_8));
                stopServer();
                return;
            }

            if (path.equals("/files")) {
                StringBuilder items = new StringBuilder();
                int i = 0;
                for (Uri uri : selectedFiles) {
                    items.append("<li><a href=\"/download/")
                            .append(i)
                            .append("\">")
                            .append(getFileName(uri))
                            .append("</a></li>");
                    i++;
                }
                String html = loadHtml("paged.html");
                html = html.replace("{{items}}", items.toString());
                writeResponse(out, "text/html; charset=UTF-8", html.getBytes(StandardCharsets.UTF_8));
                return;
            }

            if (path.startsWith("/download/")) {
                int idx = Integer.parseInt(path.split("/")[2]);
                if (idx < 0 || idx >= selectedFiles.size()) {
                    write404(out);
                    return;
                }

                Uri uri = selectedFiles.get(idx);
                String fileName = "unknown";
                long size = -1;
                try (Cursor cursor = context.getContentResolver().query(uri, 
                        new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                        if (nameIndex != -1) fileName = cursor.getString(nameIndex);
                        if (sizeIndex != -1) size = cursor.getLong(sizeIndex);
                    }
                }
                try (InputStream fis = new BufferedInputStream(context.getContentResolver().openInputStream(uri), BUFFER_SIZE)) {
                    StringBuilder headerBuilder = new StringBuilder();
                    headerBuilder.append("HTTP/1.1 200 OK\r\n")
                            .append("Content-Type: application/octet-stream\r\n");
                    
                    if (size != -1) {
                        headerBuilder.append("Content-Length: ").append(size).append("\r\n")
                                .append("Connection: keep-alive\r\n");
                    } else {
                        headerBuilder.append("Connection: close\r\n");
                    }
                    headerBuilder.append("Content-Disposition: attachment; filename=\"")
                            .append(fileName).append("\"\r\n\r\n");

                    out.write(headerBuilder.toString().getBytes(StandardCharsets.UTF_8));

                    byte[] buffer = new byte[BUFFER_SIZE];
                    int n;
                    long totalSent = 0;
                    int lastPercent = -1;

                    while ((n = fis.read(buffer)) != -1) {
                        out.write(buffer, 0, n);
                        totalSent += n;

                        final int percent = (size > 0) ? (int) ((totalSent * 100L) / size) : 0;
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            final String fname = fileName;
                            final int fidx = (idx + 1);
                            
                            mainHandler.post(() -> {
                                if (progInterface != null) {
                                    progInterface.onProgress('0', fname, String.valueOf(fidx), percent);
                                }
                            });
                        }
                    }
                    out.flush();
                }
                return;
            }
            write404(out);
        }

        void handlePost(String path, InputStream input, OutputStream out, long length, String name, String size, String fileIndex) throws Exception {
            if (!path.equals("/upload")) {
                write404(out);
                return;
            }

            ContentResolver resolver = context.getContentResolver();
            Uri uri = null;

            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                values.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/uploads");
                values.put(MediaStore.Downloads.IS_PENDING, 1);

                uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    throw new IOException("Failed to create file in Downloads");
                }

                try (OutputStream fos = resolver.openOutputStream(uri)) {
                    long remaining = length;
                    int totalRead = 0;
                    int lastPercent = -1;
                    byte[] buffer = new byte[BUFFER_SIZE];

                    while (remaining > 0) {
                        int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (read == -1) break;

                        fos.write(buffer, 0, read);
                        remaining -= read;
                        totalRead += read;

                        final int percent = (length > 0) ? (int) ((totalRead * 100L) / length) : 0;
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            final String fname = name;
                            final String ffileIndex = fileIndex;
                            
                            mainHandler.post(() -> {
                                if (progInterface != null) {
                                    progInterface.onProgress('1', fname, ffileIndex, percent);
                                }
                            });
                        }
                    }
                    fos.flush();
                }

                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                resolver.update(uri, values, null, null);

            } catch (Exception e) {
                if (uri != null) {
                    resolver.delete(uri, null, null);
                }
                throw e;
            }

            String resp = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok";
            out.write(resp.getBytes(StandardCharsets.UTF_8));
        }

        void writeResponse(OutputStream out, String type, byte[] data) throws Exception {
            String header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: " + type + "\r\n" +
                    "Content-Length: " + data.length + "\r\n" +
                    "Connection: keep-alive\r\n\r\n";
            out.write(header.getBytes(StandardCharsets.UTF_8));
            out.write(data);
        }

        void write404(OutputStream out) throws Exception {
            out.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        }
    }

    private String loadHtml(String fileName) {
        StringBuilder html = new StringBuilder();
        try (InputStream is = context.getAssets().open(fileName);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                html.append(line).append("\n");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return html.toString();
    }

    public String getFileName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = context.getContentResolver().query(uri, 
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index != -1) {
                        result = cursor.getString(index);
                    }
                }
            }
        }
        if (result == null) {
            result = uri.getLastPathSegment();
        }
        return result;
    }
}