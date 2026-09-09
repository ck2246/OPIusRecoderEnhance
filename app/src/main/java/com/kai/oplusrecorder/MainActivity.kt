package com.kai.oplusrecorder

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        setContentView(
            R.layout.activity_main
        )

        val videoEdit =
            findViewById<EditText>(
                R.id.videoBitrate
            )

        val audioEdit =
            findViewById<EditText>(
                R.id.audioBitrate
            )

        val prefs =
            RemoteSettings.prefs

        videoEdit.setText(
            ((prefs?.getInt(
                "video_bitrate",
                30_000_000
            ) ?: 30_000_000) / 1_000_000.0)
                .toString()
        )

        audioEdit.setText(
            (
                    prefs?.getInt(
                        "audio_bitrate",
                        288_000
                    ) ?: 288_000
                    ).div(1000).toString()
        )

        findViewById<Button>(
            R.id.saveButton
        ).setOnClickListener {

            val videoMbps =
                videoEdit.text
                    .toString()
                    .toDoubleOrNull()

            val audioKbps =
                audioEdit.text
                    .toString()
                    .toIntOrNull()

            if (
                videoMbps == null ||
                audioKbps == null
            ) {

                Toast.makeText(
                    this,
                    "请输入有效数字",
                    Toast.LENGTH_SHORT
                ).show()

                return@setOnClickListener
            }

            val videoBps =
                (videoMbps * 1_000_000)
                    .toInt()

            val audioBps =
                audioKbps * 1000

            RemoteSettings.prefs
                ?.edit()
                ?.putInt(
                    "video_bitrate",
                    videoBps
                )
                ?.putInt(
                    "audio_bitrate",
                    audioBps
                )
                ?.apply()

            Toast.makeText(
                this,
                "设置已保存",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}