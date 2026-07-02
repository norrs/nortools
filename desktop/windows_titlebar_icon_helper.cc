#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <tlhelp32.h>

#include <cwchar>

namespace {

constexpr UINT_PTR kRetryCount = 50;
constexpr DWORD kRetryDelayMs = 100;
constexpr wchar_t kWindowTitle[] = L"NorTools";
constexpr wchar_t kIconName[] = L"nortools.ico";

DWORD GetParentProcessId() {
    const DWORD current_pid = GetCurrentProcessId();
    HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snapshot == INVALID_HANDLE_VALUE) {
        return 0;
    }

    PROCESSENTRY32W entry{};
    entry.dwSize = sizeof(entry);
    DWORD parent_pid = 0;
    if (Process32FirstW(snapshot, &entry)) {
        do {
            if (entry.th32ProcessID == current_pid) {
                parent_pid = entry.th32ParentProcessID;
                break;
            }
        } while (Process32NextW(snapshot, &entry));
    }

    CloseHandle(snapshot);
    return parent_pid;
}

bool QueryProcessPath(DWORD pid, wchar_t* buffer, DWORD buffer_len) {
    HANDLE process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
    if (process == nullptr) {
        return false;
    }

    DWORD size = buffer_len;
    const BOOL ok = QueryFullProcessImageNameW(process, 0, buffer, &size);
    CloseHandle(process);
    return ok && size > 0;
}

const wchar_t* BaseName(const wchar_t* path) {
    const wchar_t* last_slash = std::wcsrchr(path, L'\\');
    const wchar_t* last_forward_slash = std::wcsrchr(path, L'/');
    if (last_forward_slash != nullptr && (last_slash == nullptr || last_forward_slash > last_slash)) {
        last_slash = last_forward_slash;
    }
    return last_slash == nullptr ? path : last_slash + 1;
}

bool IsNorToolsExecutablePath(const wchar_t* path) {
    const wchar_t* exe_name = BaseName(path);
    return _wcsicmp(exe_name, L"nortools.exe") == 0 || _wcsicmp(exe_name, L"nortools-gui.exe") == 0;
}

bool ResolveIconBesideExecutable(const wchar_t* exe_path, wchar_t* icon_path, DWORD icon_path_len) {
    if (std::wcslen(exe_path) + 1 >= icon_path_len) {
        return false;
    }
    std::wcscpy(icon_path, exe_path);

    wchar_t* last_slash = std::wcsrchr(icon_path, L'\\');
    wchar_t* last_forward_slash = std::wcsrchr(icon_path, L'/');
    if (last_forward_slash != nullptr && (last_slash == nullptr || last_forward_slash > last_slash)) {
        last_slash = last_forward_slash;
    }
    if (last_slash == nullptr) {
        return false;
    }
    *(last_slash + 1) = L'\0';

    if (std::wcslen(icon_path) + std::wcslen(kIconName) + 1 >= icon_path_len) {
        return false;
    }
    std::wcscat(icon_path, kIconName);
    return GetFileAttributesW(icon_path) != INVALID_FILE_ATTRIBUTES;
}

bool WindowBelongsToProcess(HWND hwnd, DWORD target_pid) {
    DWORD window_pid = 0;
    GetWindowThreadProcessId(hwnd, &window_pid);
    return window_pid == target_pid;
}

int SetNorToolsWindowIcon(DWORD target_pid, const wchar_t* icon_path) {
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
        HWND hwnd = FindWindowW(nullptr, kWindowTitle);
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
    const DWORD parent_pid = GetParentProcessId();
    if (parent_pid == 0) {
        return 2;
    }

    wchar_t parent_path[32768]{};
    if (!QueryProcessPath(parent_pid, parent_path, static_cast<DWORD>(sizeof(parent_path) / sizeof(parent_path[0])))) {
        return 2;
    }
    if (!IsNorToolsExecutablePath(parent_path)) {
        return 2;
    }

    wchar_t icon_path[32768]{};
    if (!ResolveIconBesideExecutable(parent_path, icon_path, static_cast<DWORD>(sizeof(icon_path) / sizeof(icon_path[0])))) {
        return 2;
    }

    return SetNorToolsWindowIcon(parent_pid, icon_path);
}
