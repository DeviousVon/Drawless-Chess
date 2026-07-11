/*
  Drawless Chess JNI transport for Fairy-Stockfish.

  Fairy-Stockfish is licensed under GPL-3.0-or-later. This file is part of the
  combined native work and is distributed under the same terms.
*/

#if !defined(DRAWLESS_HOST_BRIDGE_TEST)
#include <jni.h>
#endif

#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <iostream>
#include <memory>
#include <mutex>
#include <streambuf>
#include <string>
#include <thread>
#include <vector>

#include "bitboard.h"
#include "endgame.h"
#include "evaluate.h"
#include "misc.h"
#include "piece.h"
#include "position.h"
#include "psqt.h"
#include "search.h"
#include "syzygy/tbprobe.h"
#include "thread.h"
#include "tt.h"
#include "uci.h"
#include "variant.h"
#include "xboard.h"

namespace {

using namespace Stockfish;

constexpr std::size_t kInputCapacity = 256U * 1024U;
constexpr std::size_t kOutputCapacity = 1024U * 1024U;
constexpr std::size_t kMaximumVariantConfigBytes = 256U * 1024U;
#if !defined(DRAWLESS_HOST_BRIDGE_TEST)
constexpr char kBindingClass[] = "com/drawlesschess/engine/FairyNativeBindings";
#endif

class BytePipe final {
 public:
  explicit BytePipe(std::size_t capacity) : storage_(capacity) {}

  BytePipe(const BytePipe&) = delete;
  BytePipe& operator=(const BytePipe&) = delete;

  std::size_t write(const char* source, std::size_t length) {
    std::size_t written = 0;
    std::unique_lock<std::mutex> lock(mutex_);
    while (written < length) {
      writable_.wait(lock, [this] { return size_ < storage_.size() || write_closed_; });
      if (write_closed_)
        break;

      const std::size_t tail = (head_ + size_) % storage_.size();
      const std::size_t contiguous = std::min(storage_.size() - tail, storage_.size() - size_);
      const std::size_t count = std::min(contiguous, length - written);
      std::memcpy(storage_.data() + tail, source + written, count);
      size_ += count;
      written += count;
      readable_.notify_one();
    }
    return written;
  }

  // Returns -1 only after the writer is closed and all buffered bytes are read.
  std::ptrdiff_t read(char* destination, std::size_t length) {
    if (length == 0)
      return 0;

    std::unique_lock<std::mutex> lock(mutex_);
    readable_.wait(lock, [this] { return size_ != 0 || write_closed_; });
    if (size_ == 0)
      return -1;

    const std::size_t contiguous = std::min(storage_.size() - head_, size_);
    const std::size_t count = std::min(contiguous, length);
    std::memcpy(destination, storage_.data() + head_, count);
    head_ = (head_ + count) % storage_.size();
    size_ -= count;
    writable_.notify_all();
    return static_cast<std::ptrdiff_t>(count);
  }

  // Stops future writes without discarding bytes already produced.
  void close_writer() {
    std::lock_guard<std::mutex> lock(mutex_);
    write_closed_ = true;
    readable_.notify_all();
    writable_.notify_all();
  }

  // Interrupts application writes and replaces unread input with an unconditional
  // line boundary followed by the two commands needed for deterministic teardown.
  void request_engine_shutdown() {
    static constexpr char shutdown[] = "\nstop\nquit\n";
    std::lock_guard<std::mutex> lock(mutex_);
    head_ = 0;
    size_ = sizeof(shutdown) - 1;
    std::memcpy(storage_.data(), shutdown, size_);
    write_closed_ = true;
    readable_.notify_all();
    writable_.notify_all();
  }

 private:
  std::vector<char> storage_;
  std::size_t head_ = 0;
  std::size_t size_ = 0;
  bool write_closed_ = false;
  std::mutex mutex_;
  std::condition_variable readable_;
  std::condition_variable writable_;
};

class PipeInputBuffer final : public std::streambuf {
 public:
  explicit PipeInputBuffer(BytePipe& pipe) : pipe_(pipe) {
    setg(&current_, &current_, &current_);
  }

