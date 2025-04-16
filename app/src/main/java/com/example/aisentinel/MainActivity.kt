package com.example.aisentinel // *** MAKE SURE THIS MATCHES YOUR PACKAGE NAME ***

// Android & Kotlin Core Imports
import android.Manifest // Needed for permission constants
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent // Needed for Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.media.Image // Import android.media.Image for format constants
import android.net.Uri // Needed for Uri.parse("tel:")
import android.os.Build // Needed for version checks (optional but good practice)
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log // For logging
import android.view.View // Needed for System UI Visibility flags (alternative) or View class
import android.view.WindowInsets // Needed for newer fullscreen API
import android.view.WindowInsetsController // Needed for newer fullscreen API
import android.view.WindowManager // Needed for FLAG_KEEP_SCREEN_ON
import android.webkit.JavascriptInterface // <-- Import JavascriptInterface
import android.webkit.WebView
import android.widget.Toast

// AndroidX Imports
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat // For modern fullscreen/insets handling
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray // Keep for potential future use, but not used by current call interface
import org.json.JSONException // Keep for potential future use
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// Import your project's R class (Resource Definitions)
// *** MAKE SURE this package name is correct for your project's generated R file ***
import com.example.aisentinel.R

class MainActivity : AppCompatActivity() {

    // --- UI Elements ---
    private lateinit var webViewChat: WebView // Only the WebView remains

    // --- CameraX Variables ---
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    @Volatile private var isCameraReady = false // Flag to indicate successful binding

