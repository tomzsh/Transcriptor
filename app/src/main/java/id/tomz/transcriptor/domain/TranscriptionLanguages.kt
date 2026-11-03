package id.tomz.transcriptor.domain

import java.util.Locale

data class LanguageOption(val code: String, val label: String, val nativeName: String) {
    fun matches(query: String): Boolean = query.trim().let {
        code.equals(it, ignoreCase = true) || label.contains(it, ignoreCase = true) || nativeName.contains(it, ignoreCase = true)
    }
}

fun languageOption(code: String): LanguageOption {
    val locale = Locale.forLanguageTag(if (code == "jw") "jv" else code)
    val label = when (code) {
        "id" -> "Indonesia"
        "en" -> "Inggris"
        "ja" -> "Jepang"
        "jw" -> "Jawa"
        "su" -> "Sunda"
        "auto" -> "Deteksi otomatis"
        else -> locale.getDisplayLanguage(Locale.forLanguageTag("id"))
            .replaceFirstChar { it.titlecase(Locale.forLanguageTag("id")) }
    }
    return LanguageOption(code, label, if (code == "auto") "" else locale.getDisplayLanguage(locale))
}

object TranscriptionLanguages {
    // whisper.cpp IDs 0–98: Tiny, Base and Small have no Cantonese (large-v3) token.
    private val codes = ("en zh de es ru ko fr ja pt tr pl ca nl ar sv it id hi fi vi he uk el ms cs ro da hu ta no th ur hr bg lt la mi ml cy sk te fa lv bn sr az sl kn et mk br eu is hy ne mn bs kk sq sw gl mr pa si km sn yo so af oc ka be tg sd gu am yi lo uz fo ht ps tk nn mt sa lb my bo tl mg as tt haw ln ha ba jw su").split(" ").toSet()
    val all: List<LanguageOption> = codes.map(::languageOption).sortedBy { it.label }
    fun supports(code: String): Boolean = code in codes
    fun search(query: String): List<LanguageOption> = all.filter { it.matches(query) }
    fun forModel(code: String, englishOnly: Boolean, transcriptionLanguage: String? = null): String =
        when {
            englishOnly -> "en"
            transcriptionLanguage != null -> transcriptionLanguage
            else -> code.takeIf { it == "auto" || supports(it) } ?: "auto"
        }
}

object TranslationLanguages {
    // AI capability depends on the selected model. Keep legacy tags for existing projects.
    val codes = TranscriptionLanguages.all.map { it.code }.toSet() + "af ar be bg bn ca cs cy da de el en eo es et fa fi fr ga gl gu he hi hr ht hu id is it ja ka kn ko lt lv mk mr ms mt nl no pl pt ro ru sk sl sq sv sw ta te th tl tr uk ur vi zh".split(" ").toSet()
    val all = codes.map(::languageOption).sortedBy { it.label }
    fun supports(code: String): Boolean = code in codes
    fun validate(source: String, target: String) {
        require(supports(source) && supports(target)) { "Pilih bahasa teks asal dan tujuan yang didukung" }
        require(source != target) { "Bahasa asal dan tujuan harus berbeda" }
    }
}
