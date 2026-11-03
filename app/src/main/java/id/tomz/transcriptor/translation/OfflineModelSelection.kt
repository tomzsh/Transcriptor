package id.tomz.transcriptor.translation

import id.tomz.transcriptor.model.ModelCatalog
import id.tomz.transcriptor.model.ModelKind
import id.tomz.transcriptor.model.ModelSpec
import java.io.File

internal const val IMPORTED_MODEL_ID = "imported"

/** Never silently switches to another file when an explicitly selected model is missing. */
internal fun offlineModelCandidate(id: String?, imported: File, catalogDirectory: File,
    specs: List<ModelSpec> = ModelCatalog.translationModels): File? {
    val candidate = when (id) {
        IMPORTED_MODEL_ID -> imported
        null -> return null
        else -> {
            val spec = specs.firstOrNull { it.id == id && it.kind == ModelKind.AI_TRANSLATION } ?: return null
            File(catalogDirectory, spec.fileName).takeIf { it.length() == spec.bytes } ?: return null
        }
    }
    return candidate.takeIf { runCatching { validateOfflineGguf(it) }.isSuccess }
}
