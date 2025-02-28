package com.example.cloudycam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpProgressMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.time.LocalDateTime
import android.provider.OpenableColumns
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.graphics.drawable.toBitmap
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import java.io.File
import java.text.DecimalFormat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.text.SimpleDateFormat
import java.util.*
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt



//END OF IMPORTS START OF VARIABLES

class CameraActivity : AppCompatActivity() {
    companion object {

        private const val KNOWN_HOSTS_KEY = "known_hosts"
    }
//DECLARE VARIABLES
    var sftpChannel: ChannelSftp? = null // Reference to the SFTP channel
    lateinit var knownHosts: String
    var latestTmpFileUri: Uri? = null
    var snap: Uri? = null
    lateinit var username: EditText
    lateinit var password: EditText
    lateinit var hostname: EditText
    lateinit var port: EditText
    lateinit var remoteFilePath: EditText
    lateinit var sharedPreferences: SharedPreferences
    lateinit var progressBar: ProgressBar
    lateinit var extension: String
    var lastkey: String? = null
    var keymode: Boolean = false
    lateinit var progressBarHorizontal: ProgressBar
    var filesizeinbytes: Long = 0
    var filesizeformatted: String = ""
    var video: Boolean = false
    var privatemode: Boolean = false
    var filename: String = ""
    var BiometricEnabled = true
    private var shouldRequestBiometric = true


    //END OF VARIABLES START OF PERMISSION CHECK
    private val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
            Manifest.permission.RECEIVE_BOOT_COMPLETED,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.USE_BIOMETRIC
        )
    } else {
        arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
            Manifest.permission.RECEIVE_BOOT_COMPLETED,
            Manifest.permission.USE_BIOMETRIC
        )
    }


    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                show("Permissions granted")
                video = readBooleanFromFile("videoSwitchState.txt")
                val videoSwitchState = video
                privatemode = readBooleanFromFile("privateSwitchState.txt")
                takeMedia(videoSwitchState, privatemode)
            } else {
                show("Permissions not granted")
                finish()
            }
        }
    // END OF PERMISSION CHECK START OF IMAGE CAPTURE

    private val takeMediaResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && privatemode) {
                //authenticateUser()
                latestTmpFileUri = snap

                // Image capture was successful
                latestTmpFileUri?.let { uri ->
                    findViewById<Button>(R.id.button_upload)?.apply {
                        visibility = Button.VISIBLE
                    }
                    findViewById<ImageView>(R.id.preview)?.apply {
                        visibility = ImageView.VISIBLE
                        setImageBitmap(getImageFromUri(context, uri))

                        val internaldir = File ("$filesDir/Storage")
                        val filenametokeep = getFileName(snap!!)
                        deleteFilesExcept(filenametokeep, internaldir)



                    }}}else{
            if (result.resultCode == Activity.RESULT_OK) {
               // authenticateUser()
                latestTmpFileUri = snap
                // Image capture was successful
                latestTmpFileUri?.let { uri ->
                    findViewById<Button>(R.id.button_upload)?.apply {
                        visibility = Button.VISIBLE
                    }
                    findViewById<ImageView>(R.id.preview)?.apply {
                        visibility = ImageView.VISIBLE
                        (setImageBitmap(getImageFromUri(context, uri)))
                    }
                }
             } else {            if (result.resultCode == Activity.RESULT_CANCELED && !privatemode) {
                // Image capture was not successful (e.g., canceled or failed)
                //authenticateUser()
                show("Capture canceled.")
                contentResolver.delete(snap!!, null, null)
                snap = null


            }else { authenticateUser()
                show("Capture canceled.")
            }
            }
        }





        }
    fun deleteFilesExcept(filenameToKeep: String, directory: File) {
        if (!directory.exists() || !directory.isDirectory) {
            // Directory doesn't exist or is not a directory, nothing to delete
            return
        }

        val files = directory.listFiles()
        files?.forEach { file ->
            // Check if the file name is not equal to the specified file name
            if (file.name != filenameToKeep) {
                // Delete the file
                file.delete()
            }
        }
    }



//DISPLAY PREVIEW OF VIDEO FROM URI
    fun getVideoFrameAtTime(context: Context, videoUri: Uri): Bitmap? {
        var bitmap: Bitmap? = null
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, videoUri)
            bitmap = retriever.getFrameAtTime(
                (1 * 1000).toLong(),
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )
            retriever.release()
        } catch (e: IllegalArgumentException) {
            e.printStackTrace()
        } catch (e: RuntimeException) {
            e.printStackTrace()
        }
        return bitmap
    }

// START OF DISPLAY PREVIEW IMAGE FROM URI

    @SuppressLint("UseCompatLoadingForDrawables")
    fun imageuriToBitmap(uri: Uri): Bitmap? {
        return try {
            val inputStream = contentResolver.openInputStream(uri)
            inputStream?.use { input ->
                // Decode bitmap with BitmapFactory without considering orientation
                val options = BitmapFactory.Options()
                options.inJustDecodeBounds = false
                options.inPreferredConfig = Bitmap.Config.ARGB_8888
                options.inMutable = true
                var bitmap = BitmapFactory.decodeStream(input, null, options)

                // Check the orientation of the image
                val exif = ExifInterface(contentResolver.openInputStream(uri)!!)
                val orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_UNDEFINED
                )
                // Rotate the bitmap if necessary
                bitmap = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> bitmap?.let { rotateBitmap(it, 90f) }
                    ExifInterface.ORIENTATION_ROTATE_180 -> bitmap?.let { rotateBitmap(it, 180f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> bitmap?.let { rotateBitmap(it, 270f) }
                    else -> bitmap
                }
                bitmap
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null

        }
    }
    // Function to write boolean value to a file
    fun writeBooleanToFile(value: Boolean, fileName: String) {
        val file = File("${filesDir}$fileName")
        file.writeText(value.toString())
    }

    // Function to read boolean value from a file
    fun readBooleanFromFile(fileName: String): Boolean {
        val file = File("${filesDir}$fileName")
        return if (file.exists()) {
            val text = file.readText().trim()
            text.toBoolean()
        } else {
            false // Default value if file does not exist
        }
    }
    //SHOW A PREVIEW IMAGE "TYPE" FOR GENERIC FILE UPLOAD
    //DOCUMENTS, AUDIO, AND ARCHIVES ETC.
    @SuppressLint("UseCompatLoadingForDrawables")
    fun getImageFromUri(context: Context, uri: Uri): Bitmap? {
        progressBarHorizontal.progress = 0
        latestTmpFileUri = uri
        val mimeType = context.contentResolver.getType(uri)
        val filename = getFileName(uri)
        val fileNameTextView = findViewById<TextView>(R.id.progresstext)
        filesizeformatted = formatFileSize(getFileSize(this, latestTmpFileUri!!))
        filesizeinbytes = getFileSize(this, latestTmpFileUri!!)
        val monkey = if(privatemode) {"PRIVATE 🙈 "} else {""}
        val progressText = "$monkey$filename $filesizeformatted"
        fileNameTextView.text = progressText
        return if (filename.contains(".jpg", true) || filename.contains(
                ".jpeg",
                true
            ) || filename.contains(".png", true) || filename.contains(".gif", true)
        ) {
            imageuriToBitmap(uri)
        } else if (filename.contains(".mov", true) || filename.contains(
                ".mp4",
                true
            ) || filename.contains(".avi", true)
        ) {
            getVideoFrameAtTime(context, uri)

        } else if (filename.contains(".wav", true) || filename.contains(
                ".mpeg",
                true
            ) || filename.contains(".ogg", true)
        ) {
            getDrawable(R.drawable.audio_file_fill0_wght400_grad0_opsz24)?.toBitmap(600, 600)

        } else
            when (mimeType) {
                // Audio MIME types
                in listOf(
                    "audio/flac",
                    "audio/aac",
                    "audio/x-ms-wma",
                    "audio/x-aiff",
                    "audio/midi"
                ) -> {
                    getDrawable(R.drawable.audio_file_fill0_wght400_grad0_opsz24)?.toBitmap(
                        600,
                        600
                    )
                }
                // Document MIME types
                in listOf(
                    "application/pdf",
                    "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.ms-powerpoint",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "text/plain",
                    "application/rtf",
                    "text/html",
                    "application/xml",
                    "text/csv",
                    "application/json",
                    "text/markdown",
                    "application/x-tex"
                ) -> {
                    getDrawable(R.drawable.description_fill0_wght400_grad0_opsz24)?.toBitmap(
                        600,
                        600
                    )
                }
                // Compressed archive MIME types
                in listOf(
                    "application/zip",
                    "application/x-rar-compressed",
                    "application/x-7z-compressed",
                    "application/x-tar",
                    "application/gzip",
                    "application/x-bzip2"
                ) -> {
                    getDrawable(R.drawable.folder_zip_fill0_wght400_grad0_opsz24)?.toBitmap(
                        600,
                        600
                    )
                }
                // Default case
                else -> {
                    getDrawable(R.drawable.draft_fill0_wght400_grad0_opsz24)?.toBitmap(600, 600)
                }
            }
    }

//ROTATE THE PREVIEW IF NECESSARY
    fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = android.graphics.Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
    // END OF IMAGE PREVIEW GENERATION, START OF PRIVATE KEY SELECTOR

    val pickPemFileLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    handlePemFile(uri)
                }
            }
        }

