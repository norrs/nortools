#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <shellapi.h>

#include <cwchar>

namespace {

constexpr UINT_PTR kRetryCount = 50;
constexpr DWORD kRetryDelayMs = 100;

bool WindowBelongsToProcess(HWND hwnd, DWORD target_pid) {
    DWORD window_pid = 0;
    GetWindowThreadProcessId(hwnd, &window_pid);
    return window_pid == target_pid;
}

int SetNorToolsWindowIcon(DWORD target_pid, const wchar_t* icon_path, const wchar_t* title) {
    HICON icon = reinterpret_cast<HICON>(LoadImageW(
        nullptr,
        icon_path,
        IMAGE_ICON,
        0,
        0,
        LR_LOADFROMFILE | LR_DEFAULTSIZE));
    if (icon == nullptr) {
        return 2;
    }

    for (UINT_PTR i = 0; i < kRetryCount; ++i) {
        HWND hwnd = FindWindowW(nullptr, title);
        if (hwnd != nullptr && WindowBelongsToProcess(hwnd, target_pid)) {
            SendMessageW(hwnd, WM_SETICON, ICON_SMALL, reinterpret_cast<LPARAM>(icon));
            SendMessageW(hwnd, WM_SETICON, ICON_BIG, reinterpret_cast<LPARAM>(icon));
            return 0;
        }
        Sleep(kRetryDelayMs);
    }

    return 1;
}

}  // namespace

int WINAPI wWinMain(HINSTANCE, HINSTANCE, PWSTR, int) {
    int argc = 0;
    LPWSTR* argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    if (argv == nullptr) {
        return 2;
    }

    if (argc < 3) {
        LocalFree(argv);
        return 2;
    }

    wchar_t* end = nullptr;
    const unsigned long parsed_pid = std::wcstoul(argv[1], &end, 10);
    if (end == argv[1] || parsed_pid == 0) {
        LocalFree(argv);
        return 2;
    }

    const wchar_t* title = argc >= 4 ? argv[3] : L"NorTools";
    const int rc = SetNorToolsWindowIcon(static_cast<DWORD>(parsed_pid), argv[2], title);
    LocalFree(argv);
    return rc;
}
