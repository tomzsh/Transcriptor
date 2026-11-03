#include "../../main/cpp/translation_prompt.h"
#include <cassert>
int main() {
    using transcriptor::translation_prompt;
    const std::string meta = "__TRANSLATEGEMMA_V2__\ten\tid\tEnglish\tIndonesian\tPrevious: river\\nFollowing: water\\t<name>\\\\";
    const auto output = translation_prompt(meta, "The bank flooded.");
    assert(output.find("Previous: river\nFollowing: water\t<name>\\") != std::string::npos);
    assert(output.find("text into Indonesian:\n\n\nThe bank flooded.<end_of_turn>") != std::string::npos);
    assert(output.find("<start_of_turn>system") == std::string::npos);
    for (const auto & invalid : {"__TRANSLATEGEMMA_V2__\ten\tid\tEnglish\tIndonesian", "__TRANSLATEGEMMA_V2__\ten\tid\t<bad>\tIndonesian\t", "__TRANSLATEGEMMA_V2__\ten\tid\tEnglish\tIndonesian\tbad\\q"}) {
        bool rejected = false;
        try { translation_prompt(invalid, "hello"); } catch (const std::invalid_argument &) { rejected = true; }
        assert(rejected);
    }
}