//GET FILE EXTENSION FROM URI BASED ON LAST INDEX OF DOT CHARACTER
    fun getFileExtension(uri: Uri): String {
        val path = uri.path ?: return ""
        val lastIndexOfDot = path.lastIndexOf('.')
        return if (lastIndexOfDot != -1 && lastIndexOfDot < path.length - 1) {
            path.substring(lastIndexOfDot + 1)
        } else {
            ""
        }
    }

    //GET FILE SIZE FROM URI

    fun getFileSize(context: Context, uri: Uri): Long {
        var fileSize: Long = 0
        val contentResolver: ContentResolver = context.contentResolver

        // Check if the URI scheme is "content"
        if ("content".equals(uri.scheme, ignoreCase = true)) {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex != -1) {
                        fileSize = cursor.getLong(sizeIndex)
                    }
                }
            }
        } else {
            // If the URI scheme is "file", get the file size directly
            val file = uri.path?.let { File(it) }
            if (file != null) {
                fileSize = file.length()
            }
        }

        return fileSize
    }

//FORMAT THE FILE SIZE OF THE FILE TO BE EASILY READABLE BY THE USER
    fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        val df = DecimalFormat("#.##")
        return df.format(size / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
    }



    // START OF FILE FOR UPLOAD SELECTOR AND AUTO UPLOAD
    @SuppressLint("UseSwitchCompatOrMaterialCode")
    val pickFileForUploadLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    extension = getFileExtension(uri)
                    filename = getFileName(uri)
                    findViewById<ImageView>(R.id.preview)?.apply {
                        visibility = ImageView.VISIBLE
                        setImageBitmap(getImageFromUri(context, uri))

                    }
                    if (keymode) {
                        val alertDialogBuilder =
                            AlertDialog.Builder(this@CameraActivity, R.style.CustomAlertDialog)
                        val filesize = formatFileSize(getFileSize(this, uri))
                        alertDialogBuilder.setTitle("File Selected\nUploading with Key \uD83D\uDD10")
                        alertDialogBuilder.setMessage("Would you like to continue with the upload of:\n$filename?\n\n$filesize")
                        alertDialogBuilder.setCancelable(false) // Set non-dismissable
                        alertDialogBuilder.setPositiveButton("Yes") { _, _ ->
                            lastkey?.let { uploadFILEToServerWithAuthMethod(uri, it) }
                        }
                        alertDialogBuilder.setNegativeButton("No") { dialog, _ ->
                            progressBar.visibility = ProgressBar.INVISIBLE
                            dialog.dismiss()
                        }
                        val alertDialog = alertDialogBuilder.create()
                        alertDialog.show()
                    } else {
                        val alertDialogBuilder =
                            AlertDialog.Builder(this@CameraActivity, R.style.CustomAlertDialog)
                        val filesize = formatFileSize(getFileSize(this, uri))
                        alertDialogBuilder.setTitle("File Selected\nUploading without Key \uD83D\uDEC2")
                        alertDialogBuilder.setMessage("Would you like to continue with the upload of:\n$filename?\n\n$filesize")
                        alertDialogBuilder.setCancelable(false) // Set non-dismissable
                        alertDialogBuilder.setPositiveButton("Yes") { _, _ ->
                            uploadFILEToServer(uri)
                        }
                        alertDialogBuilder.setNegativeButton("No") { dialog, _ ->
                            progressBar.visibility = ProgressBar.INVISIBLE
                            dialog.dismiss()
                        }
                        val alertDialog = alertDialogBuilder.create()
                        alertDialog.show()
                    }
                }
            }
        }

    // GET FILENAME AND EXTENSION FROM URI
    @SuppressLint("Range")
    fun getFileName(uri: Uri): String {
        var result = ""
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    result = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))
                }
            }
        }
        if (result.isEmpty()) {
            result = uri.path ?: ""
            val cut = result.lastIndexOf('/')
            if (cut != -1) {
                result = result.substring(cut + 1)
            }
        }
        return result
    }
