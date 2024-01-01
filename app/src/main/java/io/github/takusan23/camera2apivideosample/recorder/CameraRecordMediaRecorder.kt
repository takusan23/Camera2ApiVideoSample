package io.github.takusan23.camera2apivideosample.recorder

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import androidx.core.content.contentValuesOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** [MediaRecorder]で録画する */
class CameraRecordMediaRecorder(
    private val context: Context
) : CameraRecordInterface {

    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File? = null

    override val surface: Surface
        get() = mediaRecorder!!.surface

    override var isRecording: Boolean = false

    override suspend fun prepareRecorder(
        codec: CameraRecordInterface.Codec,
        fileName: String,
        videoWidth: Int,
        videoHeight: Int,
        videoFps: Int,
        videoBitrate: Int,
        videoKeyFrameInterval: Int,
        audioChannelCount: Int,
        audioSamplingRate: Int,
        audioBitrate: Int
    ) {
        mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()).apply {
            // 呼び出し順があるので注意
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(
                when (codec) {
                    CameraRecordInterface.Codec.AVC -> MediaRecorder.VideoEncoder.H264
                    CameraRecordInterface.Codec.HEVC -> MediaRecorder.VideoEncoder.HEVC
                    CameraRecordInterface.Codec.AV1 -> MediaRecorder.VideoEncoder.AV1
                }
            )
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(audioChannelCount)
            setVideoEncodingBitRate(videoBitrate) // ニコ動が H.264 AVC で 6M なので、AV1 なら半分でも同等の画質を期待して
            setVideoFrameRate(videoFps)
            // 解像度、縦動画の場合は、代わりに回転情報を付与する（縦横の解像度はそのまま）
            val isPortlate = videoWidth < videoHeight
            setVideoSize(videoWidth, videoHeight)
            setOrientationHint(if (isPortlate) 90 else 0)
            setAudioEncodingBitRate(audioBitrate)
            setAudioSamplingRate(audioSamplingRate)
            // 保存先
            // 動画フォルダに保存する処理が追加で必要
            recordingFile = context.getExternalFilesDir(null)?.resolve(fileName)
            setOutputFile(recordingFile!!.path)
            prepare()
        }
    }

    override suspend fun startRecorder() {
        // 録画開始
        isRecording = true
        mediaRecorder?.start()
    }

    override suspend fun stopRecorder() {
        isRecording = false
        // 録画停止
        mediaRecorder?.stop()
        mediaRecorder?.release()
        mediaRecorder = null

        withContext(Dispatchers.IO) {
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
    }
}