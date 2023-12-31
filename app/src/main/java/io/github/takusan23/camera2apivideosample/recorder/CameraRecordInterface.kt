package io.github.takusan23.camera2apivideosample.recorder

import android.view.Surface

interface CameraRecordInterface {

    val surface: Surface

    val isRecording: Boolean

    suspend fun prepareRecorder(
        codec: Codec,
        fileName: String,
        videoWidth: Int,
        videoHeight: Int,
        videoFps: Int,
        videoBitrate: Int,
        videoKeyFrameInterval: Int,
        audioChannelCount: Int,
        audioSamplingRate: Int,
        audioBitrate: Int,
        isPortrait: Boolean,
        isForceSoftwareEncode: Boolean // MediaCodec Only
    )

    /** エンコーダーを起動します */
    suspend fun startRecorder()

    /** エンコーダーを終了します */
    suspend fun stopRecorder()

    enum class Codec {
        AVC,
        HEVC,
        AV1
    }

}