//ON CREATE ACTIONS
    @SuppressLint("WrongViewCast", "UseSwitchCompatOrMaterialCode", "SuspiciousIndentation")
    override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    setContentView(R.layout.activity_main)
    createNotificationChannel()
    username = findViewById(R.id.editTextUsername)
    password = findViewById(R.id.editTextPassword)
    hostname = findViewById(R.id.editTextHostname)
    port = findViewById(R.id.editTextPort)
    remoteFilePath = findViewById(R.id.editTextRemoteFilePath)
    progressBar = findViewById(R.id.progressBar)
    progressBarHorizontal = findViewById(R.id.progressBarHorizontal)
    sharedPreferences = getSharedPreferences("com.example.cloudycam", Context.MODE_PRIVATE)
    knownHosts = sharedPreferences.getString(KNOWN_HOSTS_KEY, "") ?: ""


    loadSavedEntries()
    if (!arePermissionsGranted()) {
        requestPermissions()
    } else {
        video = readBooleanFromFile("videoSwitchState.txt")
        privatemode = readBooleanFromFile("privateSwitchState.txt")
        val privateSwitch: Switch = findViewById(R.id.privatemode)
        if (privatemode) {
            privateSwitch.setTextColor(Color.GREEN)
        } else {
            privateSwitch.setTextColor(Color.parseColor("#A7A2A9"))
        }


        takeMedia(video, privatemode)

    }
    val upload = findViewById<Button>(R.id.button_upload)
    upload.setOnClickListener {
        if (keymode) {
            if (lastkey.isNullOrBlank()) {
                show("Select a Private Key")
            } else {
                latestTmpFileUri?.let { it1 ->
                    uploadFILEToServerWithAuthMethod(
                        it1,
                        lastkey!!
                    )
                }
            }
        } else {
            latestTmpFileUri?.let { it1 -> uploadFILEToServer(it1) }
        }
    }
    findViewById<Button>(R.id.buttonretake)?.apply {
        setOnClickListener {
            takeMedia(video, privatemode)
        }
    }

    val pairsSwitch: Switch = findViewById(R.id.pairs)
    keymode = readBooleanFromFile("pairsSwitchState.txt")
    val pemButton: Button = findViewById<Button?>(R.id.pem).apply {
        visibility = if (keymode) {
            Button.VISIBLE
        } else {
            Button.INVISIBLE
        }
    }
    pairsSwitch.isChecked = keymode
    pairsSwitch.setOnCheckedChangeListener { _, isChecked ->
        pemButton.visibility = if (isChecked) {

            keymode = true
            writeBooleanToFile(keymode, "pairsSwitchState.txt")
            Button.VISIBLE

        } else {
            keymode = false
            writeBooleanToFile(keymode, "pairsSwitchState.txt")
            Button.INVISIBLE
        }
    }
    pemButton.setOnClickListener {
        openKeyFilePicker()

    }

    val videoSwitch: Switch = findViewById(R.id.video)
    videoSwitch.isChecked = sharedPreferences.getBoolean("videoSwitchState", false)
    videoSwitch.setOnCheckedChangeListener { _, isChecked ->
        sharedPreferences.edit().putBoolean("videoSwitchState", isChecked).apply()

        video = isChecked

        writeBooleanToFile(video, "videoSwitchState.txt")

    }
    val privateSwitch: Switch = findViewById(R.id.privatemode)
    privateSwitch.isChecked = sharedPreferences.getBoolean("privateSwitchState", false)

    privateSwitch.setOnCheckedChangeListener { _, isChecked ->
        // Update the switch state in the shared preferences based on the current state
        sharedPreferences.edit().putBoolean("privateSwitchState", isChecked).apply()

        // Set the privatemode variable accordingly
        privatemode = isChecked

        // Write the new state to the file
        writeBooleanToFile(isChecked, "privateSwitchState.txt")
        privatemode = readBooleanFromFile("privateSwitchState.txt")
        if (privatemode) {
            privateSwitch.setTextColor(Color.GREEN)
        } else {
            privateSwitch.setTextColor(Color.parseColor("#A7A2A9"))
        }
    }



    findViewById<ImageView>(R.id.gallery)?.apply {
        setOnClickListener {
            openFilePickerForUpload()
        }
    }
    val listbutton = findViewById<Button>(R.id.buttonlist)
    listbutton.setOnClickListener {
        if (keymode) {
            if (lastkey.isNullOrBlank()) {
                show("Select a Private Key")
            } else {
                listwithkey()
            }
        } else {
            listwithoutkey()
        }
    }

    // Call the biometric authentication method

    // Find the settings button and set an OnClickListener
    findViewById<Button>(R.id.settings)?.setOnClickListener {
        showBiometricSettingsDialog()
    }
}

    private fun authenticateUser() {
        val biometricManager = BiometricManager.from(this)
        when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                // Device can authenticate with biometrics
                showBiometricPrompt()
            }
            else -> {
                // Device cannot authenticate with biometrics
                show("No biometrics available on this device")
            }
        }
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                //unblurScreen() // Unblur the screen on error
                finish() // Close the app on error
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                //unblurScreen() // Unblur the screen on failure
                finish() // Close the app on failure
            }

            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                shouldRequestBiometric = false
                unblurScreen() // Unblur the screen on success
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Biometric Authentication")
            .setSubtitle("Authenticate to access the app")
            .setNegativeButtonText("Cancel")
            .build()

        // Blur the screen when the prompt is shown
        blurScreen()
        biometricPrompt.authenticate(promptInfo)
    }

    private fun blurScreen() {
        val view = window.decorView
        view.alpha = 0f // Adjust the alpha value to create a blur effect
    }

    private fun unblurScreen (){
        val view = window.decorView
        view.alpha = 1f
    }

    fun listwithkey() {
        progressBar.visibility = ProgressBar.VISIBLE
        val user = username.text.toString()
        val host = hostname.text.toString()
        val port = port.text.toString().toIntOrNull() ?: 22
        val lastkey = lastkey
        val folderPath = remoteFilePath.text.toString()

        // Ensure folder path ends with "/"
        if (!folderPath.endsWith("/")) {
            show("Folder path format incorrect")
            progressBar.visibility = ProgressBar.INVISIBLE

            return
        }

        if (user.isBlank()) {
            show("Please enter Username")
            progressBar.visibility = ProgressBar.INVISIBLE

            return

        }

        if (host.isBlank()) {
            show("Please enter Server Address")
            progressBar.visibility = ProgressBar.INVISIBLE

            return
        }
        if (!port.equals(22)) {
            show("Warning! Port is not 22")
        }


        runBlocking {
            launch(Dispatchers.IO) {
                try {
                    val session =
                        lastkey?.let { establishSSHConnectionWithPrivateKey(user, host, port, it) }
                    val serverFingerprint = session?.hostKey?.getFingerPrint(JSch())
                    session?.disconnect()
                    if (serverFingerprint != null) {
                        session.disconnect()
                        show("Failed to retrieve server fingerprint")
                        progressBar.visibility = ProgressBar.INVISIBLE

                    } else {
                        session?.disconnect()
                        show("Failed to establish SSH connection")
                        progressBar.visibility = ProgressBar.INVISIBLE

                    }
                } catch (e: Exception) {

                    if (e.message?.contains("UnknownHostKey:") == true) {
                        val errorMessage = e.message.toString().removePrefix("UnknownHostKey: ")
                        listhostKeyConfirmationwithkey(user, host, port, lastkey, errorMessage)
                    } else {
                        runOnUiThread {
                            show("Using Basic Auth. Failed to establish SSH connection: ${e.message}")
                            progressBar.visibility = ProgressBar.INVISIBLE
                        }
                    }
                }
            }
        }

    }

    //CONFIRM TO LIST OF THE CURRENT FOLDERS CONTENTS - USING A KEY
    private fun listhostKeyConfirmationwithkey(
        user: String,
        host: String,
        port: Int,
        lastkey: String?,
        errorMessage: String
    ) {
        runOnUiThread {
            val positiveClickListener = DialogInterface.OnClickListener { _, _ ->
                if (lastkey != null) {
                    downloadlistwithkey(user, host, port, lastkey)
                }
            }

            val negativeClickListener = DialogInterface.OnClickListener { _, _ ->
                show("Connection cancelled")
                progressBar.visibility = ProgressBar.INVISIBLE
            }

            AlertDialog.Builder(this@CameraActivity, R.style.CustomAlertDialog)
                .setTitle("Host Key Confirmation")
                .setMessage(
                    "${
                        errorMessage.replace(". ", "\n").replace(" is ", ":\n\n")
                    }\n\nYou are using a Private Key \uD83D\uDD10\n\nDo you want to get folder contents as a list?"
                )
                .setPositiveButton("Yes", positiveClickListener)
                .setNegativeButton("No", negativeClickListener)
                .setCancelable(false)
                .show()
        }
    }
