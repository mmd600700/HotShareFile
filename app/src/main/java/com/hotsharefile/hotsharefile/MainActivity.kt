package com.hotsharefile.hotsharefile

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

class MainActivity : ComponentActivity() {

    private var server: SimpleHttpServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val initialFiles = mutableListOf<Uri>()
        handleIncomingIntent(intent, initialFiles)

        setContent {
            HotShareApp(
                context = this,
                initialFiles = initialFiles,
                onServerInit = { httpServer -> server = httpServer },
                getDeviceIp = { getDeviceIp() }
            )
        }
    }

    private fun handleIncomingIntent(intent: Intent?, fileList: MutableList<Uri>) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type ?: return

        if (Intent.ACTION_SEND == action) {
            val uri = getParcelableExtra<Uri>(intent, Intent.EXTRA_STREAM)
            uri?.let { fileList.add(it) }
        } else if (Intent.ACTION_SEND_MULTIPLE == action) {
            val uris = getParcelableArrayListExtra<Uri>(intent, Intent.EXTRA_STREAM)
            uris?.let { fileList.addAll(it) }
        }
    }

    @Suppress("DEPRECATION")
    private fun <T : Parcelable> getParcelableExtra(intent: Intent, key: String): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(key, Uri::class.java) as? T
        } else {
            intent.getParcelableExtra(key) as? T
        }
    }

    @Suppress("DEPRECATION")
    private fun <T : Parcelable> getParcelableArrayListExtra(intent: Intent, key: String): ArrayList<T>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(key, Uri::class.java) as? ArrayList<T>
        } else {
            intent.getParcelableArrayListExtra(key)
        }
    }

    private fun getDeviceIp(): String {
        try {
            val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.let {
                val network: Network? = it.activeNetwork
                network?.let { net ->
                    val lp = it.getLinkProperties(net)
                    lp?.linkAddresses?.forEach { la: LinkAddress ->
                        val address = la.address
                        if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                            return address.hostAddress ?: "127.0.0.1"
                        }
                    }
                }
            }

            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                val enumIpAddr = intf.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address && !inetAddress.isLinkLocalAddress) {
                        return inetAddress.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "127.0.0.1"
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stopServer()
    }
}

data class DownloadProgressItem(
    val fileName: String,
    val fileIndexStr: String,
    val percent: Int
)

data class UploadProgressItem(
    val fileName: String,
    val fileIndex: String,
    val percent: Int
)

@Composable
fun HotShareApp(
    context: Context,
    initialFiles: List<Uri>,
    onServerInit: (SimpleHttpServer) -> Unit,
    getDeviceIp: () -> String
) {
    val selectedFiles = remember { mutableStateListOf<Uri>().apply { addAll(initialFiles) } }
    var showingImage by remember { mutableStateOf(false) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val downloadsMap = remember { mutableStateMapOf<Int, DownloadProgressItem>() }
    val uploadsMap = remember { mutableStateMapOf<String, UploadProgressItem>() }
    var canClearUploads by remember { mutableStateOf(false) }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val data = result.data
            selectedFiles.clear()
            downloadsMap.clear()

            data?.clipData?.let { clipData ->
                for (i in 0 until clipData.itemCount) {
                    selectedFiles.add(clipData.getItemAt(i).uri)
                }
            } ?: data?.data?.let { uri ->
                selectedFiles.add(uri)
            }
        }
    }
    val server = remember {
        SimpleHttpServer(context, selectedFiles).also {
            onServerInit(it)
            it.startServer()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            server.stopServer()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(server) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            server.progressFlow.collect { update ->
                if (update.type == '1') {
                    if (canClearUploads) {
                        uploadsMap.clear()
                        canClearUploads = false
                    }
                    val parts = update.fileIndex.split("/")
                    if (parts.size == 2 && parts[0] == parts[1] && update.percent == 100) {
                        canClearUploads = true
                    }
                    uploadsMap[update.fileName] = UploadProgressItem(
                        fileName = update.fileName,
                        fileIndex = update.fileIndex,
                        percent = update.percent
                    )
                } else if (update.type == '0') {
                    val idx = update.fileIndex.toIntOrNull()?.minus(1) ?: -1
                    if (idx >= 0) {
                        downloadsMap[idx] = DownloadProgressItem(
                            fileName = update.fileName,
                            fileIndexStr = "${update.fileIndex}/${selectedFiles.size}",
                            percent = update.percent
                        )
                    }
                }
            }
        }
    }

    val ipAddress = remember { getDeviceIp() }
    val serverUrl = "http://$ipAddress:8888/"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .navigationBarsPadding()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .padding(vertical = 12.dp)
                .clickable {
                    if (!showingImage) {
                        qrBitmap = QR.generateQrCode(400, serverUrl)
                        showingImage = true
                    } else {
                        showingImage = false
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (showingImage && qrBitmap != null) {
                Image(
                    bitmap = qrBitmap!!.asImageBitmap(),
                    contentDescription = "QR Code",
                    modifier = Modifier.size(200.dp)
                )
            } else {
                BasicText(
                    text = "$ipAddress:8888",
                    style = TextStyle(
                        color = Color.Red,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = Modifier.padding(8.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .padding(vertical = 10.dp)
                .background(Color(0xFF2E2E2E), RoundedCornerShape(8.dp))
                .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                .clickable {
                    val pickerIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    pickerLauncher.launch(Intent.createChooser(pickerIntent, "Select Files"))
                }
                .padding(horizontal = 24.dp, vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            BasicText(
                text = if (selectedFiles.isEmpty()) "select file " else "Selected ${selectedFiles.size} Files",
                style = TextStyle(
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF1E1E1E), RoundedCornerShape(6.dp))
                .border(1.dp, Color(0xFF2D2D2D), RoundedCornerShape(6.dp))
                .padding(4.dp)
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(selectedFiles) { index, uri ->
                    val fileName = remember(uri) { server.getFileName(uri) }
                    val progressInfo = downloadsMap[index]

                    val displayText = if (progressInfo != null) {
                        "\u200E$fileName  ${progressInfo.fileIndexStr} ${progressInfo.percent}%"
                    } else {
                        "\u200E$fileName - ${index + 1}"
                    }

                    val textColor = if (progressInfo != null) {
                        if (progressInfo.percent == 100) Color.Green else Color.Magenta
                    } else {
                        Color.Magenta
                    }

                    BasicText(
                        text = displayText,
                        style = TextStyle(
                            color = textColor,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF1E1E1E), RoundedCornerShape(6.dp))
                .border(1.dp, Color(0xFF2D2D2D), RoundedCornerShape(6.dp))
                .padding(4.dp)
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(uploadsMap.values.toList()) { upload ->
                    val textColor = if (upload.percent == 100) Color.Green else Color.Blue

                    BasicText(
                        text = "\u200E${upload.fileName} ${upload.fileIndex}  ${upload.percent}%",
                        style = TextStyle(
                            color = textColor,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}
