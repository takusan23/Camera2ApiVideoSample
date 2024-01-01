package io.github.takusan23.camera2apivideosample

import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.takusan23.camera2apivideosample.ui.component.RecorderSettingData
import io.github.takusan23.camera2apivideosample.ui.component.RecordingSettingScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val isPortrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    val cameraController = remember { CameraController(context) }

    // 権限
    val isGrantedPermission = remember { mutableStateOf(CameraController.checkPermission(context)) }
    val permissionRequester = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { isGrantedPermission.value = it.all { (_, isGranted) -> isGranted } }
    )

    // カメラの用意
    DisposableEffect(key1 = Unit) {
        scope.launch {
            // 権限がなければ貰う
            if (!isGrantedPermission.value) {
                permissionRequester.launch(CameraController.PERMISSION_LIST.toTypedArray())
            }

            // 権限が付与されるまで待つ
            snapshotFlow { isGrantedPermission.value }.first { isGranted -> isGranted /* == true */ }

            // カメラを開く
            cameraController.setupCamera()
        }
        onDispose { cameraController.destroy() }
    }

    // 設定ボトムシート
    val isSettingOpen = remember { mutableStateOf(false) }
    val settingData = remember { mutableStateOf(RecorderSettingData()) }
    if (isSettingOpen.value) {
        ModalBottomSheet(onDismissRequest = { isSettingOpen.value = false }) {
            RecordingSettingScreen(
                settingData = settingData.value,
                onUpdateSetting = { settingData.value = it }
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        AndroidView(
            modifier = Modifier
                //プレビューが歪むのでサイズとアスペクト比修正
                // 正方形にしてはみ出すようなサイズにすれば良い
                // Jetpack Compose かゆいところに手が届いて神だろ
                .then(
                    if (isPortrait) {
                        Modifier
                            .fillMaxHeight()
                            .aspectRatio(1f, true)
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f, false)
                    }
                ),
            factory = { cameraController.previewSurfaceView }
        )

        Button(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 50.dp),
            onClick = {
                scope.launch {
                    if (!cameraController.isRecording) {
                        // 設定を渡す
                        cameraController.startRecord(
                            codec = settingData.value.codec,
                            videoWidth = settingData.value.width,
                            videoHeight = settingData.value.height,
                            videoFps = settingData.value.frameRate,
                            videoBitrate = settingData.value.bitRate,
                            isForceSoftwareEncode = settingData.value.isForceSoftwareEncoder
                        )
                    } else {
                        cameraController.stopRecord()
                    }
                }
            }
        ) {
            Text("録画開始・終了")
        }

        Button(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 50.dp),
            onClick = { isSettingOpen.value = true }
        ) {
            Text(text = "設定")
        }

    }

}
