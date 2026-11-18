"""Listar y cerrar programas de Windows (sin librerías externas, vía ctypes)."""
from __future__ import annotations

import ctypes
import ntpath
import os
import sys
from ctypes import wintypes

_k32 = ctypes.WinDLL("kernel32", use_last_error=True)

_TH32CS_SNAPPROCESS = 0x00000002
_PROCESS_TERMINATE = 0x0001
_PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
_INVALID_HANDLE_VALUE = ctypes.c_void_p(-1).value


class _PROCESSENTRY32W(ctypes.Structure):
    _fields_ = [
        ("dwSize", wintypes.DWORD),
        ("cntUsage", wintypes.DWORD),
        ("th32ProcessID", wintypes.DWORD),
        ("th32DefaultHeapID", ctypes.c_size_t),
        ("th32ModuleID", wintypes.DWORD),
        ("cntThreads", wintypes.DWORD),
        ("th32ParentProcessID", wintypes.DWORD),
        ("pcPriClassBase", ctypes.c_long),
        ("dwFlags", wintypes.DWORD),
        ("szExeFile", ctypes.c_wchar * 260),
    ]


_k32.CreateToolhelp32Snapshot.argtypes = [wintypes.DWORD, wintypes.DWORD]
_k32.CreateToolhelp32Snapshot.restype = wintypes.HANDLE
_k32.Process32FirstW.argtypes = [wintypes.HANDLE, ctypes.POINTER(_PROCESSENTRY32W)]
_k32.Process32FirstW.restype = wintypes.BOOL
_k32.Process32NextW.argtypes = [wintypes.HANDLE, ctypes.POINTER(_PROCESSENTRY32W)]
_k32.Process32NextW.restype = wintypes.BOOL
_k32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
_k32.OpenProcess.restype = wintypes.HANDLE
_k32.TerminateProcess.argtypes = [wintypes.HANDLE, wintypes.UINT]
_k32.TerminateProcess.restype = wintypes.BOOL
_k32.QueryFullProcessImageNameW.argtypes = [wintypes.HANDLE, wintypes.DWORD, wintypes.LPWSTR,
                                             ctypes.POINTER(wintypes.DWORD)]
_k32.QueryFullProcessImageNameW.restype = wintypes.BOOL
_k32.CloseHandle.argtypes = [wintypes.HANDLE]
_k32.CloseHandle.restype = wintypes.BOOL

# Programas que nunca se pueden bloquear: partes de Windows y el propio bloqueador.
PROTEGIDOS = frozenset({
    "system.exe", "registry.exe", "smss.exe", "csrss.exe", "wininit.exe", "winlogon.exe",
    "services.exe", "lsass.exe", "lsaiso.exe", "svchost.exe", "dwm.exe", "explorer.exe",
    "fontdrvhost.exe", "sihost.exe", "ctfmon.exe", "conhost.exe", "taskhostw.exe",
    "runtimebroker.exe", "startmenuexperiencehost.exe", "shellexperiencehost.exe",
    "spoolsv.exe", "audiodg.exe", "msmpeng.exe", "schtasks.exe", "ipconfig.exe",
    "python.exe", "pythonw.exe", "py.exe", "pyw.exe", "bloqueador.exe",
    ntpath.basename(sys.executable).lower(),
})
_CARACTERES_INVALIDOS = set('<>:"/\\|?*')


def listar() -> list[tuple[int, str]]:
    """Devuelve [(pid, nombre.exe), ...] de todos los procesos abiertos."""
    snap = _k32.CreateToolhelp32Snapshot(_TH32CS_SNAPPROCESS, 0)
    if not snap or snap == _INVALID_HANDLE_VALUE:
        return []
    resultado = []
    try:
        entrada = _PROCESSENTRY32W()
        entrada.dwSize = ctypes.sizeof(_PROCESSENTRY32W)
        ok = _k32.Process32FirstW(snap, ctypes.byref(entrada))
        while ok:
            resultado.append((entrada.th32ProcessID, entrada.szExeFile))
            ok = _k32.Process32NextW(snap, ctypes.byref(entrada))
    finally:
        _k32.CloseHandle(snap)
    return resultado


def terminar(pid: int) -> bool:
    """Cierra el proceso a la fuerza. True si lo logró."""
    h = _k32.OpenProcess(_PROCESS_TERMINATE, False, pid)
    if not h:
        return False
    try:
        return bool(_k32.TerminateProcess(h, 1))
    finally:
        _k32.CloseHandle(h)


def ruta_de(pid: int) -> str | None:
    h = _k32.OpenProcess(_PROCESS_QUERY_LIMITED_INFORMATION, False, pid)
    if not h:
        return None
    try:
        buf = ctypes.create_unicode_buffer(1024)
        n = wintypes.DWORD(len(buf))
        if _k32.QueryFullProcessImageNameW(h, 0, buf, ctypes.byref(n)):
            return buf.value
        return None
    finally:
        _k32.CloseHandle(h)


def nombres_en_ejecucion() -> list[str]:
    """Programas abiertos que no son parte de Windows (para la lista desplegable)."""
    windir = os.path.normcase(os.environ.get("SystemRoot", r"C:\Windows")) + os.sep
    nombres = set()
    for pid, nombre in listar():
        n = nombre.lower()
        if not n.endswith(".exe") or n in PROTEGIDOS or n in nombres:
            continue
        ruta = ruta_de(pid)
        if ruta and not os.path.normcase(ruta).startswith(windir):
            nombres.add(n)
    return sorted(nombres)


def normalizar_app(texto: str) -> tuple[str | None, str | None]:
    """Convierte lo que escribió el usuario en 'programa.exe'. Devuelve (nombre, error)."""
    t = texto.strip().strip('"').strip()
    if not t:
        return None, "Escribe el nombre del programa, por ejemplo discord.exe."
    nombre = ntpath.basename(t).strip().lower()
    if not nombre.endswith(".exe"):
        nombre += ".exe"
    if nombre == ".exe" or any(c in _CARACTERES_INVALIDOS for c in nombre):
        return None, f"«{t}» no es un nombre de programa válido."
    if nombre in PROTEGIDOS:
        return None, (f"{nombre} es parte de Windows o del propio Bloqueador; "
                      "bloquearlo podría dejar la PC inestable.")
    return nombre, None
