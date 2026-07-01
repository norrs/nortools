#include <windows.h>

#include <shellapi.h>

#include <ctime>
#include <string>
#include <vector>

namespace {

std::wstring QuoteArg(const std::wstring& arg) {
  if (arg.empty()) {
    return L"\"\"";
  }
  if (arg.find_first_of(L" \t\"") == std::wstring::npos) {
    return arg;
  }

  std::wstring out = L"\"";
  unsigned int backslashes = 0;
  for (wchar_t ch : arg) {
    if (ch == L'\\') {
      ++backslashes;
      continue;
    }
    if (ch == L'"') {
      out.append(backslashes * 2 + 1, L'\\');
      out += ch;
      backslashes = 0;
      continue;
    }
    out.append(backslashes, L'\\');
    backslashes = 0;
    out += ch;
  }
  out.append(backslashes * 2, L'\\');
  out += L"\"";
  return out;
}

std::wstring GetArgValue(const std::vector<std::wstring>& args,
                         const std::wstring& name) {
  for (size_t i = 0; i + 1 < args.size(); ++i) {
    if (args[i] == name) {
      return args[i + 1];
    }
  }
  return L"";
}

DWORD RunAndWait(const std::wstring& application,
                 const std::wstring& command,
                 const std::wstring& working_dir) {
  STARTUPINFOW si{};
  si.cb = sizeof(si);
  PROCESS_INFORMATION pi{};
  std::wstring mutable_command = command;

  BOOL ok = CreateProcessW(
      application.empty() ? nullptr : application.c_str(),
      mutable_command.data(),
      nullptr,
      nullptr,
      FALSE,
      CREATE_NO_WINDOW,
      nullptr,
      working_dir.empty() ? nullptr : working_dir.c_str(),
      &si,
      &pi);
  if (!ok) {
    return GetLastError();
  }

  WaitForSingleObject(pi.hProcess, INFINITE);
  DWORD exit_code = 1;
  if (!GetExitCodeProcess(pi.hProcess, &exit_code)) {
    exit_code = GetLastError();
  }
  CloseHandle(pi.hThread);
  CloseHandle(pi.hProcess);
  return exit_code;
}

std::wstring JoinPath(const std::wstring& left, const std::wstring& right) {
  if (left.empty()) {
    return right;
  }
  if (left.back() == L'\\' || left.back() == L'/') {
    return left + right;
  }
  return left + L"\\" + right;
}

std::wstring GetLocalAppData() {
  wchar_t buffer[MAX_PATH]{};
  DWORD size = GetEnvironmentVariableW(L"LOCALAPPDATA", buffer, MAX_PATH);
  if (size == 0 || size >= MAX_PATH) {
    return L"";
  }
  return std::wstring(buffer, size);
}

void EnsureDirectory(const std::wstring& path) {
  if (path.empty()) {
    return;
  }
  CreateDirectoryW(path.c_str(), nullptr);
}

std::wstring MakeTimestamp() {
  std::time_t now = std::time(nullptr);
  std::tm tm{};
  localtime_s(&tm, &now);
  wchar_t buffer[32]{};
  wcsftime(buffer, 32, L"%Y%m%d-%H%M%S", &tm);
  return buffer;
}

struct LogPaths {
  std::wstring updater_log;
  std::wstring msi_log;
};

LogPaths GetLogPaths() {
  std::wstring local_app_data = GetLocalAppData();
  if (local_app_data.empty()) {
    return {};
  }
  std::wstring app_dir = JoinPath(local_app_data, L"NorTools");
  std::wstring log_dir = JoinPath(app_dir, L"logs");
  EnsureDirectory(app_dir);
  EnsureDirectory(log_dir);
  std::wstring timestamp = MakeTimestamp();
  return {
      JoinPath(log_dir, L"nortools-updater.log"),
      JoinPath(log_dir, L"nortools-msi-update-" + timestamp + L".log"),
  };
}

void AppendLogLine(const std::wstring& path, const std::wstring& line) {
  if (path.empty()) {
    return;
  }
  HANDLE file = CreateFileW(
      path.c_str(),
      FILE_APPEND_DATA,
      FILE_SHARE_READ,
      nullptr,
      OPEN_ALWAYS,
      FILE_ATTRIBUTE_NORMAL,
      nullptr);
  if (file == INVALID_HANDLE_VALUE) {
    return;
  }

  std::wstring wide_line = MakeTimestamp() + L" " + line + L"\r\n";
  int utf8_size = WideCharToMultiByte(
      CP_UTF8,
      0,
      wide_line.data(),
      static_cast<int>(wide_line.size()),
      nullptr,
      0,
      nullptr,
      nullptr);
  if (utf8_size > 0) {
    std::string utf8(static_cast<size_t>(utf8_size), '\0');
    WideCharToMultiByte(
        CP_UTF8,
        0,
        wide_line.data(),
        static_cast<int>(wide_line.size()),
        utf8.data(),
        utf8_size,
        nullptr,
        nullptr);
    DWORD written = 0;
    WriteFile(file, utf8.data(), static_cast<DWORD>(utf8.size()), &written, nullptr);
  }
  CloseHandle(file);
}

void StartDetached(const std::wstring& executable, const std::wstring& working_dir) {
  STARTUPINFOW si{};
  si.cb = sizeof(si);
  PROCESS_INFORMATION pi{};
  std::wstring command = QuoteArg(executable);

  BOOL ok = CreateProcessW(
      executable.c_str(),
      command.data(),
      nullptr,
      nullptr,
      FALSE,
      CREATE_NO_WINDOW,
      nullptr,
      working_dir.empty() ? nullptr : working_dir.c_str(),
      &si,
      &pi);
  if (ok) {
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
  }
}

void WaitForPid(DWORD pid) {
  if (pid == 0) {
    return;
  }
  HANDLE process = OpenProcess(SYNCHRONIZE, FALSE, pid);
  if (process == nullptr) {
    return;
  }
  WaitForSingleObject(process, INFINITE);
  CloseHandle(process);
}

}  // namespace

