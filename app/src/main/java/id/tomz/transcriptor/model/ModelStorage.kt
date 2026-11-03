package id.tomz.transcriptor.model

import android.content.Context
import java.io.File

internal fun localModelDirectory(context: Context): File =
    context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
