package com.example.camerawithocr

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.media.MediaPlayer
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.impl.utils.MatrixExt.postRotate
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.camerawithocr.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Environment
import android.view.MotionEvent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var imageCapture: ImageCapture
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var cameraControl: androidx.camera.core.CameraControl
    private var torchEnabled = false  // Boolean flag to track the torch state
    private lateinit var receivedGroups: List<String>  // To store user-entered groups

    // Define the activity result launcher for getting the result from InputNumbersActivity
    private val inputNumbersLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            if (data != null) {
                val group1 = data.getStringExtra("GROUP_1") ?: ""
                val group2 = data.getStringExtra("GROUP_2") ?: ""
                val group3 = data.getStringExtra("GROUP_3") ?: ""
                val group4 = data.getStringExtra("GROUP_4") ?: ""
                val group5 = data.getStringExtra("GROUP_5") ?: ""
                val group6 = data.getStringExtra("GROUP_6") ?: ""
                val group7 = data.getStringExtra("GROUP_7") ?: ""
                val group8 = data.getStringExtra("GROUP_8") ?: ""

                receivedGroups = listOf(group1, group2, group3, group4, group5, group6, group7, group8)

                // Log the received values
                Log.d("MainActivity", "Received Groups: $group1, $group2, $group3, $group4, $group5, $group6, $group7, $group8")

                // Now continue with your camera setup or any other processing
                startCamera()
                setUpTapToFocus()
                setupButtons()


                // Initialize cameraExecutor if needed
                if (!::cameraExecutor.isInitialized) {
                    cameraExecutor = Executors.newSingleThreadExecutor()
                }
            }
        }
    }

    // Function to setup button click listeners
    private fun setupButtons() {
        // Set click listener for the capture button
        binding.captureButton.setOnClickListener { takePhoto() }

        // Set click listener for the torch button (flashlight)
        binding.torchButton.setOnClickListener {
            toggleTorch()
        }

        binding.viewFileButton.setOnClickListener {
            showFileContents()
        }

        binding.sendFileButton.setOnClickListener {
            sendFileViaEmail()
        }

        binding.resetFileButton.setOnClickListener {
            resetFile()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Show the dialog to ask if the user wants to input numbers
        showInputNumbersDialog()

        // Launch the InputNumbersActivity to input known numbers
        //val intent = Intent(this, InputNumbersActivity::class.java)
        //inputNumbersLauncher.launch(intent)  // Launch InputNumbersActivity and wait for result
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            // Setup Preview Use Case
            val preview = Preview.Builder()
                .setTargetRotation(windowManager.defaultDisplay.rotation)  // Handle screen rotation
                .build().also {
                    it.setSurfaceProvider(binding?.cameraPreview?.surfaceProvider)
                }

            // Setup ImageCapture Use Case with CAPTURE_MODE_MINIMIZE_LATENCY for speed
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)  // Minimize latency mode
                .setTargetRotation(windowManager.defaultDisplay.rotation)  // Handle screen rotation
                .build()

            // Select back camera as default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Bind the camera to lifecycle
                val camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )

                // Initialize cameraControl for tap-to-focus
                cameraControl = camera.cameraControl

            } catch (exc: Exception) {
                Log.e("CameraXApp", "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun setUpTapToFocus() {
        binding.cameraPreview.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                // Perform focus metering action
                val factory = binding.cameraPreview.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)

                val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).build()

                cameraControl.startFocusAndMetering(action)

                // Call performClick for accessibility
                view.performClick()

                true
            } else {
                false
            }
        }
    }
    // Take the photo and crop it to the overlay size
    private fun takePhoto() {
        val imageCapture = imageCapture ?: run {
            Log.e("CameraXApp", "ImageCapture is not initialized.")
            return
        }

        // Capture image
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    var bitmap = imageProxyToBitmap(imageProxy)
                    imageProxy.close()  // Don't forget to close the ImageProxy

                    // Check if the image is in landscape (width > height)
                    if (bitmap.width > bitmap.height) {
                        // Rotate the image by 90 degrees to make it portrait
                        bitmap = rotateBitmap(bitmap, 0f)
                        Log.d("DebugRotation", "Rotated the image by 90 degrees")
                    }

                    // Get the overlay rectangle based on the captured image
                    val overlayRect = getOverlayRectForImage(bitmap)

                    // Crop the image to the overlay area
                    val croppedBitmap = cropBitmapToOverlay(bitmap, overlayRect)

                    // Enhance contrast of the cropped bitmap
                    val contrastEnhancedBitmap = enhanceContrast(croppedBitmap)

                    saveBitmapToFile(contrastEnhancedBitmap, "contrastEnhancedImage")

                    // Perform OCR on the cropped image
                    performOCR(contrastEnhancedBitmap)

                    // Play shutter sound
                    playShutterSound()
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e("CameraXApp", "Photo capture failed: ${exc.message}", exc)
                }
            }
        )
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun getOverlayRectForImage(originalBitmap: Bitmap): Rect {
        val overlay = binding.overlay

        // Get the overlay's position and size relative to the screen
        val location = IntArray(2)
        overlay.getLocationOnScreen(location)
        val overlayX = location[0]
        val overlayY = location[1]
        val overlayWidth = overlay.width
        val overlayHeight = overlay.height

        // Get the dimensions of the camera preview and image
        val previewWidth = binding.cameraPreview.width
        val previewHeight = binding.cameraPreview.height
        val imageWidth = originalBitmap.width
        val imageHeight = originalBitmap.height

        // Scaling factors based on landscape dimensions
        val scaleX = imageWidth.toFloat() / previewWidth
        val scaleY = imageHeight.toFloat() / previewHeight

        // Scale the overlay coordinates to match the captured image
        val scaledX = (overlayX * scaleX).toInt()
        val scaledY = (overlayY * scaleY).toInt()
        val scaledWidth = (overlayWidth * scaleX).toInt()
        val scaledHeight = (overlayHeight * scaleY).toInt()

        // Return the cropping rectangle based on scaled dimensions
        return Rect(scaledX, scaledY, scaledX + scaledWidth, scaledY + scaledHeight)
    }

    // Helper function to convert ImageProxy to Bitmap
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val buffer = imageProxy.planes[0].buffer
        val bytes = ByteArray(buffer.capacity())
        buffer.get(bytes)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    // Helper function to crop the Bitmap based on the overlay
    private fun cropBitmapToOverlay(originalBitmap: Bitmap, overlayRect: Rect): Bitmap {
        return Bitmap.createBitmap(
            originalBitmap,
            overlayRect.left,
            overlayRect.top,
            overlayRect.width(),
            overlayRect.height()
        )
    }

    // Helper function to save images to the gallery
    private fun saveBitmapToFile(bitmap: Bitmap, fileName: String): File {
        // Create a directory in the external storage (or use any other directory)
        val directory = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val file = File(directory, "$fileName.jpg")

        try {
            // Create output stream to write the bitmap data to a file
            val outputStream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)  // 100 = best quality
            outputStream.flush()
            outputStream.close()

            Log.d("CameraXApp", "Bitmap saved to: ${file.absolutePath}")
        } catch (e: IOException) {
            e.printStackTrace()
            Log.e("CameraXApp", "Failed to save bitmap: ${e.message}")
        }

        return file
    }

    private fun enhanceContrast(bitmap: Bitmap): Bitmap {
        val contrast = 0.8  // Adjust contrast level as needed
        val bitmapConfig = bitmap.config
        val contrastBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, bitmapConfig)

        val paint = Paint()
        val contrastMatrix = ColorMatrix().apply {
            setScale(contrast.toFloat(), contrast.toFloat(), contrast.toFloat(), 1.0f)
        }
        val colorFilter = ColorMatrixColorFilter(contrastMatrix)
        paint.colorFilter = colorFilter

        val canvas = Canvas(contrastBitmap)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)

        return contrastBitmap
    }

    // Play a camera shutter sound
    private fun playShutterSound() {
        val mediaPlayer = MediaPlayer.create(this, R.raw.shutter_sound)  // Replace with your sound file
        mediaPlayer.start()
    }

    // Function to perform OCR on the cropped bitmap
    private fun performOCR(croppedBitmap: Bitmap) {
        val image = InputImage.fromBitmap(croppedBitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        Log.d("performOCR", "Starting OCR process on cropped bitmap")

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                Log.d("performOCR", "OCR Success")
                val ocrText = visionText.text  // Extract the recognized text as a string

                if (ocrText.isNullOrEmpty()) {
                    Log.e("performOCR", "OCR text is empty or null")
                    return@addOnSuccessListener
                }

                // Split OCR result into chunks of 4 characters
                val detectedGroups = ocrText.filter { it.isDigit() }  // Ensure we only extract digits
                    .chunked(4)  // Split into chunks of 4 digits

                if (detectedGroups.size < 11) {
                    Log.e("performOCR", "Insufficient groups detected from OCR")
                    return@addOnSuccessListener
                }

                // Extract the first 8 groups and the last 3 groups from OCR
                val firstEightGroupsFromOCR = detectedGroups.take(8).joinToString(" ")
                val lastThreeGroupsFromOCR = detectedGroups.takeLast(3).joinToString(" ")
                Log.d("OCRGroups", "First eight groups from OCR: $firstEightGroupsFromOCR")
                Log.d("OCRGroups", "Last three groups from OCR: $lastThreeGroupsFromOCR")

                // Use the received groups if they were entered by the user; otherwise, fall back to the OCR result
                val finalFirstEightGroups = if (receivedGroups.all { it.isEmpty() }) {
                    firstEightGroupsFromOCR  // If "No" was chosen, use the OCR groups
                } else {
                    receivedGroups.joinToString(" ")  // Use the user-entered groups
                }

                // Combine the user-entered first 8 groups with OCR-detected last 3 groups
                val finalResult = "$finalFirstEightGroups $lastThreeGroupsFromOCR"

                // Validate the length of finalResult (44 characters: 11 groups of 4 digits plus spaces)
                if (finalResult.replace(" ", "").length != 44) {
                    Log.e("OCRValidation", "Invalid number of digits detected. Expected 44, but got ${finalResult.replace(" ", "").length}.")
                    Toast.makeText(this, "Não foi possível ler todos os números: $finalResult", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener  // Do not append to the file if validation fails
                }

                Log.d("OCRResult", "Final Result: $finalResult")
                appendToFile(finalResult)  // Append the result to the file
            }
            .addOnFailureListener { e ->
                Log.e("OCRFailure", "Text recognition failed: ${e.message}")
            }
    }

    // Modify extractNumbers to return the last three groups
    private fun extractNumbers(text: String): DetectedGroups {
        val regex = Regex("\\d{4}")  // Extract groups of 4 digits
        val numbers = regex.findAll(text).map { it.value }.toList()

        return if (numbers.size >= 11) {
            DetectedGroups(
                firstEightGroups = numbers.subList(0, 8).joinToString(" "),
                lastThreeGroups = numbers.subList(8, 11).joinToString(" ")
            )
        } else {
            DetectedGroups("", "")  // Handle case where OCR fails
        }
    }

    // Data class to hold detected groups
    data class DetectedGroups(
        val firstEightGroups: String,
        val lastThreeGroups: String
    )

    // Function to append recognized numbers to a file in the Documents directory
    private fun appendToFile(numbers: String) {
        // Define the directory path for Documents/cameraocrextract
        val documentsDir = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "cameraocrextract")

        // Ensure the directory exists, create it if it doesn't
        if (!documentsDir.exists()) {
            documentsDir.mkdirs()
        }

        // Define the file where the numbers will be stored
        val fileName = "recognized_numbers.txt"
        val file = File(documentsDir, fileName)

        try {
            val fileOutputStream = FileOutputStream(file, true)  // true to append, false to overwrite
            fileOutputStream.write((numbers + "\n").toByteArray())
            fileOutputStream.close()
            Log.d("FileWrite", "Numbers appended to file: $numbers")

            // Display a message to the user
            runOnUiThread {
                Toast.makeText(this, "Numbers saved to Documents/cameraocrextract/$fileName", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("FileWriteError", "Error writing to file: ${e.message}")
        }
    }

    private fun showFileContents() {
        val file = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "cameraocrextract/recognized_numbers.txt")

        if (file.exists()) {
            val fileContents = file.readText()  // Read the entire file as a string

            // Show the file contents in an AlertDialog
            AlertDialog.Builder(this)
                .setTitle("File Contents")
                .setMessage(fileContents)
                .setPositiveButton("OK", null)
                .show()
        } else {
            Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendFileViaEmail() {
        val file = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "cameraocrextract/recognized_numbers.txt")

        if (file.exists()) {
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)

            val emailIntent = Intent(Intent.ACTION_SEND).apply {
                type = "vnd.android.cursor.dir/email"
                putExtra(Intent.EXTRA_SUBJECT, "Recognized Numbers File")
                putExtra(Intent.EXTRA_TEXT, "Please find the recognized numbers file attached.")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            try {
                startActivity(Intent.createChooser(emailIntent, "Send email..."))
            } catch (e: Exception) {
                Toast.makeText(this, "No email clients installed", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetFile() {
        val file = File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "cameraocrextract/recognized_numbers.txt")

        try {
            FileOutputStream(file, false).use { it.write("".toByteArray()) }  // Overwrite the file with an empty string
            Toast.makeText(this, "File reset", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error resetting file", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    // Function to toggle the torch (flashlight)
    private fun toggleTorch() {
        if (::cameraControl.isInitialized) {
            torchEnabled = !torchEnabled
            cameraControl.enableTorch(torchEnabled)  // Toggle the torch state
            Toast.makeText(this, if (torchEnabled) "Flashlight ON" else "Flashlight OFF", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showInputNumbersDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Entrada de dados")
        builder.setMessage("Quer entrar com os 8 primeiros grupos manualmente?")

        // Set Yes button
        builder.setPositiveButton("Sim") { dialog, _ ->
            // Launch InputNumbersActivity if user wants to input numbers
            val intent = Intent(this, InputNumbersActivity::class.java)
            inputNumbersLauncher.launch(intent)
            dialog.dismiss()
        }

        // Set No button
        builder.setNegativeButton("Não") { dialog, _ ->

            // Initialize receivedGroups with default empty strings
            receivedGroups = listOf("", "", "", "", "", "", "", "")

            // Directly start the camera without input
            startCamera()
            setUpTapToFocus()
            setupButtons()  // Make sure to setup the button click listeners here as well
            dialog.dismiss()
        }

        // Show the dialog
        builder.create().show()
    }

    override fun onDestroy() {
        super.onDestroy()

        // Check if cameraExecutor is initialized before attempting to shut it down
        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }
    }
}