int WINAPI wWinMain(HINSTANCE, HINSTANCE, PWSTR, int) {
  int argc = 0;
  LPWSTR* argv = CommandLineToArgvW(GetCommandLineW(), &argc);
  if (argv == nullptr) {
    return 1;
  }

  std::vector<std::wstring> args;
  args.reserve(static_cast<size_t>(argc));
  for (int i = 0; i < argc; ++i) {
    args.emplace_back(argv[i]);
  }
  LocalFree(argv);

  std::wstring pid_arg = GetArgValue(args, L"--pid");
  std::wstring msi = GetArgValue(args, L"--msi");
  std::wstring launch = GetArgValue(args, L"--launch");
  std::wstring working_dir = GetArgValue(args, L"--working-dir");

  if (msi.empty() || launch.empty()) {
    MessageBoxW(nullptr,
                L"Missing updater arguments.",
                L"NorTools Updater",
                MB_OK | MB_ICONERROR);
    return 2;
  }

  DWORD pid = 0;
  if (!pid_arg.empty()) {
    pid = static_cast<DWORD>(std::wcstoul(pid_arg.c_str(), nullptr, 10));
  }

  WaitForPid(pid);

  LogPaths logs = GetLogPaths();
  std::wstring msiexec = L"msiexec.exe";
  std::wstring command = L"msiexec.exe /i " + QuoteArg(msi) + L" /passive /norestart";
  if (!logs.msi_log.empty()) {
    command += L" /L*v " + QuoteArg(logs.msi_log);
  }
  AppendLogLine(logs.updater_log, L"Running: " + command);
  DWORD exit_code = RunAndWait(msiexec, command, working_dir);
  AppendLogLine(logs.updater_log, L"msiexec exit code: " + std::to_wstring(exit_code));
  if (exit_code == 0 || exit_code == 3010) {
    AppendLogLine(logs.updater_log, L"Launching: " + launch);
    StartDetached(launch, working_dir);
  }
  return static_cast<int>(exit_code);
}