 protected:
  int_type underflow() override {
    if (gptr() < egptr())
      return traits_type::to_int_type(*gptr());

    if (pipe_.read(&current_, 1) != 1)
      return traits_type::eof();

    setg(&current_, &current_, &current_ + 1);
    return traits_type::to_int_type(current_);
  }

 private:
  BytePipe& pipe_;
  char current_ = 0;
};

class PipeOutputBuffer final : public std::streambuf {
 public:
  explicit PipeOutputBuffer(BytePipe& pipe) : pipe_(pipe) {}

 protected:
  std::streamsize xsputn(const char* source, std::streamsize length) override {
    if (length <= 0)
      return 0;
    return static_cast<std::streamsize>(
        pipe_.write(source, static_cast<std::size_t>(length)));
  }

  int_type overflow(int_type value) override {
    if (traits_type::eq_int_type(value, traits_type::eof()))
      return traits_type::not_eof(value);
    const char byte = traits_type::to_char_type(value);
    return pipe_.write(&byte, 1) == 1 ? value : traits_type::eof();
  }

  int sync() override { return 0; }

 private:
  BytePipe& pipe_;
};

enum class SessionState {
  kCreated,
  kStarting,
  kRunning,
  kClosing,
  kExited,
  kFailed,
  kClosed,
};

std::mutex g_engine_lifecycle_mutex;
bool g_engine_initialized = false;
bool g_engine_poisoned = false;
std::string g_engine_failure;
std::string g_variant_config_contents;

bool read_file(const std::string& path, std::string* contents, std::string* error) {
  if (path.empty()) {
    *error = "Variant configuration path must not be blank";
    return false;
  }
  if (path.front() != '/') {
    *error = "Variant configuration path must be absolute";
    return false;
  }

  std::ifstream file(path, std::ios::binary | std::ios::ate);
  if (!file.is_open()) {
    *error = "Variant configuration file is not readable";
    return false;
  }
  const std::streamoff length = file.tellg();
  if (length <= 0 || length > static_cast<std::streamoff>(kMaximumVariantConfigBytes)) {
    *error = "Variant configuration file has an invalid size";
    return false;
  }
  contents->resize(static_cast<std::size_t>(length));
  file.seekg(0, std::ios::beg);
  file.read(contents->data(), length);
  if (!file) {
    *error = "Variant configuration file could not be read completely";
    return false;
  }

  // This is defense in depth; Kotlin verifies the pinned SHA-256 before create.
  if (contents->find("[drawless:chess]") == std::string::npos ||
      contents->find("[escape:drawless]") == std::string::npos ||
      contents->find("drawlessForcedRepetition = true") == std::string::npos) {
    *error = "Variant configuration does not declare the required Drawless rulesets";
    return false;
  }
  return true;
}

bool verify_loaded_variants(std::string* error) {
  const auto drawless = variants.find("drawless");
  const auto escape = variants.find("escape");
  if (drawless == variants.end() || drawless->second == nullptr ||
      escape == variants.end() || escape->second == nullptr) {
    *error = "Fairy-Stockfish did not load both drawless and escape variants";
    return false;
  }

  const Variant* drawless_rules = drawless->second;
  const Variant* escape_rules = escape->second;
  if (!drawless_rules->drawlessForcedRepetition ||
      !escape_rules->drawlessForcedRepetition ||
      drawless_rules->nFoldRule != 3 || escape_rules->nFoldRule != 3 ||
      drawless_rules->nFoldValue != VALUE_MATE ||
      escape_rules->nFoldValue != VALUE_MATE ||
      drawless_rules->nFoldValueAbsolute || escape_rules->nFoldValueAbsolute ||
      drawless_rules->nMoveRule != 0 || escape_rules->nMoveRule != 0 ||
      drawless_rules->stalemateValue != -VALUE_MATE ||
      escape_rules->stalemateValue != VALUE_MATE) {
    *error = "Loaded Drawless variants do not match the native rules contract";
    return false;
  }
  return true;
}

void reset_reusable_options() {
  // UCI::Option preserves its insertion index, so reconstructing Options would
  // break option enumeration on a second in-process session. Reset values in
  // place and retain the already verified custom variant combo instead.
  const auto set = [](const char* name, const char* value) {
    Options[name] = std::string(value);
  };
  set("Threads", "1");
  set("Hash", "16");
  set("Ponder", "false");
  set("MultiPV", "1");
  set("Skill Level", "20");
  set("Move Overhead", "10");
  set("Slow Mover", "100");
  set("nodestime", "0");
  set("UCI_Chess960", "false");
  set("UCI_AnalyseMode", "false");
  set("UCI_LimitStrength", "false");
  set("UCI_Elo", "1350");
  set("UCI_ShowWDL", "false");
  set("SyzygyPath", "<empty>");
  set("SyzygyProbeDepth", "1");
  set("Syzygy50MoveRule", "true");
  set("SyzygyProbeLimit", "7");
  set("Use NNUE", "true");
  set("TsumeMode", "false");
  set("usemillisec", "true");
  Options["UCI_Variant"].set_default("chess");
  CurrentProtocol = UCI_GENERAL;
  Search::clear();
  Eval::NNUE::init();
}

bool initialize_engine(const std::string& config_path,
                       const std::string& expected_contents,
                       std::string* error) {
  if (g_engine_poisoned) {
    *error = g_engine_failure;
    return false;
  }

  std::string current_contents;
  if (!read_file(config_path, &current_contents, error) ||
      current_contents != expected_contents) {
    if (error->empty())
      *error = "Variant configuration changed between create and start";
    return false;
  }

  if (g_engine_initialized) {
    if (current_contents != g_variant_config_contents) {
      *error = "A different variant configuration cannot replace the initialized rules";
      return false;
    }
    reset_reusable_options();
    return verify_loaded_variants(error);
  }

  pieceMap.init();
  variants.init();

  char program_name[] = "libdrawless_fairy.so";
  char* command_line[] = {program_name, nullptr};
  CommandLine::init(1, command_line);
  UCI::init(Options);

  // Assigning VariantPath invokes Fairy's parser and refreshes the UCI_Variant
  // combo before UCI::loop can receive its first handshake command.
  Options["VariantPath"] = config_path;
  if (!verify_loaded_variants(error)) {
    g_engine_poisoned = true;
    g_engine_failure = *error;
    return false;
  }

  Tune::init();
  PSQT::init(variants.find(Options["UCI_Variant"])->second);
  Bitboards::init();
  Position::init();
  Bitbases::init();
  Endgames::init();
  Threads.set(static_cast<std::size_t>(Options["Threads"]));
  Search::clear();
  Eval::NNUE::init();

  g_variant_config_contents = current_contents;
  g_engine_initialized = true;
  return true;
}

class EngineSession final {
 public:
  EngineSession(std::uint64_t handle, std::string path, std::string config_contents)
      : handle_(handle),
        config_path_(std::move(path)),
        config_contents_(std::move(config_contents)),
        input_(kInputCapacity),
        output_(kOutputCapacity),
        error_output_(kOutputCapacity),
        input_buffer_(input_),
        output_buffer_(output_),
        error_buffer_(error_output_) {}

