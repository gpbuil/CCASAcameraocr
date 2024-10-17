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
import android.net.Uri
import android.os.Environment
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var imageCapture: ImageCapture
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Set up ViewBinding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Start the camera when the activity starts
        startCamera()

        // Set click listener for the capture button
        binding.captureButton.setOnClickListener { takePhoto() }

        // Initialize our background executor
        cameraExecutor = Executors.newSingleThreadExecutor()

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
    
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // Used to bind the lifecycle of cameras to the lifecycle owner
            val cameraProvider = cameraProviderFuture.get()

            // Preview use case
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
            }

            // Image capture use case
            imageCapture = ImageCapture.Builder().build()

            // Select back camera as default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind use cases before rebinding
                cameraProvider.unbindAll()

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )

            } catch (exc: Exception) {
                Log.e("CameraXApp", "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(this))
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
                        bitmap = rotateBitmap(bitmap, 90f)
                        Log.d("DebugRotation", "Rotated the image by 90 degrees")
                    }

                    // Get the overlay rectangle based on the captured image
                    val overlayRect = getOverlayRectForImage(bitmap)

                    // Crop the image to the overlay area
                    val croppedBitmap = cropBitmapToOverlay(bitmap, overlayRect)

                    // Save the cropped image to the gallery
                    saveBitmapToGallery(croppedBitmap)

                    // Perform OCR on the cropped image
                    performOCR(croppedBitmap)

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

        // Get the overlay position and size relative to the screen
        val location = IntArray(2)
        overlay.getLocationOnScreen(location)
        val overlayX = location[0]
        val overlayY = location[1]
        val overlayWidth = overlay.width
        val overlayHeight = overlay.height

        // Get the dimensions of the PreviewView (the camera preview)
        val previewWidth = binding.cameraPreview.width
        val previewHeight = binding.cameraPreview.height

        // Get the dimensions of the captured image (bitmap)
        var imageWidth = originalBitmap.width
        var imageHeight = originalBitmap.height

        // If the image is captured in landscape mode but the preview is in portrait, swap width and height
        if (imageWidth > imageHeight) {
            Log.d("DebugOrientation", "Swapping image width and height for portrait mode")
            val temp = imageWidth
            imageWidth = imageHeight
            imageHeight = temp
        }

        // Log the adjusted image dimensions
        Log.d("DebugImage", "Image Width: $imageWidth, Height: $imageHeight")

        // Calculate the scaling factor between the PreviewView and the captured image
        val scaleX = imageWidth.toFloat() / previewWidth
        val scaleY = imageHeight.toFloat() / previewHeight

        // Log the scaling factors
        Log.d("DebugScale", "Scale X: $scaleX, Scale Y: $scaleY")

        // Scale the overlay coordinates to match the image size
        val scaledX = (overlayX * scaleX).toInt()
        val scaledY = (overlayY * scaleY).toInt()
        val scaledWidth = (overlayWidth * scaleX).toInt()
        val scaledHeight = (overlayHeight * scaleY).toInt()

        // Log the scaled overlay values
        Log.d("DebugScaledOverlay", "Scaled X: $scaledX, Y: $scaledY, Width: $scaledWidth, Height: $scaledHeight")

        // Ensure that the crop rectangle stays within the image bounds
        val croppedX = scaledX.coerceAtLeast(0)  // Make sure it's >= 0
        val croppedY = scaledY.coerceAtLeast(0)  // Make sure it's >= 0
        val croppedWidth = (croppedX + scaledWidth).coerceAtMost(imageWidth) - croppedX
        val croppedHeight = (croppedY + scaledHeight).coerceAtMost(imageHeight) - croppedY

        // Log the final cropped rectangle
        Log.d("DebugCroppedRect", "Cropped X: $croppedX, Y: $croppedY, Width: $croppedWidth, Height: $croppedHeight")

        return Rect(croppedX, croppedY, croppedX + croppedWidth, croppedY + croppedHeight)
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

    // Helper function to save the cropped bitmap to the gallery
    private fun saveBitmapToGallery(bitmap: Bitmap) {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Cropped_IMG_${System.currentTimeMillis()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraWithOCR")
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            val outputStream = contentResolver.openOutputStream(it)
            outputStream?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
                Log.d("CameraXApp", "Cropped photo saved to gallery!")
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Cropped photo saved to gallery!", Toast.LENGTH_SHORT).show()
                }
            }
        }
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

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val detectedNumbers = extractNumbers(visionText.text)
                Log.d("OCRResult", "Detected Numbers: $detectedNumbers")
                if (detectedNumbers.isNotEmpty()) {
                    appendToFile(detectedNumbers)
                }
            }
            .addOnFailureListener { e ->
                Log.e("OCRFailure", "Text recognition failed: ${e.message}")
            }
    }


    // Function to extract numbers from the OCR result
    private fun extractNumbers(text: String): String {
        // Use a regular expression to match only digits (numbers)
        val regex = Regex("\\d+")

        // Extract numbers, remove spaces, and join the result into a single string
        val numbers = regex.findAll(text)
            .map { it.value }  // Extract the matched number from MatchResult
            .joinToString("")  // Join all numbers without any space separator

        return numbers
    }



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


    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
