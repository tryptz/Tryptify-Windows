// Host test for the side input: the second deck the DJ console mixes beside
// the player's signal. Built and run by CTest with the other host tests.
//
// Checks that a bus set to the side input hears it and not the player, that
// the console's layout (Mix A -> FX A -> master, Mix B -> FX B -> master)
// sums the two decks at unity, that a block with no side input is silent on
// those buses, that delayed input reads the side history rather than the
// player's, and that saves carry the choice (and old saves load without it).

#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "../dsp_engine.h"

namespace {

int failures = 0;

void check(bool ok, const char* what) {
    std::printf("%s  %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok) ++failures;
}

constexpr int kRate = 48000;
constexpr int kBlock = 512;

bool near(float a, float b, float tol = 1e-3f) { return std::fabs(a - b) < tol; }

// Runs [blocks] blocks of constant [a] on the player input and [b] on the
// side (no side input at all when [side] is false); the last left sample.
float runDual(DspEngine& e, float a, float b, bool side = true, int blocks = 40) {
    std::vector<float> l(kBlock), r(kBlock), sl(kBlock), sr(kBlock);
    for (int i = 0; i < blocks; i++) {
        std::fill(l.begin(), l.end(), a);
        std::fill(r.begin(), r.end(), a);
        std::fill(sl.begin(), sl.end(), b);
        std::fill(sr.begin(), sr.end(), b);
        if (side) e.process(l.data(), r.data(), sl.data(), sr.data(), kBlock);
        else e.process(l.data(), r.data(), kBlock);
    }
    return l[kBlock - 1];
}

// The console: bus 1 Mix A (the player) into bus 2 FX A, bus 3 Mix B (the
// side input) into bus 4 FX B, both FX buses to the master.
void djLayout(DspEngine& e) {
    e.setBusInputEnabled(0, true);
    e.setBusInputEnabled(2, true);
    e.setBusInputSource(2, DspEngine::INPUT_SIDE);
    e.setSend(0, MASTER_BUS, 0.0f);
    e.setSend(0, 1, 1.0f);
    e.setSend(2, MASTER_BUS, 0.0f);
    e.setSend(2, 3, 1.0f);
}

}  // namespace

int main() {
    {
        DspEngine e(kRate, kBlock);
        check(e.getBusInputSource(0) == DspEngine::INPUT_PLAYER, "a bus starts on the player's input");
        check(near(runDual(e, 0.5f, 0.25f), 0.5f), "the default mixer ignores the side input");
    }
    {
        DspEngine e(kRate, kBlock);
        e.setBusInputSource(0, DspEngine::INPUT_SIDE);
        check(near(runDual(e, 0.5f, 0.25f), 0.25f), "a bus on the side input hears deck B only");
        check(near(runDual(e, 0.5f, 0.25f, false), 0.0f), "with no side input it is silent");
        e.setBusInputSource(MASTER_BUS, DspEngine::INPUT_SIDE);
        check(e.getBusInputSource(MASTER_BUS) == DspEngine::INPUT_PLAYER, "the master takes no input source");
    }
    {
        DspEngine e(kRate, kBlock);
        djLayout(e);
        check(near(runDual(e, 0.25f, 0.125f), 0.375f), "Mix A + Mix B through their FX buses sum at unity");
        e.setBusMute(2, true);
        check(near(runDual(e, 0.25f, 0.125f), 0.25f), "muting Mix B leaves deck A");
        e.setBusMute(2, false);
        e.setBusMute(0, true);
        check(near(runDual(e, 0.25f, 0.125f), 0.125f), "muting Mix A leaves deck B");
    }
    {
        // A slower bus feeding the master delays the decks' input; deck B's
        // must come from its own history, not deck A's.
        DspEngine e(kRate, kBlock);
        djLayout(e);
        std::vector<float> l(kBlock), r(kBlock), sl(kBlock), sr(kBlock);
        float worst = 0.0f;
        for (int blk = 0; blk < 60; blk++) {
            std::fill(l.begin(), l.end(), 0.0f);
            std::fill(r.begin(), r.end(), 0.0f);
            std::fill(sl.begin(), sl.end(), 0.5f);
            std::fill(sr.begin(), sr.end(), 0.5f);
            e.process(l.data(), r.data(), sl.data(), sr.data(), kBlock);
            if (blk > 10) {
                for (float v : l) worst = std::max(worst, std::fabs(v - 0.5f));
            }
        }
        check(worst < 1e-3f, "deck B alone comes through whole, block after block");
    }
    {
        DspEngine e(kRate, kBlock);
        djLayout(e);
        const std::string saved = e.getStateJson();
        check(saved.find("\"inputSource\":1") != std::string::npos, "a save carries the side input");
        DspEngine f(kRate, kBlock);
        f.loadStateJson(saved);
        check(f.getBusInputSource(2) == DspEngine::INPUT_SIDE && f.getBusInputSource(0) == DspEngine::INPUT_PLAYER,
              "and a load restores it");
        check(near(runDual(f, 0.25f, 0.125f), 0.375f), "the loaded console mixes both decks");

        DspEngine plain(kRate, kBlock);
        check(plain.getStateJson().find("inputSource") == std::string::npos,
              "a mix with no side input saves exactly as before");
        f.loadStateJson(plain.getStateJson());
        check(f.getBusInputSource(2) == DspEngine::INPUT_PLAYER, "an old save puts every bus back on the player");
    }
    {
        // Removing a bus moves the ones after it down, input source included.
        DspEngine e(kRate, kBlock);
        const int b5 = e.addBus();
        const int b6 = e.addBus();
        e.setBusInputEnabled(b6, true);
        e.setBusInputSource(b6, DspEngine::INPUT_SIDE);
        check(e.removeBus(b5), "remove the bus before it");
        check(e.getBusInputSource(b5) == DspEngine::INPUT_SIDE, "the input source moves with its bus");
        check(e.addBus() == b6 && e.getBusInputSource(b6) == DspEngine::INPUT_PLAYER,
              "and the freed slot comes back on the player");
    }

    std::printf(failures ? "\n%d FAILED\n" : "\nall passed\n", failures);
    return failures ? 1 : 0;
}