//DOWNLOAD LIST OF THE CURRENT FOLDERS CONTENTS - USING A KEY
    private fun downloadlistwithkey(user: String, host: String, port: Int, lastkey: String) {
        val folderPath = remoteFilePath.text.toString()

        Thread {
            try {
                val jsch = JSch()
                val session = jsch.getSession(user, host, port)
                jsch.addIdentity("key", lastkey.toByteArray(), null, null)
                session.setConfig("StrictHostKeyChecking", "yes")
                session.connect()

                sftpChannel = session.openChannel("sftp") as ChannelSftp
                sftpChannel!!.connect()

                val entries = sftpChannel!!.ls(folderPath).toArray().toMutableList()
                val detailedEntries = entries.map {parseFileEntry(it as ChannelSftp.LsEntry) }



                sftpChannel!!.disconnect()
                session.disconnect()

                runOnUiThread {

                    showlisthtml(detailedEntries.toString())


                    progressBar.visibility = ProgressBar.INVISIBLE
                }


            } catch (e: Exception) {
                runOnUiThread {
                    val message = e.message.toString().replace("file","Folder")
                    show("Failed: $message")
                    // Set cancel button visibility to INVISIBLE when upload fails
                    progressBar.visibility = ProgressBar.INVISIBLE

                }
            }
        }.start()
    }

//GET FINGERPRINT FROM SERVER FOR OF THE CURRENT FOLDERS CONTENTS - WITHOUT A KEY
    fun listwithoutkey() {
        progressBar.visibility = ProgressBar.VISIBLE
        val user = username.text.toString()
        val host = hostname.text.toString()
        val port = port.text.toString().toIntOrNull() ?: 22
        val password = password.text.toString()
        val folderPath = remoteFilePath.text.toString()

        // Ensure folder path ends with "/"
        if (!folderPath.endsWith("/")) {
            show("Folder path format incorrect")
            progressBar.visibility = ProgressBar.INVISIBLE

            return
        }
//Ensure there is a username
        if (user.isBlank()) {
            show("Please enter Username")
            progressBar.visibility = ProgressBar.INVISIBLE

            return

        }
//Ensure there is a password
        if (password.isBlank()) {
            show("Please enter Password")
            progressBar.visibility = ProgressBar.INVISIBLE

            return
        }
//Ensure there is a server address
        if (host.isBlank()) {
            show("Please enter Server Address")
            progressBar.visibility = ProgressBar.INVISIBLE

            return
        }
        

//Establish SSH connection and retrieve server fingerprint
        runBlocking {
            launch(Dispatchers.IO) {
                try {
                    val session = establishSSHConnection(user, host, port, password)
                    val serverFingerprint = session?.hostKey?.getFingerPrint(JSch())
                    session?.disconnect()
                    if (serverFingerprint != null) {
                        session.disconnect()
                        show("Failed to retrieve server fingerprint")
                        progressBar.visibility = ProgressBar.INVISIBLE

                    } else {
                        session?.disconnect()
                        show("Failed to establish SSH connection")
                        progressBar.visibility = ProgressBar.INVISIBLE

                    }
                } catch (e: Exception) {

                    if (e.message?.contains("UnknownHostKey:") == true) {
                        val errorMessage = e.message.toString().removePrefix("UnknownHostKey: ")
                        listhostKeyConfirmation(user, host, port, password, errorMessage)
                    } else {
                        runOnUiThread {
                            show("Using Basic Auth. Failed to establish SSH connection: ${e.message}")
                            progressBar.visibility = ProgressBar.INVISIBLE
                        }
                    }
                }
            }
        }
    }

    fun listhostKeyConfirmation(
        user: String,
        host: String,
        port: Int,
        password: String,
        errorMessage: String,
    ) {
        runOnUiThread {
            val positiveClickListener = DialogInterface.OnClickListener { _, _ ->
                downloadlistwithoutkey(user, host, port, password)
            }

            val negativeClickListener = DialogInterface.OnClickListener { _, _ ->
                show("Connection cancelled")
                progressBar.visibility = ProgressBar.INVISIBLE
            }

            AlertDialog.Builder(this@CameraActivity, R.style.CustomAlertDialog)
                .setTitle("Host Key Confirmation")
                .setMessage(
                    "${
                        errorMessage.replace(". ", "\n").replace(" is ", ":\n\n")
                    }\n\nYou are using basic Auth \uD83D\uDEC2\n\nDo you want to get folder contents as a list?"
                )
                .setPositiveButton("Yes", positiveClickListener)
                .setNegativeButton("No", negativeClickListener)
                .setCancelable(false)
                .show()

        }
    }

//DOWNLOAD LIST OF THE CURRENT FOLDERS CONTENTS - WITHOUT A KEY
    @SuppressLint("SuspiciousIndentation")
    fun downloadlistwithoutkey(
        user: String,
        host: String,
        port: Int,
        password: String
    ) {
        val folderPath = remoteFilePath.text.toString()

        Thread {
            try {
                val jsch = JSch()
                val session = jsch.getSession(user, host, port)
                session.setPassword(password)
                session.setConfig("StrictHostKeyChecking", "no")
                session.connect()

                val sftpChannel = session.openChannel("sftp") as ChannelSftp
                sftpChannel.connect()

                val entries = sftpChannel.ls(folderPath).toArray().toMutableList()
                val detailedEntries = entries.map {parseFileEntry(it as ChannelSftp.LsEntry) }

                sftpChannel.disconnect()
                session.disconnect()

                runOnUiThread {

                    showlisthtml(detailedEntries.sortedDescending().reversed().toString().removeSurrounding("[","]"))


                    progressBar.visibility = ProgressBar.INVISIBLE
                }

            } catch (e: Exception) {
                runOnUiThread {
                    val message = e.message.toString().replace("file","Folder")
                    show("Failed: $message")
                    // Set cancel button visibility to INVISIBLE when upload fails
                    progressBar.visibility = ProgressBar.INVISIBLE
                }
            }



        }.start()
    }
    //PARSE THE RETRIEVED LIST SO AS TO DISPLAY THE CONTENTS OF THE FOLDER IN HTML FORMAT
    @SuppressLint("SimpleDateFormat")
    private fun parseFileEntry(entry: ChannelSftp.LsEntry): String {
        val fileName = entry.filename

        val color = if (fileName.matches(Regex("^[^.].*\\..+$"))) "#80ccff" else "#80ff80"
        val emoji = if (fileName.matches(Regex("^[^.].*\\..+$"))) "📄" else "📁"
        val attrs = entry.attrs
        val fileSize = formatFileSize(attrs.size)
        val permissions = Integer.toOctalString(attrs.permissions)
        val timestamp = attrs.mTime * 1000L // Convert to milliseconds
        val date = Date(timestamp)
        val dateFormat = SimpleDateFormat("dd MMM yyyy HH:mm")
        val formattedDate = dateFormat.format(date)
        val firstLine = "<font color=\"$color\"><br><br><b>$emoji $fileName</b></font><br>$fileSize<br>$formattedDate<br>$permissions"


        // Display the popup with the first line

        return firstLine
    }