    // --- ActivityResultLaunchers ---
    // Launcher for CAMERA permission
    private val requestCameraPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d("Permissions", "Camera permission granted by user.")
                startCameraX() // Try starting camera again now permission is granted
            } else {
                Log.w("Permissions", "Camera permission denied by user.")
                showToast("Camera Permission denied. Cannot take photo.")
                isCameraReady = false // Explicitly set camera as not ready
            }
        }

    // Launcher for CALL_PHONE permission
    private val requestCallPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d("Permissions", "CALL_PHONE permission granted by user.")
                showToast("Call permission granted. Trigger call again if needed.")
                // NOTE: We don't automatically retry the call here.
                // The JS will trigger it again on the next detection if conditions are met.
            } else {
                Log.w("Permissions", "CALL_PHONE permission denied by user.")
                showToast("Call Permission Denied. Cannot make emergency calls.")
            }
        }

    // --- Activity Lifecycle Methods ---
    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // *** NEW: Keep Screen ON ***
        // This prevents the physical screen from turning off due to inactivity.
        // Essential for the HTML blackout overlay to work as intended.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.d("Lifecycle", "FLAG_KEEP_SCREEN_ON set.")

        // *** NEW: Set Fullscreen Mode (Hide Status Bar) ***
        // Uses modern WindowInsetsController API
        hideSystemUI()
        Log.d("Lifecycle", "System UI (Status Bar) hidden.")


        setContentView(R.layout.activity_main) // Use the layout
        Log.d("Lifecycle", "onCreate - ContentView set")

        webViewChat = findViewById(R.id.webViewChat) // Initialize WebView

        // --- WebView Setup ---
        webViewChat.settings.javaScriptEnabled = true
        webViewChat.settings.domStorageEnabled = true
        WebView.setWebContentsDebuggingEnabled(true) // Enable Chrome DevTools debugging
        webViewChat.addJavascriptInterface(WebAppInterface(this), "AndroidInterface")
        webViewChat.loadUrl("file:///android_asset/chat.html") // Ensure this matches your file
        Log.d("WebView", "WebView initialized and chat.html loading.")


        // --- Initialize Camera Executor ---
        cameraExecutor = Executors.newSingleThreadExecutor()

        // --- Check permissions and start CameraX setup ---
        checkCameraPermissionAndSetupCamera() // Checks CAMERA on startup
    }

     override fun onStop() {
        super.onStop()
        Log.d("Lifecycle", "onStop called")
        // Potentially stop camera here if needed to conserve resources when not visible,
        // though FLAG_KEEP_SCREEN_ON aims to keep it active.
        // cameraProvider?.unbindAll() // Consider if needed
     }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("Lifecycle", "onDestroy - Shutting down executor, unbinding camera")
        cameraExecutor.shutdown()
        cameraProvider?.unbindAll() // Ensure camera is released
        isCameraReady = false
    }

    // --- NEW: Function to Hide System Bars (Status Bar) ---
    private fun hideSystemUI() {
        // Use WindowCompat to handle decor fitting and insets controller
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (controller != null) {
            // Hide the status bar
            controller.hide(WindowInsetsCompat.Type.statusBars())
            // Optional: Hide navigation bar as well
            // controller.hide(WindowInsetsCompat.Type.navigationBars())

            // Keep bars hidden, allow revealing with swipe
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
             // Fallback for older devices or unusual scenarios (less likely with androidx)
             @Suppress("DEPRECATION")
             window.decorView.systemUiVisibility = (
                 View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                 or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                 or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION // Optional: if hiding nav bar
                 or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                 or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION // Optional: if hiding nav bar
                 or View.SYSTEM_UI_FLAG_FULLSCREEN)
             Log.w("Fullscreen", "Using fallback systemUiVisibility flags.")
        }
    }


    // --- CameraX Setup and Control ---
    private fun checkCameraPermissionAndSetupCamera() {
        Log.d("CameraSetup", "Checking camera permission...")
        when (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)) {
            PackageManager.PERMISSION_GRANTED -> {
                Log.d("CameraSetup", "Camera Permission granted. Starting CameraX.")
                startCameraX()
            }
            else -> {
                Log.d("CameraSetup", "Camera Permission not granted. Requesting...")
                isCameraReady = false
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun startCameraX() {
        if (isCameraReady && cameraProvider != null) {
             Log.d("CameraSetup", "startCameraX called, but camera is already ready. Skipping.")
             return
        }
        Log.d("CameraSetup", "startCameraX: Initializing CameraProvider...")
        val cameraProviderFuture: ListenableFuture<ProcessCameraProvider> = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                Log.d("CameraSetup", "CameraProvider obtained.")

                // Configure ImageCapture Use Case
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                     // Consider setting target resolution if needed for performance/consistency
                     // .setTargetResolution(Size(1280, 720)) // Example
                    .build()
                Log.d("CameraSetup", "ImageCapture use case configured.")

                // Select Back Camera
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                Log.d("CameraSetup", "Unbinding previous use cases (if any)...")
                // Crucial step before rebinding
                cameraProvider?.unbindAll()

                Log.d("CameraSetup", "Binding ImageCapture use case to lifecycle...")
                // Bind the use case to the lifecycle of this activity
                cameraProvider?.bindToLifecycle(this, cameraSelector, imageCapture)

                isCameraReady = true
                Log.i("CameraSetup", "CameraX initialized and bound. Camera is READY.")
                showToast("Camera ready.")

            } catch (e: ExecutionException) {
                Log.e("CameraSetup", "Error Getting CameraProvider", e.cause ?: e)
                showToast("Failed to get camera provider.")
                isCameraReady = false
            } catch (e: InterruptedException) {
                Log.e("CameraSetup", "CameraProvider Future Interrupted", e)
                showToast("Camera setup interrupted.")
                Thread.currentThread().interrupt()
                isCameraReady = false
            } catch (e: Exception) { // Catch generic Exception for robustness
                Log.e("CameraSetup", "Use case binding failed", e)
                showToast("Failed to initialize camera.")
                isCameraReady = false
            }
        }, ContextCompat.getMainExecutor(this)) // Run listener on main thread
    }


    private fun takePictureInBackground() {
        if (!isCameraReady) {
            Log.e("CameraCapture", "takePictureInBackground called but camera is NOT ready.")
            showToast("Camera not ready. Please wait or check permissions.")
            // Notify JS to stop if it's trying to auto-capture
            Handler(Looper.getMainLooper()).post {
                webViewChat.evaluateJavascript("javascript:if(typeof stopAutoCapture === 'function' && typeof isAutoCapturing !== 'undefined' && isAutoCapturing) stopAutoCapture();", null)
            }
            return
        }
        // Use the member variable, checking for null safety
        val imageCapture = this.imageCapture ?: run {
            Log.e("CameraCapture", "takePictureInBackground called, isCameraReady=true, but imageCapture is null! Re-initializing.")
            showToast("Camera error. Attempting re-init.")
            isCameraReady = false // Mark as not ready
             // Try to re-initialize
            checkCameraPermissionAndSetupCamera()
             // Notify JS to stop
             Handler(Looper.getMainLooper()).post {
                 webViewChat.evaluateJavascript("javascript:if(typeof stopAutoCapture === 'function' && typeof isAutoCapturing !== 'undefined' && isAutoCapturing) stopAutoCapture();", null)
             }
            return
        }

        Log.d("CameraCapture", "Attempting to take picture...")
        imageCapture.takePicture(
            cameraExecutor, // Execute on the dedicated camera thread
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    Log.d("CameraCapture", "Image captured successfully. Format: ${imageProxy.format}")
                    var bitmap: Bitmap? = null
                    try {
                        // Convert the ImageProxy to a Bitmap
                        bitmap = imageProxyToBitmap(imageProxy)

                        if (bitmap != null) {
                            // Convert Bitmap to Base64 Data URL
                            val base64ImageData = bitmapToBase64(bitmap)
                            if (base64ImageData != null) {
                                // Format as a data URL for WebView
                                val dataUrl = "data:image/jpeg;base64,$base64ImageData"
                                // Escape single quotes for JavaScript string literal
                                val escapedDataUrl = dataUrl.replace("'", "\\'")
                                val script = "javascript:previewImage('$escapedDataUrl')"

                                // Execute JavaScript on the main thread
                                Handler(Looper.getMainLooper()).post {
                                    webViewChat.evaluateJavascript(script, null)
                                }
                                Log.d("CameraCapture", "Successfully processed image and sent to JS.")
                            } else {
                                Log.e("CameraCapture", "Base64 conversion failed.")
                                showToast("Failed to process image (Base64).")
                                // Notify JS to stop if auto-capturing
                                Handler(Looper.getMainLooper()).post {
                                    webViewChat.evaluateJavascript("javascript:if(typeof stopAutoCapture === 'function' && typeof isAutoCapturing !== 'undefined' && isAutoCapturing) stopAutoCapture();", null)
                                 }
                            }
                        } else {
                            Log.e("CameraCapture", "imageProxyToBitmap returned null.")
                            showToast("Failed to process captured image data.")
                            Handler(Looper.getMainLooper()).post {
                                webViewChat.evaluateJavascript("javascript:if(typeof stopAutoCapture === 'function' && typeof isAutoCapturing !== 'undefined' && isAutoCapturing) stopAutoCapture();", null)
                             }
                        }
                    } catch (e: Exception) {
                         Log.e("CameraCapture", "Exception during onCaptureSuccess processing", e)
                         showToast("Error processing image.")
                         Handler(Looper.getMainLooper()).post {
                            webViewChat.evaluateJavascript("javascript:if(typeof stopAutoCapture === 'function' && typeof isAutoCapturing !== 'undefined' && isAutoCapturing) stopAutoCapture();", null)
                         }
                    } finally {
                         // *** CRUCIAL: Always close the ImageProxy ***
                         Log.d("CameraCapture", "Closing ImageProxy.")
                        imageProxy.close()
                    }
                }

                override fun onError(exc: ImageCaptureException) {
                    Log.e("CameraCapture", "Photo capture failed: ${exc.message} (Code: ${exc.imageCaptureError})", exc)
                    showToast("Photo capture failed: ${exc.imageCaptureError}")
                     // Notify JS to stop
                     Handler(Looper.getMainLooper()).post {
                        webViewChat.evaluateJavascript("javascript:if(typeof stopAutoCapture === 'function' && typeof isAutoCapturing !== 'undefined' && isAutoCapturing) stopAutoCapture();", null)
                     }
                }
            }
        )
    }


    // --- Utility Functions ---
    @SuppressLint("UnsafeOptInUsageError")
     private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
          Log.d("ImageConvert", "Attempting conversion for format: ${imageProxy.format} Size: ${imageProxy.width}x${imageProxy.height}")
         return try {
             when (imageProxy.format) {
                 // Common format for CameraX ImageAnalysis and ImageCapture
                 ImageFormat.YUV_420_888 -> {
                     val image = imageProxy.image ?: run { Log.e("ImageConvert", "YUV_420_888 ImageProxy.image was null"); return null }
                     val yBuffer = image.planes[0].buffer; val uBuffer = image.planes[1].buffer; val vBuffer = image.planes[2].buffer
                     val ySize = yBuffer.remaining(); val uSize = uBuffer.remaining(); val vSize = vBuffer.remaining()
                     if (ySize <= 0 || uSize <= 0 || vSize <= 0) { Log.e("ImageConvert", "YUV buffer size invalid: Y=$ySize, U=$uSize, V=$vSize"); return null }

                     // Combine the Y, U, V planes into a single NV21 byte array.
                     // NV21 format is supported by YuvImage.
                     val nv21 = ByteArray(ySize + uSize + vSize)
                     yBuffer.get(nv21, 0, ySize) // Copy Y
                     vBuffer.get(nv21, ySize, vSize) // Copy V
                     uBuffer.get(nv21, ySize + vSize, uSize) // Copy U

                     // Create YuvImage from the NV21 data
                     val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
                     val out = ByteArrayOutputStream()
                     // Compress YuvImage to JPEG
                     if (!yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 90, out)) { // Quality 90
                         Log.e("ImageConvert", "YuvImage.compressToJpeg failed for YUV_420_888"); return null
                     }
                     val imageBytes = out.toByteArray()
                     // Decode JPEG bytes into a Bitmap
                     BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                 }
                 // If the camera directly provides JPEG
                 ImageFormat.JPEG -> {
                     val buffer = imageProxy.planes[0].buffer; val bytes = ByteArray(buffer.remaining()); buffer.get(bytes)
                     BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                 }
                 // Add other formats if needed (e.g., RGB_565, but less common for camera)
                 else -> { Log.e("ImageConvert", "Unsupported ImageProxy format: ${imageProxy.format}"); null }
             }
         } catch (e: Exception) { Log.e("ImageConvert", "Error converting ImageProxy to Bitmap", e); null }
     }

     private fun bitmapToBase64(bitmap: Bitmap?): String? {
         if (bitmap == null) return null
         return try {
             val byteArrayOutputStream = ByteArrayOutputStream()
             // Compress Bitmap to JPEG format with 80% quality
             bitmap.compress(Bitmap.CompressFormat.JPEG, 80, byteArrayOutputStream)
             val byteArray = byteArrayOutputStream.toByteArray()
             // Encode byte array to Base64 string (NO_WRAP prevents line breaks)
             Base64.encodeToString(byteArray, Base64.NO_WRAP)
         } catch (e: Exception) {
             Log.e("Base64Convert", "Error converting bitmap to Base64", e)
             showToast("Error encoding bitmap")
             null
         }
     }

     // Helper to show Toast messages safely from any thread
     private fun showToast(message: String) {
         Handler(Looper.getMainLooper()).post {
             Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
         }
     }


    // --- Emergency Call Logic ---
    /**
     * Checks for CALL_PHONE permission and initiates a direct call
     * to the first valid number in the provided list if permission granted.
     * Requests permission if not granted.
     * This function now handles the permission check and intent creation.
     */
    private fun performEmergencyCall(phoneNumbers: List<String>) {
         // Ensure execution on the main thread for UI interactions (Toast, startActivity, permission request)
         Handler(Looper.getMainLooper()).post {
            Log.d("EmergencyCall", "Attempting to initiate calls to: $phoneNumbers")

            if (phoneNumbers.isEmpty()) {
                Log.w("EmergencyCall", "performEmergencyCall called with empty list.")
                showToast("No phone number provided.")
                return@post
            }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                Log.d("EmergencyCall", "CALL_PHONE permission is granted.")
                var callInitiated = false
                for (number in phoneNumbers) { // Iterate even if list usually has one element
                    val trimmedNumber = number.trim()
                    if (trimmedNumber.isNotEmpty() && trimmedNumber.matches(Regex("^\\+?[0-9\\s\\-\\(\\)]+\$"))) { // Basic phone number pattern validation
                        try {
                            Log.i("EmergencyCall", "Attempting to call valid number: $trimmedNumber")
                            val callIntent = Intent(Intent.ACTION_CALL) // Use ACTION_CALL for direct call
                            callIntent.data = Uri.parse("tel:$trimmedNumber")
                            // FLAG_ACTIVITY_NEW_TASK might be needed if called from a non-Activity context,
                            // but here it's called from Activity via Handler, so might not be strictly necessary,
                            // but doesn't hurt.
                            callIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            startActivity(callIntent)
                            showToast("Initiating emergency call to $trimmedNumber...")
                            callInitiated = true
                            break // Stop after first successful attempt (or first valid number in list)
                        } catch (e: SecurityException) {
                            Log.e("EmergencyCall", "SecurityException initiating call (Permission issue?)", e)
                            showToast("Call permission error.")
                            // If security exception happens, likely permission issue, stop trying
                            break
                        } catch (e: Exception) {
                            Log.e("EmergencyCall", "Failed to initiate call to $trimmedNumber", e)
                            showToast("Could not initiate call to $trimmedNumber.")
                            // Continue to next number in the list (though list has only 1 currently)
                        }
                    } else {
                         Log.w("EmergencyCall", "Skipping invalid or empty phone number entry: '$trimmedNumber'")
                    }
                }
                if (!callInitiated) {
                    Log.w("EmergencyCall", "No valid numbers found or all call attempts failed in the list.")
                    showToast("Could not initiate call. Check number format/permission.")
                }
            } else {
                // Permission is not granted
                Log.w("EmergencyCall", "CALL_PHONE permission NOT granted. Requesting...")
                showToast("Call permission needed for emergency calls.")
                // Request the permission. The actual call will need to be triggered again by JS after grant.
                requestCallPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
            }
        } // End Handler.post
    }


    // --- JavaScript Interface Class ---
    inner class WebAppInterface(private val context: Context) {

        /** Called by JavaScript to request a background photo capture. */
        @JavascriptInterface
        fun requestPhotoCapture() {
            Log.d("WebAppInterface", "JS requested photo capture. Checking camera status...")
            // Ensure camera operations are triggered from the main thread if they interact with UI/lifecycle
            Handler(Looper.getMainLooper()).post {
                if (isCameraReady) {
                    Log.d("WebAppInterface", "Camera is ready, calling takePictureInBackground.")
                    takePictureInBackground()
                } else {
                    Log.w("WebAppInterface", "Camera not ready when JS requested photo capture.")
                    showToast("Camera is initializing or permission denied.")
                    // Optionally try to restart camera setup if JS requests photo and it's not ready
                    // checkCameraPermissionAndSetupCamera()
                }
            }
        }

        /**
         * *** UPDATED: Called by JavaScript to initiate an emergency call to a SINGLE number ***
         * Matches the function name and parameter type called by the latest chat.html
         */
        @JavascriptInterface
        fun initiateEmergencyCall(phoneNumber: String?) { // Accept nullable string for safety
             val numberToCall = phoneNumber?.trim() // Trim whitespace

             Log.d("WebAppInterface", "JS requested single emergency call to: '$numberToCall'")

             if (numberToCall.isNullOrEmpty()) {
                  Log.w("WebAppInterface", "Received null or empty phone number from JS.")
                  showToast("No valid phone number provided for call.")
                  return // Exit early
             }

             try {
                  // Call the existing function that handles permission checks and the call intent.
                  // Pass the single number as a list with one element.
                  performEmergencyCall(listOf(numberToCall))

             } catch (e: Exception) {
                  // Catch unexpected errors during the call process initiation
                  Log.e("WebAppInterface", "Unexpected error in initiateEmergencyCall", e)
                  showToast("Internal error processing call request.")
             }
        }

        /* --- OLD Interface - Keep for reference or remove if sure ---
        @JavascriptInterface
        fun initiateEmergencyCalls(phoneNumbersJson: String) {
             Log.d("WebAppInterface", "JS requested emergency call with numbers JSON: $phoneNumbersJson")
             try {
                 val jsonArray = JSONArray(phoneNumbersJson)
                 val numbersList = mutableListOf<String>()
                 for (i in 0 until jsonArray.length()) {
                     val num = jsonArray.optString(i, null) // Use optString for safety
                     if (num != null && num.isNotBlank()) {
                         numbersList.add(num)
                     } else {
                         Log.w("WebAppInterface", "Skipping null or blank number in JSON array at index $i")
                     }
                 }

                 if (numbersList.isNotEmpty()) {
                     performEmergencyCall(numbersList) // Call the Kotlin function
                 } else {
                      Log.w("WebAppInterface", "Received empty/invalid list of phone numbers from JS JSON.")
                      showToast("No valid phone numbers provided for call.")
                 }
             } catch (e: JSONException) {
                  Log.e("WebAppInterface", "Error parsing phone numbers JSON from JS", e)
                  showToast("Error processing phone numbers.")
             } catch (e: Exception) {
                  Log.e("WebAppInterface", "Unexpected error in initiateEmergencyCalls", e)
                  showToast("Internal error processing call request.")
             }
        }
        */

    } // End WebAppInterface

} // End MainActivity