  ~EngineSession() { close(); }

  std::uint64_t handle() const { return handle_; }

  bool terminal() const {
    std::lock_guard<std::mutex> lock(state_mutex_);
    return state_ == SessionState::kExited || state_ == SessionState::kFailed ||
           state_ == SessionState::kClosed;
  }

  bool start(std::string* error) {
    {
      std::lock_guard<std::mutex> lock(state_mutex_);
      if (state_ != SessionState::kCreated) {
        *error = "Native engine session can only be started once";
        return false;
      }
      state_ = SessionState::kStarting;
    }

    worker_ = std::thread(&EngineSession::run, this);

    std::unique_lock<std::mutex> lock(state_mutex_);
    initialized_.wait(lock, [this] { return initialization_complete_; });
    if (!initialization_successful_) {
      *error = failure_.empty() ? "Native engine initialization failed" : failure_;
      return false;
    }
    if (state_ == SessionState::kClosing || state_ == SessionState::kClosed) {
      *error = "Native engine was closed during startup";
      return false;
    }
    return true;
  }

  bool write(const char* bytes, std::size_t length) {
    {
      std::lock_guard<std::mutex> lock(state_mutex_);
      if (state_ != SessionState::kRunning)
        return false;
    }
    return input_.write(bytes, length) == length;
  }

  std::ptrdiff_t read(char* bytes, std::size_t length) {
    return output_.read(bytes, length);
  }