//SHOW THE RETRIEVED LIST IN HTML FORMAT
    private fun showlisthtml(firstLine: String) {
        val listdialog = AlertDialog.Builder(this@CameraActivity, R.style.CustomAlertDialogList)
            .setTitle("Directory Information")
            .setMessage(Html.fromHtml(firstLine))
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .setCancelable(true)
            .show()

        listdialog.findViewById<TextView>(android.R.id.message)?.apply {
            textSize = 10f
        }
    }



//OPEN FILE PICKER FOR PRIVATE KEY
    fun openKeyFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*" // Accept all MIME types
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/x-pem-file", // PEM files
                    "application/octet-stream" // BIN files
                )
            )
        }
        pickPemFileLauncher.launch(intent)
    }
//OPEN FILE PICKER FOR UPLOAD OF GENERIC FILE
    fun openFilePickerForUpload() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*" // Accept all MIME types
        }
        pickFileForUploadLauncher.launch(intent)
    }
//PROCESS THE RETRIEVED PRIVATE KEY
    fun handlePemFile(uri: Uri) {
        contentResolver.openInputStream(uri)?.use { inputStream ->
            val reader = BufferedReader(InputStreamReader(inputStream))
            val privateKeyStringBuilder = StringBuilder()
            var line: String? = reader.readLine()
            while (line != null) {
                privateKeyStringBuilder.append(line).append("\n")
                line = reader.readLine()
            }
            val privateKey = privateKeyStringBuilder.toString()
            val privatekeylength = privateKey.length
            val privatepreview = privateKey.replace("-", "").dropLast(((privatekeylength / 10) * 9))
            lastkey = privateKey
            val alertDialogBuilder = AlertDialog.Builder(this, R.style.CustomAlertDialog)
            alertDialogBuilder.setTitle("Private Key Read Successfully! \uD83D\uDD11")
            alertDialogBuilder.setMessage("Preview:\n\n$privatepreview\n\n...\n\nDo you want to proceed with the upload?")
            alertDialogBuilder.setCancelable(false) // Set non-dismissable
            alertDialogBuilder.setPositiveButton("Yes") { _, _ ->
                uploadFILEToServerWithAuthMethod(uri, privateKey)
            }
            alertDialogBuilder.setNegativeButton("No") { dialog, _ ->
                dialog.dismiss()
            }
            val alertDialog = alertDialogBuilder.create()
            alertDialog.show()
        }
    }
//LOAD THE SAVED ENTRIES FROM SHARED PREFERENCES AND DISPLAY THEM IN THE FIELDS OF THE ACTIVITY
    fun loadSavedEntries() {
        val fields = arrayOf(username, password, hostname, port, remoteFilePath)
        val keys = arrayOf("username", "password", "hostname", "port", "remoteFilePath")
        keys.forEachIndexed { index, key ->
            fields[index].setText(sharedPreferences.getString(key, ""))
        }
    }

    override fun onPause() {
        super.onPause()
        blurScreen()
        shouldRequestBiometric = true
        val editor = sharedPreferences.edit()
        val fields = arrayOf(username, password, hostname, port, remoteFilePath)
        val keys = arrayOf("username", "password", "hostname", "port", "remoteFilePath")
        keys.forEachIndexed { index, key ->
            editor.putString(key, fields[index].text.toString())
        }
        editor.apply()

        editor.putString(KNOWN_HOSTS_KEY, knownHosts)
        editor.apply()
    }
    //SAVE THE MEDIA TO DEVICE (CURRENTLY IN MP4 or JPG)
    fun takeMedia(isVideo: Boolean, privatemode: Boolean) {
        val date = LocalDateTime.now()
        val formatter = java.time.format.DateTimeFormatter.ofPattern("HHmmss_dd-MM-yyyy")
        val datetime = date.format(formatter)
        val externalFilesDir = this.getExternalFilesDir(null)

        extension = if (isVideo) ".mp4" else ".jpg"
        filename = "$datetime$extension"

        if (!privatemode){
        val intent = if (isVideo) Intent(MediaStore.ACTION_VIDEO_CAPTURE) else Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        intent.resolveActivity(packageManager)?.let {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else "image/jpeg")
                if (privatemode){put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    filesDir.path
                )}else {
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "DCIM/CloudyCam"
                    ) // Note: Use "DCIM/photos" to match the default camera directory structure
                }
            }

            val contentResolver = applicationContext.contentResolver

            // Create the media file
            snap =
                contentResolver.insert(if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

            snap?.let { uri ->
                intent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
                takeMediaResult.launch(intent)
                val photosDir = File(externalFilesDir, "CloudyCam")

                if (!photosDir.exists()) {
                    photosDir.mkdirs() // Creates directories if they do not exist
                }
            } ?: show("Failed to create media file")
        } ?: show("No camera app found")
    }else{
takeAndHandleMedia(isVideo,privatemode)

    }}
//SAVE THE MEDIA TO APP (CURRENTLY IN .MP4 or .JPG ONLY)
    @SuppressLint("QueryPermissionsNeeded")
    fun takeAndHandleMedia(isVideo: Boolean, privatemode: Boolean) {
        val storageDir = File("${filesDir}/Storage")

        // Check if the directory exists, if not, create it
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
    // Get the current date and time
        val date = LocalDateTime.now()
        val formatter = java.time.format.DateTimeFormatter.ofPattern("HHmmss_dd-MM-yyyy")
        val datetime = date.format(formatter)

        val extension = if (isVideo) ".mp4" else ".jpg" //This may need updating to support other file types in the future
        val filename = "$datetime$extension"//"$datetime$extension"

        val intent = if (isVideo) Intent(MediaStore.ACTION_VIDEO_CAPTURE) else Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        intent.resolveActivity(packageManager)?.let {
            val contentResolver = applicationContext.contentResolver

            // Create a temporary file to store the captured media
            val file = File("${filesDir}/Storage", filename)
            snap = FileProvider.getUriForFile(this, "com.example.cloudycam.provider", file)
            // Set the output file for the camera intent
            intent.putExtra(MediaStore.EXTRA_OUTPUT, snap)

            takeMediaResult.launch(intent)
        } ?: show("No camera app found")
    }


