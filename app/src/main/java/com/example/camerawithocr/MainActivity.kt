package com.example.camerawithocr

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.camerawithocr.databinding.ActivityMainBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private var binding: ActivityMainBinding? = null
    private lateinit var cameraControl: CameraControl
    private lateinit var imageCapture: ImageCapture
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var mediaPlayer: MediaPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding?.root)

        startCamera()
        setUpTapToFocus()

        // Set up the media player for beep sound
        mediaPlayer = MediaPlayer.create(this, R.raw.shutter_sound)  // Use your own sound file

        binding?.captureButton?.setOnClickListener { takePhoto() }

        // Initialize a background thread for camera
        cameraExecutor = Executors.newSingleThreadExecutor()

        binding?.viewFileButton?.setOnClickListener { viewFile() }
        binding?.sendFileButton?.setOnClickListener { sendFile() }
        binding?.resetFileButton?.setOnClickListener { resetFile() }

    }

    override fun onDestroy() {
        super.onDestroy()
        binding = null
        cameraExecutor.shutdown()
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
        binding?.cameraPreview?.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                // Get metering point factory from the camera preview
                val factory = binding?.cameraPreview?.meteringPointFactory
                val point = factory?.createPoint(event.x, event.y)

                // Create the FocusMeteringAction for the point where the user tapped
                val action = FocusMeteringAction.Builder(point!!, FocusMeteringAction.FLAG_AF).build()

                // Start the focus and metering action using the camera control
                cameraControl.startFocusAndMetering(action)

                // Perform click for accessibility compliance
                view.performClick()

                true
            } else {
                false
            }
        }
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val photoFile = File(
            getExternalFilesDir(null), "${System.currentTimeMillis()}.jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e("CameraXApp", "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Log.d("CameraXApp", "Photo capture succeeded: ${photoFile.absolutePath}")
                    mediaPlayer.start()  // Play the sound on capture

                    // Process the image for cropping and OCR
                    processImage(photoFile)
                }
            }
        )
    }

    private fun processImage(photoFile: File) {
        // Decode the image from the file into a bitmap
        val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)

        // Crop the bitmap according to your overlay
        val croppedBitmap = cropBitmapToOverlay(bitmap)

        // Resize the cropped bitmap for better OCR performance
        //val resizedBitmap = resizeBitmap(croppedBitmap, targetWidth = 1024, targetHeight = 768)  // Adjust dimensions based on your needs

        // Enhance contrast of the resized bitmap
        val contrastEnhancedBitmap = enhanceContrast(croppedBitmap)

        // Save the contrast-enhanced bitmap for debugging
        saveBitmapToFile(contrastEnhancedBitmap, "contrastEnhancedImage")

