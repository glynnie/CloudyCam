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
import android.view.WindowManager
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
import android.text.Html
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.text.SimpleDateFormat
import java.util.*
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import android.util.Log
import android.os.Environment
import androidx.lifecycle.lifecycleScope
import com.example.cloudycam.databinding.ActivityMainBinding
import kotlinx.coroutines.withContext


//END OF IMPORTS START OF VARIABLES

class CameraActivity : AppCompatActivity() {
    companion object {

        private const val KNOWN_HOSTS_KEY = "known_hosts"
    }

    //DECLARE VARIABLES
    var sftpChannel: ChannelSftp? = null // Reference to the SFTP channel
    lateinit var knownHosts: String
    private var currentFileUri: Uri? = null
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
    var BiometricEnabled = false
    private var shouldRequestBiometric = true
    private var failedAttempts = 0
    private val maxFailedAttempts = 5
    private var isMainLayoutShown = false
    private lateinit var binding: ActivityMainBinding


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
            if (result.resultCode == Activity.RESULT_OK) {
                // Handle successful capture
                currentFileUri = result.data?.data ?: currentFileUri

                // Display the captured image if currentFileUri is available
                currentFileUri?.let { uri ->
                    getImageFromUri(this, uri)?.let { bitmap ->
                        binding.preview.apply {
                            setImageBitmap(bitmap)
                        }
                    }
                }
                val storageDir = File(filesDir, "Storage")
                if (storageDir.exists()) {
                    // Delete all files except the latest one
                    deleteFilesExcept(filename, storageDir)
                }
            } else if (result.resultCode == Activity.RESULT_CANCELED) {
                runOnUiThread {
                    show("Media capture canceled.")
                }
            } else {
                show("Unexpected result code: ${result.resultCode}")
            }
            isMainLayoutShown = true // Set to true when media capture is completed or canceled
            shouldRequestBiometric = true
        }

    // Decode and display bitmap using coroutines
    

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
            // Open the input stream once and use it for both decoding bounds and the actual bitmap
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                // Decode bounds to get image dimensions
                BitmapFactory.decodeStream(inputStream, null, options)

                // Calculate inSampleSize
                options.inSampleSize = calculateInSampleSize(options, 600, 600)
                options.inJustDecodeBounds = false
                options.inPreferredConfig = Bitmap.Config.ARGB_8888
                options.inMutable = true

                // Reopen the input stream for actual bitmap decoding
                contentResolver.openInputStream(uri)?.use { newInputStream ->
                    var bitmap = BitmapFactory.decodeStream(newInputStream, null, options)

                    // Check the orientation of the image
                    contentResolver.openInputStream(uri)?.use { exifInputStream ->
                        val exif = ExifInterface(exifInputStream)
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
                    }
                    bitmap
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val reducedHeight: Int = height / 8
            val reducedWidth: Int = width / 8

            while (reducedHeight / inSampleSize >= reqHeight && reducedWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 8
            }
        }

        return inSampleSize
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

    // Function to determine if the currentFileUri is stored in the specified internal storage path
    private fun isStoredInInternalStorage(uri: Uri): Boolean {
        val isInternal = uri.path?.startsWith("/internal_files/") == true
        return isInternal
    }

    //SHOW A PREVIEW IMAGE "TYPE" FOR GENERIC FILE UPLOAD
    //DOCUMENTS, AUDIO, AND ARCHIVES ETC.
    @SuppressLint("UseCompatLoadingForDrawables")
    fun getImageFromUri(context: Context, uri: Uri): Bitmap? {
        progressBarHorizontal.progress = 0
        val mimeType = context.contentResolver.getType(currentFileUri!!)
        val filename = getFileName(currentFileUri!!)
        val fileNameTextView = binding.progresstext
        filesizeformatted = formatFileSize(getFileSize(this, currentFileUri!!))
        filesizeinbytes = getFileSize(this, currentFileUri!!)
        val monkey = if (isStoredInInternalStorage(currentFileUri!!)) {
            "PRIVATE 🙈 "
        } else {
            ""
        }
        val progressText = "$monkey$filename $filesizeformatted"
        if (filesizeformatted=="0 B")
            {
        fileNameTextView.text =""}
        else{fileNameTextView.text = progressText}
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
        if (size <= 0){return "0 B"}
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
                    currentFileUri = uri
                    extension = getFileExtension(currentFileUri!!)
                    filename = getFileName(currentFileUri!!)
                    binding.preview.apply {setImageBitmap(getImageFromUri(this@CameraActivity, currentFileUri!!))}


                    if (keymode) {
                        val alertDialogBuilder =
                            AlertDialog.Builder(this@CameraActivity, R.style.CustomAlertDialog)
                        val filesize = formatFileSize(getFileSize(this, currentFileUri!!))
                        alertDialogBuilder.setTitle("File Selected\nUploading with Key \uD83D\uDD10")
                        alertDialogBuilder.setMessage("Would you like to continue with the upload of:\n$filename?\n\n$filesize")
                        alertDialogBuilder.setCancelable(false) // Set non-dismissable
                        alertDialogBuilder.setPositiveButton("Yes") { _, _ ->
                            lastkey?.let { uploadFILEToServerWithAuthMethod(it) }
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
                        val filesize = formatFileSize(getFileSize(this, currentFileUri!!))
                        alertDialogBuilder.setTitle("File Selected\nUploading without Key \uD83D\uDEC2")
                        alertDialogBuilder.setMessage("Would you like to continue with the upload of:\n$filename?\n\n$filesize")
                        alertDialogBuilder.setCancelable(false) // Set non-dismissable
                        alertDialogBuilder.setPositiveButton("Yes") { _, _ ->
                            uploadFILEToServer(currentFileUri!!)
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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Check if biometric is enabled and set FLAG_SECURE accordingly
        BiometricEnabled = readBooleanFromFile("BiometricSettings.txt")
        if (BiometricEnabled) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        createNotificationChannel()

        // Initialize sharedPreferences and load private mode state
        sharedPreferences = getSharedPreferences("com.example.cloudycam", Context.MODE_PRIVATE)
        privatemode = readBooleanFromFile("privateSwitchState.txt")

        // Initialize views
        username = binding.editTextUsername
        password = binding.editTextPassword
        hostname = binding.editTextHostname
        port = binding.editTextPort
        remoteFilePath = binding.editTextRemoteFilePath
        progressBar = binding.progressBar
        progressBarHorizontal = binding.progressBarHorizontal
        knownHosts = sharedPreferences.getString(KNOWN_HOSTS_KEY, "") ?: ""

        // Initialize other views
        val upload = binding.buttonUpload
        val retakeButton = binding.buttonretake
        val pairsSwitch: Switch = binding.pairs
        val pemButton: Button = binding.pem
        val videoSwitch: Switch = binding.video
        val privateSwitch: Switch? = binding.privatemode
        val listbutton = binding.buttonlist
        val settingsbutton: FloatingActionButton? = binding.settings

        // Load the last file from internal storage if private mode is enabled
        if (privatemode)
            loadprivatesnapshot()
           else {loadPublicSnapshot()}
        // Add logging to check if onCreate is reached
        Log.d("CameraActivity", "onCreate: Activity created and views initialized")

        loadSavedEntries()
        checkAndRequestPermissions()

        upload.setOnClickListener {
            if (keymode) {
                if (lastkey.isNullOrBlank()) {
                    show("Select a Private Key")
                } else {
                        uploadFILEToServerWithAuthMethod(lastkey!!)
                    }

            } else {
                currentFileUri?.let { it1 -> uploadFILEToServer(it1) }
            }
        }
        retakeButton.setOnClickListener {
            Log.d("CameraActivity", "Retake button pressed")
            restartCamera()
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

        videoSwitch.isChecked = sharedPreferences.getBoolean("videoSwitchState", false)
        videoSwitch.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit().putBoolean("videoSwitchState", isChecked).apply()

            video = isChecked

            writeBooleanToFile(video, "videoSwitchState.txt")

        }
        privateSwitch?.isChecked = sharedPreferences.getBoolean("privateSwitchState", false)

        val pickFileButton = binding.gallery // Assuming 'gallery' is the button for picking a generic file

        privateSwitch?.setOnCheckedChangeListener { _, isChecked ->
            // Update the switch state in the shared preferences based on the current state
            sharedPreferences.edit().putBoolean("privateSwitchState", isChecked).apply()
            // Set the privatemode variable accordingly
            privatemode = isChecked
            // Write the new state to the file
            writeBooleanToFile(isChecked, "privateSwitchState.txt")
            privatemode = readBooleanFromFile("privateSwitchState.txt")
            if (privatemode) {
                privateSwitch.setTextColor(Color.GREEN)
                // Load the last file from internal storage
                loadprivatesnapshot()
            } else {
                privateSwitch.setTextColor(Color.parseColor("#A7A2A9"))
                pickFileButton.visibility = View.VISIBLE // Make the button visible in public mode
                // Load the most recent file from DCIM/CloudyCam
                loadPublicSnapshot()
            }
        }



        binding.gallery.setOnClickListener {
            openFilePickerForUpload()
        }
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

        // Find the settings button and set an OnClickLis

        settingsbutton?.setOnClickListener {
            showBiometricSettingsDialog()
        }

    }

    private fun loadprivatesnapshot() {
        if (privatemode) {
            val storageDir = File(filesDir, "Storage")
            if (storageDir.exists() && storageDir.isDirectory) {
                val files = storageDir.listFiles()
                if (!files.isNullOrEmpty()) {
                    // Sort files by last modified date in descending order
                    files.sortByDescending { it.lastModified() }
                    // Get the most recently modified file
                    val lastFile = files.firstOrNull()
                    lastFile?.let {
                        updateLatestTmpFileUri(it, "Private")
                        filename = it.name.toString()
                    }
                } else {
                    Log.d("CameraActivity", "No files in internal storage directory")
                }
            } else {
                Log.d("CameraActivity", "Internal storage directory does not exist or is not a directory")
            }
        }
    }

    private fun loadPublicSnapshot() {
        val externalStorageDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "CloudyCam")
        
        // Ensure the directory exists
        if (!externalStorageDir.exists()) {
            externalStorageDir.mkdirs() // Create the directory if it doesn't exist
        }
        
        if (externalStorageDir.exists() && externalStorageDir.isDirectory) {
            val files = externalStorageDir.listFiles()
            if (!files.isNullOrEmpty()) {
                // Sort files by last modified date in descending order
                files.sortByDescending { it.lastModified() }
                // Get the most recently modified file
                val lastFile = files.firstOrNull()
                lastFile?.let {
                    updateLatestTmpFileUri(it, "Public")
                    filename = it.name.toString()
                }
            } else {
                Log.d("CameraActivity", "No files in DCIM/CloudyCam directory")
                // Set progresstext to a null string and display default PNG
                binding.progresstext.text = ""
                binding.preview.apply {
                    setImageResource(R.drawable.whitecloudycam) // Ensure you have a default_image.png in your drawable resources
                }
            }
        } else {
            Log.d("CameraActivity", "DCIM/CloudyCam directory does not exist or is not a directory")
            // Set progresstext to a null string and display default PNG
            binding.progresstext.text = ""
            binding.preview.apply {
                setImageResource(R.drawable.whitecloudycam) // Ensure you have a default_image.png in your drawable resources
            }
        }
    }

    private fun updateLatestTmpFileUri(file: File, mode: String) {
        currentFileUri = FileProvider.getUriForFile(this, "com.example.cloudycam.provider", file)
        Log.d("CameraActivity", "$mode snapshot loaded: ${file.name}")
        // Display the image or video frame
        currentFileUri?.let { uri ->
            getImageFromUri(this, uri)?.let { bitmap ->
                binding.preview.apply {
                    setImageBitmap(bitmap)
                    Log.d("CameraActivity", "ImageView updated with $mode snapshot")
                }
            } ?: Log.d("CameraActivity", "Failed to get bitmap from URI")
        }
    }

    override fun onResume() {
        super.onResume()
        // Check if private mode is enabled and URI is null
        if (BiometricEnabled && shouldRequestBiometric && isMainLayoutShown) {
            authenticateUser()
        }

        // Show biometric prompt when the main window is loaded and isMainLayoutShown is true

        }


    private fun authenticateUser() {
        blur()
        val biometricManager = BiometricManager.from(this)
        when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                // Device can authenticate with biometrics
                showBiometricPrompt()
            }
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                show("No biometric hardware available")
            }
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                show("Biometric hardware is currently unavailable")
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                show("No biometric credentials enrolled")
            }
            else -> {
                show("Biometric authentication is not supported")
            }
        }
    }

    private fun showBiometricPrompt() {
        if (BiometricEnabled) {
            val executor = ContextCompat.getMainExecutor(applicationContext)
            val biometricPrompt =
                BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
showBiometricPrompt()
                        if (errorCode == BiometricPrompt.ERROR_USER_CANCELED)
                        {
                            finish()
                        }
                        if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                        ) {
                            // Retry the biometric prompt if canceled or back button is pressed
                            showBiometricPrompt()
                        }
                    }

                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        runOnUiThread {
                            failedAttempts++
                            show("Authentication failed")
                            if (failedAttempts >= maxFailedAttempts) {
                                finish() // Close the app after max failed attempts
                            } else {
                                showBiometricPrompt() // Retry the authentication
                            }
                        }
                    }

                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        runOnUiThread {
                            failedAttempts = 0
                            shouldRequestBiometric = false
                            unblur() // Unblur the screen on success
                            show("Authentication succeeded")
                        }
                    }
                })

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Biometric Authentication")
                .setSubtitle("Authenticate to access the app")
                .setNegativeButtonText("Cancel")
                .build()

            biometricPrompt.authenticate(promptInfo)
        } else {
            show("Biometric authentication is not enabled")
        }
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
                val detailedEntries = entries.map { parseFileEntry(it as ChannelSftp.LsEntry) }



                sftpChannel!!.disconnect()
                session.disconnect()

                runOnUiThread {

                    showlisthtml(detailedEntries.toString())


                    progressBar.visibility = ProgressBar.INVISIBLE
                }


            } catch (e: Exception) {
                runOnUiThread {
                    val message = e.message.toString().replace("file", "Folder")
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
                val detailedEntries = entries.map { parseFileEntry(it as ChannelSftp.LsEntry) }

                sftpChannel.disconnect()
                session.disconnect()

                runOnUiThread {

                    showlisthtml(
                        detailedEntries.sortedDescending().reversed().toString()
                            .removeSurrounding("[", "]")
                    )


                    progressBar.visibility = ProgressBar.INVISIBLE
                }

            } catch (e: Exception) {
                runOnUiThread {
                    val message = e.message.toString().replace("file", "Folder")
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
        val firstLine =
            "<font color=\"$color\"><br><br><b>$emoji $fileName</b></font><br>$fileSize<br>$formattedDate<br>$permissions"


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
                uploadFILEToServerWithAuthMethod(privateKey)
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
        BiometricEnabled = readBooleanFromFile("BiometricSettings.txt")
        if (BiometricEnabled) {
            // Ensure blur is applied immediately
            runOnUiThread {
                blur()
            }
            shouldRequestBiometric = true
        }
        // Save any necessary data or state
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

    override fun onStop() {
        super.onStop()
        if (BiometricEnabled) {
            // Ensure blur is applied immediately
            runOnUiThread {
                blur()
            }
        }
    }

    //SAVE THE MEDIA TO DEVICE (CURRENTLY IN MP4 or JPG)
    @SuppressLint("QueryPermissionsNeeded")
    fun takeMedia(isVideo: Boolean, privatemode: Boolean) {
        Log.d(
            "CameraActivity",
            "takeMedia called with isVideo: $isVideo, privatemode: $privatemode"
        )
        shouldRequestBiometric = false // Disable biometric requests during media capture
        isMainLayoutShown = false // Set to false when starting media capture
        val date = LocalDateTime.now()
        val formatter = java.time.format.DateTimeFormatter.ofPattern("HHmmss_dd-MM-yyyy")
        val datetime = date.format(formatter)

        extension = if (isVideo) ".mp4" else ".jpg"
        filename = "$datetime$extension"

        val intent = if (isVideo) Intent(MediaStore.ACTION_VIDEO_CAPTURE) else Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        intent.resolveActivity(packageManager)?.let {
            if (privatemode) {
                // Store in internal storage
                val storageDir = File(filesDir, "Storage")
                if (!storageDir.exists()) {
                    storageDir.mkdirs() // Create the directory if it doesn't exist
                }
                val file = File(storageDir, filename)
                currentFileUri = FileProvider.getUriForFile(this, "com.example.cloudycam.provider", file)
            } else {
                // Store in external storage
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/CloudyCam")
                }
                currentFileUri = contentResolver.insert(
                    if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                )
            }

            currentFileUri?.let { uri ->
                intent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
                takeMediaResult.launch(intent)
            } ?: show("Failed to create media file")
        } ?: show("No camera app found")
    }




    //UPLOAD FILE TO SERVER WITH USERNAME AND PASSWORD AS AUTH
    fun uploadFILEToServer(uri: Uri) {
        currentFileUri?.let { uri ->
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
                            hostKeyConfirmation(user, host, port, password, uri, errorMessage)
                        } else {
                            runOnUiThread {
                                show("Using Basic Auth. Failed to establish SSH connection: ${e.message}")
                                progressBar.visibility = ProgressBar.INVISIBLE
                            }
                        }
                    }
                }
            }
        } ?: show("Select a file to upload")
    }

    //UPLOAD FILE TO SERVER WITH PRIVATE KEY AS AUTH
    fun uploadFILEToServerWithAuthMethod(privateKey: String) {
        currentFileUri?.let { uri ->
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

            if (filesizeformatted.isNullOrBlank()) {
                show("Select a file to upload")
                progressBar.visibility = ProgressBar.INVISIBLE

                return
            }


            runBlocking {
                launch(Dispatchers.IO) {
                    try {
                        val session =
                            establishSSHConnectionWithPrivateKey(user, host, port, privateKey)
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
                            runOnUiThread {
                                show("Failed to establish SSH connection: ${e.message}")
                                progressBar.visibility = ProgressBar.INVISIBLE
                            }
                        }

                    }
                }
            }
        } ?: show("Select a file to upload")
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
                establishSSHConnectionAndUploadWithoutPrivateKey(
                    imageUri,
                    user,
                    host,
                    port,
                    password
                )
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
                    }\n\nYou are using basic Auth \uD83D\uDEC2\n\nDo you want to upload?"
                )
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
                    imageUri,
                    user,
                    host,
                    port,
                    privateKey
                )
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
                    }\n\nYou are using a Private Key \uD83D\uDD10\n\nDo you want to upload?"
                )
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
        val cancel = binding.buttonCancel
        val fileNameTextView = binding.progresstext

        GlobalScope.launch(Dispatchers.IO) {


            val folderPath = remoteFilePath.text.toString()
            val finalPath = "$folderPath$filename"
            val inputStream: InputStream? = imageUri?.let {
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
        val cancel = binding.buttonCancel
        val fileNameTextView = binding.progresstext

        GlobalScope.launch(Dispatchers.IO) {

            val folderPath = remoteFilePath.text.toString()
            val finalPath = "$folderPath$filename"
            val inputStream: InputStream? = imageUri?.let {
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
    fun shownotifyfail(message: String) {
        val notificationId = 1 // You can use any unique ID for the notification
        val notificationIntent = Intent(this, CameraActivity::class.java)
        notificationIntent.flags =
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
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
            val channel =
                NotificationChannel(
                    channelId,
                    channelName,
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            val notificationManager = this.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        // Build the notification and display it
        val notification = builder.build()
        val notificationManager =
            this.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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


    fun updateNotificationProgress(
        notificationId: Int,
        progress: Int,
        title: String,
        text: String
    ) {
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
            notificationIntent.flags =
                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
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


    fun shownotifysuccess(message: String) {
        val notificationId = 1 // You can use any unique ID for the notification
        val notificationIntent = Intent(this, CameraActivity::class.java)
        notificationIntent.flags =
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
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
            val channel =
                NotificationChannel(
                    channelId,
                    channelName,
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            val notificationManager = this.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        // Build the notification and display it
        val notification = builder.build()
        val notificationManager =
            this.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notification)
    }

    // A function to simplify the toast messages
    fun show(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // END OF PERMISSION CHECKING
    fun arePermissionsGranted(): Boolean {
        for (permission in permissions) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    permission
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        return true
    }

    private fun requestPermissions() {
        requestPermissionLauncher.launch(permissions)

    }


    private fun showBiometricSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_biometric_settings, null)
        val biometricSwitch = dialogView.findViewById<SwitchMaterial>(R.id.switch_biometric)

        // Load the saved state of the biometric switch
        biometricSwitch.isChecked = readBooleanFromFile("BiometricSettings.txt")

        val dialog = AlertDialog.Builder(this, R.style.CustomAlertDialog)
            .setTitle("Settings")
            .setView(dialogView)
            .setPositiveButton("OK") { _, _ ->
                // Save the state of the switch
                BiometricEnabled = biometricSwitch.isChecked
                writeBooleanToFile(BiometricEnabled, "BiometricSettings.txt")

                // Update FLAG_SECURE based on the new biometric setting
                if (BiometricEnabled) {
                    window.setFlags(
                        WindowManager.LayoutParams.FLAG_SECURE,
                        WindowManager.LayoutParams.FLAG_SECURE
                    )
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
    }


    fun blur() {
        //OBSCURE THE TEXT ENTRY
        binding.editTextUsername.visibility = View.INVISIBLE
        binding.editTextPassword.visibility = View.INVISIBLE
        binding.editTextPort.visibility = View.INVISIBLE
        binding.editTextHostname.visibility = View.INVISIBLE
        binding.editTextRemoteFilePath.visibility = View.INVISIBLE
        binding.buttonretake.visibility = View.INVISIBLE
        binding.buttonUpload.visibility = View.INVISIBLE
        binding.buttonCancel.visibility = View.INVISIBLE
        binding.progressBar.visibility = View.INVISIBLE
        binding.progressBarHorizontal.visibility = View.INVISIBLE
        binding.progresstext.visibility = View.INVISIBLE
        binding.preview.visibility = View.INVISIBLE
        binding.video.visibility = View.INVISIBLE
        binding.pairs.visibility = View.INVISIBLE
        binding.buttonlist.visibility = View.INVISIBLE
        binding.gallery.visibility = View.INVISIBLE
        binding.settings?.visibility = View.INVISIBLE
        binding.privatemode?.visibility = View.INVISIBLE


    }

    fun unblur() {

        binding.editTextUsername.visibility = View.VISIBLE
        binding.editTextPassword.visibility = View.VISIBLE
        binding.editTextPort.visibility = View.VISIBLE
        binding.editTextHostname.visibility = View.VISIBLE
        binding.editTextRemoteFilePath.visibility = View.VISIBLE
        binding.buttonretake.visibility = View.VISIBLE
        binding.buttonUpload.visibility = View.VISIBLE
        binding.progressBarHorizontal.visibility = View.VISIBLE
        binding.progresstext.visibility = View.VISIBLE
        binding.preview.visibility = View.VISIBLE
        binding.video.visibility = View.VISIBLE
        binding.pairs.visibility = View.VISIBLE
        binding.buttonlist.visibility = View.VISIBLE
        binding.gallery.visibility = View.VISIBLE
        binding.settings?.visibility = View.VISIBLE
        binding.privatemode?.visibility = View.VISIBLE


    }

    private fun restartCamera() {
        // Reset any necessary state here


        // Reinitialize the camera
        if (arePermissionsGranted()) {
            takeMedia(video, privatemode)
        } else {
            Log.d("CameraActivity", "Permissions not granted")
            requestPermissions()
        }

    }

    private fun showAlertDialog(
        title: String,
        message: String,
        positiveAction: () -> Unit,
        negativeAction: () -> Unit
    ) {
        AlertDialog.Builder(this@CameraActivity, R.style.CustomAlertDialog)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Yes") { _, _ -> positiveAction() }
            .setNegativeButton("No") { _, _ -> negativeAction() }
            .setCancelable(false)
            .show()
    }

    private fun checkAndRequestPermissions() {
        if (!arePermissionsGranted()) {
            requestPermissions()
        } else {
            initializeMediaSettings()
        }
    }

    private fun initializeMediaSettings() {
        video = readBooleanFromFile("videoSwitchState.txt")
        BiometricEnabled = readBooleanFromFile("BiometricSettings.txt")
        if (privatemode) {
            binding.privatemode?.setTextColor(Color.GREEN)
        } else {
            binding.privatemode?.setTextColor(Color.parseColor("#A7A2A9"))
        }
        takeMedia(video, privatemode)
    }

    private fun uploadFile(uri: Uri, user: String, host: String, port: Int, password: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val session = establishSSHConnection(user, host, port, password)
                // Handle session and upload logic
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    show("Failed to establish SSH connection: ${e.message}")
                    progressBar.visibility = ProgressBar.INVISIBLE
                }
            }
        }
    }
}