//UPLOAD FILE TO SERVER WITH USERNAME AND PASSWORD AS AUTH
    fun uploadFILEToServer(uri: Uri) {
        progressBar.visibility = ProgressBar.VISIBLE
        val user = username.text.toString()
        val host = hostname.text.toString()
        val port = port.text.toString().toIntOrNull() ?: 22
        val password = password.text.toString()
        val folderPath = remoteFilePath.text.toString()

        // Ensure folder path ends with "/"
        if (!folderPath.endsWith("/")) {
            show("Folder path format incorrect")
            progressBar.visibility = ProgressBar.INVISIBLE

            return
        }

        if (user.isBlank()) {
            show("Please enter Username")
            progressBar.visibility = ProgressBar.INVISIBLE

            return

        }

        if (password.isBlank()) {
            show("Please enter Password")
            progressBar.visibility = ProgressBar.INVISIBLE

            return
        }

        if (host.isBlank()) {
            show("Please enter Server Address")
            progressBar.visibility = ProgressBar.INVISIBLE

            return
        }
        if (!port.equals(22)) {
            show("Warning! Port is not 22")
        }

        if (filesizeformatted.isNullOrBlank()) {
            show("Select a file to upload")
            progressBar.visibility = ProgressBar.INVISIBLE

            return
        }

        runBlocking {
            launch(Dispatchers.IO) {
                try {
                    val session = establishSSHConnection(user, host, port,password)
                    val serverFingerprint = session?.hostKey?.getFingerPrint(JSch())
                    session?.disconnect()
                    if (serverFingerprint != null) {
                        session.disconnect()
                        show("Failed to retrieve server fingerprint")
                        progressBar.visibility = ProgressBar.INVISIBLE

                    } else {
                        session?.disconnect()
                        show("Failed to establish SSH connection")
                        progressBar.visibility = ProgressBar.INVISIBLE

                    }
                } catch (e: Exception) {

                    if (e.message?.contains("UnknownHostKey:") == true) {
                        val errorMessage = e.message.toString().removePrefix("UnknownHostKey: ")
                        hostKeyConfirmation(user, host, port, password, uri, errorMessage)
                    } else {
                        runOnUiThread { show("Using Basic Auth. Failed to establish SSH connection: ${e.message}")
                            progressBar.visibility = ProgressBar.INVISIBLE
                        }
                    }
                }
            }
        }
    }

//UPLOAD FILE TO SERVER WITH PRIVATE KEY AS AUTH
    fun uploadFILEToServerWithAuthMethod(
        uri: Uri,
        privateKey: String
    ) {
        progressBar.visibility = ProgressBar.VISIBLE
        val user = username.text.toString()
        val host = hostname.text.toString()
        val port = port.text.toString().toIntOrNull() ?: 22

        val folderPath = remoteFilePath.text.toString()

        // Ensure folder path ends with "/"
        if (!folderPath.endsWith("/")) {
            show("Folder path format incorrect")
            progressBar.visibility = ProgressBar.INVISIBLE
            return
        }

        if(user.isBlank()){show("Please enter Username")
            progressBar.visibility = ProgressBar.INVISIBLE
            return
        }

        if (host.isBlank()){show("Please enter Server Address")
            progressBar.visibility = ProgressBar.INVISIBLE
            return
        }
        if (!port.equals(22)){show("Warning! Port is not 22")}

        if (filesizeformatted.isNullOrBlank()) {
            show("Select a file to upload")
            progressBar.visibility = ProgressBar.INVISIBLE

            return
        }


        runBlocking {
            launch(Dispatchers.IO) {
                try {
                    val session = establishSSHConnectionWithPrivateKey(user, host, port, privateKey)
                    val serverFingerprint = session?.hostKey?.getFingerPrint(JSch())
                    session?.disconnect()
                    if (serverFingerprint != null) {
                        session.disconnect()
                        show("Failed to retrieve server fingerprint")
                        progressBar.visibility = ProgressBar.INVISIBLE

                    } else {
                        session?.disconnect()
                        show("Failed to establish SSH connection")
                        progressBar.visibility = ProgressBar.INVISIBLE

                    }
                } catch (e: Exception) {
                    if (e.message?.contains("UnknownHostKey:") == true) {
                        val errorMessage = e.message.toString().removePrefix("UnknownHostKey: ")
                        hostKeyConfirmationWithPrivateKey(
                            uri,
                            user,
                            host,
                            port,
                            privateKey,
                            errorMessage
                        )
                    } else {
                        runOnUiThread { show("Failed to establish SSH connection: ${e.message}")
                            progressBar.visibility = ProgressBar.INVISIBLE
                        }
                    }

                }
            }
        }
    }

    //SHOW THE DIALOG BOX FOR HOST KEY CONFIRMATION WITH USERNAME AND PASSWORD AS AUTH
    fun hostKeyConfirmation(
        user: String,
        host: String,
        port: Int,
        password: String,
        imageUri: Uri,
        errorMessage: String,
    ) {
        runOnUiThread {
            val positiveClickListener = DialogInterface.OnClickListener { _, _ ->
                establishSSHConnectionAndUploadWithoutPrivateKey(latestTmpFileUri!!,user,host,port,password)
            }

            val negativeClickListener = DialogInterface.OnClickListener { _, _ ->
                show("Connection cancelled")
                progressBar.visibility = ProgressBar.INVISIBLE
            }

            AlertDialog.Builder(this@CameraActivity, R.style.CustomAlertDialog)
                .setTitle("Host Key Confirmation")
                .setMessage("${errorMessage.replace(". ","\n").replace(" is ",":\n\n")}\n\nYou are using basic Auth \uD83D\uDEC2\n\nDo you want to upload?")
                .setPositiveButton("Yes", positiveClickListener)
                .setNegativeButton("No", negativeClickListener)
                .setCancelable(false)
                .show()
        }
    }
//SHOW THE DIALOG BOX FOR HOST KEY CONFIRMATION WITH PRIVATE KEY AS AUTH
    fun hostKeyConfirmationWithPrivateKey(
        imageUri: Uri,
        user: String,
        host: String,
        port: Int,
        privateKey: String,
        errorMessage: String,
    ) {
        runOnUiThread {
            val positiveClickListener = DialogInterface.OnClickListener { _, _ ->

                establishSSHConnectionAndUploadWithPrivateKey(
                    latestTmpFileUri!!,
                    user,
                    host,
                    port,
                    privateKey)
            }
            val negativeClickListener = DialogInterface.OnClickListener { _, _ ->
                show("Connection cancelled")
                progressBar.visibility = ProgressBar.INVISIBLE
            }

            AlertDialog.Builder(this@CameraActivity, R.style.CustomAlertDialog)
                .setTitle("Host Key Confirmation")
                .setMessage("${errorMessage.replace(". ","\n").replace(" is ",":\n\n")}\n\nYou are using a Private Key \uD83D\uDD10\n\nDo you want to upload?")
                .setPositiveButton("Yes", positiveClickListener)
                .setNegativeButton("No", negativeClickListener)
                .setCancelable(false)
                .show()
        }
    }
//ESTABLISH SSH CONNECTION USER USERNAME - WITHOUT PRIVATE KEY
    fun establishSSHConnection(
        user: String,
        host: String,
        port: Int,
        password: String
    ): Session? {
        val jsch = JSch()
        val session = jsch.getSession(user, host, port)
        session.setConfig("StrictHostKeyChecking", "ask")
        try {
            session.connect()
            return session
        } catch (e: Exception) {
            session.disconnect()
            throw e
        }
    }
