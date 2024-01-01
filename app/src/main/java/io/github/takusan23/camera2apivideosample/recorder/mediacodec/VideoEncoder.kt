package io.github.takusan23.camera2apivideosample.recorder.mediacodec

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

class VideoEncoder {

    /** MediaCodec エンコーダー */
    private var mediaCodec: MediaCodec? = null

    /** MediaCodecの入力Surface */
    var inputSurface: Surface? = null
        private set

    /**
     * エンコーダーを初期化する
     *
     * @param videoWidth 動画の幅
     * @param videoHeight 動画の高さ
     * @param bitRate ビットレート
     * @param frameRate フレームレート
     * @param iFrameInterval キーフレーム生成間隔
     * @param codecName [MediaFormat.MIMETYPE_VIDEO_VP9]や[MediaFormat.MIMETYPE_VIDEO_AVC]など
     * @param isForceSoftwareEncode ソフトウェアエンコーダーを利用するか（非推奨、ハードウェアを優先すべきです）
     */
    fun prepareEncoder(
        videoWidth: Int,
        videoHeight: Int,
        bitRate: Int,
        frameRate: Int,
        iFrameInterval: Int = 1,
        codecName: String,
        isForceSoftwareEncode: Boolean
    ) {
        val videoEncodeFormat = MediaFormat.createVideoFormat(codecName, videoWidth, videoHeight).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        }
        // エンコーダー用意
        mediaCodec = if (isForceSoftwareEncode) {
            // もしソフトウェアエンコーダーの場合は名前を探す
            val softwareCodecName = MediaCodecList(MediaCodecList.ALL_CODECS)
                .codecInfos
                .filter { it.isEncoder && it.isSoftwareOnly }
                .first { codecInfo -> codecInfo.supportedTypes.any { type -> type.equals(codecName, ignoreCase = true) } }
                .name
            MediaCodec.createByCodecName(softwareCodecName)
        } else {
            // 特段していなければハードウェアが優先される
            MediaCodec.createEncoderByType(codecName)
        }.apply {
            configure(videoEncodeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        inputSurface = mediaCodec!!.createInputSurface()
    }

    /**
     * エンコーダーを開始する。同期モードを使うのでコルーチンを使います（スレッドでも良いけど）
     *
     * @param onOutputBufferAvailable エンコードされたデータが流れてきます
     * @param onOutputFormatAvailable エンコード後のMediaFormatが入手できる
     */
    suspend fun startVideoEncode(
        onOutputBufferAvailable: suspend (ByteBuffer, MediaCodec.BufferInfo) -> Unit,
        onOutputFormatAvailable: suspend (MediaFormat) -> Unit,
    ) = withContext(Dispatchers.Default) {
        // 多分使い回す
        val bufferInfo = MediaCodec.BufferInfo()
        mediaCodec?.start()

        try {
            while (isActive) {
                // もし -1 が返ってくれば configure() が間違ってる
                val outputBufferId = mediaCodec!!.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputBufferId >= 0) {
                    val outputBuffer = mediaCodec!!.getOutputBuffer(outputBufferId)!!
                    if (bufferInfo.size > 1) {
                        // ファイルに書き込む...
                        onOutputBufferAvailable(outputBuffer, bufferInfo)
                    }
                    // 返却
                    mediaCodec!!.releaseOutputBuffer(outputBufferId, false)
                } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 多分映像データより先に呼ばれる
                    // MediaMuxerへ映像トラックを追加するのはこのタイミングで行う
                    // このタイミングでやると固有のパラメーターがセットされたMediaFormatが手に入る(csd-0 とか)
                    // 映像がぶっ壊れている場合（緑で塗りつぶされてるとか）は多分このあたりが怪しい
                    onOutputFormatAvailable(mediaCodec!!.outputFormat)
                }
            }
        } catch (e: Exception) {
            // なぜか例外を吐くので
            // java.lang.IllegalStateException
            // at android.media.MediaCodec.native_dequeueOutputBuffer(Native Method)
            e.printStackTrace()
        }
    }

    /** リソースを開放する */
    fun release() {
        try {
            inputSurface?.release()
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {

        /** MediaCodec タイムアウト */
        private const val TIMEOUT_US = 10_000L

    }
}