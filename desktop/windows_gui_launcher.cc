#include <windows.h>
#include <shellapi.h>

#include <string>

namespace {

std::wstring QuoteArg(const std::wstring& arg) {
  if (arg.empty()) {
    return L"\"\"";
  }
  if (arg.find_first_of(L" \t\"") == std::wstring::npos) {
    return arg;
  }
  std::wstring out = L"\"";
  for (wchar_t ch : arg) {
    if (ch == L'"') {
      out += L"\\\"";
    } else {
      out += ch;
    }
  }
  out += L"\"";
  return out;
}

std::wstring GetSelfDir() {
  wchar_t path[MAX_PATH];
  DWORD len = GetModuleFileNameW(nullptr, path, MAX_PATH);
  if (len == 0 || len >= MAX_PATH) {
    return L".";
  }
  std::wstring full(path, len);
  size_t pos = full.find_last_of(L"\\/");
  if (pos == std::wstring::npos) {
    return L".";
  }
  return full.substr(0, pos);
}

}  // namespace

int WINAPI wWinMain(HINSTANCE, HINSTANCE, PWSTR, int) {
  std::wstring dir = GetSelfDir();
  std::wstring target = dir + L"\\nortools.exe";

  int argc = 0;
  LPWSTR* argv = CommandLineToArgvW(GetCommandLineW(), &argc);
  if (argv == nullptr) {
    return 1;
  }

  std::wstring cmd = QuoteArg(target);
  for (int i = 1; i < argc; ++i) {
    cmd += L" ";
    cmd += QuoteArg(argv[i]);
  }
  LocalFree(argv);

  STARTUPINFOW si{};
  si.cb = sizeof(si);
  PROCESS_INFORMATION pi{};

  std::wstring mutable_cmd = cmd;
  BOOL ok = CreateProcessW(
      target.c_str(),
      mutable_cmd.data(),
      nullptr,
      nullptr,
      FALSE,
      CREATE_NO_WINDOW,
      nullptr,
      dir.c_str(),
      &si,
      &pi);

  if (!ok) {
    MessageBoxW(nullptr,
                L"Failed to start nortools.exe",
                L"NorTools Launcher",
                MB_OK | MB_ICONERROR);
    return 1;
  }

  CloseHandle(pi.hThread);
  WaitForSingleObject(pi.hProcess, INFINITE);
  DWORD child_exit = 0;
  if (!GetExitCodeProcess(pi.hProcess, &child_exit)) {
    child_exit = 1;
  }
  CloseHandle(pi.hProcess);
  return static_cast<int>(child_exit);
}
