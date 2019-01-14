package jp.study.fuji.studymlkit

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.ImageFormat
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.support.v4.content.ContextCompat
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import com.google.firebase.ml.vision.face.FirebaseVisionFaceDetectorOptions
import kotlinx.android.synthetic.main.activity_camera.*
import java.util.*

class CameraActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CameraActivity"
        private const val MAX_PREVIEW_HEIGHT = 1920
        private const val MAX_PREVIEW_WIDTH = 1080

        /**
         * Given `choices` of `Size`s supported by a camera, choose the smallest one that
         * is at least as large as the respective texture view size, and that is at most as large as
         * the respective max size, and whose aspect ratio matches with the specified value. If such
         * size doesn't exist, choose the largest one that is at most as large as the respective max
         * size, and whose aspect ratio matches with the specified value.
         *
         * @param choices           The list of sizes that the camera supports for the intended
         *                          output class
         * @param textureViewWidth  The width of the texture view relative to sensor coordinate
         * @param textureViewHeight The height of the texture view relative to sensor coordinate
         * @param maxWidth          The maximum width that can be chosen
         * @param maxHeight         The maximum height that can be chosen
         * @param aspectRatio       The aspect ratio
         * @return The optimal `Size`, or an arbitrary one if none were big enough
         */
        @JvmStatic
        private fun chooseOptimalSize(
                choices: Array<Size>,
                textureViewWidth: Int,
                textureViewHeight: Int,
                maxWidth: Int,
                maxHeight: Int,
                aspectRatio: Size
        ): Size {

            // Collect the supported resolutions that are at least as big as the preview Surface
            val bigEnough = ArrayList<Size>()
            // Collect the supported resolutions that are smaller than the preview Surface
            val notBigEnough = ArrayList<Size>()
            val w = aspectRatio.width
            val h = aspectRatio.height
            for (option in choices) {
                if (option.width <= maxWidth && option.height <= maxHeight &&
                        option.height == option.width * h / w) {
                    if (option.width >= textureViewWidth && option.height >= textureViewHeight) {
                        bigEnough.add(option)
                    } else {
                        notBigEnough.add(option)
                    }
                }
            }

            // Pick the smallest of those big enough. If there is no one big enough, pick the
            // largest of those not big enough.
            if (bigEnough.size > 0) {
                return Collections.min(bigEnough, CompareSizesByArea())
            } else if (notBigEnough.size > 0) {
                return Collections.max(notBigEnough, CompareSizesByArea())
            } else {
                Log.e(TAG, "Couldn't find any suitable preview size")
                return choices[0]
            }
        }
    }

    enum class CameraState {
        PREVIEW,
        WAITING_LOCK,
        WAITING_PRECAPTURE,
        WAITING_NON_PRECAPTURE,
        PICTURE_TOKEN
    }

    private lateinit var cameraId: String

    private var captureSession: CameraCaptureSession? = null

    private var cameraDevice: CameraDevice? = null

    private lateinit var previewSize: Size

    private var imageReader: ImageReader? = null

    private lateinit var cameraManager: CameraManager

    /**
     * Whether the current camera device supports Flash or not.
     */
    private var flashSupported = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val options = FirebaseVisionFaceDetectorOptions.Builder()
                .setPerformanceMode(FirebaseVisionFaceDetectorOptions.FAST)
                .setLandmarkMode(FirebaseVisionFaceDetectorOptions.ALL_LANDMARKS)
                .setContourMode(FirebaseVisionFaceDetectorOptions.ALL_CONTOURS)
                .build()
    }

    override fun onResume() {
        super.onResume()

        if (camera_view.isAvailable) {
            openCamera(camera_view.width, camera_view.height)
        } else {
            camera_view.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        super.onPause()

        closeCamera()
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture?, width: Int, height: Int) {
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture?) {
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture?): Boolean {
            return false
        }

        override fun onSurfaceTextureAvailable(texture: SurfaceTexture?, width: Int, height: Int) {
            openCamera(width, height)
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(cameraDevice: CameraDevice) {
            this@CameraActivity.cameraDevice = cameraDevice
            createCameraPreviewSession(camera_view.surfaceTexture)
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            cameraDevice.close()
            this@CameraActivity.cameraDevice = null
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            finish()
        }
    }

    private val captureCallback = object: CameraCaptureSession.CaptureCallback() {

    }

    private fun setUp(textureViewWidth: Int, textureViewHeight: Int) {
        try {
            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)

                // カメラの向き
                val cameraDirection = characteristics.get(CameraCharacteristics.LENS_FACING)
                if (cameraDirection == null) {
                    continue
                } else if (cameraDirection == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue
                }

                val map = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue

                // For still image captures, we use the largest available size.
                val largest = Collections.max(
                        Arrays.asList(*map.getOutputSizes(ImageFormat.JPEG)),
                        CompareSizesByArea())
                imageReader = ImageReader.newInstance(largest.width, largest.height,
                        ImageFormat.JPEG, /*maxImages*/ 2).apply {
                    //                    setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)
                }

                // Find out if we need to swap dimension to get the preview size relative to sensor
                // coordinate.
                val displayRotation = windowManager.defaultDisplay.rotation


                val displaySize = Point()
                windowManager.defaultDisplay.getSize(displaySize)

                val maxPreviewWidth = if (textureViewWidth > MAX_PREVIEW_WIDTH)
                    MAX_PREVIEW_WIDTH else textureViewWidth

                val maxPreviewHeight = if (textureViewHeight > MAX_PREVIEW_HEIGHT)
                    MAX_PREVIEW_HEIGHT else textureViewHeight


                // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
                // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
                // garbage capture data.
                previewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture::class.java),
                        textureViewWidth, textureViewHeight,
                        maxPreviewWidth, maxPreviewHeight,
                        largest)

                /*
                // We fit the aspect ratio of TextureView to the size of preview we picked.
                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    camera_view.setAspectRatio(previewSize.width, previewSize.height)
                } else {
                    textureView.setAspectRatio(previewSize.height, previewSize.width)
                }
                */

                // Check if the flash is supported.
                flashSupported =
                        characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

                this.cameraId = cameraId
            }


        } finally {

        }

    }

    private fun openCamera(textureViewWidth: Int, textureViewHeight: Int) {
        val permission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        if (permission != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission()
            return
        }
        setUp(textureViewWidth, textureViewHeight)

        try {
            cameraManager.openCamera(cameraId, stateCallback, null)
        } catch (e: CameraAccessException) {

        }

    }

    /**
     * Closes the current [CameraDevice].
     */
    private fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
        }
    }

    private fun configureTransform(textureViewWidth: Int, textureViewHeight: Int) {

    }

    private fun requestCameraPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val permission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)

            if (permission != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.CAMERA), 200)
            }
        }
    }

    private fun createCameraPreviewSession(texture: SurfaceTexture) {
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)

        val surface = Surface(texture)
        cameraDevice?.apply {
            val builder = createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            createCaptureSession(Arrays.asList(surface, imageReader?.surface),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigureFailed(p0: CameraCaptureSession) {
                        }

                        override fun onConfigured(session: CameraCaptureSession) {
                            if (cameraDevice == null) return

                            captureSession = session
                            try {
                                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)

                                val preViewRequest = builder.build()
                                captureSession?.setRepeatingRequest(preViewRequest, captureCallback, null)
                            } catch (e: CameraAccessException) {
                                Log.e(TAG, e.toString())
                            }
                        }
                    },
                    null)
        }
    }
}