/*        // Binarize the contrast-enhanced bitmap
        val binarizedBitmap = binarizeBitmap(contrastEnhancedBitmap)

        // Save the binarized bitmap for debugging purposes
        saveBitmapToFile(binarizedBitmap, "binarizedImage")*/

        // Now run text recognition on the binarized bitmap
        runTextRecognition(contrastEnhancedBitmap)

        // After OCR, you can delete the image if you no longer need it
        if (photoFile.exists()) {
            photoFile.delete()
            Log.d("CameraXApp", "Photo file deleted after processing: ${photoFile.absolutePath}")
        }
    }

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


    private fun cropBitmapToOverlay(originalBitmap: Bitmap): Bitmap {
        val overlayRect = getOverlayRectForImage(originalBitmap)

        return Bitmap.createBitmap(
            originalBitmap,
            overlayRect.left,
            overlayRect.top,
            overlayRect.width(),
            overlayRect.height()
        )
    }

    private fun getOverlayRectForImage(originalBitmap: Bitmap): Rect {
        val overlay = binding?.overlay ?: return Rect()

        val location = IntArray(2)
        overlay.getLocationOnScreen(location)
        val overlayX = location[0]
        val overlayY = location[1]
        val overlayWidth = overlay.width
        val overlayHeight = overlay.height

        val previewWidth = binding?.cameraPreview?.width ?: 0
        val previewHeight = binding?.cameraPreview?.height ?: 0
        val imageWidth = originalBitmap.width
        val imageHeight = originalBitmap.height

        val scaleX = imageWidth.toFloat() / previewWidth
        val scaleY = imageHeight.toFloat() / previewHeight

        return Rect(
            (overlayX * scaleX).toInt(),
            (overlayY * scaleY).toInt(),
            (overlayX * scaleX + overlayWidth * scaleX).toInt(),
            (overlayY * scaleY + overlayHeight * scaleY).toInt()
        )
    }

    private fun runTextRecognition(croppedBitmap: Bitmap) {
        val image = InputImage.fromBitmap(croppedBitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                // Extract numbers from recognized text
                val detectedNumbers = extractNumbers(visionText.text)
                Log.d("CameraXApp", "Detected Numbers: $detectedNumbers")
                saveDetectedNumbers(detectedNumbers)
            }
            .addOnFailureListener { e ->
                Log.e("CameraXApp", "Text recognition failed: ${e.message}", e)
            }
    }

    private fun extractNumbers(text: String): String {
        return Regex("\\d+").findAll(text).joinToString { it.value }
    }

    private fun saveDetectedNumbers(numbers: String) {
        val file = File(getExternalFilesDir(null), "detected_numbers.txt")
        file.appendText("$numbers\n")
    }

    private fun sendFile() {
        val file = File(getExternalFilesDir(null), "detected_numbers.txt")

        if (file.exists()) {
            val fileUri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "OCR Detected Numbers")
                putExtra(Intent.EXTRA_STREAM, fileUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(intent, "Send email using:"))
        } else {
            Log.e("SendFile", "File does not exist")
        }
    }

    private fun viewFile() {
        val file = File(getExternalFilesDir(null), "detected_numbers.txt")

        if (file.exists()) {
            val fileContent = file.readText()

            // Create a dialog to display the file content
            val dialogBuilder = android.app.AlertDialog.Builder(this)
            dialogBuilder.setTitle("Detected Numbers")
            dialogBuilder.setMessage(fileContent)
            dialogBuilder.setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()  // Close the dialog
            }

            val dialog = dialogBuilder.create()
            dialog.show()

        } else {
            Log.e("ViewFile", "File does not exist")
        }
    }

    private fun resetFile() {
        val file = File(getExternalFilesDir(null), "detected_numbers.txt")

        if (file.exists()) {
            file.writeText("")  // Clear the file content
            Log.d("ResetFile", "File content reset")
        } else {
            Log.e("ResetFile", "File does not exist")
        }
    }

/*    private fun binarizeBitmap(original: Bitmap): Bitmap {
        val binarizedBitmap = Bitmap.createBitmap(original.width, original.height, original.config)
        var totalBrightness = 0

        // First pass: Calculate the total brightness of the image
        for (x in 0 until original.width) {
            for (y in 0 until original.height) {
                val pixel = original.getPixel(x, y)
                val gray = (0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)).toInt()
                totalBrightness += gray
            }
        }

        // Calculate average brightness to use as a dynamic threshold
        val avgBrightness = totalBrightness / (original.width * original.height)

        // Second pass: Apply binarization using the dynamic threshold
        for (x in 0 until original.width) {
            for (y in 0 until original.height) {
                val pixel = original.getPixel(x, y)
                val gray = (0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)).toInt()

                // Apply dynamic thresholding based on the average brightness
                if (gray > avgBrightness) {
                    binarizedBitmap.setPixel(x, y, Color.WHITE)
                } else {
                    binarizedBitmap.setPixel(x, y, Color.BLACK)
                }
            }
        }

        return binarizedBitmap
    }
*/

/*
    private fun resizeBitmap(original: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        return Bitmap.createScaledBitmap(original, targetWidth, targetHeight, true)
    }
*/


    private fun enhanceContrast(bitmap: Bitmap): Bitmap {
        val contrast = 0.7  // Adjust contrast as needed
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

}
