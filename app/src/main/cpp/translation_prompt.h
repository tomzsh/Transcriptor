#pragma once
#include <stdexcept>
#include <string>
#include <vector>

namespace transcriptor {
inline bool is_translation_prompt(const std::string & system) {
    return system.rfind("__TRANSLATEGEMMA_V2__\t", 0) == 0;
}
inline std::string translation_prompt(const std::string & system, const std::string & user) {
    if (!is_translation_prompt(system) || system.size() > 8192 || user.empty())
        throw std::invalid_argument("Metadata TranslateGemma tidak valid");
    std::vector<std::string> fields;
    size_t start = std::string("__TRANSLATEGEMMA_V2__\t").size();
    while (start <= system.size()) {
        const auto end = system.find('\t', start);
        fields.push_back(system.substr(start, end == std::string::npos ? end : end - start));
        if (end == std::string::npos) break;
        start = end + 1;
    }
    if (fields.size() != 5) throw std::invalid_argument("Metadata bahasa TranslateGemma tidak valid");
    for (int i = 0; i < 4; ++i) {
        if (fields[i].empty() || fields[i].size() > 128 || fields[i].find_first_of("\r\n<>") != std::string::npos ||
            fields[i].find('\0') != std::string::npos) throw std::invalid_argument("Metadata bahasa tidak valid");
    }
    std::string reference;
    for (size_t i = 0; i < fields[4].size(); ++i) {
        char c = fields[4][i];
        if (c == '\\') {
            if (++i == fields[4].size()) throw std::invalid_argument("Konteks TranslateGemma tidak valid");
            switch (fields[4][i]) {
                case '\\': c = '\\'; break;
                case 'n': c = '\n'; break;
                case 'r': c = '\r'; break;
                case 't': c = '\t'; break;
                default: throw std::invalid_argument("Konteks TranslateGemma tidak valid");
            }
        }
        if (c == '\0') throw std::invalid_argument("Konteks TranslateGemma tidak valid");
        reference += c;
    }
    const auto & source = fields[2]; const auto & target = fields[3];
    const auto context = reference.empty() ? std::string() :
        "Reference context (do not translate or reproduce):\n" + reference + "\n\n";
    return "<start_of_turn>user\n" + context + "You are a professional " + source + " (" + fields[0] + ") to " +
        target + " (" + fields[1] + ") translator. Your goal is to accurately convey the meaning and nuances of the original " +
        source + " text while adhering to " + target + " grammar, vocabulary, and cultural sensitivities.\n" +
        "Produce only the " + target + " translation, without any additional explanations or commentary. Please translate the following " +
        source + " text into " + target + ":\n\n\n" + user + "<end_of_turn>\n<start_of_turn>model\n";
}
}
