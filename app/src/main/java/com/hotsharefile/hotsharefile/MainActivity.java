package com.hotsharefile.hotsharefile;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends ComponentActivity implements ProgInterface {

    private TextView ipText;
    private ImageView ipImage;
    private Button btnPickFiles;
    
    private boolean showingImage = false;
    private boolean canclearpost = false;
    private SimpleHttpServer server;
    
    public ArrayList<Uri> selectedFiles = new ArrayList<>();
    public int global_len_i = 0;
    private final Map<String, TextView> fileTextViews = new HashMap<>();

    private final ActivityResultLauncher<Intent> pickFilesLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Intent data = result.getData();
                    selectedFiles.clear();
                    
                    if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        for (int i = 0; i < count; i++) {
                            selectedFiles.add(data.getClipData().getItemAt(i).getUri());
                        }
                    } else if (data.getData() != null) {
                        selectedFiles.add(data.getData());
                    }
                    doUi();
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); 

        ipText = findViewById(R.id.ipText);
        ipImage = findViewById(R.id.ipImage);
        btnPickFiles = findViewById(R.id.btnPickFiles);

        Intent intent = getIntent();
        String action = intent.getAction();
        String type = intent.getType();

        if (Intent.ACTION_SEND.equals(action) && type != null) {
            handleSingleSharedFile(intent);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action) && type != null) {
            handleMultipleSharedFiles(intent);
        }
        
        if (server == null) {
            server = new SimpleHttpServer(this, selectedFiles, this);
            server.start();
        }

        ipText.setText(getDeviceIp() + ":8888");

        View.OnClickListener toggleListener = v -> {
            if (!showingImage) {
                Bitmap bmp = QR.GenerateQrCode(400, "http://" + getDeviceIp() + ":8888/");
                ipImage.setImageBitmap(bmp);
                ipText.setVisibility(View.GONE);
                ipImage.setVisibility(View.VISIBLE);
                showingImage = true;
            } else {
                ipText.setTextColor(Color.RED);
                ipText.setText(getDeviceIp() + ":8888");
                ipImage.setVisibility(View.GONE);
                ipText.setVisibility(View.VISIBLE);
                showingImage = false;
            }
        };

        ipText.setOnClickListener(toggleListener);
        ipImage.setOnClickListener(toggleListener);
        btnPickFiles.setOnClickListener(v -> {
            Intent pickerIntent = new Intent(Intent.ACTION_GET_CONTENT);
            pickerIntent.setType("*/*");
            pickerIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            pickerIntent.addCategory(Intent.CATEGORY_OPENABLE);
            pickFilesLauncher.launch(Intent.createChooser(pickerIntent, "Select Files"));
        });
    }

    @Override
    public void onProgress(final char type, final String fileName, final String fileIndex, final int percent) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;

            if (type == '1') {
                LinearLayout containers = findViewById(R.id.containers);
                if (containers == null) return;

                if (canclearpost) {
                    containers.removeAllViews();
                    fileTextViews.clear();
                    canclearpost = false;
                }

                String[] parts = fileIndex.split("/");
                if (parts.length == 2 && parts[0].equals(parts[1]) && percent == 100) {
                    canclearpost = true;
                }

                TextView tv = fileTextViews.get(fileName);
                if (tv == null) {
                    tv = new TextView(MainActivity.this);
                    tv.setTextSize(16);
                    tv.setPadding(30, 20, 20, 20);
                    tv.setTypeface(null, android.graphics.Typeface.BOLD);
                    containers.addView(tv);
                    fileTextViews.put(fileName, tv);
                }

                tv.setText("\u200E" + fileName + " " + fileIndex + "  " + percent + "%");
                tv.setTextColor(percent == 100 ? Color.GREEN : Color.BLUE);

            } else if (type == '0') {
                LinearLayout container = findViewById(R.id.container);
                if (container == null) return;

                try {
                    int idx = Integer.parseInt(fileIndex) - 1;
                    if (idx >= 0 && idx < container.getChildCount()) {
                        TextView item_n = (TextView) container.getChildAt(idx);
                        if (item_n != null) {
                            item_n.setText("\u200E" + fileName + "  " + fileIndex + "/" + global_len_i + " " + percent + "%");
                            item_n.setTextColor(percent == 100 ? Color.GREEN : Color.MAGENTA);
                        }
                    }
                } catch (NumberFormatException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private void handleMultipleSharedFiles(Intent intent) {
        ArrayList<Uri> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
        if (uris != null && !uris.isEmpty()) {
            selectedFiles.addAll(uris);
            doUi();
        }
    }

    private void handleSingleSharedFile(Intent intent) {
        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        if (uri != null) {
            selectedFiles.add(uri);
        }
        doUi();
    }

    public void doUi() {
        LinearLayout container = findViewById(R.id.container);
        if (server == null || container == null) return;
        
        container.removeAllViews();
        int i = 1;
        for (Uri uri : selectedFiles) {
            TextView tv = new TextView(this);
            tv.setText("\u200E" + server.getFileName(uri) + " - " + i);
            tv.setTextSize(16);
            tv.setTypeface(null, android.graphics.Typeface.BOLD);
            tv.setTextColor(Color.MAGENTA);
            tv.setPadding(30, 20, 20, 20);
            container.addView(tv);
            i++;
        }
        global_len_i = (i - 1);
        btnPickFiles.setText("Selected " + global_len_i + " Files");
    }

    private String getDeviceIp() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getApplicationContext().getSystemService(CONNECTIVITY_SERVICE);

            if (cm != null) {
                Network network = cm.getActiveNetwork();
                if (network != null) {
                    LinkProperties lp = cm.getLinkProperties(network);
                    if (lp != null) {
                        for (LinkAddress la : lp.getLinkAddresses()) {
                            InetAddress address = la.getAddress();
                            if (address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
                                return address.getHostAddress();
                            }
                        }
                    }
                }
            }

            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address && !inetAddress.isLinkLocalAddress()) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "127.0.0.1";
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (server != null && server.isAlive()) {
            server.interrupt();
        }
    }
}