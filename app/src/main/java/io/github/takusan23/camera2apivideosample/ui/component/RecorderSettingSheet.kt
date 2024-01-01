package io.github.takusan23.camera2apivideosample.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.takusan23.camera2apivideosample.recorder.CameraRecordInterface

@Composable
fun RecordingSettingScreen(
    settingData: RecorderSettingData,
    onUpdateSetting: (RecorderSettingData) -> Unit
) {

    fun update(update: (RecorderSettingData) -> RecorderSettingData) {
        onUpdateSetting(update(settingData))
    }

    Column(
        modifier = Modifier.padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {

        Text(
            text = "録画設定",
            fontSize = 24.sp
        )

        Text(text = "コーデック")
        Row(modifier = Modifier.selectableGroup()) {
            CameraRecordInterface.Codec.entries.forEach { codec ->
                Row(
                    Modifier
                        .selectable(
                            selected = settingData.codec == codec,
                            onClick = { update { it.copy(codec = codec) } },
                            role = Role.RadioButton
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    RadioButton(
                        selected = settingData.codec == codec,
                        onClick = null
                    )
                    Text(text = codec.name)
                }
            }
        }

        Row(
            modifier = Modifier
                .selectable(
                    selected = settingData.isForceSoftwareEncoder,
                    onClick = { update { it.copy(isForceSoftwareEncoder = !settingData.isForceSoftwareEncoder) } },
                    role = Role.Checkbox
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = settingData.isForceSoftwareEncoder, onCheckedChange = null)
            Text(text = "ソフトウェアエンコーダーを利用する（非推奨。ハードウェアを優先すべきです）")
        }

        NumberInput(
            modifier = Modifier.fillMaxWidth(),
            value = settingData.bitRate,
            onValueChange = { number -> update { it.copy(bitRate = number) } },
            label = "ビットレート（単位: bit）"
        )

        NumberInput(
            modifier = Modifier.fillMaxWidth(),
            value = settingData.frameRate,
            onValueChange = { number -> update { it.copy(frameRate = number) } },
            label = "フレームレート（fps）"
        )

        NumberInput(
            modifier = Modifier.fillMaxWidth(),
            value = settingData.width,
            onValueChange = { number -> update { it.copy(width = number) } },
            label = "動画の幅"
        )


        NumberInput(
            modifier = Modifier.fillMaxWidth(),
            value = settingData.height,
            onValueChange = { number -> update { it.copy(height = number) } },
            label = "動画の高さ"
        )

        Spacer(modifier = Modifier.height(50.dp))
    }
}

@Composable
private fun NumberInput(
    modifier: Modifier,
    value: Int,
    onValueChange: (number: Int) -> Unit,
    label: String
) {
    OutlinedTextField(
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        value = value.toString(),
        onValueChange = { bitRate ->
            bitRate.toIntOrNull()?.let { number ->
                onValueChange(number)
            }
        },
        label = { Text(text = label) }
    )

}