  std::ptrdiff_t read_error(char* bytes, std::size_t length) {
    return error_output_.read(bytes, length);
  }

  void close() {
    std::lock_guard<std::mutex> close_lock(close_mutex_);
    bool needs_shutdown = false;
    {
      std::lock_guard<std::mutex> lock(state_mutex_);
      switch (state_) {
        case SessionState::kCreated:
          state_ = SessionState::kClosed;
          initialization_complete_ = true;
          initialized_.notify_all();
          input_.close_writer();
          output_.close_writer();
          error_output_.close_writer();
          return;
        case SessionState::kStarting:
        case SessionState::kRunning:
          state_ = SessionState::kClosing;
          needs_shutdown = true;
          break;
        case SessionState::kClosing:
          needs_shutdown = true;
          break;
        case SessionState::kExited:
        case SessionState::kFailed:
        case SessionState::kClosed:
          break;
      }
    }

    if (needs_shutdown) {
      Threads.stop = true;
      input_.request_engine_shutdown();
    } else {
      input_.close_writer();
    }
    // A full output pipe must never keep an engine/search thread alive while
    // close waits. Buffered bytes remain readable until each pipe reaches EOF.
    output_.close_writer();
    error_output_.close_writer();

    if (worker_.joinable())
      worker_.join();

    {
      std::lock_guard<std::mutex> lock(state_mutex_);
      state_ = SessionState::kClosed;
      initialization_complete_ = true;
      initialized_.notify_all();
    }
  }

 private:
  void run() {
    // Fairy uses process-global engine state and standard streams. The registry
    // prevents concurrent sessions; this mutex also guards sequential teardown.
    std::lock_guard<std::mutex> lifecycle_lock(g_engine_lifecycle_mutex);

    const std::ios::iostate old_input_state = std::cin.rdstate();
    const std::ios::iostate old_output_state = std::cout.rdstate();
    const std::ios::iostate old_error_state = std::cerr.rdstate();
    std::streambuf* old_input = std::cin.rdbuf(&input_buffer_);
    std::streambuf* old_output = std::cout.rdbuf(&output_buffer_);
    std::streambuf* old_error = std::cerr.rdbuf(&error_buffer_);
    std::cin.clear();
    std::cout.clear();
    std::cerr.clear();

    std::cout << engine_info() << std::endl;

    std::string initialization_error;
    const bool ready = initialize_engine(config_path_, config_contents_, &initialization_error);
    {
      std::lock_guard<std::mutex> lock(state_mutex_);
      initialization_complete_ = true;
      initialization_successful_ = ready;
      if (!ready) {
        failure_ = initialization_error;
        state_ = SessionState::kFailed;
      } else if (state_ == SessionState::kStarting) {
        state_ = SessionState::kRunning;
      }
      initialized_.notify_all();
    }

    if (ready) {
      // "noautoload" prevents UCI::loop from replacing the already verified
      // VariantPath with an ambient process environment variable.
      char program_name[] = "libdrawless_fairy.so";
      char no_autoload[] = "noautoload";
      char* arguments[] = {program_name, no_autoload, nullptr};
      UCI::loop(2, arguments);

      Threads.stop = true;
      Threads.set(0);
      delete XBoard::stateMachine;
      XBoard::stateMachine = nullptr;
    }

    std::cout.flush();
    std::cerr.flush();
    std::cin.rdbuf(old_input);
    std::cout.rdbuf(old_output);
    std::cerr.rdbuf(old_error);
    std::cin.clear(old_input_state);
    std::cout.clear(old_output_state);
    std::cerr.clear(old_error_state);
    input_.close_writer();
    output_.close_writer();
    error_output_.close_writer();

    {
      std::lock_guard<std::mutex> lock(state_mutex_);
      if (state_ != SessionState::kClosing && state_ != SessionState::kFailed)
        state_ = SessionState::kExited;
    }
  }

  const std::uint64_t handle_;
  const std::string config_path_;
  const std::string config_contents_;
  BytePipe input_;
  BytePipe output_;
  BytePipe error_output_;
  PipeInputBuffer input_buffer_;
  PipeOutputBuffer output_buffer_;
  PipeOutputBuffer error_buffer_;

