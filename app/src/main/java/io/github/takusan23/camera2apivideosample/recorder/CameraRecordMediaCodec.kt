package io.github.takusan23.camera2apivideosample.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import androidx.core.content.contentValuesOf
import io.github.takusan23.camera2apivideosample.recorder.mediacodec.AudioEncoder
import io.github.takusan23.camera2apivideosample.recorder.mediacodec.VideoEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CameraRecordMediaCodec(private val context: Context) : CameraRecordInterface {

    private var encoderJob: Job? = null

    // 自前の MediaCodec ラッパーです
    private var audioEncoder: AudioEncoder? = null
    private var videoEncoder: VideoEncoder? = null

    private var mediaMuxer: MediaMuxer? = null
    private var audioRecord: AudioRecord? = null
    private var recordingFile: File? = null

    override val surface: Surface
        get() = videoEncoder!!.inputSurface!!

    override var isRecording: Boolean = false

    override suspend fun prepareRecorder(
        codec: CameraRecordInterface.Codec,
        videoWidth: Int,
        videoHeight: Int,
        videoFps: Int,
        videoBitrate: Int,
        videoKeyFrameInterval: Int,
        audioChannelCount: Int,
        audioSamplingRate: Int,
        audioBitrate: Int
    ) {
        // 音声エンコーダーの初期化
        prepareAudioEncoder(
            audioChannelCount = audioChannelCount,
            audioSamplingRate = audioSamplingRate,
            audioBitrate = audioBitrate
        )
        // 映像エンコーダーの初期化
        prepareVideoEncoder(
            codec = codec,
            // 解像度は縦長の動画は作れない？
            // 代わりに回転情報を付与する
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            videoFps = videoFps,
            videoBitrate = videoBitrate,
            videoKeyFrameInterval = videoKeyFrameInterval
        )
        // マルチプレクサの初期化
        prepareMuxer(
            isPortlate = videoWidth < videoHeight
        )
    }

    override suspend fun startRecorder() {
        val videoEncoder = videoEncoder ?: return
        val audioEncoder = audioEncoder ?: return
        val audioRecord = audioRecord ?: return
        val mediaMuxer = mediaMuxer ?: return

        coroutineScope {
            // エンコーダー開始
            isRecording = true

            // MediaMuxer に addTrack したときの返り値
            var audioIndex = -1
            var videoIndex = -1
            var isStarted = false

            // 録音開始
            audioRecord.startRecording()

            // MediaMuxer を開始していいか
            fun startMediaMuxer() {
                // 両方揃ったら Muxer 開始
                if (audioIndex != -1 && videoIndex != -1) {
                    isStarted = true
                    mediaMuxer.start()
                }
            }

            encoderJob = launch {
                try {
                    listOf(
                        launch {
                            audioEncoder.startAudioEncode(
                                onRecordInput = { bytes ->
                                    audioRecord.read(bytes, 0, bytes.size)
                                },
                                onOutputBufferAvailable = { byteBuffer, bufferInfo ->
                                    if (isStarted) {
                                        mediaMuxer.writeSampleData(audioIndex, byteBuffer, bufferInfo)
                                    }
                                },
                                onOutputFormatAvailable = { mediaFormat ->
                                    audioIndex = mediaMuxer.addTrack(mediaFormat)
                                    startMediaMuxer()
                                }
                            )
                        },
                        launch {
                            videoEncoder.startVideoEncode(
                                onOutputBufferAvailable = { byteBuffer, bufferInfo ->
                                    if (isStarted) {
                                        mediaMuxer.writeSampleData(videoIndex, byteBuffer, bufferInfo)
                                    }
                                },
                                onOutputFormatAvailable = { mediaFormat ->
                                    println("onOutputFormatAvailable = $mediaFormat")
                                    videoIndex = mediaMuxer.addTrack(mediaFormat)
                                    startMediaMuxer()
                                }
                            )
                        }
                    ).joinAll()
                } finally {
                    // キャンセル時（終了時）はリソース開放
                    mediaMuxer.stop()
                    mediaMuxer.release()
                    audioRecord.stop()
                    audioRecord.release()
                    audioEncoder.release()
                    videoEncoder.release()
                    this@CameraRecordMediaCodec.audioRecord = null
                    this@CameraRecordMediaCodec.audioEncoder = null
                    this@CameraRecordMediaCodec.videoEncoder = null
                    this@CameraRecordMediaCodec.mediaMuxer = null
                }
            }
        }
    }

    override suspend fun stopRecorder() {
        // 停止
        isRecording = false
        encoderJob?.cancelAndJoin()

        // 端末の動画フォルダに移動
        withContext(Dispatchers.IO) {
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

    private fun prepareMuxer(isPortlate: Boolean) {
        recordingFile = context.getExternalFilesDir(null)?.resolve("Camera2ApiVideoSample_${System.currentTimeMillis()}.mp4")
        mediaMuxer = MediaMuxer(recordingFile!!.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        mediaMuxer?.setOrientationHint(if (isPortlate) 90 else 0)
    }

    private fun prepareVideoEncoder(
        codec: CameraRecordInterface.Codec,
        videoWidth: Int,
        videoHeight: Int,
        videoFps: Int,
        videoBitrate: Int,
        videoKeyFrameInterval: Int
    ) {
        // 映像エンコーダーの初期化
        val codecName = when (codec) {
            CameraRecordInterface.Codec.AVC -> MediaFormat.MIMETYPE_VIDEO_AVC
            CameraRecordInterface.Codec.HEVC -> MediaFormat.MIMETYPE_VIDEO_HEVC
            CameraRecordInterface.Codec.AV1 -> MediaFormat.MIMETYPE_VIDEO_AV1
        }
        videoEncoder = VideoEncoder()
        videoEncoder?.prepareEncoder(
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            bitRate = videoBitrate,
            frameRate = videoFps,
            iFrameInterval = videoKeyFrameInterval,
            codecName = codecName
        )
    }

    @SuppressLint("MissingPermission", "NewApi")
    private fun prepareAudioEncoder(
        audioChannelCount: Int,
        audioSamplingRate: Int,
        audioBitrate: Int
    ) {
        // マイク録音、PCM バイト配列がもらえる
        val channelConfig = if (audioChannelCount == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val bufferSizeInBytes = AudioRecord.getMinBufferSize(audioSamplingRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
        val audioFormat = AudioFormat.Builder().apply {
            setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            setSampleRate(audioSamplingRate)
            setChannelMask(channelConfig)
        }.build()
        audioRecord = AudioRecord.Builder().apply {
            setAudioFormat(audioFormat)
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setBufferSizeInBytes(bufferSizeInBytes)
        }.build()

        // 音声エンコーダーの初期化
        audioEncoder = AudioEncoder()
        audioEncoder?.prepareEncoder(sampleRate = audioSamplingRate, channelCount = audioChannelCount, bitRate = audioBitrate)
    }
}