//ESTABLISH SSH CONNECTION USER USERNAME - WITH PRIVATE KEY
    private fun establishSSHConnectionWithPrivateKey(
        user: String,
        host: String,
        port: Int,
        privateKey: String
    ): Session? {
        val jsch = JSch()
        val session = jsch.getSession(user, host, port)

        session.setConfig("StrictHostKeyChecking", "ask")
        try {
            session.connect()
            return session
        } catch (e: Exception) {
            session.disconnect()
            throw e
        }
    }
// ESTABLISH SSH CONNECTION AND UPLOAD USING USERNAME AND PASSWORD - WITHOUT PRIVATE KEY
    @OptIn(DelicateCoroutinesApi::class)
    fun establishSSHConnectionAndUploadWithoutPrivateKey(
        imageUri: Uri,
        user: String,
        host: String,
        port: Int,
        password: String,
    ) {
        val cancel = findViewById<Button>(R.id.button_cancel)
        val fileNameTextView = findViewById<TextView>(R.id.progresstext)

        GlobalScope.launch(Dispatchers.IO) {


            val folderPath = remoteFilePath.text.toString()
            val finalPath = "$folderPath$filename"
            val inputStream: InputStream? = latestTmpFileUri?.let {
                contentResolver.openInputStream(
                    it
                )
            }

            try {
                val jsch = JSch()
                val session = jsch.getSession(user, host, port)
                session.setPassword(password)
                session.setConfig("StrictHostKeyChecking", "no")
                session.connect()

                sftpChannel = session.openChannel("sftp") as ChannelSftp
                sftpChannel!!.connect()


                // Set cancel button visibility to VISIBLE when upload starts
                runOnUiThread {
                    progressBar.visibility = ProgressBar.INVISIBLE
                    cancel.visibility = View.VISIBLE
                    cancel.elevation = 1f
                }

                try {
                    inputStream?.use { input ->

                        val fileSize = input.available()
                            .toLong() // Get the size of the input stream (file size)
                        val monitor = object : SftpProgressMonitor {
                            var total: Long = 0
                            var count: Long = 0


                            override fun init(
                                op: Int,
                                src: String,
                                dest: String,
                                max: Long
                            ) {
                                total = fileSize // Initialize total with the file size
                            }

                            override fun count(bytes: Long): Boolean {
                                count += bytes
                                val progress =
                                    ((count.toDouble() / total.toDouble()) * 100).toInt()
                                val progressInBytes = (progress * total / 100)
                                val progressFormatted = formatFileSize(progressInBytes)
                                val notificationLayout =
                                    RemoteViews(packageName, R.layout.progressnote)

                                val progressText =
                                    "$filename $progressFormatted of $filesizeformatted" // Using string interpolation
                                runOnUiThread {
                                    fileNameTextView.text = progressText

                                    progressBarHorizontal.progress = progress

                                }
                                updateNotificationProgress(
                                    1,
                                    progress,
                                    "",
                                    "$progressFormatted of $filesizeformatted"
                                )
                                return true // Return false to abort the transfer
                            }

                            override fun end() {
                            }
                        }
                        cancel.apply {
                            setOnClickListener {
                                //inputStream.close() // Close the input stream
                                sftpChannel!!.disconnect()
                                session.disconnect()
                                show("Upload was cancelled by user")
                                cancel.visibility = View.INVISIBLE
                                cancel.elevation = 0f
                            }
                        }

                        sftpChannel!!.put(input, finalPath, monitor, ChannelSftp.RESUME)

                        //sftpChannel!!.put(input, finalPath, monitor, ChannelSftp.OVERWRITE)
                        runOnUiThread {
                            progressBarHorizontal.progress =
                                100 // Ensure progress bar is at 100% after completion
                        }
                        inputStream.close()
                        sftpChannel!!.disconnect()
                        session.disconnect()
                        runOnUiThread { show("Upload Successful") }
                        shownotifysuccess("Sucessfully Uploaded:\n$filename")
                        cancel.visibility = View.INVISIBLE
                        cancel.elevation = 0f
                        val progressText = "$filename $filesizeformatted ✅"
                        fileNameTextView.text = progressText
                    }
                } catch (e: JSchException) {
                    runOnUiThread {
                        show("Failed to establish SSH connection: ${e.message}")
                        // Set cancel button visibility to INVISIBLE when upload fails
                        cancel.visibility = View.INVISIBLE
                        cancel.elevation = 0f
                    }
                } catch (e: Exception) {
                    if (progressBarHorizontal.progress < 100) runOnUiThread {
                        show("Failed to upload file: ${e.message}")
                        shownotifyfail("Failed to upload:\n$filename\n${e.message}")
                        // Set cancel button visibility to INVISIBLE when upload fails
                        cancel.visibility = View.INVISIBLE
                        cancel.elevation = 0f
                    }
                } finally {
                    sftpChannel!!.disconnect()
                    session.disconnect()
                }
            } catch (e: JSchException) {
                runOnUiThread {
                    show("Failed to establish SSH connection: ${e.message}")
                    // Set cancel button visibility to INVISIBLE when upload fails
                    cancel.visibility = View.INVISIBLE
                    cancel.elevation = 0f
                }
            }

        }
    }
