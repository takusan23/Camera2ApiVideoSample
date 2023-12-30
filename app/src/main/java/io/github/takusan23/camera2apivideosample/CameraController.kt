package io.github.takusan23.camera2apivideosample

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.contentValuesOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume

@SuppressLint("NewApi") // Android Pie 以降、以前では camera2 API を直す必要があります
class CameraController(
    private val context: Context,
) {

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private var cameraDevice: CameraDevice? = null
    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File? = null

    var isRecording = false
        private set

    val previewSurfaceView = SurfaceView(context)

    private val isLandscape: Boolean
        get() = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    suspend fun setupCamera() {
        // カメラを開く
        cameraDevice = awaitOpenBackCamera()
        // プレビューを開始
        startPreview()
    }

    suspend fun startRecord() {
        // 録画するやつを用意
        this@CameraController.mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()).apply {
            // 呼び出し順があるので注意
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.AV1) // AV1
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(2)
            setVideoEncodingBitRate(3_000_000) // ニコ動が H.264 AVC で 6M なので、AV1 なら半分でも同等の画質を期待して
            setVideoFrameRate(30)
            // 解像度、縦動画の場合は、代わりに回転情報を付与する（縦横の解像度はそのまま）
            setVideoSize(CAMERA_RESOLUTION_WIDTH, CAMERA_RESOLUTION_HEIGHT)
            setOrientationHint(if (isLandscape) 0 else 90)
            setAudioEncodingBitRate(192_000)
            setAudioSamplingRate(44_100)
            // 保存先
            // 動画フォルダに保存する処理が追加で必要
            recordingFile = context.getExternalFilesDir(null)?.resolve("Camera2ApiVideoSample_${System.currentTimeMillis()}.mp4")
            setOutputFile(recordingFile)
            prepare()
        }
        val mediaRecorder = mediaRecorder!!

        // 録画モードでキャプチャーセッションを開く
        val previewSurface = awaitSurface()
        val cameraDevice = cameraDevice!!
        val captureRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(previewSurface)
            addTarget(mediaRecorder.surface)
        }.build()
        val outputList = listOf(
            OutputConfiguration(previewSurface),
            OutputConfiguration(mediaRecorder.surface)
        )
        // 変な解像度を入れるとここでエラーなります
        SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputList, cameraExecutor, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                // 録画開始
                if (!isRecording) {
                    isRecording = true
                    mediaRecorder.start()
                }
                session.setRepeatingRequest(captureRequest, null, null)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                // do nothing
            }
        }).also { sessionConfiguration -> cameraDevice.createCaptureSession(sessionConfiguration) }
    }


    suspend fun stopRecord() = withContext(Dispatchers.IO) {
        // プレビューに戻す
        isRecording = false
        startPreview()

        // 録画停止
        mediaRecorder?.stop()
        mediaRecorder?.release()
        mediaRecorder = null

        // 端末の動画フォルダに移動
        recordingFile?.also { recordingFile ->
            val contentValues = contentValuesOf(
                MediaStore.MediaColumns.DISPLAY_NAME to recordingFile.name,
                MediaStore.MediaColumns.RELATIVE_PATH to "${Environment.DIRECTORY_MOVIES}/Camera2ApiVideoSample",
                MediaStore.MediaColumns.MIME_TYPE to "video/mp4"
            )
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)!!
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                recordingFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            recordingFile.delete()
        }
    }

    fun destroy() {
        cameraDevice?.close()
        mediaRecorder?.release()
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

        /** 720P 解像度 幅 */
        private const val CAMERA_RESOLUTION_WIDTH = 1280

        /** 720P 解像度 高さ */
        private const val CAMERA_RESOLUTION_HEIGHT = 720

        /** 必要な権限 */
        val PERMISSION_LIST = listOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.CAMERA)

        /** 権限があるか */
        fun checkPermission(context: Context): Boolean = PERMISSION_LIST.all { permission -> ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED }
    }
}