  mutable std::mutex state_mutex_;
  std::mutex close_mutex_;
  std::condition_variable initialized_;
  SessionState state_ = SessionState::kCreated;
  bool initialization_complete_ = false;
  bool initialization_successful_ = false;
  std::string failure_;
  std::thread worker_;
};

std::mutex g_registry_mutex;
std::mutex g_create_mutex;
std::shared_ptr<EngineSession> g_session;
std::atomic<std::uint64_t> g_next_handle{1};

std::shared_ptr<EngineSession> find_session(std::uint64_t handle, std::string* error) {
  if (handle == 0) {
    *error = "Native engine handle is invalid";
    return {};
  }
  std::lock_guard<std::mutex> lock(g_registry_mutex);
  if (!g_session || g_session->handle() != handle) {
    *error = "Native engine handle is stale or unknown";
    return {};
  }
  return g_session;
}

std::uint64_t create_session(const std::string& path, std::string* error) {
  std::string contents;
  if (!read_file(path, &contents, error))
    return 0;

  std::lock_guard<std::mutex> create_lock(g_create_mutex);
  std::shared_ptr<EngineSession> previous;
  {
    std::lock_guard<std::mutex> lock(g_registry_mutex);
    if (g_session && !g_session->terminal()) {
      *error = "Only one native Fairy-Stockfish session may exist at a time";
      return 0;
    }
    previous = std::move(g_session);
  }
  if (previous)
    previous->close();

  std::uint64_t handle = g_next_handle.fetch_add(1, std::memory_order_relaxed);
  if (handle == 0)
    handle = g_next_handle.fetch_add(1, std::memory_order_relaxed);
  auto session = std::make_shared<EngineSession>(handle, path, std::move(contents));
  {
    std::lock_guard<std::mutex> lock(g_registry_mutex);
    g_session = std::move(session);
  }
  return handle;
}

bool start_session(std::uint64_t handle, std::string* error) {
  const auto session = find_session(handle, error);
  if (!session)
    return false;
  if (!session->start(error)) {
    if (session->terminal())
      session->close();
    return false;
  }
  return true;
}

std::ptrdiff_t write_session(std::uint64_t handle,
                             const char* bytes,
                             std::size_t length,
                             std::string* error) {
  const auto session = find_session(handle, error);
  if (!session)
    return -1;
  if (length == 0)
    return 0;
  if (bytes == nullptr) {
    *error = "Native engine input must not be null";
    return -1;
  }
  if (!session->write(bytes, length)) {
    *error = "Native engine input is closed";
    return -1;
  }
  return static_cast<std::ptrdiff_t>(length);
}

std::ptrdiff_t read_session(std::uint64_t handle,
                            char* bytes,
                            std::size_t length,
                            bool standard_error,
                            std::string* error) {
  const auto session = find_session(handle, error);
  if (!session)
    return -1;
  if (length == 0)
    return 0;
  if (bytes == nullptr) {
    *error = "Native engine output buffer must not be null";
    return -1;
  }
  return standard_error ? session->read_error(bytes, length) : session->read(bytes, length);
}

bool close_session(std::uint64_t handle, std::string* error) {
  const auto session = find_session(handle, error);
  if (!session)
    return false;
  session->close();
  return true;
}

#if !defined(DRAWLESS_HOST_BRIDGE_TEST)
void throw_java(JNIEnv* environment, const char* class_name, const std::string& message) {
  if (environment->ExceptionCheck())
    return;
  jclass exception_class = environment->FindClass(class_name);
  if (exception_class == nullptr)
    return;
  environment->ThrowNew(exception_class, message.c_str());
  environment->DeleteLocalRef(exception_class);
}

void throw_illegal_argument(JNIEnv* environment, const std::string& message) {
  throw_java(environment, "java/lang/IllegalArgumentException", message);
}

void throw_illegal_state(JNIEnv* environment, const std::string& message) {
  throw_java(environment, "java/lang/IllegalStateException", message);
}

void throw_bounds(JNIEnv* environment, const std::string& message) {
  throw_java(environment, "java/lang/IndexOutOfBoundsException", message);
}

bool validate_slice(JNIEnv* environment,
                    jbyteArray array,
                    jint offset,
                    jint length) {
  if (array == nullptr) {
    throw_illegal_argument(environment, "Byte array must not be null");
    return false;
  }
  const jsize array_length = environment->GetArrayLength(array);
  if (offset < 0 || length < 0 || offset > array_length || length > array_length - offset) {
    throw_bounds(environment, "Byte array offset and length are out of bounds");
    return false;
  }
  return true;
}

jlong native_create(JNIEnv* environment, jclass, jstring variant_config_path) {
  if (variant_config_path == nullptr) {
    throw_illegal_argument(environment, "Variant configuration path must not be null");
    return 0;
  }
  const char* utf_path = environment->GetStringUTFChars(variant_config_path, nullptr);
  if (utf_path == nullptr)
    return 0;  // The JVM has already raised OutOfMemoryError.
  const std::string path(utf_path);
  environment->ReleaseStringUTFChars(variant_config_path, utf_path);

  std::string error;
  const std::uint64_t handle = create_session(path, &error);
  if (handle == 0) {
    if (error == "Only one native Fairy-Stockfish session may exist at a time")
      throw_illegal_state(environment, error);
    else
      throw_illegal_argument(environment, error);
  }
  return static_cast<jlong>(handle);
}

void native_start(JNIEnv* environment, jclass, jlong handle) {
  std::string error;
  if (handle <= 0)
    error = "Native engine handle is invalid";
  if (handle <= 0 || !start_session(static_cast<std::uint64_t>(handle), &error))
    throw_illegal_state(environment, error);
}

jint native_write(JNIEnv* environment,
                  jclass,
                  jlong handle,
                  jbyteArray bytes,
                  jint offset,
                  jint length) {
  if (!validate_slice(environment, bytes, offset, length))
    return -1;

  std::vector<char> copy(static_cast<std::size_t>(length));
  if (length > 0) {
    environment->GetByteArrayRegion(bytes, offset, length,
                                    reinterpret_cast<jbyte*>(copy.data()));
    if (environment->ExceptionCheck())
      return -1;
  }
  std::string error;
  const std::ptrdiff_t count = handle > 0
      ? write_session(static_cast<std::uint64_t>(handle), copy.data(), copy.size(), &error)
      : -1;
  if (count < 0) {
    if (error.empty())
      error = "Native engine handle is invalid";
    throw_illegal_state(environment, error);
    return -1;
  }
  return static_cast<jint>(count);
}

jint read_pipe(JNIEnv* environment,
               jlong handle,
               jbyteArray bytes,
               jint offset,
               jint length,
               bool standard_error) {
  if (!validate_slice(environment, bytes, offset, length))
    return -1;

  std::vector<char> copy(static_cast<std::size_t>(length));
  std::string error;
  if (handle <= 0)
    error = "Native engine handle is invalid";
  const std::ptrdiff_t count = handle > 0
      ? read_session(static_cast<std::uint64_t>(handle), copy.data(), copy.size(),
                     standard_error, &error)
      : -1;
  if (!error.empty()) {
    throw_illegal_state(environment, error);
    return -1;
  }
  if (count < 0)
    return -1;
  if (count == 0)
    return 0;

  environment->SetByteArrayRegion(bytes, offset, static_cast<jsize>(count),
                                  reinterpret_cast<const jbyte*>(copy.data()));
  if (environment->ExceptionCheck())
    return -1;
  return static_cast<jint>(count);
}

jint native_read(JNIEnv* environment,
                 jclass,
                 jlong handle,
                 jbyteArray bytes,
                 jint offset,
                 jint length) {
  return read_pipe(environment, handle, bytes, offset, length, false);
}

jint native_read_error(JNIEnv* environment,
                       jclass,
                       jlong handle,
                       jbyteArray bytes,
                       jint offset,
                       jint length) {
  return read_pipe(environment, handle, bytes, offset, length, true);
}

void native_close(JNIEnv* environment, jclass, jlong handle) {
  std::string error;
  if (handle <= 0 || !close_session(static_cast<std::uint64_t>(handle), &error)) {
    if (error.empty())
      error = "Native engine handle is invalid";
    throw_illegal_state(environment, error);
  }
}

const JNINativeMethod kMethods[] = {
    {const_cast<char*>("nativeCreate"), const_cast<char*>("(Ljava/lang/String;)J"),
     reinterpret_cast<void*>(native_create)},
    {const_cast<char*>("nativeStart"), const_cast<char*>("(J)V"),
     reinterpret_cast<void*>(native_start)},
    {const_cast<char*>("nativeWrite"), const_cast<char*>("(J[BII)I"),
     reinterpret_cast<void*>(native_write)},
    {const_cast<char*>("nativeRead"), const_cast<char*>("(J[BII)I"),
     reinterpret_cast<void*>(native_read)},
    {const_cast<char*>("nativeReadError"), const_cast<char*>("(J[BII)I"),
     reinterpret_cast<void*>(native_read_error)},
    {const_cast<char*>("nativeClose"), const_cast<char*>("(J)V"),
     reinterpret_cast<void*>(native_close)},
};
#endif

}  // namespace

