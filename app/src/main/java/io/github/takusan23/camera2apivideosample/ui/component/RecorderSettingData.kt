package io.github.takusan23.camera2apivideosample.ui.component

import io.github.takusan23.camera2apivideosample.recorder.CameraRecordInterface

data class RecorderSettingData(
    val codec: CameraRecordInterface.Codec = CameraRecordInterface.Codec.AV1,
    val isForceSoftwareEncoder: Boolean = false,
    val width: Int = 1280,
    val height: Int = 720,
    val bitRate: Int = 3_000_000, // 3Kbps
    val frameRate: Int = 60,
)