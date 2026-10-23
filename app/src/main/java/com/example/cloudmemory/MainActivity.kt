package com.example.cloudmemory

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val bg = Color.rgb(13, 17, 28)
    private val panel = Color.rgb(25, 32, 48)
    private val accent = Color.rgb(104, 128, 255)
    private val muted = Color.rgb(165, 176, 199)
    private val white = Color.rgb(245, 247, 255)

    private val PICK_FOLDER = 2001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
        window.navigationBarColor = bg

        render()
    }

    private fun render() {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(bg)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(22), dp(24), dp(22), dp(28))

        scroll.addView(root)
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "CLOUD MEMORY"
            textSize = 12f
            letterSpacing = 0.18f
            setTextColor(accent)
            typeface = Typeface.DEFAULT_BOLD
        })

        root.addView(TextView(this).apply {
            text = "Your files,\none place."
            textSize = 32f
            setTextColor(white)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(10), 0, dp(8))
        })

        root.addView(TextView(this).apply {
            text = "Your phone and cloud storage, together."
            textSize = 15f
            setTextColor(muted)
        })

        val summary = card()

        summary.addView(TextView(this).apply {
            text = "STORAGE OVERVIEW"
            textSize = 12f
            setTextColor(muted)
        })

        summary.addView(TextView(this).apply {
            text = "Phone storage"
            textSize = 18f
            setTextColor(white)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(12), 0, dp(4))
        })

        summary.addView(TextView(this).apply {
            text = "Choose a folder to grant access to local files."
            textSize = 13f
            setTextColor(muted)
        })

        summary.addView(actionButton("Browse phone files") {
            openFolderPicker()
        })

        root.addView(summary, marginTop(24))

        root.addView(sectionTitle("CONNECTED CLOUDS"), marginTop(24))

        root.addView(
            cloudCard(
                "Google Drive",
                "Cloud file access setup is coming next.",
                "Connect Google Drive"
            ) {
                showMessage(
                    "Google Drive requires authorized sign-in and API setup."
                )
            },
            marginTop(10)
        )

        root.addView(
            cloudCard(
                "TeraBox",
                "Integration requires a supported, authorized interface.",
                "Set up TeraBox"
            ) {
                showMessage(
                    "TeraBox integration is not implemented yet."
                )
            },
            marginTop(10)
        )

        root.addView(sectionTitle("FEATURES"), marginTop(24))

        root.addView(TextView(this).apply {
            text = """
                • Dark, modern interface
                • Local folder access
                • Unified cloud dashboard
                • Future upload and download support
                • Future offline file support

                Cloud storage remains online storage.
                It cannot be merged directly into your
                phone's physical internal memory.
            """.trimIndent()

            textSize = 14f
            setTextColor(muted)
            setLineSpacing(dp(5).toFloat(), 1f)
            setPadding(dp(4), dp(12), dp(4), 0)
        })

        root.addView(TextView(this).apply {
            text = "CLOUD MEMORY • VERSION 0.1.0"
            textSize = 11f
            setTextColor(muted)
            gravity = Gravity.CENTER
            setPadding(0, dp(30), 0, dp(8))
        })
    }

    private fun card(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(17), dp(17), dp(17))
            setBackgroundColor(panel)
        }
    }

    private fun cloudCard(
        title: String,
        description: String,
        button: String,
        click: () -> Unit
    ): LinearLayout {

        val box = card()

        box.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(white)
            typeface = Typeface.DEFAULT_BOLD
        })

        box.addView(TextView(this).apply {
            text = description
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(7), 0, dp(4))
        })

        box.addView(actionButton(button, click))

        return box
    }

    private fun sectionTitle(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 12f
            letterSpacing = 0.08f
            setTextColor(muted)
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    private fun actionButton(
        label: String,
        click: () -> Unit
    ): Button {

        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(accent)

            setOnClickListener {
                click()
            }

            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply {
                topMargin = dp(14)
            }
        }
    }

    private fun openFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent, PICK_FOLDER)
    }

    @Deprecated("Deprecated API retained for compatibility")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == PICK_FOLDER &&
            resultCode == RESULT_OK
        ) {
            val uri: Uri? = data?.data

            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )

                showMessage(
                    "Folder access granted successfully!"
                )
            }
        }
    }

    private fun showMessage(message: String) {
        Toast.makeText(
            this,
            message,
            Toast.LENGTH_LONG
        ).show()
    }

    private fun marginTop(value: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(value)
        }
    }

    private fun dp(value: Int): Int {
        return (
            value * resources.displayMetrics.density
        ).toInt()
    }
}
