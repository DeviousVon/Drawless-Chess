/*
  Direct RulesContractV1 state regressions for the pinned Fairy-Stockfish
  source. This is a verification-only executable; it is not linked into the
  Android library or production UCI binary.
*/

#include <cstdlib>
#include <deque>
#include <iostream>
#include <string>

#include "bitboard.h"
#include "endgame.h"
#include "evaluate.h"
#include "misc.h"
#include "movegen.h"
#include "piece.h"
#include "position.h"
#include "psqt.h"
#include "search.h"
#include "thread.h"
#include "tt.h"
#include "uci.h"
#include "variant.h"
#include "xboard.h"

using namespace Stockfish;

namespace {

[[noreturn]] void fail(const std::string& message) {
  std::cerr << "drawless-native-state-test: " << message << '\n';
  std::exit(1);
}

void expect(bool condition, const std::string& message) {
  if (!condition)
    fail(message);
}

const Variant* drawless_variant() {
  const auto found = variants.find("drawless");
  if (found == variants.end() || found->second == nullptr)
    fail("drawless variant was not loaded");
  return found->second;
}

Move legal_move(const Position& pos, const char* notation) {
  std::string text(notation);
  const Move move = UCI::to_move(pos, text);
  if (move == MOVE_NONE || !MoveList<LEGAL>(pos).contains(move))
    fail(std::string("expected legal move ") + notation);
  return move;
}

void test_null_state() {
  std::deque<StateInfo> states(1);
  Position pos;
  pos.set(drawless_variant(),
          "4k2r/8/8/8/8/8/R7/4K3 w - - 98 1",
          false, &states.back(), Threads.main());

  expect(pos.rule50_count() == 98, "initial halfmove clock was not 98");
  Value result;
  expect(!pos.is_game_end(result), "rule-98 position was unexpectedly terminal");

  StateInfo nullState;
  ASSERT_ALIGNED(&nullState, Eval::NNUE::CacheLineSize);
  pos.do_null_move(nullState);
  expect(pos.rule50_count() == 98,
         "RulesContractV1 null move advanced the halfmove clock");

  StateInfo quietState;
  ASSERT_ALIGNED(&quietState, Eval::NNUE::CacheLineSize);
  const Move quiet = legal_move(pos, "e8f8");
  pos.do_move(quiet, quietState);
  expect(pos.rule50_count() == 99,
         "first real quiet move after null did not reach rule 99");
  expect(!pos.is_game_end(result),
         "null plus one real quiet move manufactured an early fifty terminal");
  pos.undo_move(quiet);
  pos.undo_null_move();
  expect(pos.rule50_count() == 98, "undo_null_move did not restore rule 98");
}

void test_last_capture_across_null() {
  std::deque<StateInfo> states(1);
  Position pos;
  pos.set(drawless_variant(),
          "4k3/8/8/8/8/8/r6R/4K1N1 b - - 0 1",
          false, &states.back(), Threads.main());

  states.emplace_back();
  const Move capture = legal_move(pos, "a2h2");
  pos.do_move(capture, states.back());
  expect(pos.drawless_last_capture_by() == BLACK,
         "real capture was attributed to the wrong mover");

  StateInfo nullOnly;
  ASSERT_ALIGNED(&nullOnly, Eval::NNUE::CacheLineSize);
  pos.do_null_move(nullOnly);
  expect(pos.drawless_last_capture_by() == BLACK,
         "copied capture in a null frame changed the last capturer");
  pos.undo_null_move();

  StateInfo nullThenQuiet;
  ASSERT_ALIGNED(&nullThenQuiet, Eval::NNUE::CacheLineSize);
  pos.do_null_move(nullThenQuiet);
  StateInfo quietState;
  ASSERT_ALIGNED(&quietState, Eval::NNUE::CacheLineSize);
  const Move quiet = legal_move(pos, "e8f8");
  pos.do_move(quiet, quietState);
  expect(pos.drawless_last_capture_by() == BLACK,
         "null plus quiet history toggled the inferred real capturer");
  pos.undo_move(quiet);
  pos.undo_null_move();
  pos.undo_move(capture);
}

void test_legal_en_passant_keying() {
  std::deque<StateInfo> pinnedStates(1), noEpStates(1), legalStates(1), legalNoEpStates(1);
  Position pinned, noEp, legal, legalNoEp;

  pinned.set(drawless_variant(),
             "k3r1n1/8/8/3pP3/8/8/8/4K1N1 w - d6 0 2",
             false, &pinnedStates.back(), Threads.main());
  noEp.set(drawless_variant(),
           "k3r1n1/8/8/3pP3/8/8/8/4K1N1 w - - 0 2",
           false, &noEpStates.back(), Threads.main());
  expect(!pinned.ep_squares(), "pinned, illegal en-passant target survived FEN setup");
  expect(pinned.key() == noEp.key(), "illegal en-passant target changed the repetition key");

  legal.set(drawless_variant(),
            "k5n1/8/8/3pP3/8/8/8/4K1N1 w - d6 0 2",
            false, &legalStates.back(), Threads.main());
  legalNoEp.set(drawless_variant(),
                "k5n1/8/8/3pP3/8/8/8/4K1N1 w - - 0 2",
                false, &legalNoEpStates.back(), Threads.main());
  expect(bool(legal.ep_squares()), "truly legal en-passant target was removed");
  expect(legal.key() != legalNoEp.key(), "legal en-passant target did not distinguish the key");
  expect(type_of(legal_move(legal, "e5d6")) == EN_PASSANT,
         "legal en-passant control was not generated as EN_PASSANT");

  std::deque<StateInfo> pushedStates(1), pushedNoEpStates(1);
  Position pushed, pushedNoEp;
  pushed.set(drawless_variant(),
             "k3r1n1/3p4/8/4P3/8/8/8/4K1N1 b - - 0 1",
             false, &pushedStates.back(), Threads.main());
  pushedStates.emplace_back();
  const Move doublePush = legal_move(pushed, "d7d5");
  pushed.do_move(doublePush, pushedStates.back());
  pushedNoEp.set(drawless_variant(),
                 "k3r1n1/8/8/3pP3/8/8/8/4K1N1 w - - 0 2",
                 false, &pushedNoEpStates.back(), Threads.main());
  expect(!pushed.ep_squares(), "illegal en-passant target survived do_move");
  expect(pushed.key() == pushedNoEp.key(),
         "do_move illegal en-passant target changed the repetition key");
}

void test_probe_node_accounting() {
  std::deque<StateInfo> states(1);
  Position pos;
  pos.set(drawless_variant(),
          "4k2r/8/8/8/8/8/R7/4K3 w - - 0 1",
          false, &states.back(), Threads.main());

  const uint64_t nodesBefore = Threads.main()->nodes.load(std::memory_order_relaxed);
  StateInfo probeState;
  ASSERT_ALIGNED(&probeState, Eval::NNUE::CacheLineSize);
  const Move probe = legal_move(pos, "a2a3");
  pos.do_drawless_probe_move(probe, probeState);
  expect(Threads.main()->nodes.load(std::memory_order_relaxed) == nodesBefore,
         "speculative Drawless move changed the searched-node count");
  pos.undo_move(probe);
}

} // namespace

int main(int argc, char* argv[]) {
  if (argc != 2)
    fail("usage: drawless-native-state-test <variants.ini>");

  pieceMap.init();
  variants.init();
  CommandLine::init(argc, argv);
  UCI::init(Options);
  Options["VariantPath"] = std::string(argv[1]);
  Options["UCI_Variant"] = std::string("drawless");
  Options["Drawless Bare King"] = std::string("continue");
  Options["Drawless Fifty Move"] = std::string("material-victory");
  Options["Drawless Dead Position"] = std::string("material-victory");
  Tune::init();
  PSQT::init(drawless_variant());
  Bitboards::init();
  Position::init();
  Bitbases::init();
  Endgames::init();
  Threads.set(1);
  Search::clear();

  test_null_state();
  test_last_capture_across_null();
  test_legal_en_passant_keying();
  test_probe_node_accounting();

  Threads.set(0);
  variants.clear_all();
  pieceMap.clear_all();
  delete XBoard::stateMachine;
  XBoard::stateMachine = nullptr;

  std::cout << "ok - direct Drawless null/history/en-passant/node-count state regressions\n";
  return 0;
}