// ESTABLISH SSH CONNECTION AND UPLOAD USING USERNAME - WITH PRIVATE KEY
    @OptIn(DelicateCoroutinesApi::class)
    fun establishSSHConnectionAndUploadWithPrivateKey(
        imageUri: Uri,
        user: String,
        host: String,
        port: Int,
        privateKey: String,
    ) {
        val cancel = findViewById<Button>(R.id.button_cancel)
        val fileNameTextView = findViewById<TextView>(R.id.progresstext)

        GlobalScope.launch(Dispatchers.IO) {

            val folderPath = remoteFilePath.text.toString()
            val finalPath = "$folderPath$filename"
            val inputStream: InputStream? = latestTmpFileUri?.let {
                contentResolver.openInputStream(
                    it
                )
            }
            try {
                val jsch = JSch()
                val session = jsch.getSession(user, host, port)
                jsch.addIdentity("key", privateKey.toByteArray(), null, null)
                session.setConfig("StrictHostKeyChecking", "ask")
                session.connect()
                sftpChannel = session.openChannel("sftp") as ChannelSftp
                sftpChannel!!.connect()


                // Set cancel button visibility to VISIBLE when upload starts
                runOnUiThread {
                    progressBar.visibility = ProgressBar.INVISIBLE
                    cancel.visibility = View.VISIBLE
                    cancel.elevation = 1f
                }

                try {
                    inputStream?.use { input ->

                        val fileSize = input.available()
                            .toLong() // Get the size of the input stream (file size)
                        val monitor = object : SftpProgressMonitor {
                            var total: Long = 0
                            var count: Long = 0


                            override fun init(
                                op: Int,
                                src: String,
                                dest: String,
                                max: Long
                            ) {
                                total = fileSize // Initialize total with the file size
                            }

                            override fun count(bytes: Long): Boolean {
                                count += bytes
                                val progress =
                                    ((count.toDouble() / total.toDouble()) * 100).toInt()
                                val progressInBytes = (progress * total / 100)
                                val progressFormatted = formatFileSize(progressInBytes)
                                val notificationLayout =
                                    RemoteViews(packageName, R.layout.progressnote)

                                val progressText =
                                    "$filename $progressFormatted of $filesizeformatted" // Using string interpolation
                                runOnUiThread {
                                    fileNameTextView.text = progressText

                                    progressBarHorizontal.progress = progress

                                }
                                updateNotificationProgress(
                                    1,
                                    progress,
                                    "",
                                    "$progressFormatted of $filesizeformatted"
                                )
                                return true // Return false to abort the transfer
                            }

                            override fun end() {
                            }
                        }
                        cancel.apply {
                            setOnClickListener {
                                //inputStream.close() // Close the input stream
                                sftpChannel!!.disconnect()
                                session.disconnect()
                                show("Upload was cancelled by user")
                                cancel.visibility = View.INVISIBLE
                                cancel.elevation = 0f
                            }
                        }

                        sftpChannel!!.put(input, finalPath, monitor, ChannelSftp.RESUME)

                        //sftpChannel!!.put(input, finalPath, monitor, ChannelSftp.OVERWRITE)
                        runOnUiThread {
                            progressBarHorizontal.progress =
                                100 // Ensure progress bar is at 100% after completion
                        }
                        inputStream.close()
                        sftpChannel!!.disconnect()
                        session.disconnect()
                        runOnUiThread { show("Upload Successful") }
                        shownotifysuccess("Sucessfully Uploaded:\n$filename")
                        cancel.visibility = View.INVISIBLE
                        cancel.elevation = 0f
                        val progressText = "$filename $filesizeformatted ✅"
                        fileNameTextView.text = progressText
                    }
                } catch (e: JSchException) {
                    runOnUiThread {
                        show("Failed to establish SSH connection: ${e.message}")
                        // Set cancel button visibility to INVISIBLE when upload fails
                        cancel.visibility = View.INVISIBLE
                        cancel.elevation = 0f
                    }
                } catch (e: Exception) {
                    if (progressBarHorizontal.progress < 100) runOnUiThread {
                        show("Failed to upload file: ${e.message}")
                        shownotifyfail("Failed to upload:\n$filename\n${e.message}")
                        // Set cancel button visibility to INVISIBLE when upload fails
                        cancel.visibility = View.INVISIBLE
                        cancel.elevation = 0f
                    }
                } finally {
                    sftpChannel!!.disconnect()
                    session.disconnect()
                }
            } catch (e: JSchException) {
                runOnUiThread {
                    show("Failed to establish SSH connection: ${e.message}")
                    // Set cancel button visibility to INVISIBLE when upload fails
                    cancel.visibility = View.INVISIBLE
                    cancel.elevation = 0f
                }
            }

        }
    }
    // Declare notification builder and channel outside the function
    private val channelId = "CloudyCam"
    private val channelName = "CloudyCam"

    //THE NOTIFICATION BAR ON THE LOCK SCREEN
    fun shownotifyfail( message: String) {
        val notificationId = 1 // You can use any unique ID for the notification
        val notificationIntent = Intent(this, CameraActivity::class.java)
        notificationIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Create a notification builder
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.close_fill0_wght400_grad0_opsz24) // Set the small icon for the notification
            .setContentTitle("CloudyCam") // Set the title of the notification
            .setContentText(message) // Set the content text of the notification
            .setPriority(NotificationCompat.PRIORITY_DEFAULT) // Set the priority of the notification
            .setContentIntent(pendingIntent)                // Create a RemoteViews object for your custom layout


        // Check if the Android version is Oreo or higher, as notification channels are required for Oreo and higher
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT)
            val notificationManager = this.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        // Build the notification and display it
        val notification = builder.build()
        val notificationManager = this.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notification)
    }


    // Create notification channel if not already created
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_HIGH

            )
            val notificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private var lastProgress = -1 // Variable to store the last progress value
    private var lastUpdateTime: Long = 0


    fun updateNotificationProgress(notificationId: Int, progress: Int, title: String, text: String) {
        // Check if one second has elapsed and progress has changed
        val currentTimeMillis = System.currentTimeMillis()
        val oneSecondElapsed = currentTimeMillis - lastUpdateTime >= 1000
        val significantChange = progress != lastProgress

        if (progress != 100 && (oneSecondElapsed && significantChange)) {
            // Update the last progress value and last update time
            lastProgress = progress
            lastUpdateTime = currentTimeMillis

            // Create or update notification only when progress changes significantly
            val notificationIntent = Intent(this, CameraActivity::class.java)
            notificationIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.upload_fill0_wght400_grad0_opsz24)
                .setVibrate(longArrayOf(0)) /* Stop vibration */
                .setSound(null)
                .setSilent(true)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .setContentTitle(title)

            // Create a RemoteViews object for your custom layout
            val notificationLayout = RemoteViews(packageName, R.layout.progressnote)
            notificationLayout.setProgressBar(
                R.id.progressBarNote, // ProgressBar ID
                100, // Maximum value of the ProgressBar
                progress, // Current progress value
                false // Whether to show the indeterminate progress
            )
            notificationLayout.setTextViewText(R.id.notificationText, text)

            // Set the custom layout for the notification
            builder.setCustomContentView(notificationLayout)

            // Issue the updated notification
            val notification = builder.build()
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(notificationId, notification)
        } else if (progress == 100) {
            // Cancel the notification when progress is 100%
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.cancel(notificationId)
        }
    }


    fun shownotifysuccess( message: String) {
        val notificationId = 1 // You can use any unique ID for the notification
        val notificationIntent = Intent(this, CameraActivity::class.java)
        notificationIntent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Create a notification builder
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.cloud_done_fill0_wght400_grad0_opsz24) // Set the small icon for the notification
            .setContentTitle("CloudyCam") // Set the title of the notification
            .setContentText(message) // Set the content text of the notification
            .setPriority(NotificationCompat.PRIORITY_DEFAULT) // Set the priority of the notification
            .setContentIntent(pendingIntent)                // Create a RemoteViews object for your custom layout


        // Check if the Android version is Oreo or higher, as notification channels are required for Oreo and higher
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT)
            val notificationManager = this.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        // Build the notification and display it
        val notification = builder.build()
        val notificationManager = this.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notification)
    }

// A function to simplify the toast messages
    fun show(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // END OF PERMISSION CHECKING
    fun arePermissionsGranted(): Boolean {
        for (permission in permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }
        return true
    }
    private fun requestPermissions() {
        requestPermissionLauncher.launch(permissions)

    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && shouldRequestBiometric) {authenticateUser()
        }

    }
    override fun onDestroy() {
        super.onDestroy()
        // Clear the FLAG_KEEP_SCREEN_ON flag when the activity is destroyed
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun showBiometricSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_biometric_settings, null)
        val biometricSwitch = dialogView.findViewById<Switch>(R.id.switch_biometric)

        // Load the saved state of the biometric switch
        biometricSwitch.isChecked = sharedPreferences.getBoolean("BiometricEnabled", true)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Biometric Authentication Settings")
            .setView(dialogView)
            .setPositiveButton("OK") { _, _ ->
                // Save the state of the switch
                BiometricEnabled = biometricSwitch.isChecked
                sharedPreferences.edit().putBoolean("BiometricEnabled", BiometricEnabled).apply()
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
    }
}

