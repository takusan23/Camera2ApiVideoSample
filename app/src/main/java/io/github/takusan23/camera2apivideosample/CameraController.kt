package io.github.takusan23.camera2apivideosample

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.util.Range
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import io.github.takusan23.camera2apivideosample.recorder.CameraRecordInterface
import io.github.takusan23.camera2apivideosample.recorder.CameraRecordMediaCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.coroutines.resume

@SuppressLint("NewApi") // Android Pie 以降、以前では camera2 API を直す必要があります
class CameraController(
    private val context: Context
) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private var cameraDevice: CameraDevice? = null

    // MediaRecorder / MediaCodec どっちかでエンコードする
    private var cameraRecorder: CameraRecordInterface? = null

    val previewSurfaceView = SurfaceView(context)

    val isRecording: Boolean
        get() = cameraRecorder?.isRecording ?: false

    private val isPortrait: Boolean
        get() = context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

    suspend fun setupCamera() {
        // カメラを開く
        cameraDevice = awaitOpenBackCamera()
        // プレビューを開始
        startPreview()
    }

    suspend fun startRecord() {
        // 録画するやつを用意
        cameraRecorder = CameraRecordMediaCodec(context)
        cameraRecorder?.prepareRecorder(
            codec = CameraRecordInterface.Codec.AV1,
            fileName = "Camera2ApiVideoSample_${System.currentTimeMillis()}.mp4",
            videoWidth = CAMERA_RESOLUTION_WIDTH,
            videoHeight = CAMERA_RESOLUTION_HEIGHT,
            videoFps = 30,
            videoBitrate = 3_000_000,
            videoKeyFrameInterval = 1,
            audioChannelCount = 2,
            audioSamplingRate = 44_100,
            audioBitrate = 192_000,
            isPortrait = isPortrait,
            isForceSoftwareEncode = false // ハードウェアエンコードじゃないと現実的じゃないです
        )

        // 録画モードでキャプチャーセッションを開く
        val previewSurface = awaitSurface()
        val cameraDevice = cameraDevice!!
        val captureRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(previewSurface)
            addTarget(cameraRecorder!!.surface)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 60))
        }.build()
        val outputList = listOf(
            OutputConfiguration(previewSurface),
            OutputConfiguration(cameraRecorder!!.surface)
        )
        // 変な解像度を入れるとここでエラーなります
        // コールバックを suspendCoroutine にする
        val captureSession = suspendCancellableCoroutine { continuation ->
            SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputList, cameraExecutor, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    continuation.resume(session)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    // do nothing
                }
            }).also { sessionConfiguration -> cameraDevice.createCaptureSession(sessionConfiguration) }
        }
        // カメラ映像を流し始める
        captureSession.setRepeatingRequest(captureRequest, null, null)
        // 録画開始
        if (!isRecording) {
            cameraRecorder?.startRecorder()
        }
    }


    suspend fun stopRecord() = withContext(Dispatchers.Default) {
        // プレビューに戻す
        cameraRecorder?.stopRecorder()
        cameraRecorder = null
        startPreview()
    }

    fun destroy() {
        cameraDevice?.close()
    }

    /** プレビューを開始する */
    private suspend fun startPreview() {
        // プレビューモードでキャプチャーセッションを開く
        val previewSurface = awaitSurface()
        val cameraDevice = cameraDevice!!
        val captureRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
        }.build()
        val outputList = listOf(OutputConfiguration(previewSurface))
        SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputList, cameraExecutor, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(captureSession: CameraCaptureSession) {
                captureSession.setRepeatingRequest(captureRequest, null, null)
            }

            override fun onConfigureFailed(p0: CameraCaptureSession) {
                // do nothing
            }
        }).also { sessionConfiguration -> cameraDevice.createCaptureSession(sessionConfiguration) }
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.P)
    private suspend fun awaitOpenBackCamera(): CameraDevice = suspendCancellableCoroutine {
        val backCameraId = cameraManager
            .cameraIdList
            .first { cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
        cameraManager.openCamera(backCameraId, cameraExecutor, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                it.resume(camera)
            }

            override fun onDisconnected(camera: CameraDevice) {
                // do nothing
            }

            override fun onError(camera: CameraDevice, error: Int) {
                // do nothing
            }
        })
    }

    /** SurfaceView のコールバックを待つ */
    private suspend fun awaitSurface() = suspendCancellableCoroutine {
        if (!previewSurfaceView.holder.isCreating) {
            // コールバックを待たなくていい場合はすぐ返す
            it.resume(previewSurfaceView.holder.surface)
            return@suspendCancellableCoroutine
        }
        val callback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                it.resume(holder.surface)
                previewSurfaceView.holder.removeCallback(this)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // do nothing
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // do nothing
            }
        }
        previewSurfaceView.holder.addCallback(callback)
        it.invokeOnCancellation { previewSurfaceView.holder.removeCallback(callback) }
    }

    companion object {

        /** 1080p 解像度 幅 */
        private const val CAMERA_RESOLUTION_WIDTH = 1280

        /** 1080p 解像度 高さ */
        private const val CAMERA_RESOLUTION_HEIGHT = 720

        /** 必要な権限 */
        val PERMISSION_LIST = listOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.CAMERA)

        /** 権限があるか */
        fun checkPermission(context: Context): Boolean = PERMISSION_LIST.all { permission -> ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED }
    }
}