#if !defined(DRAWLESS_HOST_BRIDGE_TEST)
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* virtual_machine, void*) {
  JNIEnv* environment = nullptr;
  if (virtual_machine->GetEnv(reinterpret_cast<void**>(&environment), JNI_VERSION_1_6) != JNI_OK)
    return JNI_ERR;

  jclass binding_class = environment->FindClass(kBindingClass);
  if (binding_class == nullptr)
    return JNI_ERR;
  const jint result = environment->RegisterNatives(
      binding_class, kMethods, static_cast<jint>(sizeof(kMethods) / sizeof(kMethods[0])));
  environment->DeleteLocalRef(binding_class);
  return result == JNI_OK ? JNI_VERSION_1_6 : JNI_ERR;
}
#else
#if defined(__GNUC__)
#define DRAWLESS_HOST_EXPORT __attribute__((visibility("default")))
#else
#define DRAWLESS_HOST_EXPORT
#endif

namespace {
void copy_host_error(const std::string& message, char* destination, std::size_t capacity) {
  if (destination == nullptr || capacity == 0)
    return;
  const std::size_t count = std::min(message.size(), capacity - 1);
  std::memcpy(destination, message.data(), count);
  destination[count] = '\0';
}
}  // namespace

extern "C" {

DRAWLESS_HOST_EXPORT std::uint64_t drawless_host_create(
    const char* variant_config_path, char* error, std::size_t error_capacity) {
  std::string message;
  const std::uint64_t handle = variant_config_path == nullptr
      ? 0
      : create_session(variant_config_path, &message);
  if (variant_config_path == nullptr)
    message = "Variant configuration path must not be null";
  copy_host_error(message, error, error_capacity);
  return handle;
}

DRAWLESS_HOST_EXPORT int drawless_host_start(
    std::uint64_t handle, char* error, std::size_t error_capacity) {
  std::string message;
  const bool success = start_session(handle, &message);
  copy_host_error(message, error, error_capacity);
  return success ? 0 : -1;
}

DRAWLESS_HOST_EXPORT std::ptrdiff_t drawless_host_write(
    std::uint64_t handle, const char* bytes, std::size_t length,
    char* error, std::size_t error_capacity) {
  std::string message;
  const std::ptrdiff_t result = write_session(handle, bytes, length, &message);
  copy_host_error(message, error, error_capacity);
  return result;
}

DRAWLESS_HOST_EXPORT std::ptrdiff_t drawless_host_read(
    std::uint64_t handle, char* bytes, std::size_t length,
    char* error, std::size_t error_capacity) {
  std::string message;
  const std::ptrdiff_t result = read_session(handle, bytes, length, false, &message);
  copy_host_error(message, error, error_capacity);
  return result;
}

DRAWLESS_HOST_EXPORT std::ptrdiff_t drawless_host_read_error(
    std::uint64_t handle, char* bytes, std::size_t length,
    char* error, std::size_t error_capacity) {
  std::string message;
  const std::ptrdiff_t result = read_session(handle, bytes, length, true, &message);
  copy_host_error(message, error, error_capacity);
  return result;
}

DRAWLESS_HOST_EXPORT int drawless_host_close(
    std::uint64_t handle, char* error, std::size_t error_capacity) {
  std::string message;
  const bool success = close_session(handle, &message);
  copy_host_error(message, error, error_capacity);
  return success ? 0 : -1;
}

}  // extern "C"
#endif
