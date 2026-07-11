/* Correctness smoke for the JNI-free DRAWLESS_HOST_BRIDGE_TEST lane. */

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <string>
#include <thread>

extern "C" {
std::uint64_t drawless_host_create(const char*, char*, std::size_t);
int drawless_host_start(std::uint64_t, char*, std::size_t);
std::ptrdiff_t drawless_host_write(std::uint64_t, const char*, std::size_t,
                                   char*, std::size_t);
std::ptrdiff_t drawless_host_read(std::uint64_t, char*, std::size_t,
                                  char*, std::size_t);
std::ptrdiff_t drawless_host_read_error(std::uint64_t, char*, std::size_t,
                                        char*, std::size_t);
int drawless_host_close(std::uint64_t, char*, std::size_t);
}

namespace {

constexpr std::size_t kErrorCapacity = 512;

bool send(std::uint64_t handle, const std::string& commands) {
  char error[kErrorCapacity] = {};
  const std::ptrdiff_t count = drawless_host_write(
      handle, commands.data(), commands.size(), error, sizeof(error));
  if (count != static_cast<std::ptrdiff_t>(commands.size())) {
    std::cerr << "write failed: " << error << '\n';
    return false;
  }
  return true;
}

bool read_until(std::uint64_t handle,
                std::string* output,
                const std::string& required) {
  char buffer[4096];
  char error[kErrorCapacity] = {};
  while (output->find(required) == std::string::npos) {
    const std::ptrdiff_t count = drawless_host_read(
        handle, buffer, sizeof(buffer), error, sizeof(error));
    if (count <= 0) {
      std::cerr << "stdout ended before '" << required << "': " << error << '\n';
      return false;
    }
    output->append(buffer, static_cast<std::size_t>(count));
  }
  return true;
}

bool verify_session(const char* variants, bool exercise_search) {
  char error[kErrorCapacity] = {};
  const std::uint64_t handle = drawless_host_create(variants, error, sizeof(error));
  if (handle == 0) {
    std::cerr << "create failed: " << error << '\n';
    return false;
  }
  if (drawless_host_create(variants, error, sizeof(error)) != 0 ||
      std::strstr(error, "Only one") == nullptr) {
    std::cerr << "singleton gate was not enforced\n";
    return false;
  }
  if (drawless_host_start(handle, error, sizeof(error)) != 0) {
    std::cerr << "start failed: " << error << '\n';
    return false;
  }

  std::string diagnostics;
  std::thread stderr_reader([&] {
    char buffer[1024];
    char local_error[kErrorCapacity] = {};
    while (true) {
      const std::ptrdiff_t count = drawless_host_read_error(
          handle, buffer, sizeof(buffer), local_error, sizeof(local_error));
      if (count < 0)
        break;
      diagnostics.append(buffer, static_cast<std::size_t>(count));
    }
  });

  std::string commands = "uci\nisready\n";
  if (exercise_search) {
    commands +=
        "setoption name UCI_Variant value drawless\n"
        "setoption name Threads value 1\n"
        "setoption name Hash value 1\n"
        "isready\n"
        "position fen 6k1/7p/5Q2/8/8/8/8/6K1 w - - 0 1 "
        "moves f6f7 g8h8 f7f6 h8g8 f6f7 g8h8 f7f6\n"
        "go depth 4\n";
  }

  std::string output;
  bool passed = send(handle, commands) &&
      read_until(handle, &output, exercise_search ? "bestmove h8g8" : "readyok") &&
      output.find("var drawless") != std::string::npos &&
      output.find("var escape") != std::string::npos &&
      output.find("Drawless Patch Version") != std::string::npos;
  if (exercise_search)
    passed = passed && output.find("score mate 1") != std::string::npos;

  std::atomic<bool> stdout_eof{false};
  std::thread stdout_reader([&] {
    char buffer[128];
    char local_error[kErrorCapacity] = {};
    while (drawless_host_read(handle, buffer, sizeof(buffer),
                              local_error, sizeof(local_error)) > 0) {}
    stdout_eof = true;
  });

  if (drawless_host_close(handle, error, sizeof(error)) != 0) {
    std::cerr << "close failed: " << error << '\n';
    passed = false;
  }
  stdout_reader.join();
  stderr_reader.join();
  passed = passed && stdout_eof && diagnostics.empty();

  // The handle remains registered until a later create replaces it, so close
  // must remain idempotent in this state.
  if (drawless_host_close(handle, error, sizeof(error)) != 0)
    passed = false;

  if (!passed) {
    std::cerr << "unexpected stdout:\n" << output
              << "\nunexpected stderr:\n" << diagnostics << '\n';
  }
  return passed;
}

}  // namespace

int main(int argc, char** argv) {
  if (argc != 2) {
    std::cerr << "usage: native_bridge_smoke VARIANTS\n";
    return 2;
  }
  if (!verify_session(argv[1], true) || !verify_session(argv[1], false))
    return 1;
  std::cout << "PASSED host native bridge lifecycle, rules, search, restart, and close gates\n";
  return 0;
}
